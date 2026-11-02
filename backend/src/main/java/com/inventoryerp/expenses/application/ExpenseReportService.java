package com.inventoryerp.expenses.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expense reports (EX-6, ADR-S052): spend by category / branch / department / cost centre / project / supplier /
 * claimant / paid-by / month (allocation lines split the dimension views), monthly trend, the approval-to-payment
 * pipeline, outstanding reimbursements, open advances, petty cash, schedules and capital purchases.
 */
@Service
public class ExpenseReportService {

    private static final String LIVE = " e.\"organizationId\" = ? AND e.\"deletedAt\" IS NULL AND e.status::text NOT IN"
            + " ('VOID', 'CANCELLED', 'REJECTED')";

    private static final Map<String, String> GROUPS = Map.of(
            "category", "COALESCE(e.\"categoryId\", '')",
            "branch", "COALESCE(a.\"branchId\", e.\"branchId\", '')",
            "department", "COALESCE(a.\"departmentId\", e.\"departmentId\", '')",
            "costCenter", "COALESCE(a.\"costCenterId\", e.\"costCenterId\", '')",
            "project", "COALESCE(a.\"projectId\", e.\"projectId\", '')",
            "supplier", "COALESCE(e.\"supplierId\", '')",
            "claimant", "COALESCE(e.\"claimantId\", '')",
            "paidBy", "e.\"paidBy\"",
            "month", "to_char(e.\"expenseDate\", 'YYYY-MM')");

    private final JdbcClient jdbc;
    private final PettyCashService pettyCash;

    public ExpenseReportService(JdbcClient jdbc, PettyCashService pettyCash) {
        this.jdbc = jdbc;
        this.pettyCash = pettyCash;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static void range(StringBuilder where, List<Object> params, String from, String to) {
        if (from != null && !from.isBlank()) {
            where.append(" AND e.\"expenseDate\" >= ?");
            params.add(Timestamp.from(EntityPatch.toInstant(from)));
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND e.\"expenseDate\" <= ?");
            params.add(Timestamp.from(EntityPatch.toInstant(to + "T23:59:59.999Z")));
        }
    }

    /** Spend grouped by {@code groupBy}; amounts are gross, with tax, paid and outstanding beside. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String groupBy, String from, String to) {
        String key = GROUPS.get(groupBy == null ? "category" : groupBy);
        if (key == null) {
            throw ApiException.badRequest("groupBy must be one of the following values: " + String.join(", ",
                    List.of("category", "branch", "department", "costCenter", "project", "supplier", "claimant",
                            "paidBy", "month")));
        }
        StringBuilder where = new StringBuilder(LIVE);
        List<Object> params = new ArrayList<>(List.of(org()));
        range(where, params, from, to);
        String share = "CASE WHEN a.id IS NULL THEN 1 ELSE a.amount / NULLIF(e.amount - e.\"taxAmount\", 0) END";
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT " + key + " AS k, count(DISTINCT e.id) AS n,"
                + " SUM(e.amount * " + share + ") AS amount, SUM(e.\"taxAmount\" * " + share + ") AS tax,"
                + " SUM(e.\"amountPaid\" * " + share + ") AS paid FROM \"Expense\" e LEFT JOIN \"ExpenseAllocation\" a ON"
                + " a.\"expenseId\" = e.id WHERE" + where + " GROUP BY 1 ORDER BY 3 DESC NULLS LAST").params(params));
        Map<String, String> labels = labels(groupBy == null ? "category" : groupBy,
                rows.stream().map(r -> (String) r.get("k")).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal amount = Money.dec(r.get("amount"));
            BigDecimal paid = Money.dec(r.get("paid"));
            total = total.add(amount);
            String k = (String) r.get("k");
            out.add(Obj.of("key", k == null || k.isEmpty() ? null : k, "label", labels.getOrDefault(k,
                    k == null || k.isEmpty() ? "Unassigned" : k), "count", r.get("n"), "amount", JsNumbers.js(amount),
                    "taxAmount", JsNumbers.js(Money.dec(r.get("tax"))), "netAmount",
                    JsNumbers.js(amount.subtract(Money.dec(r.get("tax")))), "paid", JsNumbers.js(paid), "outstanding",
                    JsNumbers.js(amount.subtract(paid))));
        }
        return Obj.of("groupBy", groupBy == null ? "category" : groupBy, "from", from, "to", to, "total",
                JsNumbers.js(total), "rows", out);
    }

    private Map<String, String> labels(String groupBy, List<String> keys) {
        List<String> ids = keys.stream().filter(k -> k != null && !k.isEmpty()).toList();
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String sql = switch (groupBy) {
            case "category" -> "SELECT id, name AS label FROM \"ExpenseCategory\" WHERE id IN (:ids)";
            case "branch" -> "SELECT id, name AS label FROM \"Branch\" WHERE id IN (:ids)";
            case "costCenter" -> "SELECT id, code || ' · ' || name AS label FROM \"CostCenter\" WHERE id IN (:ids)";
            case "project" -> "SELECT id, name AS label FROM \"Project\" WHERE id IN (:ids)";
            case "supplier" -> "SELECT id, name AS label FROM \"Partner\" WHERE id IN (:ids)";
            case "claimant" -> "SELECT id, trim(coalesce(\"firstName\", '') || ' ' || coalesce(\"lastName\", '')) AS label"
                    + " FROM \"User\" WHERE id IN (:ids)";
            default -> null;
        };
        if (sql != null) {
            for (Map<String, Object> r : jdbc.sql(sql).param("ids", ids).query().listOfRows()) {
                out.put((String) r.get("id"), (String) r.get("label"));
            }
        }
        return out;
    }

    /** Monthly spend for the last {@code months} months (oldest first), zero-filled. */
    @Transactional(readOnly = true)
    public Map<String, Object> trend(int months, String categoryId) {
        int n = Math.max(1, Math.min(36, months));
        YearMonth last = YearMonth.now(BusinessZone.get());
        YearMonth first = last.minusMonths(n - 1L);
        StringBuilder where = new StringBuilder(LIVE + " AND e.\"expenseDate\" >= ?");
        List<Object> params = new ArrayList<>(List.of(org(), Timestamp.valueOf(first.atDay(1).atStartOfDay())));
        if (categoryId != null && !categoryId.isBlank()) {
            where.append(" AND e.\"categoryId\" = ?");
            params.add(categoryId);
        }
        Map<String, Map<String, Object>> byMonth = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT to_char(e.\"expenseDate\", 'YYYY-MM') AS m, count(*) AS n,"
                + " SUM(e.amount) AS amount, SUM(e.\"amountPaid\") AS paid FROM \"Expense\" e WHERE" + where
                + " GROUP BY 1").params(params))) {
            byMonth.put((String) r.get("m"), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            Map<String, Object> r = byMonth.get(m.toString());
            out.add(Obj.of("month", m.toString(), "count", r == null ? 0 : r.get("n"), "amount",
                    JsNumbers.js(r == null ? BigDecimal.ZERO : Money.dec(r.get("amount"))), "paid",
                    JsNumbers.js(r == null ? BigDecimal.ZERO : Money.dec(r.get("paid")))));
        }
        return Obj.of("months", out);
    }

    /** Where expenses stand: awaiting approval, returned, awaiting payment (finance), part paid, paid. */
    @Transactional(readOnly = true)
    public Map<String, Object> pipeline(String from, String to) {
        StringBuilder where = new StringBuilder(" e.\"organizationId\" = ? AND e.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        range(where, params, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT CASE WHEN e.status::text = 'DRAFT' THEN 'pendingApproval'"
                + " WHEN e.status::text = 'RETURNED' THEN 'returned' WHEN e.status::text IN ('VOID', 'CANCELLED', 'REJECTED')"
                + " THEN 'closed' WHEN e.\"paymentStatus\"::text = 'PAID' THEN 'paid' WHEN e.\"paymentStatus\"::text ="
                + " 'PARTIALLY_PAID' THEN 'partiallyPaid' ELSE 'pendingPayment' END AS stage, count(*) AS n,"
                + " SUM(e.amount) AS amount, SUM(e.amount - e.\"amountPaid\") AS open FROM \"Expense\" e WHERE" + where
                + " GROUP BY 1").params(params))) {
            out.put((String) r.get("stage"), Obj.of("count", r.get("n"), "amount", JsNumbers.js(Money.dec(r.get("amount"))),
                    "outstanding", JsNumbers.js(Money.dec(r.get("open")))));
        }
        for (String s : List.of("pendingApproval", "returned", "pendingPayment", "partiallyPaid", "paid", "closed")) {
            out.putIfAbsent(s, Obj.of("count", 0, "amount", 0, "outstanding", 0));
        }
        return out;
    }

    /** What the company owes employees for approved, employee-paid expenses, by claimant. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> reimbursements() {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now(BusinessZone.get());
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT e.\"claimantId\", trim(coalesce(u.\"firstName\", '') || ' '"
                + " || coalesce(u.\"lastName\", '')) AS name, count(*) AS n, SUM(e.amount - e.\"amountPaid\") AS open,"
                + " MIN(e.\"expenseDate\") AS oldest FROM \"Expense\" e LEFT JOIN \"User\" u ON u.id = e.\"claimantId\" WHERE"
                + LIVE + " AND e.\"paidBy\" = 'EMPLOYEE' AND e.status::text IN ('APPROVED', 'POSTED') AND"
                + " e.\"paymentStatus\"::text <> 'PAID' GROUP BY 1, 2 ORDER BY 4 DESC").param(org()))) {
            LocalDate oldest = ExpenseControlService.instant(r.get("oldest")).atOffset(java.time.ZoneOffset.UTC)
                    .toLocalDate();
            out.add(Obj.of("claimantId", r.get("claimantId"), "name", r.get("name"), "count", r.get("n"),
                    "outstanding", JsNumbers.js(Money.dec(r.get("open"))), "oldestDate", oldest.toString(),
                    "ageDays", ChronoUnit.DAYS.between(oldest, today)));
        }
        return out;
    }

    /** Disbursed advances with money still out, oldest first, with their age. */
    @Transactional(readOnly = true)
    public Map<String, Object> advances() {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        LocalDate today = LocalDate.now(BusinessZone.get());
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT a.*, trim(coalesce(u.\"firstName\", '') || ' ' ||"
                + " coalesce(u.\"lastName\", '')) AS name FROM \"ExpenseAdvance\" a LEFT JOIN \"User\" u ON u.id ="
                + " a.\"employeeId\" WHERE a.\"organizationId\" = ? AND a.status = 'disbursed' ORDER BY a.\"disbursedAt\"")
                .param(org()))) {
            BigDecimal open = ExpenseAdvanceService.outstanding(a);
            total = total.add(open);
            Object at = a.get("disbursedAt");
            long age = at == null ? 0 : ChronoUnit.DAYS.between(ExpenseControlService.instant(at)
                    .atOffset(java.time.ZoneOffset.UTC).toLocalDate(), today);
            rows.add(Obj.of("id", a.get("id"), "advanceNumber", a.get("advanceNumber"), "employeeId", a.get("employeeId"),
                    "name", a.get("name"), "purpose", a.get("purpose"), "amount", JsNumbers.js(Money.dec(a.get("amount"))),
                    "outstanding", JsNumbers.js(open), "ageDays", age));
        }
        return Obj.of("total", JsNumbers.js(total), "rows", rows);
    }

    /** Funds with balance, float, last count and the cash short / over booked in counts this year. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pettyCash() {
        List<Map<String, Object>> out = new ArrayList<>();
        Timestamp yearStart = Timestamp.valueOf(LocalDate.now(BusinessZone.get()).withDayOfYear(1).atStartOfDay());
        for (Map<String, Object> f : pettyCash.list()) {
            Map<String, Object> m = new LinkedHashMap<>(f);
            BigDecimal diff = jdbc.sql("SELECT COALESCE(SUM(difference), 0) FROM \"PettyCashCount\" WHERE \"fundId\" = ? AND"
                    + " \"countedAt\" >= ?").params(f.get("id"), yearStart).query(BigDecimal.class).single();
            m.put("overShortThisYear", JsNumbers.js(diff));
            out.add(m);
        }
        return out;
    }

    /** Prepaid balances still to release, open period-end accruals and recurring runs in the next 30 days. */
    @Transactional(readOnly = true)
    public Map<String, Object> schedules() {
        LocalDate today = LocalDate.now(BusinessZone.get());
        BigDecimal prepaid = jdbc.sql("SELECT COALESCE(SUM(\"totalAmount\" - \"releasedAmount\"), 0) FROM \"ExpensePrepayment\""
                + " WHERE \"organizationId\" = ? AND status = 'active'").param(org()).query(BigDecimal.class).single();
        BigDecimal accrued = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM \"ExpenseAccrual\" WHERE \"organizationId\" = ?"
                + " AND status = 'posted'").param(org()).query(BigDecimal.class).single();
        List<Map<String, Object>> upcoming = SqlRows.list(jdbc.sql("SELECT id, name, amount, \"nextRunDate\", frequency FROM"
                + " \"ExpenseRecurrence\" WHERE \"organizationId\" = ? AND status = 'active' AND \"nextRunDate\" <= ?::date"
                + " ORDER BY \"nextRunDate\"").params(org(), today.plusDays(30).toString())).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.put("amount", JsNumbers.js(Money.dec(r.get("amount"))));
                    return m;
                }).toList();
        return Obj.of("prepaidRemaining", JsNumbers.js(prepaid), "openAccruals", JsNumbers.js(accrued),
                "upcomingRecurring", upcoming);
    }

    /** Capitalised expenses (debited to an asset account), newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> capitalPurchases(String from, String to) {
        StringBuilder where = new StringBuilder(LIVE + " AND e.capitalize");
        List<Object> params = new ArrayList<>(List.of(org()));
        range(where, params, from, to);
        return SqlRows.list(jdbc.sql("SELECT e.id, e.\"expenseCode\", e.title, e.amount, e.\"taxAmount\", e.\"expenseDate\","
                + " e.\"categoryName\", e.status::text AS status, e.\"paymentStatus\"::text AS \"paymentStatus\" FROM"
                + " \"Expense\" e WHERE" + where + " ORDER BY e.\"expenseDate\" DESC LIMIT 500").params(params)).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.put("amount", JsNumbers.js(Money.dec(r.get("amount"))));
                    m.put("taxAmount", JsNumbers.js(Money.dec(r.get("taxAmount"))));
                    return m;
                }).toList();
    }
}
