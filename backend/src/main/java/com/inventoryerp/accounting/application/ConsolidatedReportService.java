package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-branch consolidation (ADR-S005): posted GL lines attributed to their line {@code branchId} (lines without
 * one go to {@code unassigned}), shown side by side with the consolidated total. {@code pl} lists P&L accounts with
 * net profit per branch; {@code tb} lists every account as net debit (positive) / credit (negative). Single legal
 * entity, so no intercompany eliminations.
 */
@Service
public class ConsolidatedReportService {

    public static final String UNASSIGNED = "unassigned";

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public ConsolidatedReportService(JdbcClient jdbc, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    private static String plain(BigDecimal v) {
        return PostingService.plain(v.setScale(2, RoundingMode.HALF_UP));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> consolidated(String fromRaw, String toRaw, String statement) {
        String kind = statement == null || statement.isBlank() ? "pl" : statement;
        if (!"pl".equals(kind) && !"tb".equals(kind)) {
            throw ApiException.badRequest("statement must be one of the following values: pl, tb");
        }
        String org = TenantContext.organizationId();
        Instant from = LedgerQueries.date(fromRaw);
        Instant to = LedgerQueries.date(toRaw);
        StringBuilder sql = new StringBuilder("SELECT l.\"accountId\", COALESCE(l.\"branchId\", '" + UNASSIGNED
                + "') AS branch, sum(l.\"baseDebit\") AS d, sum(l.\"baseCredit\") AS c FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND e.status::text IN"
                + " ('posted', 'reversed')");
        List<Object> params = new ArrayList<>(List.of(org));
        if (from != null) {
            sql.append(" AND e.\"postingDate\" >= ?");
            params.add(FiscalPeriodControl.ts(from));
        }
        if (to != null) {
            sql.append(" AND e.\"postingDate\" <= ?");
            params.add(FiscalPeriodControl.ts(to));
        }
        sql.append(" GROUP BY 1, 2");
        Map<String, Map<String, BigDecimal>> net = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(sql.toString()).params(params))) {
            net.computeIfAbsent((String) r.get("accountId"), k -> new LinkedHashMap<>())
                    .merge((String) r.get("branch"), SqlRows.dec(r, "d").subtract(SqlRows.dec(r, "c")), BigDecimal::add);
        }

        List<Map<String, Object>> branches = new ArrayList<>(SqlRows.list(jdbc.sql("SELECT id, code, name FROM"
                + " \"Branch\" WHERE \"organizationId\" = ? ORDER BY code").param(org)));
        boolean hasUnassigned = net.values().stream().anyMatch(m -> m.containsKey(UNASSIGNED));
        List<String> columns = new ArrayList<>();
        branches.forEach(b -> columns.add((String) b.get("id")));
        if (hasUnassigned) {
            columns.add(UNASSIGNED);
            branches.add(Obj.of("id", UNASSIGNED, "code", "-", "name", "Unassigned"));
        }

        Map<String, AccountMeta> meta = accounts.meta(net.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, BigDecimal> income = zero(columns);
        Map<String, BigDecimal> expense = zero(columns);
        Map<String, BigDecimal> debitTotals = zero(columns);
        for (Map.Entry<String, Map<String, BigDecimal>> e : net.entrySet()) {
            AccountMeta a = meta.get(e.getKey());
            if (a == null) {
                continue;
            }
            boolean pnl = AccountClassification.isProfitAndLoss(a.classification());
            if ("pl".equals(kind) && !pnl) {
                continue;
            }
            boolean isIncome = BudgetService.isIncome(a);
            Map<String, Object> byBranch = new LinkedHashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            for (String col : columns) {
                BigDecimal raw = e.getValue().getOrDefault(col, BigDecimal.ZERO);
                BigDecimal v = "pl".equals(kind) ? AccountClassification.displayBalance(raw, a.normalBalance()) : raw;
                byBranch.put(col, plain(v));
                total = total.add(v);
                if ("pl".equals(kind)) {
                    (isIncome ? income : expense).merge(col, v, BigDecimal::add);
                } else {
                    debitTotals.merge(col, raw, BigDecimal::add);
                }
            }
            rows.add(Obj.of("accountId", a.id(), "code", a.code(), "name", a.name(), "classification",
                    a.classification(), "reportSection", a.reportSection(), "type", pnl ? (isIncome ? "income"
                            : "expense") : a.classification(), "byBranch", byBranch, "total", plain(total)));
        }
        rows.sort((x, y) -> String.valueOf(x.get("code")).compareTo(String.valueOf(y.get("code"))));

        Map<String, Object> totals = new LinkedHashMap<>();
        if ("pl".equals(kind)) {
            totals.put("income", columnTotals(columns, income));
            totals.put("expense", columnTotals(columns, expense));
            Map<String, BigDecimal> np = zero(columns);
            columns.forEach(c -> np.put(c, income.get(c).subtract(expense.get(c))));
            totals.put("netProfit", columnTotals(columns, np));
        } else {
            totals.put("net", columnTotals(columns, debitTotals));
        }
        return Obj.of("statement", kind, "from", fromRaw, "to", toRaw, "branches", branches, "rows", rows,
                "totals", totals);
    }

    private static Map<String, BigDecimal> zero(List<String> columns) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        columns.forEach(c -> m.put(c, BigDecimal.ZERO));
        return m;
    }

    private static Map<String, Object> columnTotals(List<String> columns, Map<String, BigDecimal> values) {
        Map<String, Object> byBranch = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (String c : columns) {
            byBranch.put(c, plain(values.get(c)));
            total = total.add(values.get(c));
        }
        return Obj.of("byBranch", byBranch, "total", plain(total));
    }
}
