package com.inventoryerp.reporting.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.reporting.application.LedgerAggregator.AccountInfo;
import com.inventoryerp.reporting.application.StatementLayoutService.Layout;
import com.inventoryerp.reporting.application.StatementLayoutService.Line;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.OptionSpec;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportBody.Check;
import com.inventoryerp.reporting.spi.ReportBody.Column;
import com.inventoryerp.reporting.spi.ReportBody.Drill;
import com.inventoryerp.reporting.spi.ReportBody.Row;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.reporting.spi.ReportProvider;
import com.inventoryerp.reporting.spi.ReportRequest;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Engine reports over the general ledger: layout-driven statement of profit or loss and statement of financial
 * position (comparative columns, account detail, drill to journal lines, integrity checks) and the journal-line
 * listing every statement cell drills into.
 */
@Component
public class LedgerReports implements ReportProvider {

    public static final String PROFIT_AND_LOSS = "fs.profit-and-loss";
    public static final String BALANCE_SHEET = "fs.balance-sheet";
    public static final String JOURNAL_LINES = "gl.journal-lines";
    public static final String CASH_FLOW = "fs.cash-flow";

    static final String EARLIEST = "1900-01-01";
    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 5000;

    /** Journal source type → the web route of the source document. */
    private static final Map<String, String> SOURCE_ROUTES = Map.of("invoice", "/invoices/", "credit_note",
            "/credit-notes/", "payment", "/payments/", "vendor_bill", "/vendor-bills/", "return", "/returns/",
            "fixed_asset", "/fixed-assets/", "order", "/orders/");

    private final LedgerAggregator ledger;
    private final StatementLayoutService layouts;
    private final BudgetFigures budgets;

    public LedgerReports(LedgerAggregator ledger, StatementLayoutService layouts, BudgetFigures budgets) {
        this.ledger = ledger;
        this.layouts = layouts;
        this.budgets = budgets;
    }

    static final OptionSpec BUDGET_OPTION = new OptionSpec("budgetId", "Budget (for the budget column)", "budget",
            null, null);

    @Override
    public List<ReportDefinition> definitions() {
        return List.of(
                ReportDefinition.of(PROFIT_AND_LOSS, ReportCategory.FINANCIAL_STATEMENTS,
                        "Statement of Profit or Loss", "Revenue to net profit from posted journals, by statement"
                        + " layout, with comparative periods, budget, variances, EBITDA and margins.", "FINANCIAL",
                        Permissions.REPORT__ACCOUNTING, List.of(FilterKey.PERIOD, FilterKey.BRANCH,
                        FilterKey.COST_CENTER, FilterKey.DIMENSION, FilterKey.POSTING_STATUS, FilterKey.COMPARE,
                        FilterKey.LAYOUT), BUDGET_OPTION),
                ReportDefinition.of(BALANCE_SHEET, ReportCategory.FINANCIAL_STATEMENTS,
                        "Statement of Financial Position", "Assets, liabilities and equity at a date, by statement"
                        + " layout, with comparative dates; checks that it balances.", "FINANCIAL",
                        Permissions.REPORT__ACCOUNTING, List.of(FilterKey.AS_OF, FilterKey.BRANCH,
                        FilterKey.POSTING_STATUS, FilterKey.COMPARE, FilterKey.LAYOUT)),
                ReportDefinition.of(CASH_FLOW, ReportCategory.FINANCIAL_STATEMENTS,
                        "Statement of Cash Flows (indirect)", "Profit adjusted for non-cash items and working capital,"
                        + " investing and financing flows; reconciles opening to closing cash.", "FINANCIAL",
                        Permissions.REPORT__ACCOUNTING, List.of(FilterKey.PERIOD, FilterKey.BRANCH,
                        FilterKey.POSTING_STATUS, FilterKey.COMPARE, FilterKey.LAYOUT)),
                ReportDefinition.of(JOURNAL_LINES, ReportCategory.GENERAL_LEDGER, "Journal Lines",
                        "Every posted journal line with account, partner, branch, cost center, source document and"
                        + " poster; running balance for a single account.", "FINANCIAL",
                        Permissions.REPORT__ACCOUNTING, List.of(FilterKey.PERIOD, FilterKey.BRANCH,
                        FilterKey.COST_CENTER, FilterKey.PARTNER, FilterKey.ACCOUNT, FilterKey.JOURNAL,
                        FilterKey.SOURCE, FilterKey.DIMENSION, FilterKey.POSTING_STATUS)));
    }

    @Override
    public ReportBody run(ReportContext ctx) {
        return switch (ctx.definition().id()) {
            case PROFIT_AND_LOSS -> statement(ctx, "PL", StatementLayoutService.DEFAULT_PL);
            case BALANCE_SHEET -> statement(ctx, "BS", StatementLayoutService.DEFAULT_BS);
            case CASH_FLOW -> statement(ctx, "CF", StatementLayoutService.DEFAULT_CF);
            case JOURNAL_LINES -> journalLines(ctx);
            default -> throw ApiException.notFound("Report " + ctx.definition().id() + " not found");
        };
    }

    // ---------------------------------------------------------------- statements

    /** Amounts of one column: line code → amount, and line code → account id → amount. */
    record Column(Map<String, BigDecimal> lines, Map<String, Map<String, BigDecimal>> accounts,
            Map<String, BigDecimal[]> balances) {}

    ReportBody statement(ReportContext ctx, String kind, String defaultLayout) {
        String code = ctx.request().layoutCode() == null || ctx.request().layoutCode().isBlank() ? defaultLayout
                : ctx.request().layoutCode();
        Layout layout = layouts.get(code);
        if (!kind.equals(layout.kind())) {
            throw ApiException.badRequest("Layout " + code + " is a " + layout.kind() + " layout");
        }
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        List<ReportPeriod> periods = new ArrayList<>();
        periods.add(ctx.current());
        periods.addAll(ctx.comparatives());
        List<Column> cols = new ArrayList<>();
        for (ReportPeriod p : periods) {
            cols.add(evaluate(layout, accounts, ledger.balances(ctx, p)));
        }
        boolean withBudget = ctx.budget() && !"BS".equals(kind);
        Column budgetCol = withBudget ? evaluate(layout, accounts, budgets.balances(ctx, accounts)) : null;

        List<ReportBody.Column> columns = new ArrayList<>();
        columns.add(new ReportBody.Column("label", "Description", "text"));
        for (ReportPeriod p : periods) {
            columns.add(new ReportBody.Column(p.key(), p.label(), "money"));
        }
        if (withBudget) {
            columns.add(new ReportBody.Column("BUDGET", "Budget", "money"));
        }
        boolean variance = (!ctx.comparatives().isEmpty() || withBudget) && ctx.variance();
        boolean variancePct = (!ctx.comparatives().isEmpty() || withBudget) && ctx.variancePct();
        if (variance) {
            columns.add(new ReportBody.Column("VARIANCE", "Variance", "money"));
        }
        if (variancePct) {
            columns.add(new ReportBody.Column("VARIANCE_PCT", "Variance %", "percent"));
        }

        List<Row> rows = new ArrayList<>();
        for (Line line : layout.lines()) {
            if ("HEADER".equals(line.lineType())) {
                rows.add(new Row(line.code(), "header", line.indent(), true, Obj.of("label", line.label()), Map.of()));
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("label", line.label());
            Map<String, Drill> drill = new LinkedHashMap<>();
            List<BigDecimal> amounts = new ArrayList<>();
            for (int i = 0; i < periods.size(); i++) {
                BigDecimal v = cols.get(i).lines().get(line.code());
                amounts.add(v);
                values.put(periods.get(i).key(), round(v, line.percent()));
                if ("ACCOUNTS".equals(line.lineType())) {
                    List<String> ids = new ArrayList<>(cols.get(i).accounts().getOrDefault(line.code(), Map.of())
                            .keySet());
                    if (!ids.isEmpty()) {
                        drill.put(periods.get(i).key(), drillToLines(ctx, periods.get(i), ids));
                    }
                }
            }
            if (withBudget) {
                values.put("BUDGET", round(budgetCol.lines().get(line.code()), line.percent()));
                if (ctx.comparatives().isEmpty()) {
                    amounts.add(budgetCol.lines().get(line.code()));
                }
            }
            putVariance(values, amounts, variance, variancePct, line.percent());
            String rowKind = line.kpi() ? "kpi" : "FORMULA".equals(line.lineType()) ? (line.bold() ? "total"
                    : "subtotal") : "line";
            if (line.percent()) {
                values.put("_format", "percent");
            }
            rows.add(new Row(line.code(), rowKind, line.indent(), line.bold(), values, drill));
            if ("ACCOUNTS".equals(line.lineType())) {
                rows.addAll(accountRows(ctx, line, accounts, periods, cols, variance, variancePct, budgetCol));
            }
        }

        List<Check> checks = new ArrayList<>(coverage(layout, accounts, cols.getFirst().balances(), kind));
        Map<String, Object> totals = new LinkedHashMap<>();
        if ("BS".equals(kind)) {
            for (int i = 0; i < periods.size(); i++) {
                BigDecimal ta = cols.get(i).lines().get("TA");
                BigDecimal tle = cols.get(i).lines().get("TLE");
                if (ta != null && tle != null) {
                    BigDecimal diff = ta.subtract(tle).setScale(2, RoundingMode.HALF_UP);
                    checks.add(new Check("BALANCED_" + periods.get(i).key(), "Total assets equal total equity and"
                            + " liabilities (" + periods.get(i).label() + ")", diff.signum() == 0, diff.signum() == 0
                            ? null : "Difference " + diff.toPlainString()));
                    totals.put(periods.get(i).key(), Obj.of("totalAssets", round(ta, false),
                            "totalEquityAndLiabilities", round(tle, false)));
                }
            }
        } else if ("CF".equals(kind)) {
            Map<String, Object> open = new LinkedHashMap<>(Map.of("label", "Cash and cash equivalents at beginning"
                    + " of period"));
            Map<String, Object> close = new LinkedHashMap<>(Map.of("label", "Cash and cash equivalents at end of"
                    + " period"));
            for (int i = 0; i < periods.size(); i++) {
                ReportPeriod p = periods.get(i);
                BigDecimal opening = cash(ctx, accounts, new ReportPeriod("O", "", null, p.from().minusDays(1)));
                BigDecimal closing = cash(ctx, accounts, new ReportPeriod("C", "", null, p.to()));
                open.put(p.key(), round(opening, false));
                close.put(p.key(), round(closing, false));
                BigDecimal net = cols.get(i).lines().get("NET");
                if (net != null) {
                    BigDecimal diff = opening.add(net).subtract(closing).setScale(2, RoundingMode.HALF_UP);
                    checks.add(new Check("CASH_RECONCILES_" + p.key(), "Opening cash plus net cash flow equals"
                            + " closing cash (" + p.label() + ")", diff.signum() == 0, diff.signum() == 0 ? null
                            : "Difference " + diff.toPlainString()));
                }
                totals.put(p.key(), Obj.of("openingCash", round(opening, false), "netCashFlow", round(net, false),
                        "closingCash", round(closing, false)));
            }
            rows.add(new Row("OPENING_CASH", "subtotal", 0, true, open, Map.of()));
            rows.add(new Row("CLOSING_CASH", "total", 0, true, close, Map.of()));
        } else {
            for (int i = 0; i < periods.size(); i++) {
                BigDecimal np = cols.get(i).lines().get("NP");
                if (np == null) {
                    continue;
                }
                BigDecimal gl = BigDecimal.ZERO;
                for (Map.Entry<String, BigDecimal[]> b : cols.get(i).balances().entrySet()) {
                    AccountInfo a = accounts.get(b.getKey());
                    if (a != null && StatementLayoutService.PL_SECTIONS.contains(a.section())) {
                        gl = gl.add(b.getValue()[1]).subtract(b.getValue()[0]);
                    }
                }
                BigDecimal diff = np.subtract(gl).setScale(2, RoundingMode.HALF_UP);
                checks.add(new Check("TIES_TO_GL_" + periods.get(i).key(), "Net profit equals the income and expense"
                        + " accounts in the ledger (" + periods.get(i).label() + ")", diff.signum() == 0,
                        diff.signum() == 0 ? null : "Difference " + diff.toPlainString()));
                totals.put(periods.get(i).key(), Obj.of("netProfit", round(np, false), "netRevenue",
                        round(cols.get(i).lines().get("NET_REV"), false)));
            }
        }
        return new ReportBody(columns, rows, totals, checks, Obj.of("layoutCode", layout.code(), "layoutName",
                layout.name(), "kind", kind));
    }

    /** Debit balance of the cash and cash-equivalent accounts at a point in time. */
    private BigDecimal cash(ReportContext ctx, Map<String, AccountInfo> accounts, ReportPeriod p) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> b : ledger.balances(ctx, p).entrySet()) {
            AccountInfo a = accounts.get(b.getKey());
            if (a != null && StatementLayoutService.CASH_CATEGORIES.contains(a.category())) {
                sum = sum.add(b.getValue()[0]).subtract(b.getValue()[1]);
            }
        }
        return sum;
    }

    Column evaluate(Layout layout, Map<String, AccountInfo> accounts, Map<String, BigDecimal[]> balances) {
        Map<String, BigDecimal> values = new HashMap<>();
        Map<String, Map<String, BigDecimal>> perAccount = new HashMap<>();
        Map<String, Line> byCode = new HashMap<>();
        for (Line line : layout.lines()) {
            byCode.put(line.code(), line);
            if (!"ACCOUNTS".equals(line.lineType()) || line.selector() == null) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            Map<String, BigDecimal> mine = new LinkedHashMap<>();
            for (AccountInfo a : accounts.values()) {
                BigDecimal[] dc = balances.get(a.id());
                if (dc == null || !line.selector().matches(a)) {
                    continue;
                }
                BigDecimal v = "DEBIT".equals(line.sign()) ? dc[0].subtract(dc[1]) : dc[1].subtract(dc[0]);
                if (v.signum() != 0) {
                    mine.put(a.id(), v);
                    sum = sum.add(v);
                }
            }
            values.put(line.code(), sum);
            perAccount.put(line.code(), mine);
        }
        for (Line line : layout.lines()) {
            if ("FORMULA".equals(line.lineType())) {
                formula(line.code(), byCode, values, new TreeSet<>());
            }
        }
        return new Column(values, perAccount, balances);
    }

    private static BigDecimal formula(String code, Map<String, Line> byCode, Map<String, BigDecimal> values,
            Set<String> path) {
        if (values.containsKey(code)) {
            return values.get(code);
        }
        Line line = byCode.get(code);
        if (line == null || !"FORMULA".equals(line.lineType())) {
            return null;
        }
        if (!path.add(code)) {
            throw ApiException.badRequest("Formula of line " + code + " refers to itself");
        }
        BigDecimal v = FormulaEvaluator.evaluate(line.formula(), ref -> formula(ref, byCode, values, path));
        values.put(code, v);
        return v;
    }

    private List<Row> accountRows(ReportContext ctx, Line line, Map<String, AccountInfo> accounts,
            List<ReportPeriod> periods, List<Column> cols, boolean variance, boolean variancePct, Column budgetCol) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (Column c : cols) {
            ids.addAll(c.accounts().getOrDefault(line.code(), Map.of()).keySet());
        }
        List<Row> out = new ArrayList<>();
        accounts.values().stream().filter(a -> ids.contains(a.id())).forEach(a -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("label", a.code() + " " + a.name());
            values.put("accountId", a.id());
            Map<String, Drill> drill = new LinkedHashMap<>();
            List<BigDecimal> amounts = new ArrayList<>();
            for (int i = 0; i < periods.size(); i++) {
                BigDecimal v = cols.get(i).accounts().getOrDefault(line.code(), Map.of()).getOrDefault(a.id(),
                        BigDecimal.ZERO);
                amounts.add(v);
                values.put(periods.get(i).key(), round(v, false));
                drill.put(periods.get(i).key(), drillToLines(ctx, periods.get(i), List.of(a.id())));
            }
            if (budgetCol != null) {
                BigDecimal b = budgetCol.accounts().getOrDefault(line.code(), Map.of()).getOrDefault(a.id(),
                        BigDecimal.ZERO);
                values.put("BUDGET", round(b, false));
                if (ctx.comparatives().isEmpty()) {
                    amounts.add(b);
                }
            }
            putVariance(values, amounts, variance, variancePct, false);
            out.add(new Row(line.code() + ":" + a.id(), "account", line.indent() + 1, false, values, drill));
        });
        return out;
    }

    /** Accounts with a balance that no line takes, or that two lines take (layout mapping errors). */
    private static List<Check> coverage(Layout layout, Map<String, AccountInfo> accounts,
            Map<String, BigDecimal[]> balances, String kind) {
        Set<String> sections = new java.util.HashSet<>(StatementLayoutService.PL_SECTIONS);
        if ("BS".equals(kind) || "CF".equals(kind)) {
            sections.addAll(StatementLayoutService.BS_SECTIONS);
        }
        List<String> unmapped = new ArrayList<>();
        List<String> twice = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> b : balances.entrySet()) {
            AccountInfo a = accounts.get(b.getKey());
            if (a == null || b.getValue()[0].compareTo(b.getValue()[1]) == 0
                    || "CF".equals(kind) && StatementLayoutService.CASH_CATEGORIES.contains(a.category())) {
                continue;
            }
            long hits = layout.lines().stream().filter(l -> "ACCOUNTS".equals(l.lineType()) && l.selector() != null
                    && l.selector().matches(a)).count();
            if (hits == 0 && sections.contains(a.section())) {
                unmapped.add(a.code());
            } else if (hits > 1) {
                twice.add(a.code());
            }
        }
        List<Check> out = new ArrayList<>();
        out.add(new Check("ALL_ACCOUNTS_MAPPED", "Every account with a balance is on a statement line",
                unmapped.isEmpty(), unmapped.isEmpty() ? null : "Not mapped: " + String.join(", ", unmapped)));
        out.add(new Check("NO_DOUBLE_MAPPING", "No account is on two statement lines", twice.isEmpty(),
                twice.isEmpty() ? null : "Mapped twice: " + String.join(", ", twice)));
        return out;
    }

    private static void putVariance(Map<String, Object> values, List<BigDecimal> amounts, boolean variance,
            boolean variancePct, boolean percent) {
        if (!variance && !variancePct) {
            return;
        }
        BigDecimal cur = amounts.get(0) == null ? BigDecimal.ZERO : amounts.get(0);
        BigDecimal cmp = amounts.get(1) == null ? BigDecimal.ZERO : amounts.get(1);
        BigDecimal diff = cur.subtract(cmp);
        if (variance) {
            values.put("VARIANCE", round(diff, percent));
        }
        if (variancePct) {
            values.put("VARIANCE_PCT", cmp.signum() == 0 ? null : diff.multiply(BigDecimal.valueOf(100))
                    .divide(cmp.abs(), 2, RoundingMode.HALF_UP));
        }
    }

    private static BigDecimal round(BigDecimal v, boolean percent) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static Drill drillToLines(ReportContext ctx, ReportPeriod p, List<String> accountIds) {
        ReportRequest r = ctx.request();
        Map<String, Object> params = new LinkedHashMap<>();
        // a balance (no lower bound) drills into the whole history up to its date
        params.put("from", p.from() == null ? EARLIEST : p.from().toString());
        params.put("to", p.to().toString());
        params.put("accountIds", accountIds);
        if (!r.branchIds().isEmpty()) {
            params.put("branchIds", r.branchIds());
        }
        if (!r.costCenterIds().isEmpty()) {
            params.put("costCenterIds", r.costCenterIds());
        }
        if (!r.dimensions().isEmpty()) {
            params.put("dimensions", r.dimensions());
        }
        if (r.postingStatus() != null) {
            params.put("postingStatus", r.postingStatus());
        }
        params.values().removeIf(v -> v == null);
        return Drill.report(JOURNAL_LINES, params);
    }

    // ---------------------------------------------------------------- journal lines

    ReportBody journalLines(ReportContext ctx) {
        ReportRequest r = ctx.request();
        int limit = r.limit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(r.limit(), MAX_LIMIT));
        int page = r.page() == null ? 1 : Math.max(1, r.page());
        LocalDate from = ctx.current().from();
        LocalDate to = ctx.current().to();
        boolean single = r.accountIds().size() == 1;
        Map<String, Object> totals = ledger.lineTotals(ctx.organizationId(), r, ctx.zone(), from, to);
        long count = ((Number) totals.get("n")).longValue();

        BigDecimal running = BigDecimal.ZERO;
        if (single) {
            if (from != null) {
                Map<String, Object> before = ledger.lineTotals(ctx.organizationId(), r, ctx.zone(), null,
                        from.minusDays(1));
                running = SqlRows.dec(before, "d").subtract(SqlRows.dec(before, "c"));
            }
            if (page > 1) {
                Map<String, Object> skipped = ledger.skippedTotals(ctx.organizationId(), r, ctx.zone(), from, to,
                        (page - 1) * limit);
                running = running.add(SqlRows.dec(skipped, "d")).subtract(SqlRows.dec(skipped, "c"));
            }
        }
        List<ReportBody.Column> columns = new ArrayList<>(List.of(
                new ReportBody.Column("postingDate", "Date", "date"),
                new ReportBody.Column("entryNumber", "Entry", "text"),
                new ReportBody.Column("journal", "Journal", "text"),
                new ReportBody.Column("account", "Account", "text"),
                new ReportBody.Column("description", "Description", "text"),
                new ReportBody.Column("partner", "Partner", "text"),
                new ReportBody.Column("branch", "Branch", "text"),
                new ReportBody.Column("costCenter", "Cost center", "text"),
                new ReportBody.Column("debit", "Debit", "money"),
                new ReportBody.Column("credit", "Credit", "money")));
        if (single) {
            columns.add(new ReportBody.Column("balance", "Balance", "money"));
        }
        columns.add(new ReportBody.Column("source", "Source", "text"));
        columns.add(new ReportBody.Column("status", "Status", "text"));
        columns.add(new ReportBody.Column("postedBy", "Posted by", "text"));

        List<Row> rows = new ArrayList<>();
        if (single && from != null && page == 1) {
            rows.add(new Row("opening", "subtotal", 0, true, Obj.of("description", "Opening balance", "balance",
                    round(running, false)), Map.of()));
        }
        for (Map<String, Object> l : ledger.lines(ctx.organizationId(), r, ctx.zone(), from, to, limit,
                (page - 1) * limit)) {
            BigDecimal d = SqlRows.dec(l, "debit");
            BigDecimal c = SqlRows.dec(l, "credit");
            running = running.add(d).subtract(c);
            Map<String, Object> v = new LinkedHashMap<>();
            Object posted = l.get("postingDate");
            v.put("postingDate", posted instanceof Instant i ? i.atZone(ctx.zone()).toLocalDate().toString()
                    : posted instanceof java.sql.Timestamp t ? t.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC)
                            .atZone(ctx.zone()).toLocalDate().toString() : String.valueOf(posted));
            v.put("entryNumber", l.get("entryNumber"));
            v.put("journal", l.get("journal"));
            v.put("account", l.get("accountCode") + " " + l.get("accountName"));
            v.put("description", l.get("description") != null ? l.get("description") : l.get("entryDescription"));
            v.put("partner", l.get("partner"));
            v.put("branch", l.get("branch"));
            v.put("costCenter", l.get("costCenter"));
            v.put("debit", round(d, false));
            v.put("credit", round(c, false));
            if (single) {
                v.put("balance", round(running, false));
            }
            String sourceType = (String) l.get("sourceType");
            v.put("source", sourceType == null ? null : sourceType + (l.get("sourceId") == null ? "" : " "
                    + l.get("sourceId")));
            v.put("status", l.get("status"));
            v.put("postedBy", l.get("postedBy"));
            v.put("journalEntryId", l.get("journalEntryId"));
            Map<String, Drill> drill = new LinkedHashMap<>();
            drill.put("_row", Drill.route("/journal-entries/" + l.get("journalEntryId")));
            String base = sourceType == null ? null : SOURCE_ROUTES.get(sourceType);
            if (base != null && l.get("sourceId") != null) {
                drill.put("source", Drill.route(base + l.get("sourceId")));
            }
            rows.add(new Row((String) l.get("id"), "line", 0, false, v, drill));
        }
        List<Check> checks = new ArrayList<>();
        BigDecimal td = SqlRows.dec(totals, "d");
        BigDecimal tc = SqlRows.dec(totals, "c");
        if (r.accountIds().isEmpty() && r.partnerIds().isEmpty()) {
            BigDecimal diff = td.subtract(tc).setScale(2, RoundingMode.HALF_UP);
            checks.add(new Check("DEBITS_EQUAL_CREDITS", "Debits equal credits for the selection", diff.signum() == 0,
                    diff.signum() == 0 ? null : "Difference " + diff.toPlainString()));
        }
        return new ReportBody(columns, rows, Obj.of("count", count, "debit", round(td, false), "credit",
                round(tc, false)), checks, Obj.of("page", page, "limit", limit, "total", count, "pages",
                (count + limit - 1) / limit));
    }
}
