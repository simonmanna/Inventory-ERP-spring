package com.inventoryerp.accounting.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch comparison and operational consolidation (BR-2): one row per branch with profit from the ledger
 * ({@link ConsolidatedReportService}), sales, receivables, stock, deliveries and production, plus a consolidated total.
 * Stock, deliveries and production follow {@code InventoryLocation.branchId}, so a location with no branch reports
 * under Unassigned. A restricted user sees their own branches only, and no Unassigned row. Plain SQL reads.
 */
@Service
public class BranchPerformanceService {

    private static final String UNASSIGNED = ConsolidatedReportService.UNASSIGNED;

    private static final List<String> MEASURES = List.of("income", "expense", "netProfit", "salesAmount", "salesCount",
            "openReceivables", "stockValue", "deliveries", "deliveredTonnes", "producedQty");

    private final JdbcClient jdbc;
    private final ConsolidatedReportService consolidated;
    private final BranchScopeService scopes;

    public BranchPerformanceService(JdbcClient jdbc, ConsolidatedReportService consolidated, BranchScopeService scopes) {
        this.jdbc = jdbc;
        this.consolidated = consolidated;
        this.scopes = scopes;
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    /** {@code from} / {@code to} are ISO dates; stock value and open receivables are as of now. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> comparison(String from, String to) {
        String org = TenantContext.organizationId();
        Instant start = LedgerQueries.date(from);
        Instant end = LedgerQueries.date(to);
        Map<String, Object> pl = consolidated.consolidated(from, to, "pl");
        Map<String, Object> totals = (Map<String, Object>) pl.get("totals");
        Map<String, Map<String, BigDecimal>> m = new HashMap<>();
        for (String key : List.of("income", "expense", "netProfit")) {
            Map<String, Object> byBranch = (Map<String, Object>) ((Map<String, Object>) totals.get(key)).get("byBranch");
            byBranch.forEach((b, v) -> put(m, b, key, dec(v)));
        }
        String docs = range("\"issueDate\"", start, end);
        for (String table : List.of("Document", "Invoice")) {
            String type = "Document".equals(table) ? " AND \"documentType\"::text = 'sales_invoice'" : "";
            List<Object> params = params(org, start, end);
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT COALESCE(\"branchId\", '" + UNASSIGNED + "') AS b,"
                    + " COALESCE(sum(\"totalAmount\"), 0) AS amount, count(*) AS n FROM \"" + table + "\" WHERE"
                    + " \"organizationId\" = ?" + type + " AND status::text IN ('posted', 'paid')" + docs
                    + " GROUP BY 1").params(params))) {
                add(m, r, "salesAmount", "amount");
                add(m, r, "salesCount", "n");
            }
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT COALESCE(\"branchId\", '" + UNASSIGNED + "') AS b,"
                    + " COALESCE(sum(\"amountResidual\"), 0) AS amount FROM \"" + table + "\" WHERE \"organizationId\" = ?"
                    + type + " AND status::text IN ('posted', 'paid') AND \"amountResidual\" > 0 GROUP BY 1").param(org))) {
                add(m, r, "openReceivables", "amount");
            }
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT COALESCE(l.\"branchId\", '" + UNASSIGNED + "') AS b,"
                + " COALESCE(sum(si.quantity * si.\"runningAverageCost\"), 0) AS amount FROM \"StockItem\" si JOIN"
                + " \"InventoryLocation\" l ON l.id = si.\"locationId\" WHERE si.\"organizationId\" = ? GROUP BY 1")
                .param(org))) {
            add(m, r, "stockValue", "amount");
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT COALESCE(o.\"branchId\", l.\"branchId\", '" + UNASSIGNED
                + "') AS b, count(*) AS n, COALESCE(sum(t.\"deliveredTonnes\"), 0) AS tonnes FROM \"FleetLoad\" t JOIN"
                + " \"FleetDeliveryOrder\" o ON o.id = t.\"deliveryOrderId\" JOIN \"InventoryLocation\" l ON l.id ="
                + " o.\"locationId\" WHERE t.\"organizationId\" = ? AND t.status IN ('delivered', 'closed')"
                + range("t.\"deliveredAt\"", start, end) + " GROUP BY 1").params(params(org, start, end)))) {
            add(m, r, "deliveries", "n");
            add(m, r, "deliveredTonnes", "tonnes");
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT COALESCE(l.\"branchId\", '" + UNASSIGNED + "') AS b,"
                + " COALESCE(sum(p.\"producedQty\"), 0) AS qty FROM \"ProductionOrder\" p JOIN \"InventoryLocation\" l ON"
                + " l.id = p.\"locationId\" WHERE p.\"organizationId\" = ? AND p.\"completedAt\" IS NOT NULL"
                + range("p.\"completedAt\"", start, end) + " GROUP BY 1").params(params(org, start, end)))) {
            add(m, r, "producedQty", "qty");
        }
        return result(pl, m, from, to);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(Map<String, Object> pl, Map<String, Map<String, BigDecimal>> m, String from,
            String to) {
        DataScope scope = scopes.scope();
        List<Map<String, Object>> branches = new ArrayList<>((List<Map<String, Object>>) pl.get("branches"));
        if (m.containsKey(UNASSIGNED) && branches.stream().noneMatch(b -> UNASSIGNED.equals(b.get("id")))) {
            branches.add(Obj.of("id", UNASSIGNED, "code", "-", "name", "Unassigned"));
        }
        Map<String, BigDecimal> sum = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> b : branches) {
            String id = (String) b.get("id");
            if (scope.restricted() && !scope.allowsBranch(id)) {
                continue;
            }
            Map<String, BigDecimal> v = m.getOrDefault(id, Map.of());
            Map<String, Object> row = Obj.of("branchId", id, "code", b.get("code"), "name", b.get("name"));
            for (String k : MEASURES) {
                BigDecimal x = v.getOrDefault(k, BigDecimal.ZERO);
                row.put(k, JsNumbers.js(x));
                sum.merge(k, x, BigDecimal::add);
            }
            row.put("marginPercent", margin(v.getOrDefault("netProfit", BigDecimal.ZERO),
                    v.getOrDefault("income", BigDecimal.ZERO)));
            rows.add(row);
        }
        Map<String, Object> total = Obj.of("branchId", "total", "name", "Consolidated");
        for (String k : MEASURES) {
            total.put(k, JsNumbers.js(sum.getOrDefault(k, BigDecimal.ZERO)));
        }
        total.put("marginPercent", margin(sum.getOrDefault("netProfit", BigDecimal.ZERO),
                sum.getOrDefault("income", BigDecimal.ZERO)));
        return Obj.of("from", from, "to", to, "rows", rows, "total", total);
    }

    private static Number margin(BigDecimal profit, BigDecimal income) {
        return income.signum() == 0 ? null
                : JsNumbers.js(profit.multiply(BigDecimal.valueOf(100)).divide(income, 2, RoundingMode.HALF_UP));
    }

    private static String range(String column, Instant start, Instant end) {
        return (start == null ? "" : " AND " + column + " >= ?") + (end == null ? "" : " AND " + column + " <= ?");
    }

    private static List<Object> params(String org, Instant start, Instant end) {
        List<Object> out = new ArrayList<>(List.of(org));
        if (start != null) {
            out.add(FiscalPeriodControl.ts(start));
        }
        if (end != null) {
            out.add(FiscalPeriodControl.ts(end));
        }
        return out;
    }

    private static void put(Map<String, Map<String, BigDecimal>> m, String branch, String key, BigDecimal v) {
        m.computeIfAbsent(branch, k -> new HashMap<>()).merge(key, v, BigDecimal::add);
    }

    private static void add(Map<String, Map<String, BigDecimal>> m, Map<String, Object> r, String key, String column) {
        put(m, (String) r.get("b"), key, SqlRows.dec(r, column));
    }
}
