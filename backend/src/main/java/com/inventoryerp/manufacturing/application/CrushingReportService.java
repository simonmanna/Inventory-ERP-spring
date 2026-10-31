package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
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
 * Crushing reports and lookups (ADR-S016), all over posted runs in a date range (default: month to date): yield &amp;
 * loss (expected vs actual split per plant and product), cost per tonne per product and per plant (feed + conversion
 * by kind), plant hours &amp; throughput (availability, t/h vs design capacity, per-unit hours / kWh / diesel), the
 * finished-aggregate stockpiles with their last survey, and the form lookups.
 */
@Service
public class CrushingReportService {

    private final MfgDb db;

    public CrushingReportService(CrushingSupport rows) {
        this.db = rows.db();
    }

    private record Range(LocalDate from, LocalDate to) {}

    private static Range range(String from, String to) {
        LocalDate t = CrushingSupport.date(to) != null ? CrushingSupport.date(to) : LocalDate.now(BusinessZone.get());
        LocalDate f = CrushingSupport.date(from) != null ? CrushingSupport.date(from) : t.withDayOfMonth(1);
        return new Range(f, t);
    }

    private static String runWhere(String plantId, List<Object> params, Range r) {
        params.add(MfgDb.org());
        params.add(java.sql.Date.valueOf(r.from()));
        params.add(java.sql.Date.valueOf(r.to()));
        String w = " r.\"organizationId\" = ? AND r.status = 'posted' AND r.\"deletedAt\" IS NULL AND r.\"runDate\" >= ?"
                + " AND r.\"runDate\" <= ?";
        if (CrushingSupport.text(plantId) != null) {
            w += " AND r.\"plantId\" = ?";
            params.add(plantId);
        }
        return w;
    }

    /** Expected vs actual yield and loss per plant, and per product (tonnes and % of feed). */
    @Transactional(readOnly = true)
    public Map<String, Object> yieldReport(String from, String to, String plantId) {
        Range r = range(from, to);
        List<Object> p1 = new ArrayList<>();
        String w1 = runWhere(plantId, p1, r);
        List<Map<String, Object>> plants = new ArrayList<>();
        for (Map<String, Object> row : SqlRows.list(db.jdbc().sql("SELECT p.id AS \"plantId\", p.code AS \"plantCode\","
                + " p.name AS \"plantName\", count(*) AS runs, SUM(r.\"feedTonnes\") AS \"feedTonnes\","
                + " SUM(r.\"outputTonnes\") AS \"outputTonnes\", SUM(r.\"lossTonnes\") AS \"lossTonnes\","
                + " SUM(r.\"recirculatedTonnes\") AS \"recirculatedTonnes\","
                + " SUM(r.\"feedTonnes\" * r.\"expectedYieldPct\") / NULLIF(SUM(r.\"feedTonnes\"), 0) AS \"expectedYieldPct\""
                + " FROM \"CrushingRun\" r JOIN \"CrushingPlant\" p ON p.id = r.\"plantId\" WHERE" + w1
                + " GROUP BY p.id ORDER BY p.code").params(p1))) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            BigDecimal feed = dec(row, "feedTonnes");
            BigDecimal actual = CrushingCalculator.pctOf(dec(row, "outputTonnes"), feed);
            BigDecimal expected = row.get("expectedYieldPct") == null ? null
                    : dec(row, "expectedYieldPct").setScale(4, RoundingMode.HALF_UP);
            m.put("expectedYieldPct", expected);
            m.put("actualYieldPct", actual);
            m.put("yieldVariancePct", actual == null || expected == null ? null : actual.subtract(expected));
            m.put("lossPct", CrushingCalculator.pctOf(dec(row, "lossTonnes"), feed));
            m.put("circulatingLoadPct", CrushingCalculator.pctOf(dec(row, "recirculatedTonnes"), feed));
            plants.add(m);
        }
        List<Object> p2 = new ArrayList<>();
        String w2 = runWhere(plantId, p2, r);
        List<Map<String, Object>> products = new ArrayList<>();
        for (Map<String, Object> row : SqlRows.list(db.jdbc().sql("SELECT pr.id AS \"productId\", pr.code AS"
                + " \"productCode\", pr.name AS \"productName\", o.role, SUM(o.\"expectedTonnes\") AS \"expectedTonnes\","
                + " SUM(o.\"actualTonnes\") AS \"actualTonnes\", SUM(r.\"feedTonnes\") AS \"feedTonnes\""
                + " FROM \"CrushingRunOutput\" o JOIN \"CrushingRun\" r ON r.id = o.\"runId\" JOIN \"Product\" pr"
                + " ON pr.id = o.\"productId\" WHERE" + w2 + " GROUP BY pr.id, o.role ORDER BY pr.name").params(p2))) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            m.put("varianceTonnes", dec(row, "actualTonnes").subtract(dec(row, "expectedTonnes")));
            m.put("expectedPct", CrushingCalculator.pctOf(dec(row, "expectedTonnes"), dec(row, "feedTonnes")));
            m.put("actualPct", CrushingCalculator.pctOf(dec(row, "actualTonnes"), dec(row, "feedTonnes")));
            products.add(m);
        }
        return Obj.of("from", r.from().toString(), "to", r.to().toString(), "plants", plants, "products", products);
    }

    /** Cost per tonne per product (valued output ÷ tonnes) and per plant (feed + conversion by kind). */
    @Transactional(readOnly = true)
    public Map<String, Object> costPerTonne(String from, String to, String plantId) {
        Range r = range(from, to);
        List<Object> p1 = new ArrayList<>();
        String w1 = runWhere(plantId, p1, r);
        List<Map<String, Object>> products = new ArrayList<>();
        for (Map<String, Object> row : SqlRows.list(db.jdbc().sql("SELECT pr.id AS \"productId\", pr.code AS"
                + " \"productCode\", pr.name AS \"productName\", SUM(o.\"actualTonnes\") AS tonnes,"
                + " SUM(COALESCE(o.value, 0)) AS value FROM \"CrushingRunOutput\" o JOIN \"CrushingRun\" r ON"
                + " r.id = o.\"runId\" JOIN \"Product\" pr ON pr.id = o.\"productId\" WHERE" + w1
                + " AND o.\"actualTonnes\" > 0 GROUP BY pr.id ORDER BY pr.name").params(p1))) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            BigDecimal t = dec(row, "tonnes");
            m.put("value", dec(row, "value").setScale(2, RoundingMode.HALF_UP));
            m.put("costPerTonne", t.signum() > 0 ? dec(row, "value").divide(t, 4, RoundingMode.HALF_UP) : null);
            products.add(m);
        }
        List<Object> p2 = new ArrayList<>();
        String w2 = runWhere(plantId, p2, r);
        List<Map<String, Object>> plants = new ArrayList<>();
        for (Map<String, Object> row : SqlRows.list(db.jdbc().sql("SELECT p.id AS \"plantId\", p.code AS \"plantCode\","
                + " p.name AS \"plantName\", count(*) AS runs, SUM(r.\"feedTonnes\") AS \"feedTonnes\","
                + " SUM(r.\"outputTonnes\") AS \"outputTonnes\", SUM(r.\"feedCost\") AS \"feedCost\","
                + " SUM(r.\"conversionCost\") AS \"conversionCost\", SUM(r.\"abnormalLossValue\") AS \"abnormalLossValue\","
                + " SUM(r.\"totalCost\") AS \"totalCost\" FROM \"CrushingRun\" r JOIN \"CrushingPlant\" p ON"
                + " p.id = r.\"plantId\" WHERE" + w2 + " GROUP BY p.id ORDER BY p.code").params(p2))) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            BigDecimal out = dec(row, "outputTonnes");
            m.put("costPerTonne", out.signum() > 0 ? dec(row, "totalCost").divide(out, 4, RoundingMode.HALF_UP) : null);
            List<Object> p3 = new ArrayList<>();
            String w3 = runWhere(null, p3, r) + " AND r.\"plantId\" = ?";
            p3.add(row.get("plantId"));
            m.put("byKind", SqlRows.list(db.jdbc().sql("SELECT c.kind, SUM(c.amount) AS amount FROM \"CrushingRunCost\" c"
                    + " JOIN \"CrushingRun\" r ON r.id = c.\"runId\" WHERE" + w3 + " GROUP BY c.kind ORDER BY c.kind")
                    .params(p3)));
            plants.add(m);
        }
        return Obj.of("from", r.from().toString(), "to", r.to().toString(), "products", products, "plants", plants);
    }

    /** Operating / downtime hours, availability, throughput vs design capacity per plant, and per-unit meters. */
    @Transactional(readOnly = true)
    public Map<String, Object> plantHours(String from, String to, String plantId) {
        Range r = range(from, to);
        List<Object> p1 = new ArrayList<>();
        String w1 = runWhere(plantId, p1, r);
        List<Map<String, Object>> plants = new ArrayList<>();
        for (Map<String, Object> row : SqlRows.list(db.jdbc().sql("SELECT p.id AS \"plantId\", p.code AS \"plantCode\","
                + " p.name AS \"plantName\", p.\"designCapacityTph\", count(*) AS runs,"
                + " SUM(r.\"feedTonnes\") AS \"feedTonnes\", SUM(r.\"outputTonnes\") AS \"outputTonnes\","
                + " COALESCE(SUM(r.\"operatingHours\"), 0) AS \"operatingHours\", SUM(r.\"downtimeHours\") AS"
                + " \"downtimeHours\", SUM(r.\"labourHours\") AS \"labourHours\" FROM \"CrushingRun\" r JOIN"
                + " \"CrushingPlant\" p ON p.id = r.\"plantId\" WHERE" + w1 + " GROUP BY p.id ORDER BY p.code")
                .params(p1))) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            CrushingCalculator.Performance perf = CrushingCalculator.performance(dec(row, "feedTonnes"),
                    dec(row, "operatingHours"), dec(row, "downtimeHours"), row.get("designCapacityTph") == null ? null
                            : dec(row, "designCapacityTph"));
            m.put("throughputTph", perf.throughputTph());
            m.put("availabilityPct", perf.availabilityPct());
            m.put("utilisationPct", perf.utilisationPct());
            plants.add(m);
        }
        List<Object> p2 = new ArrayList<>();
        String w2 = runWhere(plantId, p2, r);
        List<Map<String, Object>> units = new ArrayList<>();
        for (Map<String, Object> row : SqlRows.list(db.jdbc().sql("SELECT u.id AS \"unitId\", u.code AS \"unitCode\","
                + " u.name AS \"unitName\", u.kind, u.\"capacityTph\", p.code AS \"plantCode\", count(*) AS runs,"
                + " SUM(m.\"runHours\") AS \"runHours\", SUM(m.kwh) AS kwh, SUM(m.\"dieselLitres\") AS \"dieselLitres\","
                + " SUM(r.\"feedTonnes\") AS \"feedTonnes\" FROM \"CrushingRunMeter\" m JOIN \"CrushingRun\" r ON"
                + " r.id = m.\"runId\" JOIN \"CrushingPlantUnit\" u ON u.id = m.\"unitId\" JOIN \"CrushingPlant\" p"
                + " ON p.id = r.\"plantId\" WHERE" + w2 + " GROUP BY u.id, p.code ORDER BY p.code, u.\"sortOrder\"")
                .params(p2))) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            BigDecimal hours = dec(row, "runHours");
            BigDecimal tph = hours.signum() > 0 ? dec(row, "feedTonnes").divide(hours, 3, RoundingMode.HALF_UP) : null;
            m.put("throughputTph", tph);
            m.put("utilisationPct", tph == null || row.get("capacityTph") == null ? null
                    : CrushingCalculator.pctOf(tph, dec(row, "capacityTph")));
            m.put("kwhPerTonne", dec(row, "feedTonnes").signum() > 0 ? dec(row, "kwh").divide(dec(row, "feedTonnes"),
                    4, RoundingMode.HALF_UP) : null);
            units.add(m);
        }
        return Obj.of("from", r.from().toString(), "to", r.to().toString(), "plants", plants, "units", units);
    }

    /** Stockpiles (product kind first) with on-hand per product, value and the last survey of each. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> stockpiles(String kind) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        String w = "";
        if (CrushingSupport.text(kind) != null) {
            w = " AND k.kind = ?";
            params.add(kind);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> k : SqlRows.list(db.jdbc().sql("SELECT k.id, k.code, k.name, k.kind, k.\"locationId\","
                + " k.\"capacityT\", k.\"isActive\", s.code AS \"siteCode\", s.name AS \"siteName\" FROM \"QuarryStockpile\" k"
                + " JOIN \"QuarrySite\" s ON s.id = k.\"siteId\" WHERE k.\"organizationId\" = ? AND k.\"deletedAt\" IS NULL"
                + w + " ORDER BY CASE WHEN k.kind = 'product' THEN 0 ELSE 1 END, s.code, k.code").params(params))) {
            Map<String, Object> m = new LinkedHashMap<>(k);
            List<Map<String, Object>> stock = SqlRows.list(db.jdbc().sql("""
                    SELECT p.id AS "productId", p.code AS "productCode", p.name AS "productName",
                           SUM(si.quantity) AS "onHandTonnes", SUM(si.quantity * si."runningAverageCost") AS value,
                           (SELECT row_to_json(x) FROM (SELECT v.id, v.code, v."surveyDate", v."surveyedTonnes", v.status
                               FROM "CrushingStockpileSurvey" v WHERE v."organizationId" = si."organizationId"
                               AND v."stockpileId" = ? AND v."productId" = p.id AND v."deletedAt" IS NULL
                               ORDER BY v."surveyDate" DESC, v.code DESC LIMIT 1) x)::text AS "lastSurvey"
                    FROM "StockItem" si JOIN "Product" p ON p.id = si."productId"
                    WHERE si."organizationId" = ? AND si."locationId" = ?
                    GROUP BY si."organizationId", p.id HAVING SUM(si.quantity) <> 0 ORDER BY p.name""")
                    .params(k.get("id"), MfgDb.org(), k.get("locationId")));
            BigDecimal tonnes = BigDecimal.ZERO;
            BigDecimal value = BigDecimal.ZERO;
            for (Map<String, Object> s : stock) {
                tonnes = tonnes.add(dec(s, "onHandTonnes"));
                value = value.add(dec(s, "value"));
                s.put("value", dec(s, "value").setScale(2, RoundingMode.HALF_UP));
            }
            m.put("stock", stock);
            m.put("onHandTonnes", tonnes);
            m.put("onHandValue", value.setScale(2, RoundingMode.HALF_UP));
            out.add(m);
        }
        return out;
    }

    /** Form lookups: active plants with their active profiles, stockpiles, quarry sites. */
    @Transactional(readOnly = true)
    public Map<String, Object> lookups() {
        String org = MfgDb.org();
        List<Map<String, Object>> plants = new ArrayList<>();
        for (Map<String, Object> p : SqlRows.list(db.jdbc().sql("SELECT id, code, name, \"feedStockpileId\", \"siteId\","
                + " \"designCapacityTph\" FROM \"CrushingPlant\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"isActive\" ORDER BY code").params(org))) {
            Map<String, Object> m = new LinkedHashMap<>(p);
            m.put("profiles", SqlRows.list(db.jdbc().sql("SELECT g.id, g.code, g.name, g.version, g.\"feedProductId\","
                    + " f.name AS \"feedProductName\" FROM \"CrushingGradingProfile\" g JOIN \"Product\" f ON"
                    + " f.id = g.\"feedProductId\" WHERE g.\"organizationId\" = ? AND g.\"plantId\" = ? AND"
                    + " g.status = 'active' AND g.\"deletedAt\" IS NULL ORDER BY g.code").params(org, p.get("id"))));
            plants.add(m);
        }
        return Obj.of("plants", plants,
                "stockpiles", SqlRows.list(db.jdbc().sql("SELECT k.id, k.code, k.name, k.kind, k.\"siteId\","
                        + " k.\"locationId\", s.code AS \"siteCode\" FROM \"QuarryStockpile\" k JOIN \"QuarrySite\" s"
                        + " ON s.id = k.\"siteId\" WHERE k.\"organizationId\" = ? AND k.\"deletedAt\" IS NULL AND"
                        + " k.\"isActive\" ORDER BY s.code, k.code").params(org)),
                "sites", SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"QuarrySite\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY code").params(org)));
    }

    /** Up to 20 stock products matching {@code search} (code or name). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> products(String search) {
        String q = CrushingSupport.text(search) == null ? "%" : "%" + search.trim() + "%";
        return SqlRows.list(db.jdbc().sql("SELECT id, code, name, \"costingMethod\"::text AS \"costingMethod\" FROM"
                + " \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND (code ILIKE ? OR name ILIKE ?)"
                + " ORDER BY name LIMIT 20").params(MfgDb.org(), q, q));
    }
}
