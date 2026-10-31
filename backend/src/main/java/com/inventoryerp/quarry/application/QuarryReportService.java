package com.inventoryerp.quarry.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quarry reports (2.2): extraction vs target per plan, cost per tonne by site and period (with the cost build-up),
 * losses by site and kind, royalties / levies by site and rate. Only posted extraction logs count; the default window
 * is the current month to date (business zone).
 */
@Service
public class QuarryReportService {

    private final QuarryDb db;
    private final QuarryPlanService plans;

    public QuarryReportService(QuarryDb db, QuarryPlanService plans) {
        this.db = db;
        this.plans = plans;
    }

    record Window(LocalDate from, LocalDate to) {}

    static Window window(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(BusinessZone.get());
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate t = to == null ? today : to;
        if (t.isBefore(f)) {
            throw ApiException.badRequest("The end date must be on or after the start date");
        }
        return new Window(f, t);
    }

    /** Plans overlapping the window (not drafts) with actual tonnes, cost per tonne vs budget and the variance. */
    @Transactional(readOnly = true)
    public Map<String, Object> extractionVsTarget(LocalDate from, LocalDate to, String siteId) {
        Window w = window(from, to);
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org(), date(w.to()), date(w.from())));
        String site = "";
        if (!QuarryDb.blank(siteId)) {
            site = " AND p.\"siteId\" = ?";
            params.add(siteId);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal target = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        for (Map<String, Object> p : SqlRows.list(db.jdbc().sql("""
                SELECT p.*, s.code AS "siteCode", s.name AS "siteName", z.code AS "zoneCode", z.name AS "zoneName",
                       m.code AS "materialCode", m.name AS "materialName"
                FROM "QuarryExtractionPlan" p
                JOIN "QuarrySite" s ON s.id = p."siteId"
                LEFT JOIN "QuarryZone" z ON z.id = p."zoneId"
                LEFT JOIN "QuarryMaterial" m ON m.id = p."materialId"
                WHERE p."organizationId" = ? AND p."deletedAt" IS NULL AND p.status <> 'draft'
                  AND p."periodStart" <= ? AND p."periodEnd" >= ?""" + site
                + " ORDER BY s.code, p.\"periodStart\", p.code").params(params))) {
            Map<String, Object> r = plans.withProgress(p);
            target = target.add(SqlRows.dec(p, "targetTonnes"));
            actual = actual.add(SqlRows.dec(r, "actualTonnes"));
            rows.add(r);
        }
        return Obj.of("from", w.from(), "to", w.to(), "rows", rows, "totals", Obj.of("targetTonnes", target,
                "actualTonnes", actual, "variance", actual.subtract(target), "achievedPct", target.signum() == 0 ? null
                        : actual.multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP)));
    }

    /** Cost per tonne by site and period ({@code groupBy} day / week / month / site) with the build-up. */
    @Transactional(readOnly = true)
    public Map<String, Object> costPerTonne(LocalDate from, LocalDate to, String siteId, String groupBy) {
        Window w = window(from, to);
        String g = groupBy == null ? "month" : groupBy;
        String period = switch (g) {
            case "day" -> "l.\"extractedOn\"";
            case "week" -> "date_trunc('week', l.\"extractedOn\")::date";
            case "site" -> "NULL::date";
            default -> "date_trunc('month', l.\"extractedOn\")::date";
        };
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org(), date(w.from()), date(w.to())));
        String site = "";
        if (!QuarryDb.blank(siteId)) {
            site = " AND l.\"siteId\" = ?";
            params.add(siteId);
        }
        String sums = """
                count(*) AS logs, SUM(l."grossTonnes") AS "grossTonnes", SUM(l."lossTonnes") AS "lossTonnes",
                SUM(l."netTonnes") AS "netTonnes", SUM(l."fuelCost") AS "fuelCost", SUM(l."labourCost") AS "labourCost",
                SUM(l."equipmentCost") AS "equipmentCost", SUM(l."blastingCost") AS "blastingCost",
                SUM(l."royaltyCost") AS "royaltyCost", SUM(l."otherCost") AS "otherCost",
                SUM(l."totalCost") AS "totalCost", SUM(l."capitalisedCost") AS "capitalisedCost",
                SUM(l."lossCost") AS "lossCost"
                """;
        String where = " FROM \"QuarryExtractionLog\" l JOIN \"QuarrySite\" s ON s.id = l.\"siteId\" WHERE"
                + " l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"deletedAt\" IS NULL AND l.\"extractedOn\""
                + " BETWEEN ? AND ?" + site;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT l.\"siteId\", s.code AS \"siteCode\", s.name AS"
                + " \"siteName\", " + period + " AS period, " + sums + where + " GROUP BY l.\"siteId\", s.code, s.name, "
                + period + " ORDER BY s.code, period").params(params))) {
            rows.add(perTonne(r));
        }
        Map<String, Object> totals = perTonne(SqlRows.one(db.jdbc().sql("SELECT " + sums + where).params(params)));
        return Obj.of("from", w.from(), "to", w.to(), "groupBy", g, "rows", rows, "totals", totals);
    }

    private static Map<String, Object> perTonne(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>(r);
        BigDecimal net = SqlRows.dec(r, "netTonnes");
        BigDecimal gross = SqlRows.dec(r, "grossTonnes");
        for (String k : List.of("grossTonnes", "lossTonnes", "netTonnes", "fuelCost", "labourCost", "equipmentCost",
                "blastingCost", "royaltyCost", "otherCost", "totalCost", "capitalisedCost", "lossCost")) {
            out.put(k, SqlRows.dec(r, k));
        }
        out.put("costPerTonne", net.signum() > 0 ? SqlRows.dec(r, "totalCost").divide(net, 4, RoundingMode.HALF_UP)
                : null);
        out.put("lossPct", gross.signum() > 0 ? SqlRows.dec(r, "lossTonnes").multiply(BigDecimal.valueOf(100))
                .divide(gross, 2, RoundingMode.HALF_UP) : null);
        return out;
    }

    /** Losses by site and kind (log losses of posted logs and standalone losses), with the loss share of gross. */
    @Transactional(readOnly = true)
    public Map<String, Object> losses(LocalDate from, LocalDate to, String siteId) {
        Window w = window(from, to);
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org(), date(w.from()), date(w.to())));
        String site = "";
        if (!QuarryDb.blank(siteId)) {
            site = " AND x.\"siteId\" = ?";
            params.add(siteId);
        }
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("""
                SELECT x."siteId", s.code AS "siteCode", s.name AS "siteName", x.kind,
                       SUM(x.tonnes) FILTER (WHERE x."logId" IS NOT NULL) AS "extractionTonnes",
                       SUM(x.tonnes) FILTER (WHERE x."logId" IS NULL) AS "standaloneTonnes",
                       SUM(x.tonnes) AS tonnes, count(*) AS entries
                FROM "QuarryLoss" x
                JOIN "QuarrySite" s ON s.id = x."siteId"
                LEFT JOIN "QuarryExtractionLog" g ON g.id = x."logId"
                WHERE x."organizationId" = ? AND x."deletedAt" IS NULL AND (x."logId" IS NULL OR g.status = 'posted')
                  AND x."lossOn" BETWEEN ? AND ?""" + site + " GROUP BY x.\"siteId\", s.code, s.name, x.kind"
                + " ORDER BY s.code, x.kind").params(params));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("extractionTonnes", SqlRows.dec(r, "extractionTonnes"));
            m.put("standaloneTonnes", SqlRows.dec(r, "standaloneTonnes"));
            m.put("tonnes", SqlRows.dec(r, "tonnes"));
            out.add(m);
        }
        List<Object> sp = new ArrayList<>(List.of(QuarryDb.org(), date(w.from()), date(w.to())));
        String site2 = "";
        if (!QuarryDb.blank(siteId)) {
            site2 = " AND l.\"siteId\" = ?";
            sp.add(siteId);
        }
        List<Map<String, Object>> sites = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT l.\"siteId\", s.code AS \"siteCode\","
                + " SUM(l.\"grossTonnes\") AS \"grossTonnes\", SUM(l.\"lossTonnes\") AS \"lossTonnes\","
                + " SUM(l.\"netTonnes\") AS \"netTonnes\", SUM(l.\"lossCost\") AS \"lossCost\" FROM \"QuarryExtractionLog\""
                + " l JOIN \"QuarrySite\" s ON s.id = l.\"siteId\" WHERE l.\"organizationId\" = ? AND l.status = 'posted'"
                + " AND l.\"deletedAt\" IS NULL AND l.\"extractedOn\" BETWEEN ? AND ?" + site2
                + " GROUP BY l.\"siteId\", s.code ORDER BY s.code").params(sp))) {
            sites.add(perTonne(r));
        }
        BigDecimal total = out.stream().map(r -> (BigDecimal) r.get("tonnes")).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Obj.of("from", w.from(), "to", w.to(), "rows", out, "sites", sites, "totals", Obj.of("tonnes", total));
    }

    /** Royalties / levies by site and rate (authority), from the cost build-up of posted logs. */
    @Transactional(readOnly = true)
    public Map<String, Object> royalties(LocalDate from, LocalDate to, String siteId) {
        Window w = window(from, to);
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org(), date(w.from()), date(w.to())));
        String site = "";
        if (!QuarryDb.blank(siteId)) {
            site = " AND l.\"siteId\" = ?";
            params.add(siteId);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT l."siteId", s.code AS "siteCode", s.name AS "siteName", c."refId" AS "rateId",
                       c.description AS name, rr.kind, rr.basis, c.rate AS "ratePerTonne", c."partnerId",
                       p.name AS "authorityName", SUM(c.quantity) AS tonnes, SUM(c.amount) AS amount,
                       count(DISTINCT l.id) AS logs
                FROM "QuarryExtractionCost" c
                JOIN "QuarryExtractionLog" l ON l.id = c."logId"
                JOIN "QuarrySite" s ON s.id = l."siteId"
                LEFT JOIN "QuarryRoyaltyRate" rr ON rr.id = c."refId"
                LEFT JOIN "Partner" p ON p.id = c."partnerId" AND p."organizationId" = c."organizationId"
                WHERE c."organizationId" = ? AND c.component = 'royalty' AND l.status = 'posted'
                  AND l."deletedAt" IS NULL AND l."extractedOn" BETWEEN ? AND ?""" + site + "\n" + """
                 GROUP BY l."siteId", s.code, s.name, c."refId", c.description, rr.kind, rr.basis, c.rate,
                          c."partnerId", p.name
                 ORDER BY s.code, c.description, c.rate""").params(params))) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("tonnes", SqlRows.dec(r, "tonnes"));
            m.put("amount", SqlRows.dec(r, "amount"));
            total = total.add(SqlRows.dec(r, "amount"));
            rows.add(m);
        }
        return Obj.of("from", w.from(), "to", w.to(), "rows", rows, "totals", Obj.of("amount", total));
    }

    private static java.sql.Date date(LocalDate d) {
        return java.sql.Date.valueOf(d);
    }
}
