package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.dec;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.crm.application.CrmDealQuery.Filter;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRM reporting (ADR-S010): the filterable management report (KPIs, funnel, breakdowns by salesperson / team / source
 * / tag / lost reason, monthly trend, forecast, time in stage, quotation outcomes, activity volume), the personal
 * sales dashboard and the customer directory with CRM + receivables figures. All figures respect the deal visibility
 * rule of {@link CrmDealQuery}.
 */
@Service
public class CrmReportService {

    static final String PROB = "COALESCE(d.probability, CASE d.status WHEN 'won' THEN 100 WHEN 'lost' THEN 0 ELSE"
            + " s.probability END, 0)";

    private final CrmDb db;
    private final CrmConfigService config;
    private final CrmQuotationService quotations;

    public CrmReportService(CrmDb db, CrmConfigService config, CrmQuotationService quotations) {
        this.db = db;
        this.config = config;
        this.quotations = quotations;
    }

    // ------------------------------------------------------------------ management report

    /** Report over deals created in {@code [from, to]} (default: last 90 days) plus wins closed in that window. */
    @Transactional
    public Map<String, Object> report(Map<String, String> params) {
        config.ensureDefaults();
        ZoneId zone = BusinessZone.get();
        LocalDate to = blank(params.get("to")) ? LocalDate.now(zone) : CrmDb.date(params.get("to"));
        LocalDate from = blank(params.get("from")) ? to.minusDays(89) : CrmDb.date(params.get("from"));
        Map<String, String> p = new HashMap<>(params);
        p.put("createdFrom", from.toString());
        p.put("createdTo", to.toString());
        p.remove("from");
        p.remove("to");
        CrmDealQuery cohort = new CrmDealQuery(Filter.of(p));
        String fromW = CrmDealQuery.FROM + cohort.where;
        Object[] cp = cohort.params.toArray();

        // Wins / losses closed in the window (independent of when the deal was created).
        Map<String, String> closedP = new HashMap<>(p);
        closedP.remove("createdFrom");
        closedP.remove("createdTo");
        CrmDealQuery closed = new CrmDealQuery(Filter.of(closedP));
        List<Object> clp = new ArrayList<>(closed.params);
        java.time.Instant start = from.atStartOfDay(zone).toInstant();
        java.time.Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        clp.add(SqlRows.ts(start));
        clp.add(SqlRows.ts(end));
        clp.add(SqlRows.ts(start));
        clp.add(SqlRows.ts(end));
        String closedWhere = closed.where + " AND ((d.status = 'won' AND d.\"wonAt\" >= ? AND d.\"wonAt\" < ?) OR"
                + " (d.status = 'lost' AND d.\"lostAt\" >= ? AND d.\"lostAt\" < ?))";

        Map<String, Object> created = db.row("SELECT count(*) FILTER (WHERE d.kind = 'lead' OR d.\"convertedAt\" IS"
                + " NOT NULL) AS leads, count(*) FILTER (WHERE d.kind = 'opportunity') AS opportunities,"
                + " count(*) FILTER (WHERE d.\"convertedAt\" IS NOT NULL) AS converted, count(*) FILTER (WHERE d.status ="
                + " 'open') AS open, COALESCE(sum(d.amount) FILTER (WHERE d.status = 'open'), 0) AS \"openValue\","
                + " COALESCE(sum(d.amount * " + PROB + " / 100) FILTER (WHERE d.status = 'open'), 0) AS \"weightedValue\""
                + fromW, cp);
        Map<String, Object> outcome = db.row("SELECT count(*) FILTER (WHERE d.status = 'won') AS won, count(*) FILTER"
                + " (WHERE d.status = 'lost') AS lost, COALESCE(sum(d.amount) FILTER (WHERE d.status = 'won'), 0) AS"
                + " \"wonValue\", COALESCE(avg(EXTRACT(EPOCH FROM d.\"wonAt\" - d.\"createdAt\") / 86400) FILTER (WHERE"
                + " d.status = 'won'), 0) AS \"avgCycleDays\"" + CrmDealQuery.FROM + closedWhere, clp.toArray());
        long won = ((Number) outcome.get("won")).longValue();
        long lost = ((Number) outcome.get("lost")).longValue();
        BigDecimal wonValue = dec(outcome.get("wonValue"));
        long leads = ((Number) created.get("leads")).longValue();
        long converted = ((Number) created.get("converted")).longValue();

        Map<String, Object> quotes = quoteSummary(from, to, p);
        Map<String, Object> kpis = Obj.of("leads", leads, "opportunities", created.get("opportunities"), "converted",
                converted, "leadConversion", pct(converted, leads), "open", created.get("open"), "openValue",
                created.get("openValue"), "weightedValue", created.get("weightedValue"), "won", won, "lost", lost,
                "wonValue", JsNumbers.js(wonValue), "winRate", pct(won, won + lost), "avgDealSize", won == 0 ? 0
                        : JsNumbers.js(wonValue.divide(BigDecimal.valueOf(won), 2, RoundingMode.HALF_UP)),
                "avgCycleDays", JsNumbers.js(dec(outcome.get("avgCycleDays")).setScale(1, RoundingMode.HALF_UP)),
                "quotations", quotes.get("count"), "quotationValue", quotes.get("value"), "quoteAcceptance",
                quotes.get("acceptanceRate"));

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("range", Obj.of("from", from.toString(), "to", to.toString()));
        out.put("currency", orgCurrency());
        out.put("kpis", kpis);
        out.put("funnel", funnel(cohort));
        out.put("byStage", db.rows("SELECT s.id AS key, s.name AS label, s.color, count(d.id) AS count,"
                + " COALESCE(sum(d.amount), 0) AS amount, COALESCE(sum(d.amount * " + PROB + " / 100), 0) AS weighted"
                + fromW + " AND d.status = 'open' AND d.kind = 'opportunity' GROUP BY s.id, s.name, s.color, s.sequence"
                + " ORDER BY s.sequence", cp));
        out.put("byOwner", breakdown("d.\"ownerId\"", "COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\","
                + " u.\"lastName\")), ''), u.email, 'Unassigned')", "", cohort));
        out.put("byTeam", breakdown("d.\"teamId\"", "COALESCE(gt.name, 'No team')",
                " LEFT JOIN \"CrmTeam\" gt ON gt.id = d.\"teamId\"", cohort));
        out.put("bySource", breakdown("d.\"sourceId\"", "COALESCE(gs.name, 'Unknown')",
                " LEFT JOIN \"CrmSource\" gs ON gs.id = d.\"sourceId\"", cohort));
        out.put("byTag", breakdown("tg.id", "COALESCE(gtag.name, 'Untagged')", " LEFT JOIN LATERAL"
                + " unnest(d.\"tagIds\") tg(id) ON true LEFT JOIN \"CrmTag\" gtag ON gtag.id = tg.id", cohort));
        out.put("byCountry", breakdown("d.country", "COALESCE(d.country, 'Unknown')", "", cohort));
        out.put("lostReasons", db.rows("SELECT d.\"lostReasonId\" AS key, COALESCE(lr.name, 'Not recorded') AS label,"
                + " count(*) AS count, COALESCE(sum(d.amount), 0) AS amount" + CrmDealQuery.FROM
                + " LEFT JOIN \"CrmLostReason\" lr ON lr.id = d.\"lostReasonId\"" + closedWhere + " AND d.status = 'lost'"
                + " GROUP BY 1, 2 ORDER BY 3 DESC", clp.toArray()));
        out.put("trend", trend(from, to, p));
        out.put("forecast", db.rows("SELECT to_char(d.\"expectedClose\", 'YYYY-MM') AS month, count(*) AS count,"
                + " COALESCE(sum(d.amount), 0) AS amount, COALESCE(sum(d.amount * " + PROB + " / 100), 0) AS weighted"
                + CrmDealQuery.FROM + closed.where + " AND d.status = 'open' AND d.\"expectedClose\" IS NOT NULL GROUP BY"
                + " 1 ORDER BY 1 LIMIT 12", closed.params.toArray()));
        out.put("timeInStage", db.rows("SELECT h.\"fromStageId\" AS key, s2.name AS label, s2.sequence,"
                + " round(avg(h.\"secondsInStage\") / 86400.0, 1) AS \"avgDays\", count(*) AS samples FROM"
                + " \"CrmStageHistory\" h JOIN \"CrmStage\" s2 ON s2.id = h.\"fromStageId\" WHERE h.\"organizationId\" = ?"
                + " AND h.\"secondsInStage\" IS NOT NULL AND h.\"changedAt\" >= ? AND h.\"changedAt\" < ? AND h.\"dealId\""
                + " IN (SELECT d.id" + CrmDealQuery.FROM + closed.where + ") GROUP BY 1, 2, 3 ORDER BY 3",
                concat(List.of(org(), SqlRows.ts(start), SqlRows.ts(end)), closed.params)));
        out.put("quotations", quotes);
        out.put("activities", activities(start, end, p));
        return out;
    }

    private List<Map<String, Object>> funnel(CrmDealQuery cohort) {
        List<Object> params = new ArrayList<>(cohort.params);
        params.add(org());
        return db.rows("WITH c AS (SELECT d.id, d.status" + CrmDealQuery.FROM + cohort.where + ") SELECT st.id AS key,"
                + " st.name AS label, st.color, st.\"isWon\", (SELECT count(DISTINCT h.\"dealId\") FROM \"CrmStageHistory\""
                + " h JOIN c ON c.id = h.\"dealId\" WHERE h.\"toStageId\" = st.id) AS count FROM \"CrmStage\" st WHERE"
                + " st.\"organizationId\" = ? AND st.\"isActive\" ORDER BY st.sequence", params);
    }

    private List<Map<String, Object>> breakdown(String key, String label, String join, CrmDealQuery q) {
        List<Map<String, Object>> rows = db.rows("SELECT " + key + " AS key, " + label + " AS label, count(DISTINCT d.id)"
                + " AS total, count(DISTINCT d.id) FILTER (WHERE d.kind = 'lead' OR d.\"convertedAt\" IS NOT NULL) AS"
                + " leads, count(DISTINCT d.id) FILTER (WHERE d.status = 'open') AS open, count(DISTINCT d.id) FILTER"
                + " (WHERE d.status = 'won') AS won, count(DISTINCT d.id) FILTER (WHERE d.status = 'lost') AS lost,"
                + " COALESCE(sum(d.amount) FILTER (WHERE d.status = 'won'), 0) AS \"wonValue\", COALESCE(sum(d.amount)"
                + " FILTER (WHERE d.status = 'open'), 0) AS \"openValue\"" + CrmDealQuery.FROM + join + q.where
                + " GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 50", q.params);
        for (Map<String, Object> r : rows) {
            long w = ((Number) r.get("won")).longValue();
            long l = ((Number) r.get("lost")).longValue();
            r.put("winRate", pct(w, w + l));
            r.put("conversion", pct(w, ((Number) r.get("total")).longValue()));
        }
        return rows;
    }

    private List<Map<String, Object>> trend(LocalDate from, LocalDate to, Map<String, String> p) {
        Map<String, String> base = new HashMap<>(p);
        base.remove("createdFrom");
        base.remove("createdTo");
        CrmDealQuery q = new CrmDealQuery(Filter.of(base));
        List<Object> params = new ArrayList<>();
        params.add(java.sql.Date.valueOf(from.withDayOfMonth(1)));
        params.add(java.sql.Date.valueOf(to.withDayOfMonth(1)));
        params.addAll(q.params);
        params.addAll(q.params);
        return db.rows("WITH m AS (SELECT to_char(g, 'YYYY-MM') AS month FROM generate_series(?::date, ?::date,"
                + " interval '1 month') g), cr AS (SELECT to_char(d.\"createdAt\", 'YYYY-MM') AS month, count(*) AS"
                + " created" + CrmDealQuery.FROM + q.where + " GROUP BY 1), wn AS (SELECT to_char(d.\"wonAt\", 'YYYY-MM')"
                + " AS month, count(*) AS won, sum(d.amount) AS \"wonValue\"" + CrmDealQuery.FROM + q.where
                + " AND d.status = 'won' GROUP BY 1) SELECT m.month, COALESCE(cr.created, 0) AS created,"
                + " COALESCE(wn.won, 0) AS won, COALESCE(wn.\"wonValue\", 0) AS \"wonValue\" FROM m LEFT JOIN cr ON"
                + " cr.month = m.month LEFT JOIN wn ON wn.month = m.month ORDER BY m.month", params);
    }

    private Map<String, Object> quoteSummary(LocalDate from, LocalDate to, Map<String, String> p) {
        StringBuilder where = new StringBuilder(" WHERE q.\"organizationId\" = ? AND q.\"deletedAt\" IS NULL AND"
                + " q.\"issueDate\" >= ? AND q.\"issueDate\" <= ?");
        List<Object> params = new ArrayList<>(List.of(org(), from, to));
        if (!blank(p.get("ownerId"))) {
            where.append(" AND q.\"ownerId\" = ?");
            params.add(p.get("ownerId"));
        }
        if (!TenantContext.has(Permissions.CRM__DEAL_VIEW_ALL)) {
            where.append(" AND (q.\"ownerId\" = ? OR q.\"createdById\" = ?)");
            params.add(user());
            params.add(user());
        }
        List<Map<String, Object>> byStatus = db.rows("SELECT q.status AS key, count(*) AS count, COALESCE(sum(q.total),"
                + " 0) AS value FROM \"CrmQuotation\" q" + where + " GROUP BY 1 ORDER BY 2 DESC", params);
        long count = 0;
        double value = 0;
        long accepted = 0;
        long decided = 0;
        for (Map<String, Object> r : byStatus) {
            long n = ((Number) r.get("count")).longValue();
            count += n;
            value += ((Number) r.get("value")).doubleValue();
            String st = str(r.get("key"));
            if (List.of("accepted", "converted").contains(st)) {
                accepted += n;
            }
            if (List.of("accepted", "converted", "rejected", "expired").contains(st)) {
                decided += n;
            }
        }
        return Obj.of("count", count, "value", JsNumbers.js(Math.round(value * 100) / 100.0), "acceptanceRate",
                pct(accepted, decided), "byStatus", byStatus);
    }

    private Map<String, Object> activities(java.time.Instant start, java.time.Instant end, Map<String, String> p) {
        StringBuilder where = new StringBuilder(" WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND"
                + " a.type::text NOT IN ('deal_stage_change', 'note')");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!blank(p.get("ownerId"))) {
            where.append(" AND COALESCE(a.\"assignedToId\", a.\"createdById\") = ?");
            params.add(p.get("ownerId"));
        } else if (!TenantContext.has(Permissions.CRM__DEAL_VIEW_ALL)) {
            where.append(" AND COALESCE(a.\"assignedToId\", a.\"createdById\") = ?");
            params.add(user());
        }
        List<Object> done = new ArrayList<>(params);
        done.add(SqlRows.ts(start));
        done.add(SqlRows.ts(end));
        List<Map<String, Object>> byType = db.rows("SELECT a.type::text AS key, count(*) AS done FROM \"Activity\" a"
                + where + " AND a.status::text = 'done' AND a.\"completedAt\" >= ? AND a.\"completedAt\" < ? GROUP BY 1"
                + " ORDER BY 2 DESC", done);
        List<Object> od = new ArrayList<>(params);
        od.add(SqlRows.ts(java.time.Instant.now()));
        long overdue = db.count("SELECT count(*) FROM \"Activity\" a" + where + " AND a.status::text IN ('todo',"
                + " 'in_progress') AND a.\"dueAt\" < ?", od);
        return Obj.of("byType", byType, "overdue", overdue);
    }

    // ------------------------------------------------------------------ dashboard

    /** Personal sales dashboard ({@code scope = mine | all}). */
    @Transactional
    public Map<String, Object> dashboard(String scope) {
        config.ensureDefaults();
        boolean mine = !"all".equals(scope) || !TenantContext.has(Permissions.CRM__DEAL_VIEW_ALL);
        Map<String, String> f = new HashMap<>();
        if (mine) {
            f.put("mine", "true");
        }
        CrmDealQuery q = new CrmDealQuery(Filter.of(f));
        ZoneId zone = BusinessZone.get();
        LocalDate today = LocalDate.now(zone);
        java.time.Instant monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        List<Object> mp = new ArrayList<>(q.params);
        mp.add(SqlRows.ts(monthStart));
        Map<String, Object> totals = db.row("SELECT count(*) FILTER (WHERE d.status = 'open' AND d.kind ="
                + " 'opportunity') AS \"openCount\", COALESCE(sum(d.amount) FILTER (WHERE d.status = 'open' AND d.kind ="
                + " 'opportunity'), 0) AS \"openValue\", COALESCE(sum(d.amount * " + PROB + " / 100) FILTER (WHERE"
                + " d.status = 'open'), 0) AS weighted, count(*) FILTER (WHERE d.kind = 'lead' AND d.status = 'open') AS"
                + " leads" + CrmDealQuery.FROM + q.where, q.params.toArray());
        Map<String, Object> month = db.row("SELECT count(*) AS won, COALESCE(sum(d.amount), 0) AS \"wonValue\""
                + CrmDealQuery.FROM + q.where + " AND d.status = 'won' AND d.\"wonAt\" >= ?", mp.toArray());
        BigDecimal target = mine ? dec(db.row("SELECT COALESCE(max(\"monthlyTarget\"), 0) AS t FROM \"CrmTeam\" WHERE"
                + " \"organizationId\" = ? AND \"isActive\" AND (\"leaderId\" = ? OR ? = ANY(\"memberIds\"))", org(),
                user(), user()).get("t")) : dec(db.row("SELECT COALESCE(sum(\"monthlyTarget\"), 0) AS t FROM"
                + " \"CrmTeam\" WHERE \"organizationId\" = ? AND \"isActive\"", org()).get("t"));
        List<Object> soon = new ArrayList<>(q.params);
        soon.add(SqlRows.ts(today.plusDays(30).atStartOfDay(zone).toInstant()));
        List<Map<String, Object>> closing = db.rows("SELECT " + CrmDealQuery.COLUMNS + CrmDealQuery.FROM + q.where
                + " AND d.status = 'open' AND d.kind = 'opportunity' AND d.\"expectedClose\" IS NOT NULL AND"
                + " d.\"expectedClose\" < ? ORDER BY d.\"expectedClose\" LIMIT 8", soon);
        closing.forEach(q::decorate);
        List<Map<String, Object>> rotting = db.rows("SELECT " + CrmDealQuery.COLUMNS + CrmDealQuery.FROM + q.where
                + " AND d.status = 'open' AND d.kind = 'opportunity' AND d.\"stageChangedAt\" < now() AT TIME ZONE 'UTC'"
                + " - make_interval(days => COALESCE(s.\"rottingDays\", cs.\"rottingDays\", 14)) ORDER BY"
                + " d.\"stageChangedAt\" LIMIT 8", q.params);
        rotting.forEach(q::decorate);
        Map<String, Object> pendingApproval = TenantContext.has(Permissions.CRM__QUOTATION_APPROVE)
                ? db.row("SELECT count(*) AS count, COALESCE(sum(total), 0) AS value FROM \"CrmQuotation\" WHERE"
                        + " \"organizationId\" = ? AND status = 'pending_approval' AND \"deletedAt\" IS NULL", org())
                : null;
        java.time.Instant ts = today.atStartOfDay(zone).toInstant();
        java.time.Instant te = today.plusDays(1).atStartOfDay(zone).toInstant();
        Map<String, Object> acts = db.row("SELECT count(*) FILTER (WHERE \"dueAt\" < ?) AS overdue, count(*) FILTER"
                + " (WHERE \"dueAt\" >= ? AND \"dueAt\" < ?) AS today FROM \"Activity\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL AND status::text IN ('todo', 'in_progress') AND type::text NOT IN"
                + " ('deal_stage_change', 'note') AND COALESCE(\"assignedToId\", \"createdById\") = ?",
                SqlRows.ts(ts), SqlRows.ts(ts), SqlRows.ts(te), org(), user());
        Map<String, String> sp = new HashMap<>(f);
        sp.put("kind", "opportunity");
        sp.put("status", "open");
        CrmDealQuery sq = new CrmDealQuery(Filter.of(sp));
        List<Map<String, Object>> byStage = db.rows("SELECT s.id AS key, s.name AS label, s.color, count(d.id) AS count,"
                + " COALESCE(sum(d.amount), 0) AS amount" + CrmDealQuery.FROM + sq.where + " GROUP BY s.id, s.name,"
                + " s.color, s.sequence ORDER BY s.sequence", sq.params);
        return Obj.of("scope", mine ? "mine" : "all", "currency", orgCurrency(), "totals", totals, "month",
                Obj.of("won", month.get("won"), "wonValue", month.get("wonValue"), "target", JsNumbers.js(target),
                        "progress", target.signum() == 0 ? null : JsNumbers.js(dec(month.get("wonValue")).multiply(
                                BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP))),
                "activities", acts, "pendingApproval", pendingApproval, "byStage", byStage, "closingSoon", closing,
                "rotting", rotting);
    }

    // ------------------------------------------------------------------ customers

    /** Customer directory: customers and partners with CRM history, with pipeline and receivables figures. */
    @Transactional(readOnly = true)
    public Map<String, Object> customers(Map<String, String> p) {
        StringBuilder where = new StringBuilder(" WHERE pa.\"organizationId\" = ? AND pa.\"deletedAt\" IS NULL AND"
                + " (pa.\"isCustomer\" OR EXISTS (SELECT 1 FROM \"Deal\" x WHERE x.\"partnerId\" = pa.id AND"
                + " x.\"deletedAt\" IS NULL))");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!blank(p.get("q"))) {
            String like = "%" + BaseCrudService.escapeLike(p.get("q").trim()) + "%";
            where.append(" AND (pa.name ILIKE ? OR pa.code ILIKE ? OR pa.email ILIKE ? OR pa.phone ILIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        if ("true".equals(p.get("creditHold"))) {
            where.append(" AND pa.\"creditHold\"");
        }
        if (!blank(p.get("categoryId"))) {
            where.append(" AND pa.\"categoryId\" = ?");
            params.add(p.get("categoryId"));
        }
        int page = Math.max(1, CrmPipelineService.parseInt(p.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, CrmPipelineService.parseInt(p.get("pageSize"), 50)));
        String sort = switch (blank(p.get("sort")) ? "name" : p.get("sort")) {
            case "openValue" -> "\"openValue\" DESC";
            case "wonValue" -> "\"wonValue\" DESC";
            case "receivable" -> "\"openReceivables\" DESC";
            case "lastActivity" -> "\"lastActivityAt\" DESC NULLS LAST";
            default -> "pa.name";
        };
        List<Map<String, Object>> rows = db.rows("SELECT pa.id, pa.code, pa.name, pa.email, pa.phone, pa.\"isCompany\","
                + " pa.\"isCustomer\", pa.\"creditLimit\", pa.\"creditHold\", pc.name AS \"categoryName\","
                + " COALESCE(dd.open, 0) AS \"openOpportunities\", COALESCE(dd.\"openValue\", 0) AS \"openValue\","
                + " COALESCE(dd.\"wonValue\", 0) AS \"wonValue\", COALESCE(dd.won, 0) AS won, ar.open AS"
                + " \"openReceivables\", ar.overdue AS \"overdueReceivables\", la.at AS \"lastActivityAt\" FROM"
                + " \"Partner\" pa LEFT JOIN \"PartnerCategory\" pc ON pc.id = pa.\"categoryId\" LEFT JOIN LATERAL"
                + " (SELECT count(*) FILTER (WHERE status = 'open') AS open, sum(amount) FILTER (WHERE status = 'open') AS"
                + " \"openValue\", sum(amount) FILTER (WHERE status = 'won') AS \"wonValue\", count(*) FILTER (WHERE"
                + " status = 'won') AS won FROM \"Deal\" WHERE \"partnerId\" = pa.id AND \"deletedAt\" IS NULL) dd ON true"
                + " LEFT JOIN LATERAL (SELECT COALESCE(sum(\"amountResidual\"), 0) AS open, COALESCE(sum("
                + "\"amountResidual\") FILTER (WHERE \"dueDate\" < now()), 0) AS overdue FROM \"Document\" WHERE"
                + " \"partnerId\" = pa.id AND \"organizationId\" = pa.\"organizationId\" AND \"documentType\"::text ="
                + " 'sales_invoice' AND status::text NOT IN ('draft', 'cancelled', 'rejected')) ar ON true LEFT JOIN"
                + " LATERAL (SELECT max(\"occurredAt\") AS at FROM \"Activity\" WHERE \"partnerId\" = pa.id AND"
                + " \"deletedAt\" IS NULL) la ON true" + where + " ORDER BY " + sort + " OFFSET " + (long) (page - 1)
                * pageSize + " LIMIT " + pageSize, params);
        long total = db.count("SELECT count(*) FROM \"Partner\" pa" + where, params);
        return Obj.of("data", rows, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))));
    }

    /** Customer 360: partner, contacts, opportunities, quotations, recent activity, invoices, orders, credit, prices. */
    @Transactional
    public Map<String, Object> customer(String partnerId) {
        Map<String, Object> partner = db.require("Partner", partnerId, "Partner not found");
        partner.put("contacts", db.rows("SELECT id, \"firstName\", \"lastName\", position, email, phone, \"isPrimary\""
                + " FROM \"Contact\" WHERE \"partnerId\" = ? AND \"deletedAt\" IS NULL ORDER BY \"isPrimary\" DESC,"
                + " \"firstName\"", partnerId));
        CrmDealQuery q = new CrmDealQuery(Filter.of(Map.of("partnerId", partnerId)));
        List<Map<String, Object>> deals = db.rows("SELECT " + CrmDealQuery.COLUMNS + CrmDealQuery.FROM + q.where
                + " ORDER BY d.\"createdAt\" DESC LIMIT 100", q.params);
        deals.forEach(q::decorate);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("partner", partner);
        out.put("opportunities", deals);
        out.put("quotations", quotations.list(Map.of("partnerId", partnerId, "pageSize", "50")).get("data"));
        out.put("activities", db.rows("SELECT a.id, a.type::text AS type, a.title, a.status::text AS status,"
                + " a.\"dueAt\", a.\"occurredAt\", a.\"dealId\", a.outcome FROM \"Activity\" a WHERE a.\"partnerId\" = ?"
                + " AND a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL ORDER BY a.\"occurredAt\" DESC LIMIT 30",
                partnerId, org()));
        out.put("invoices", db.rows("SELECT id, \"documentNumber\", \"documentType\"::text AS \"documentType\","
                + " status::text AS status, \"issueDate\", \"dueDate\", \"totalAmount\", \"amountResidual\","
                + " \"paymentStatus\"::text AS \"paymentStatus\" FROM \"Document\" WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ? AND \"documentType\"::text IN ('sales_invoice', 'proforma_invoice', 'credit_note')"
                + " ORDER BY \"issueDate\" DESC LIMIT 20", org(), partnerId));
        out.put("orders", db.rows("SELECT id, \"orderNumber\", status::text AS status, \"totalAmount\" AS total, \"createdAt\", \"invoiceId\""
                + " FROM \"Order\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? ORDER BY \"createdAt\" DESC LIMIT"
                + " 20", org(), partnerId));
        out.put("credit", quotations.credit(partnerId, BigDecimal.ZERO));
        out.put("priceRules", db.rows("SELECT r.*, pr.name AS \"productName\" FROM \"CrmPriceRule\" r LEFT JOIN"
                + " \"Product\" pr ON pr.id = r.\"productId\" WHERE r.\"organizationId\" = ? AND r.\"isActive\" AND"
                + " (r.\"partnerId\" = ? OR r.\"partnerCategoryId\" = ?) ORDER BY r.priority DESC", org(), partnerId,
                partner.get("categoryId") == null ? "" : partner.get("categoryId")));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private String orgCurrency() {
        Map<String, Object> o = db.row("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        return o == null || o.get("currencyCode") == null ? "USD" : str(o.get("currencyCode"));
    }

    static Number pct(long part, long whole) {
        return whole == 0 ? 0 : JsNumbers.js(Math.round(part * 1000.0 / whole) / 10.0);
    }

    private static List<Object> concat(List<Object> a, List<Object> b) {
        List<Object> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
