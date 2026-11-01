package com.inventoryerp.costing.application;

import com.inventoryerp.costing.application.AbcCalculator.Activity;
import com.inventoryerp.costing.application.AbcCalculator.Assignment;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activity-based costing (ADR-S030 §3), analytic only: activities with a GL resource pool and a driver measured from
 * operational data, and runs that assign each pool to customers or products and report revenue, estimated cost of
 * goods, activity cost and the margin after activity costs. Nothing is posted.
 */
@Service
public class ActivityBasedCostingService {

    /** Drivers per cost object, with their labels. */
    public static final Map<String, Map<String, String>> DRIVERS = Map.of(
            "customer", ordered("sales_invoices", "Posted sales invoices", "invoice_lines", "Invoice lines",
                    "credit_notes", "Credit notes", "sales_orders", "Confirmed sales orders", "payments_received",
                    "Payments received"),
            "product", ordered("units_sold", "Units sold", "invoice_lines", "Invoice lines", "production_orders",
                    "Completed production orders", "stock_receipts", "Stock receipts"));

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{1,30}$");

    /** Activity input (update: nulls keep the stored value). */
    public record ActivityInput(String code, String name, String description, String costObject, String driver,
            List<String> accountIds, String costCenterId, BigDecimal poolPercent, Boolean isActive) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ActivityBasedCostingService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    public Map<String, Object> drivers() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String object : List.of("customer", "product")) {
            List<Map<String, Object>> list = new ArrayList<>();
            DRIVERS.get(object).forEach((k, v) -> list.add(Obj.of("driver", k, "label", v)));
            out.put(object, list);
        }
        return out;
    }

    // ---- activities -----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> activities() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"AbcActivity\" WHERE \"organizationId\" = ? ORDER BY \"costObject\","
                + " code").param(org()));
    }

    private Map<String, Object> activity(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"AbcActivity\" WHERE id = ? AND \"organizationId\""
                + " = ?").params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Activity not found");
        }
        return a;
    }

    @Transactional
    public Map<String, Object> create(ActivityInput in) {
        if (in.code() == null || !CODE.matcher(in.code()).matches()) {
            throw ApiException.badRequest("code must be 1 to 30 letters, digits, '-' or '_'");
        }
        if (jdbc.sql("SELECT count(*) FROM \"AbcActivity\" WHERE \"organizationId\" = ? AND code = ?").params(org(),
                in.code()).query(Long.class).single() > 0) {
            throw ApiException.conflict("An activity with code '" + in.code() + "' already exists");
        }
        String object = costObject(in.costObject());
        String driver = driver(object, in.driver());
        List<String> accounts = accounts(in.accountIds());
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"AbcActivity\" (id, \"organizationId\", code, name, description, \"costObject\", driver,"
                + " \"accountIds\", \"costCenterId\", \"poolPercent\", \"isActive\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?)").params(id, org(), in.code(), name(in.name()), in.description(), object, driver,
                        accounts.toArray(String[]::new), costCenter(in.costCenterId()), percent(in.poolPercent()),
                        in.isActive() == null || in.isActive()).update();
        audit.recordInTx(AuditInput.of("AbcActivity", id, "create", null, Obj.of("code", in.code())));
        return activity(id);
    }

    @Transactional
    public Map<String, Object> update(String id, ActivityInput in) {
        Map<String, Object> before = activity(id);
        String object = (String) before.get("costObject");
        jdbc.sql("UPDATE \"AbcActivity\" SET name = ?, description = ?, driver = ?, \"accountIds\" = ?,"
                + " \"costCenterId\" = ?, \"poolPercent\" = ?, \"isActive\" = ?, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?")
                .params(in.name() == null ? before.get("name") : name(in.name()),
                        in.description() == null ? before.get("description") : in.description(),
                        in.driver() == null ? before.get("driver") : driver(object, in.driver()),
                        in.accountIds() == null ? strings(before.get("accountIds")).toArray(String[]::new)
                                : accounts(in.accountIds()).toArray(String[]::new),
                        in.costCenterId() == null ? before.get("costCenterId")
                                : in.costCenterId().isBlank() ? null : costCenter(in.costCenterId()),
                        in.poolPercent() == null ? before.get("poolPercent") : percent(in.poolPercent()),
                        in.isActive() == null ? before.get("isActive") : in.isActive(), id, org()).update();
        Map<String, Object> after = activity(id);
        audit.recordInTx(AuditInput.of("AbcActivity", id, "update", before, after));
        return after;
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        activity(id);
        jdbc.sql("DELETE FROM \"AbcActivity\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("AbcActivity", id, "delete"));
        return Obj.of("id", id, "deleted", true);
    }

    // ---- runs -----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"AbcRun\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\" DESC"
                + " LIMIT 100").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> run(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"AbcRun\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (r == null) {
            throw ApiException.notFound("ABC run not found");
        }
        r.put("activities", SqlRows.list(jdbc.sql("SELECT * FROM \"AbcRunActivity\" WHERE \"runId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY code").params(id, org())));
        List<Map<String, Object>> results = SqlRows.list(jdbc.sql("SELECT * FROM \"AbcRunResult\" WHERE \"runId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY margin").params(id, org()));
        results.forEach(x -> x.put("breakdown", com.inventoryerp.shared.web.EntityJson.parseJson(x.get("breakdown"))));
        r.put("results", results);
        return r;
    }

    @Transactional
    public Map<String, Object> compute(String costObjectRaw, String fromRaw, String toRaw) {
        String object = costObject(costObjectRaw);
        LocalDate from = MaterialLedgerService.day(fromRaw, "from");
        LocalDate to = MaterialLedgerService.day(toRaw, "to");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        List<Map<String, Object>> acts = SqlRows.list(jdbc.sql("SELECT * FROM \"AbcActivity\" WHERE \"organizationId\" ="
                + " ? AND \"costObject\" = ? AND \"isActive\" ORDER BY code").params(org(), object));
        if (acts.isEmpty()) {
            throw ApiException.badRequest("Define at least one active " + object + " activity first");
        }
        Timestamp start = Timestamp.valueOf(from.atStartOfDay());
        Timestamp end = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        List<Assignment> assignments = new ArrayList<>();
        Map<String, Map<String, Object>> activityRows = new LinkedHashMap<>();
        BigDecimal poolTotal = BigDecimal.ZERO;
        BigDecimal unassigned = BigDecimal.ZERO;
        for (Map<String, Object> a : acts) {
            BigDecimal pool = pool(a, start, end);
            Assignment as = AbcCalculator.assign(new Activity((String) a.get("id"), pool,
                    volumes(object, (String) a.get("driver"), start, end)));
            assignments.add(as);
            activityRows.put(as.id(), a);
            poolTotal = poolTotal.add(as.pool());
            unassigned = unassigned.add(as.unassigned());
        }
        Map<String, BigDecimal> activityCost = AbcCalculator.totals(assignments);
        Map<String, BigDecimal[]> economics = economics(object, start, end);
        Set<String> objects = new LinkedHashSet<>(activityCost.keySet());
        objects.addAll(economics.keySet());

        String runId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"AbcRun\" (id, \"organizationId\", \"costObject\", \"periodFrom\", \"periodTo\","
                + " \"poolTotal\", \"assignedTotal\", \"unassignedTotal\", \"objectCount\", \"createdBy\") VALUES (?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?)").params(runId, org(), object, Date.valueOf(from), Date.valueOf(to), poolTotal,
                        poolTotal.subtract(unassigned), unassigned, objects.size(), TenantContext.userId()).update();
        for (Assignment as : assignments) {
            Map<String, Object> a = activityRows.get(as.id());
            jdbc.sql("INSERT INTO \"AbcRunActivity\" (id, \"organizationId\", \"runId\", \"activityId\", code, name,"
                    + " driver, pool, \"driverTotal\", rate, assigned) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), runId, as.id(), a.get("code"), a.get("name"),
                            a.get("driver"), as.pool(), as.driverTotal(), as.rate(), as.pool().subtract(as.unassigned()))
                    .update();
        }
        Map<String, String> names = names(object, objects);
        for (String o : objects) {
            BigDecimal[] eco = economics.getOrDefault(o, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal cost = activityCost.getOrDefault(o, BigDecimal.ZERO);
            List<Map<String, Object>> breakdown = new ArrayList<>();
            for (Assignment as : assignments) {
                BigDecimal c = as.costs().get(o);
                if (c != null) {
                    breakdown.add(Obj.of("code", activityRows.get(as.id()).get("code"), "cost", c.toPlainString()));
                }
            }
            BigDecimal revenue = eco[0].setScale(2, RoundingMode.HALF_UP);
            BigDecimal cogs = eco[1].setScale(2, RoundingMode.HALF_UP);
            jdbc.sql("INSERT INTO \"AbcRunResult\" (id, \"organizationId\", \"runId\", \"objectId\", \"objectName\","
                    + " revenue, \"costOfGoods\", \"activityCost\", margin, breakdown) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?::jsonb)").params(UUID.randomUUID().toString(), org(), runId, o, names.get(o), revenue, cogs,
                            cost, revenue.subtract(cogs).subtract(cost), EntityJson.toJson(breakdown)).update();
        }
        audit.recordInTx(AuditInput.of("AbcRun", runId, "create", null, Obj.of("costObject", object, "from",
                from.toString(), "to", to.toString())));
        return run(runId);
    }

    /**
     * Σ(debit − credit) of the activity's accounts in the period × pool %. A cost centre filter includes every
     * descendant of the centre (same roll-up as the cost-centre P&amp;L).
     */
    private BigDecimal pool(Map<String, Object> a, Timestamp start, Timestamp end) {
        List<String> accounts = strings(a.get("accountIds"));
        if (accounts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        String cc = (String) a.get("costCenterId");
        BigDecimal sum = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND e.status::text IN"
                + " ('posted', 'reversed') AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ? AND l.\"accountId\" = ANY"
                + " (?)" + (cc == null ? "" : " AND l.\"costCenterId\" IN (WITH RECURSIVE t AS (SELECT id FROM"
                        + " \"CostCenter\" WHERE id = ? UNION ALL SELECT c.id FROM \"CostCenter\" c JOIN t ON"
                        + " c.\"parentId\" = t.id) SELECT id FROM t)"))
                .params(cc == null ? List.of(org(), start, end, accounts.toArray(String[]::new))
                        : List.of(org(), start, end, accounts.toArray(String[]::new), cc))
                .query(BigDecimal.class).single();
        return sum.multiply(SqlRows.dec(a, "poolPercent")).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    private static final String POSTED = " d.status::text NOT IN ('draft', 'cancelled', 'rejected')";

    /** Driver volume per cost object in the period. */
    Map<String, BigDecimal> volumes(String object, String driver, Timestamp start, Timestamp end) {
        String sql = switch (object + ":" + driver) {
            case "customer:sales_invoices" -> "SELECT d.\"partnerId\" AS k, count(*) AS v FROM \"Document\" d WHERE"
                    + " d.\"organizationId\" = ? AND d.\"documentType\"::text = 'sales_invoice' AND" + POSTED
                    + " AND d.\"issueDate\" >= ? AND d.\"issueDate\" < ? GROUP BY 1";
            case "customer:credit_notes" -> "SELECT d.\"partnerId\" AS k, count(*) AS v FROM \"Document\" d WHERE"
                    + " d.\"organizationId\" = ? AND d.\"documentType\"::text = 'credit_note' AND" + POSTED
                    + " AND d.\"issueDate\" >= ? AND d.\"issueDate\" < ? GROUP BY 1";
            case "customer:invoice_lines" -> "SELECT d.\"partnerId\" AS k, count(*) AS v FROM \"DocumentLine\" l JOIN"
                    + " \"Document\" d ON d.id = l.\"documentId\" WHERE d.\"organizationId\" = ? AND"
                    + " d.\"documentType\"::text = 'sales_invoice' AND" + POSTED + " AND d.\"issueDate\" >= ? AND"
                    + " d.\"issueDate\" < ? GROUP BY 1";
            case "customer:sales_orders" -> "SELECT o.\"partnerId\" AS k, count(*) AS v FROM \"SalesOrder\" o WHERE"
                    + " o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND o.\"confirmedAt\" >= ? AND"
                    + " o.\"confirmedAt\" < ? GROUP BY 1";
            case "customer:payments_received" -> "SELECT p.\"partnerId\" AS k, count(*) AS v FROM \"Payment\" p WHERE"
                    + " p.\"organizationId\" = ? AND p.direction::text = 'inbound' AND p.status::text NOT IN ('draft',"
                    + " 'cancelled') AND p.\"paymentDate\" >= ? AND p.\"paymentDate\" < ? GROUP BY 1";
            case "product:units_sold" -> "SELECT l.\"productId\" AS k, sum(l.quantity) AS v FROM \"DocumentLine\" l JOIN"
                    + " \"Document\" d ON d.id = l.\"documentId\" WHERE d.\"organizationId\" = ? AND"
                    + " d.\"documentType\"::text = 'sales_invoice' AND" + POSTED + " AND d.\"issueDate\" >= ? AND"
                    + " d.\"issueDate\" < ? AND l.\"productId\" IS NOT NULL GROUP BY 1";
            case "product:invoice_lines" -> "SELECT l.\"productId\" AS k, count(*) AS v FROM \"DocumentLine\" l JOIN"
                    + " \"Document\" d ON d.id = l.\"documentId\" WHERE d.\"organizationId\" = ? AND"
                    + " d.\"documentType\"::text = 'sales_invoice' AND" + POSTED + " AND d.\"issueDate\" >= ? AND"
                    + " d.\"issueDate\" < ? AND l.\"productId\" IS NOT NULL GROUP BY 1";
            case "product:production_orders" -> "SELECT o.\"outputProductId\" AS k, count(*) AS v FROM"
                    + " \"ProductionOrder\" o WHERE o.\"organizationId\" = ? AND o.status::text = 'completed' AND"
                    + " o.\"completedAt\" >= ? AND o.\"completedAt\" < ? AND o.\"outputProductId\" IS NOT NULL GROUP BY 1";
            case "product:stock_receipts" -> "SELECT g.\"productId\" AS k, count(*) AS v FROM \"InventoryLedger\" g WHERE"
                    + " g.\"organizationId\" = ? AND g.type::text = 'receipt' AND g.\"createdAt\" >= ? AND g.\"createdAt\" <"
                    + " ? GROUP BY 1";
            default -> throw ApiException.badRequest("Unknown driver " + driver + " for " + object);
        };
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(sql).params(org(), start, end))) {
            if (r.get("k") != null) {
                out.put((String) r.get("k"), SqlRows.dec(r, "v"));
            }
        }
        return out;
    }

    /**
     * Revenue (net of tax, invoices − credit notes) and <b>booked</b> cost of goods per object: the value of the stock
     * movements the invoices and credit notes posted ({@code InventoryLedger}, issue = +, return = −), so the margin
     * agrees with the GL cost of sales (CM-0). Lines without a stock movement (services) carry no cost of goods.
     */
    private Map<String, BigDecimal[]> economics(String object, Timestamp start, Timestamp end) {
        String key = "customer".equals(object) ? "d.\"partnerId\"" : "l.\"productId\"";
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        String scope = " d.\"organizationId\" = ? AND d.\"documentType\"::text IN ('sales_invoice', 'credit_note') AND"
                + POSTED + " AND d.\"issueDate\" >= ? AND d.\"issueDate\" < ?";
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT " + key + " AS k,"
                + " sum(CASE WHEN d.\"documentType\"::text = 'credit_note' THEN -l.subtotal ELSE l.subtotal END) AS rev"
                + " FROM \"DocumentLine\" l JOIN \"Document\" d ON d.id = l.\"documentId\" WHERE" + scope
                + ("product".equals(object) ? " AND l.\"productId\" IS NOT NULL" : "") + " GROUP BY 1")
                .params(org(), start, end))) {
            if (r.get("k") != null) {
                out.put((String) r.get("k"), new BigDecimal[] {SqlRows.dec(r, "rev"), BigDecimal.ZERO});
            }
        }
        String ledgerKey = "customer".equals(object) ? "d.\"partnerId\"" : "g.\"productId\"";
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT " + ledgerKey + " AS k,"
                + " sum(-g.\"quantityChange\" * g.\"unitCost\") AS cogs FROM \"InventoryLedger\" g JOIN \"Document\" d"
                + " ON d.id = g.\"referenceId\" WHERE g.\"organizationId\" = ? AND g.\"referenceType\" IN"
                + " ('sales_invoice', 'invoice', 'document', 'credit_note') AND" + scope + " GROUP BY 1")
                .params(org(), org(), start, end))) {
            if (r.get("k") != null) {
                out.computeIfAbsent((String) r.get("k"), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[1] =
                        SqlRows.dec(r, "cogs");
            }
        }
        return out;
    }

    private Map<String, String> names(String object, Set<String> ids) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String table = "customer".equals(object) ? "Partner" : "Product";
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, name FROM \"" + table + "\" WHERE"
                + " \"organizationId\" = ? AND id = ANY (?)").params(org(), ids.toArray(String[]::new)))) {
            out.put((String) r.get("id"), (String) r.get("name"));
        }
        return out;
    }

    // ---- validation -----------------------------------------------------------------------------------------------

    private static String costObject(String v) {
        if (!"customer".equals(v) && !"product".equals(v)) {
            throw ApiException.badRequest("costObject must be one of the following values: customer, product");
        }
        return v;
    }

    private static String driver(String object, String driver) {
        if (driver == null || !DRIVERS.get(object).containsKey(driver)) {
            throw ApiException.badRequest("driver must be one of the following values: "
                    + String.join(", ", DRIVERS.get(object).keySet()));
        }
        return driver;
    }

    private List<String> accounts(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw ApiException.badRequest("accountIds should not be empty");
        }
        List<String> out = new ArrayList<>(new LinkedHashSet<>(ids));
        long found = jdbc.sql("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ? AND id = ANY (?) AND"
                + " \"deletedAt\" IS NULL").params(org(), out.toArray(String[]::new)).query(Long.class).single();
        if (found != out.size()) {
            throw ApiException.badRequest("One or more accounts do not exist in this organization");
        }
        return out;
    }

    private String costCenter(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (jdbc.sql("SELECT count(*) FROM \"CostCenter\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .query(Long.class).single() == 0) {
            throw ApiException.badRequest("Cost centre not found");
        }
        return id;
    }

    private static BigDecimal percent(BigDecimal p) {
        BigDecimal v = p == null ? new BigDecimal("100") : p;
        if (v.signum() <= 0 || v.compareTo(new BigDecimal("100")) > 0) {
            throw ApiException.badRequest("poolPercent must be greater than 0 and at most 100");
        }
        return v;
    }

    private static String name(String n) {
        if (n == null || n.isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        return n.trim();
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }
}
