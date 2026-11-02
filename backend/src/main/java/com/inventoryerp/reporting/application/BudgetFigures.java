package com.inventoryerp.reporting.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.reporting.application.LedgerAggregator.AccountInfo;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Budget amounts ({@code BudgetLine}, one row per account and {@code YYYY-MM} period) as ledger-like debit / credit
 * pairs, so a statement layout evaluates a budget column exactly like an actual column. A budget amount is in the
 * account's normal balance. The budget is {@code options.budgetId}, else the latest budget of the organization whose
 * dates cover the period start.
 */
@Component
public class BudgetFigures {

    static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    private final JdbcClient jdbc;

    public BudgetFigures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The budget used for this run, or {@code null} when the organization has none. */
    public Map<String, Object> budget(ReportContext ctx) {
        String id = ctx.request().option("budgetId");
        LocalDate start = ctx.current().from() == null ? ctx.current().to() : ctx.current().from();
        Map<String, Object> b = id != null && !id.isBlank()
                ? SqlRows.one(jdbc.sql("SELECT id, name, \"startDate\", \"endDate\", status FROM \"Budget\" WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, ctx.organizationId()))
                : SqlRows.one(jdbc.sql("SELECT id, name, \"startDate\", \"endDate\", status FROM \"Budget\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"startDate\" <= ? AND \"endDate\" >= ?"
                        + " ORDER BY (status = 'approved') DESC, \"createdAt\" DESC LIMIT 1").params(
                        ctx.organizationId(), start, start));
        if (id != null && !id.isBlank() && b == null) {
            throw ApiException.notFound("Budget not found");
        }
        return b;
    }

    /** accountId → budget amount of the months the period covers. */
    public Map<String, BigDecimal> amounts(ReportContext ctx, ReportPeriod p) {
        Map<String, Object> b = budget(ctx);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (b == null) {
            return out;
        }
        LocalDate from = p.from() == null ? p.to().withDayOfYear(1) : p.from();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"accountId\", sum(amount) AS amount FROM"
                + " \"BudgetLine\" WHERE \"budgetId\" = ? AND period >= ? AND period <= ? GROUP BY \"accountId\"")
                .params(b.get("id"), YM.format(from), YM.format(p.to())))) {
            out.put((String) r.get("accountId"), SqlRows.dec(r, "amount"));
        }
        return out;
    }

    /** Budget of the current period as debit / credit pairs by the account's normal balance. */
    public Map<String, BigDecimal[]> balances(ReportContext ctx, Map<String, AccountInfo> accounts) {
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : amounts(ctx, ctx.current()).entrySet()) {
            AccountInfo a = accounts.get(e.getKey());
            boolean credit = a != null && "credit".equals(a.normalBalance());
            out.put(e.getKey(), credit ? new BigDecimal[] {BigDecimal.ZERO, e.getValue()}
                    : new BigDecimal[] {e.getValue(), BigDecimal.ZERO});
        }
        return out;
    }

    /** Months between two dates as {@code YYYY-MM}, inclusive. */
    static List<String> months(LocalDate from, LocalDate to) {
        List<String> out = new java.util.ArrayList<>();
        for (LocalDate m = from.withDayOfMonth(1); !m.isAfter(to); m = m.plusMonths(1)) {
            out.add(YM.format(m));
        }
        return out;
    }
}
