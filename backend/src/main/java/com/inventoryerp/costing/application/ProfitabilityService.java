package com.inventoryerp.costing.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profitability in three separate layers (Cost Management CM-9, ADR-S040, AD-7). Layers are never summed or mixed.
 *
 * <ul>
 *   <li><b>ACTUAL</b> — booked GL only. Every posted P&amp;L line of the period is classified by its account's report
 *       section (revenue / contra revenue → revenue; cost of sales → cost of sales, 59xx → variances; operating
 *       expense → overhead, 6460 → distribution) and attributed to a commercial segment (customer, product,
 *       project; branch, cost centre and channel ride along). Lines with no segment are <em>unallocated</em>; other
 *       income / other expense accounts are <em>out of scope</em>, both listed per account. Reconciliation: Σ segment
 *       facts + unallocated + out of scope = GL net income, exactly.</li>
 *   <li><b>STANDARD</b> — posted sales invoice quantities × standard price (the product's sales price) and × standard
 *       cost (the active standard, else the product cost price).</li>
 *   <li><b>ESTIMATE</b> — approved project budgets (revenue and cost).</li>
 * </ul>
 *
 * <p>Statistical allocations (CM-3) enrich the ACTUAL view as {@code statistical_overhead} facts, flagged and
 * excluded from the reconciliation by construction.
 */
@Service
public class ProfitabilityService {

    static final Set<String> LAYERS = Set.of("actual", "standard", "estimate");
    static final Set<String> GROUPS = Set.of("customer", "product", "project", "branch", "costCenter", "channel");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProfitabilityService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** P&amp;L lines of the period, income-positive, with their measure and segment attributes. */
    private static final String PL = "WITH pl AS (SELECT jl.\"accountId\", a.code, c.\"reportSection\"::text AS section,"
            + " jl.\"branchId\", jl.\"costCenterId\", coalesce(jl.dimensions ->> 'customerId', CASE WHEN"
            + " c.\"reportSection\"::text IN ('revenue', 'contra_revenue', 'cogs') THEN jl.\"partnerId\" END) AS customer,"
            + " jl.dimensions ->> 'productId' AS product, jl.dimensions ->> 'projectId' AS project, jl.dimensions ->>"
            + " 'channel' AS channel, jl.\"baseCredit\" - jl.\"baseDebit\" AS net FROM \"JournalLine\" jl JOIN"
            + " \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" JOIN \"Account\" a ON a.id = jl.\"accountId\" JOIN"
            + " \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE jl.\"organizationId\" = ? AND e.status <> 'draft'"
            + " AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ? AND c.classification::text IN ('revenue',"
            + " 'expense')), cls AS (SELECT *, CASE WHEN section IN ('other_income', 'other_expense') THEN 'out_of_scope'"
            + " WHEN customer IS NULL AND product IS NULL AND project IS NULL THEN 'unallocated' ELSE 'segment' END AS"
            + " bucket, CASE WHEN section IN ('revenue', 'contra_revenue') THEN 'revenue' WHEN section = 'cogs' AND code"
            + " LIKE '59%' THEN 'variances' WHEN section = 'cogs' THEN 'cogs' WHEN code = '6460' THEN 'distribution' ELSE"
            + " 'overhead' END AS measure FROM pl) ";

    /** Build (or rebuild) all three layers for the period; the previous build of the same period is superseded. */
    @Transactional
    public Map<String, Object> build(String fromText, String toText) {
        LocalDate from = day(fromText, "from");
        LocalDate to = day(toText, "to");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("profitability:" + org()).query()
                .listOfRows();
        jdbc.sql("UPDATE \"ProfitabilityRun\" SET status = 'superseded' WHERE \"organizationId\" = ? AND \"periodFrom\" ="
                + " ? AND \"periodTo\" = ? AND status = 'current'").params(org(), Date.valueOf(from), Date.valueOf(to))
                .update();
        Timestamp start = Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        String id = UUID.randomUUID().toString();
        BigDecimal gl = jdbc.sql(PL + "SELECT coalesce(sum(net), 0) FROM cls").params(org(), start, end)
                .query(BigDecimal.class).single();
        jdbc.sql("INSERT INTO \"ProfitabilityRun\" (id, \"organizationId\", \"periodFrom\", \"periodTo\","
                + " \"glNetIncome\", \"segmentNet\", unallocated, \"outOfScope\", difference, \"createdBy\") VALUES (?, ?,"
                + " ?, ?, ?, 0, 0, 0, 0, ?)").params(id, org(), Date.valueOf(from), Date.valueOf(to), gl,
                TenantContext.userId()).update();

        // ACTUAL: segment facts (amount positive for revenue, positive for cost measures)
        jdbc.sql(PL + "INSERT INTO \"ProfitabilityFact\" (id, \"organizationId\", \"runId\", layer, measure, \"branchId\","
                + " \"costCenterId\", \"customerId\", \"productId\", \"projectId\", channel, amount) SELECT"
                + " gen_random_uuid()::text, ?, ?, 'actual', measure, \"branchId\", \"costCenterId\", customer, product,"
                + " project, channel, sum(CASE WHEN measure = 'revenue' THEN net ELSE -net END) FROM cls WHERE bucket ="
                + " 'segment' GROUP BY measure, \"branchId\", \"costCenterId\", customer, product, project, channel")
                .params(org(), start, end, org(), id).update();
        jdbc.sql(PL + "INSERT INTO \"ProfitabilityReconLine\" (id, \"organizationId\", \"runId\", bucket, \"accountId\","
                + " amount) SELECT gen_random_uuid()::text, ?, ?, bucket, \"accountId\", sum(net) FROM cls WHERE bucket <>"
                + " 'segment' GROUP BY bucket, \"accountId\" HAVING sum(net) <> 0").params(org(), start, end, org(), id)
                .update();
        // statistical enrichment (flagged; never reconciled)
        jdbc.sql("INSERT INTO \"ProfitabilityFact\" (id, \"organizationId\", \"runId\", layer, measure, \"costCenterId\","
                + " amount, statistical) SELECT gen_random_uuid()::text, ?, ?, 'actual', 'statistical_overhead',"
                + " l.\"receiverId\", sum(l.amount), true FROM \"AllocationLine\" l JOIN \"AllocationRun\" r ON r.id ="
                + " l.\"runId\" WHERE r.\"organizationId\" = ? AND r.\"postingMode\" = 'statistical' AND r.status ="
                + " 'posted' AND r.\"periodFrom\" >= ? AND r.\"periodTo\" <= ? GROUP BY l.\"receiverId\"").params(org(), id,
                org(), Date.valueOf(from), Date.valueOf(to)).update();

        // STANDARD: invoiced quantities × standard price / cost
        jdbc.sql("INSERT INTO \"ProfitabilityFact\" (id, \"organizationId\", \"runId\", layer, measure, \"branchId\","
                + " \"customerId\", \"productId\", amount) SELECT gen_random_uuid()::text, ?, ?, 'standard', m.measure,"
                + " d.\"branchId\", d.\"partnerId\", l.\"productId\", sum(round(l.quantity * CASE WHEN m.measure ="
                + " 'revenue' THEN p.\"salesPrice\" ELSE coalesce((SELECT s.\"totalCost\" FROM \"ProductStandardCost\" s"
                + " WHERE s.\"productId\" = p.id AND s.\"organizationId\" = d.\"organizationId\" AND s.status = 'active'"
                + " AND s.\"effectiveFrom\" <= ? ORDER BY s.\"effectiveFrom\" DESC LIMIT 1), p.\"costPrice\") END, 2))"
                + " FROM \"Document\" d JOIN \"DocumentLine\" l ON l.\"documentId\" = d.id JOIN \"Product\" p ON p.id ="
                + " l.\"productId\" CROSS JOIN (VALUES ('revenue'), ('cogs')) m(measure) WHERE d.\"organizationId\" = ?"
                + " AND d.\"documentType\"::text = 'sales_invoice' AND d.status::text NOT IN ('draft', 'cancelled') AND"
                + " d.\"issueDate\" >= ? AND d.\"issueDate\" < ? GROUP BY m.measure, d.\"branchId\", d.\"partnerId\","
                + " l.\"productId\"").params(org(), id, Date.valueOf(to), org(), start, end).update();

        // ESTIMATE: approved project budgets
        jdbc.sql("INSERT INTO \"ProfitabilityFact\" (id, \"organizationId\", \"runId\", layer, measure, \"branchId\","
                + " \"customerId\", \"projectId\", amount) SELECT gen_random_uuid()::text, ?, ?, 'estimate', m.measure,"
                + " p.\"branchId\", p.\"customerId\", p.id, sum(CASE WHEN m.measure = 'revenue' THEN bl.revenue ELSE"
                + " bl.amount END) FROM \"ProjectBudget\" b JOIN \"ProjectBudgetLine\" bl ON bl.\"budgetId\" = b.id JOIN"
                + " \"Project\" p ON p.id = b.\"projectId\" CROSS JOIN (VALUES ('revenue'), ('cogs')) m(measure) WHERE"
                + " b.\"organizationId\" = ? AND b.status = 'approved' AND p.status NOT IN ('cancelled') GROUP BY"
                + " m.measure, p.\"branchId\", p.\"customerId\", p.id").params(org(), id, org()).update();

        Map<String, Object> rec = SqlRows.one(jdbc.sql("SELECT coalesce((SELECT sum(CASE WHEN measure = 'revenue' THEN"
                + " amount ELSE -amount END) FROM \"ProfitabilityFact\" WHERE \"runId\" = ? AND layer = 'actual' AND NOT"
                + " statistical), 0) AS segment, coalesce((SELECT sum(amount) FROM \"ProfitabilityReconLine\" WHERE"
                + " \"runId\" = ? AND bucket = 'unallocated'), 0) AS unallocated, coalesce((SELECT sum(amount) FROM"
                + " \"ProfitabilityReconLine\" WHERE \"runId\" = ? AND bucket = 'out_of_scope'), 0) AS oos").params(id, id,
                id));
        BigDecimal segment = SqlRows.dec(rec, "segment");
        BigDecimal unallocated = SqlRows.dec(rec, "unallocated");
        BigDecimal oos = SqlRows.dec(rec, "oos");
        BigDecimal diff = gl.subtract(segment).subtract(unallocated).subtract(oos);
        jdbc.sql("UPDATE \"ProfitabilityRun\" SET \"segmentNet\" = ?, unallocated = ?, \"outOfScope\" = ?, difference ="
                + " ? WHERE id = ?").params(segment, unallocated, oos, diff, id).update();
        audit.recordInTx(AuditInput.of("ProfitabilityRun", id, "create", null, Obj.of("from", from.toString(), "to",
                to.toString(), "difference", diff)));
        return run(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ProfitabilityRun\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"createdAt\" DESC").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> run(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"ProfitabilityRun\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Profitability run not found");
        }
        return r;
    }

    /**
     * Facts of ONE layer grouped by a segment attribute, with contribution margins: CM1 = revenue − cost of sales;
     * CM2 = CM1 − variances − distribution; CM3 = CM2 − overhead. Statistical overhead is shown apart (flagged) and
     * never enters CM3.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> facts(String runId, String layer, String groupBy) {
        run(runId);
        if (layer == null || !LAYERS.contains(layer)) {
            throw ApiException.badRequest("layer must be one of the following values: actual, standard, estimate");
        }
        String g = groupBy == null ? "customer" : groupBy;
        if (!GROUPS.contains(g)) {
            throw ApiException.badRequest("groupBy must be one of the following values: customer, product, project,"
                    + " branch, costCenter, channel");
        }
        String col = switch (g) {
            case "product" -> "\"productId\"";
            case "project" -> "\"projectId\"";
            case "branch" -> "\"branchId\"";
            case "costCenter" -> "\"costCenterId\"";
            case "channel" -> "channel";
            default -> "\"customerId\"";
        };
        List<Map<String, Object>> raw = SqlRows.list(jdbc.sql("SELECT " + col + " AS segment, measure, statistical,"
                + " sum(amount) AS amount FROM \"ProfitabilityFact\" WHERE \"runId\" = ? AND layer = ? GROUP BY 1, 2, 3"
                + " ORDER BY 1 NULLS LAST, 2").params(runId, layer));
        Map<Object, Map<String, BigDecimal>> bySeg = new LinkedHashMap<>();
        for (Map<String, Object> r : raw) {
            String measure = Boolean.TRUE.equals(r.get("statistical")) ? "statistical_overhead"
                    : (String) r.get("measure");
            bySeg.computeIfAbsent(r.get("segment") == null ? "" : r.get("segment"), k -> new LinkedHashMap<>())
                    .merge(measure, SqlRows.dec(r, "amount"), BigDecimal::add);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Object, Map<String, BigDecimal>> e : bySeg.entrySet()) {
            Map<String, BigDecimal> m = e.getValue();
            BigDecimal cm1 = v(m, "revenue").subtract(v(m, "cogs"));
            BigDecimal cm2 = cm1.subtract(v(m, "variances")).subtract(v(m, "distribution"));
            BigDecimal cm3 = cm2.subtract(v(m, "overhead"));
            rows.add(Obj.of("segment", "".equals(e.getKey()) ? null : e.getKey(), "revenue", JsNumbers.dec(v(m,
                    "revenue")), "cogs", JsNumbers.dec(v(m, "cogs")), "variances", JsNumbers.dec(v(m, "variances")),
                    "distribution", JsNumbers.dec(v(m, "distribution")), "overhead", JsNumbers.dec(v(m, "overhead")),
                    "cm1", JsNumbers.dec(cm1), "cm2", JsNumbers.dec(cm2), "cm3", JsNumbers.dec(cm3),
                    "statisticalOverhead", JsNumbers.dec(v(m, "statistical_overhead"))));
        }
        return Obj.of("runId", runId, "layer", layer, "groupBy", g, "rows", rows);
    }

    /** ACTUAL reconciliation: segments + unallocated + out of scope (listed per account) = GL net income. */
    @Transactional(readOnly = true)
    public Map<String, Object> reconciliation(String runId) {
        Map<String, Object> r = run(runId);
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.bucket, l.\"accountId\", a.code, a.name,"
                + " l.amount FROM \"ProfitabilityReconLine\" l JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE"
                + " l.\"runId\" = ? ORDER BY l.bucket, a.code").param(runId));
        BigDecimal statistical = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"ProfitabilityFact\" WHERE \"runId\" ="
                + " ? AND statistical").param(runId).query(BigDecimal.class).single();
        return Obj.of("runId", runId, "glNetIncome", r.get("glNetIncome"), "segmentNet", r.get("segmentNet"),
                "unallocated", r.get("unallocated"), "outOfScope", r.get("outOfScope"), "difference",
                r.get("difference"), "accounts", lines, "statisticalExcluded", JsNumbers.dec(statistical));
    }

    private static BigDecimal v(Map<String, BigDecimal> m, String k) {
        return m.getOrDefault(k, BigDecimal.ZERO);
    }

    private static LocalDate day(String v, String field) {
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest(field + " should not be empty");
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a valid ISO 8601 date string");
        }
    }
}
