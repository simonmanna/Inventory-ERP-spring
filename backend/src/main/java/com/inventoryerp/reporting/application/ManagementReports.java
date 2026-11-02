package com.inventoryerp.reporting.application;

import static com.inventoryerp.reporting.application.Tables.dec;
import static com.inventoryerp.reporting.application.Tables.money;
import static com.inventoryerp.reporting.application.Tables.number;
import static com.inventoryerp.reporting.application.Tables.pct;
import static com.inventoryerp.reporting.application.Tables.text;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.reporting.application.LedgerAggregator.AccountInfo;
import com.inventoryerp.reporting.application.LedgerAggregator.Segment;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.OptionSpec;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportBody.Column;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.reporting.spi.ReportProvider;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Management accounting, budgets and analysis (FR-5): budget vs actual with variance and utilisation, monthly budget
 * phasing, purchase commitments, the ratio pack (liquidity, leverage, returns, margins, DSO / DPO / DIO and the cash
 * conversion cycle), break-even and contribution margin, revenue analysis, product and customer profitability and
 * expense trends. Ratios and break-even are management measures computed from the ledger, not statement lines.
 */
@Component
public class ManagementReports implements ReportProvider {

    static final String ACC = Permissions.REPORT__ACCOUNTING;
    static final OptionSpec BUDGET = new OptionSpec("budgetId", "Budget", "budget", null, null);

    private final LedgerAggregator ledger;
    private final BudgetFigures budgets;
    private final JdbcClient jdbc;

    public ManagementReports(LedgerAggregator ledger, BudgetFigures budgets, JdbcClient jdbc) {
        this.ledger = ledger;
        this.budgets = budgets;
        this.jdbc = jdbc;
    }

    @Override
    public List<ReportDefinition> definitions() {
        ReportCategory b = ReportCategory.BUDGETING;
        ReportCategory an = ReportCategory.ANALYSIS;
        List<FilterKey> period = List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.COST_CENTER,
                FilterKey.DIMENSION, FilterKey.POSTING_STATUS);
        return List.of(
                ReportDefinition.of("mgmt.budget-vs-actual", b, "Budget vs Actual", "Actual against budget by"
                        + " account with variance, variance % and utilisation.", "MANAGEMENT", ACC, period, BUDGET,
                        OptionSpec.select("scope", "Accounts", "pl", "pl", "capex", "all")),
                ReportDefinition.of("mgmt.budget-monthly", b, "Monthly Budget", "Approved budget by account and"
                        + " month.", "MANAGEMENT", Permissions.BUDGET__READ, List.of(FilterKey.PERIOD), BUDGET),
                ReportDefinition.of("mgmt.commitments", b, "Budget Commitment Report", "Approved purchase orders not"
                        + " yet billed: committed spend by supplier and branch.", "MANAGEMENT", ACC,
                        List.of(FilterKey.AS_OF, FilterKey.BRANCH, FilterKey.PARTNER)),
                ReportDefinition.of("mgmt.ratios", an, "Financial Ratios and KPIs", "Liquidity, leverage, returns,"
                        + " margins, DSO, DPO, DIO and the cash conversion cycle, with prior period.", "MANAGEMENT",
                        ACC, List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.POSTING_STATUS)),
                ReportDefinition.of("mgmt.break-even", an, "Break-even and Contribution Margin", "Contribution"
                        + " margin, fixed costs, break-even revenue and margin of safety.", "MANAGEMENT", ACC, period,
                        OptionSpec.number("variableOpexPct", "Variable share of operating expenses %", "0")),
                ReportDefinition.of("mgmt.revenue-analysis", an, "Revenue Analysis", "Net sales by product,"
                        + " category, customer, branch or month.", "MANAGEMENT", ACC, List.of(FilterKey.PERIOD,
                        FilterKey.BRANCH, FilterKey.PARTNER), OptionSpec.select("groupBy", "Group by", "category",
                                "product", "category", "customer", "branch", "month")),
                ReportDefinition.of("mgmt.product-profitability", an, "Product Profitability", "Net sales, cost of"
                        + " stock issued and gross margin by product.", "MANAGEMENT", ACC, List.of(FilterKey.PERIOD,
                        FilterKey.BRANCH)),
                ReportDefinition.of("mgmt.customer-profitability", an, "Customer Profitability", "Net sales,"
                        + " estimated cost at average cost and gross margin by customer.", "MANAGEMENT", ACC,
                        List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.PARTNER)),
                ReportDefinition.of("mgmt.expense-analysis", an, "Expense Analysis", "Expense accounts month by"
                        + " month with total and share.", "MANAGEMENT", ACC, period));
    }

    @Override
    public ReportBody run(ReportContext ctx) {
        return switch (ctx.definition().id()) {
            case "mgmt.budget-vs-actual" -> budgetVsActual(ctx);
            case "mgmt.budget-monthly" -> budgetMonthly(ctx);
            case "mgmt.commitments" -> commitments(ctx);
            case "mgmt.ratios" -> ratios(ctx);
            case "mgmt.break-even" -> breakEven(ctx);
            case "mgmt.revenue-analysis" -> revenue(ctx, ctx.request().option("groupBy"));
            case "mgmt.product-profitability" -> productProfitability(ctx);
            case "mgmt.customer-profitability" -> customerProfitability(ctx);
            case "mgmt.expense-analysis" -> expenses(ctx);
            default -> throw ApiException.notFound("Report " + ctx.definition().id() + " not found");
        };
    }

    private Timestamp from(ReportContext ctx) {
        return SqlRows.ts(ctx.start(ctx.current().from()));
    }

    private Timestamp to(ReportContext ctx) {
        return SqlRows.ts(ctx.endExclusive(ctx.current().to()));
    }

    // ---------------------------------------------------------------- budgets

    ReportBody budgetVsActual(ReportContext ctx) {
        String scope = ctx.request().option("scope");
        Predicate<AccountInfo> in = switch (scope == null ? "pl" : scope) {
            case "capex" -> a -> List.of("non_current_asset", "construction_in_progress").contains(a.category());
            case "all" -> a -> true;
            default -> a -> StatementLayoutService.PL_SECTIONS.contains(a.section());
        };
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal> budget = budgets.amounts(ctx, ctx.current());
        Map<String, BigDecimal[]> actual = ledger.balances(ctx, ctx.current());
        Map<String, Object> b = budgets.budget(ctx);
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("code", "Code"), text("account", "Account"),
                text("section", "Section"), money("budget", "Budget"), money("actual", "Actual"), money("variance",
                        "Variance (favourable +)"), pct("variancePct", "Variance %"), pct("utilization",
                        "Utilisation %"));
        for (AccountInfo a : accounts.values()) {
            if (!in.test(a)) {
                continue;
            }
            BigDecimal bud = budget.getOrDefault(a.id(), BigDecimal.ZERO);
            BigDecimal[] m = GlReports.get(actual, a.id());
            BigDecimal act = GlReports.natural(a, m[0], m[1]);
            if (bud.signum() == 0 && act.signum() == 0) {
                continue;
            }
            boolean income = "credit".equals(a.normalBalance()) && StatementLayoutService.PL_SECTIONS.contains(
                    a.section());
            BigDecimal variance = income ? act.subtract(bud) : bud.subtract(act);
            t.line(Obj.of("_id", a.id(), "accountId", a.id(), "code", a.code(), "account", a.name(), "section",
                    a.section(), "budget", bud, "actual", act, "variance", variance, "variancePct", Tables.pctOf(
                            variance, bud.abs()), "utilization", Tables.pctOf(act, bud)), Map.of("actual",
                    GlReports.lines(ctx, ctx.current().from(), ctx.current().to(), List.of(a.id()))));
        }
        t.check("BUDGET_FOUND", "A budget covers the period", b != null, "No budget covers the period; choose one");
        t.meta("budget", b == null ? null : b.get("name"));
        return t.build();
    }

    ReportBody budgetMonthly(ReportContext ctx) {
        Map<String, Object> b = budgets.budget(ctx);
        List<String> months = BudgetFigures.months(ctx.current().from(), ctx.current().to());
        List<Column> cols = new ArrayList<>(List.of(text("code", "Code"), text("account", "Account")));
        months.forEach(m -> cols.add(money("M" + m, m)));
        cols.add(money("total", "Total"));
        Tables.Builder t = new Tables.Builder(ctx.zone(), cols);
        if (b == null) {
            t.check("BUDGET_FOUND", "A budget covers the period", false, "No budget covers the period");
            return t.build();
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name, bl.period, sum(bl.amount) AS"
                + " amount FROM \"BudgetLine\" bl JOIN \"Account\" a ON a.id = bl.\"accountId\" WHERE bl.\"budgetId\" = ?"
                + " AND bl.period >= ? AND bl.period <= ? GROUP BY a.id, bl.period ORDER BY a.code").params(b.get("id"),
                months.getFirst(), months.getLast()))) {
            Map<String, Object> row = rows.computeIfAbsent((String) r.get("id"), k -> new LinkedHashMap<>(Obj.of(
                    "_id", k, "code", r.get("code"), "account", r.get("name"), "total", BigDecimal.ZERO)));
            row.put("M" + r.get("period"), r.get("amount"));
            row.put("total", dec(row.get("total")).add(dec(r.get("amount"))));
        }
        rows.values().forEach(t::line);
        t.meta("budget", b.get("name"));
        return t.build();
    }

    ReportBody commitments(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), SqlRows.ts(ctx.endExclusive(ctx.asOf()))));
        String f = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            f += " AND po.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        if (!ctx.request().branchIds().isEmpty()) {
            f += " AND po.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT po.id, po.\"orderNumber\", po.\"orderDate\","
                + " po.\"expectedDeliveryDate\", po.status::text AS status, pt.name AS supplier, b.name AS branch,"
                + " po.\"totalAmount\", coalesce(po.\"totalPaid\", 0) AS paid, po.\"totalAmount\" -"
                + " coalesce(po.\"totalPaid\", 0) AS committed FROM \"PurchaseOrder\" po LEFT JOIN \"Partner\" pt ON pt.id"
                + " = po.\"partnerId\" LEFT JOIN \"Branch\" b ON b.id = po.\"branchId\" WHERE po.\"organizationId\" = ? AND"
                + " po.\"orderDate\" < ? AND po.status::text IN ('approved', 'sent', 'acknowledged', 'partially_received',"
                + " 'received', 'active')" + f + " ORDER BY pt.name, po.\"orderDate\"").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("supplier", "Supplier"), text("orderNumber",
                "Order"), Tables.date("orderDate", "Ordered"), Tables.date("expectedDeliveryDate", "Expected"),
                text("status", "Status"), text("branch", "Branch"), money("totalAmount", "Order value"), money(
                        "paid", "Paid"), money("committed", "Committed (not billed)"));
        t.grouped(rows, "supplier", "supplier", r -> Map.of("_row", ReportBody.Drill.route(
                "/procurement/purchase-orders/" + r.get("id"))));
        return t.build();
    }

    // ---------------------------------------------------------------- ratios

    /** Category / section sums of one period column: point-in-time balances and period flows. */
    private record Figures(Map<String, BigDecimal> bs, Map<String, BigDecimal> pl) {

        BigDecimal b(String... keys) {
            BigDecimal s = BigDecimal.ZERO;
            for (String k : keys) {
                s = s.add(bs.getOrDefault(k, BigDecimal.ZERO));
            }
            return s;
        }

        BigDecimal p(String... keys) {
            BigDecimal s = BigDecimal.ZERO;
            for (String k : keys) {
                s = s.add(pl.getOrDefault(k, BigDecimal.ZERO));
            }
            return s;
        }
    }

    private Figures figures(ReportContext ctx, ReportPeriod period) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal> bs = new LinkedHashMap<>();
        Map<String, BigDecimal> pl = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal[]> e : ledger.balances(ctx, new ReportPeriod("C", "", null, period.to()))
                .entrySet()) {
            AccountInfo a = accounts.get(e.getKey());
            if (a != null) {
                BigDecimal dr = e.getValue()[0].subtract(e.getValue()[1]);
                bs.merge(a.category(), dr, BigDecimal::add);
                bs.merge("S:" + a.section(), dr, BigDecimal::add);
            }
        }
        for (Map.Entry<String, BigDecimal[]> e : ledger.balances(ctx, period).entrySet()) {
            AccountInfo a = accounts.get(e.getKey());
            if (a != null) {
                BigDecimal cr = e.getValue()[1].subtract(e.getValue()[0]);
                pl.merge(a.section(), cr, BigDecimal::add);
                if ("5600".equals(a.code()) || GlReports.nameHas(a, "depreciation", "amortisation",
                        "amortization")) {
                    pl.merge("DEP", cr, BigDecimal::add);
                }
            }
        }
        return new Figures(bs, pl);
    }

    ReportBody ratios(ReportContext ctx) {
        ReportPeriod cur = ctx.current();
        ReportPeriod prior = ReportPeriods.priorPeriod(cur);
        List<Object[]> lines = new ArrayList<>();
        Map<String, BigDecimal[]> values = new LinkedHashMap<>();
        Figures[] f = {figures(ctx, cur), figures(ctx, prior)};
        long[] days = {ChronoUnit.DAYS.between(cur.from(), cur.to()) + 1, ChronoUnit.DAYS.between(prior.from(),
                prior.to()) + 1};
        for (int i = 0; i < 2; i++) {
            Figures x = f[i];
            BigDecimal ca = x.b("S:current_assets");
            BigDecimal cl = x.b("S:current_liabilities").negate();
            BigDecimal inv = x.b("inventory", "work_in_progress");
            BigDecimal ar = x.b("receivable");
            BigDecimal ap = x.b("payable").negate();
            BigDecimal cash = x.b("cash", "bank", "mobile_money", "petty_cash");
            BigDecimal ta = x.b("S:current_assets", "S:non_current_assets");
            BigDecimal tl = x.b("S:current_liabilities", "S:long_term_liabilities").negate();
            BigDecimal equity = ta.subtract(tl);
            BigDecimal debt = x.b("S:long_term_liabilities").negate();
            BigDecimal revenue = x.p("revenue", "contra_revenue");
            BigDecimal cogs = x.p("cogs").negate();
            BigDecimal gp = revenue.subtract(cogs);
            BigDecimal opex = x.p("operating_expense").negate();
            BigDecimal op = gp.add(x.p("other_income")).subtract(opex);
            BigDecimal np = x.p("revenue", "contra_revenue", "cogs", "operating_expense", "other_income",
                    "other_expense");
            BigDecimal ebitda = op.subtract(x.p("DEP"));
            BigDecimal d = BigDecimal.valueOf(days[i]);
            BigDecimal annual = BigDecimal.valueOf(365).divide(d, 6, RoundingMode.HALF_UP);
            BigDecimal dso = Tables.div(ar.multiply(d), revenue, 1);
            BigDecimal dpo = Tables.div(ap.multiply(d), cogs, 1);
            BigDecimal dio = Tables.div(inv.multiply(d), cogs, 1);
            Object[][] all = {
                {"H_LIQ", "Liquidity", null, null},
                {"WC", "Working capital", ca.subtract(cl), "Current assets − current liabilities"},
                {"CR", "Current ratio", Tables.div(ca, cl, 2), "Current assets / current liabilities"},
                {"QR", "Quick ratio", Tables.div(ca.subtract(inv), cl, 2), "(Current assets − inventories) / current"
                    + " liabilities"},
                {"CASHR", "Cash ratio", Tables.div(cash, cl, 2), "Cash and equivalents / current liabilities"},
                {"H_LEV", "Leverage", null, null},
                {"DE", "Debt-to-equity", Tables.div(debt, equity, 2), "Non-current liabilities / equity"},
                {"LE", "Liabilities-to-equity", Tables.div(tl, equity, 2), "Total liabilities / equity"},
                {"H_RET", "Returns (annualised)", null, null},
                {"ROA", "Return on assets %", Tables.pctOf(np.multiply(annual), ta), "Net profit × 365 / days /"
                    + " total assets"},
                {"ROE", "Return on equity %", Tables.pctOf(np.multiply(annual), equity), "Net profit × 365 / days /"
                    + " equity"},
                {"H_MAR", "Margins", null, null},
                {"GPM", "Gross margin %", Tables.pctOf(gp, revenue), "Gross profit / net revenue"},
                {"EBITDAM", "EBITDA margin %", Tables.pctOf(ebitda, revenue), "EBITDA / net revenue"},
                {"OPM", "Operating margin %", Tables.pctOf(op, revenue), "Operating profit / net revenue"},
                {"NPM", "Net margin %", Tables.pctOf(np, revenue), "Net profit / net revenue"},
                {"H_CYC", "Working capital cycle (days)", null, null},
                {"DSO", "Receivable days (DSO)", dso, "Receivables / net revenue × days"},
                {"DIO", "Inventory days (DIO)", dio, "Inventories / cost of sales × days"},
                {"DPO", "Payable days (DPO)", dpo, "Payables / cost of sales × days"},
                {"CCC", "Cash conversion cycle", dso == null || dio == null || dpo == null ? null : dso.add(dio)
                    .subtract(dpo), "DSO + DIO − DPO"},
                {"H_BASE", "Inputs", null, null},
                {"REV", "Net revenue", revenue, null},
                {"EBITDA", "EBITDA", ebitda, "Operating profit + depreciation and amortisation"},
                {"NP", "Net profit", np, null},
                {"TA", "Total assets", ta, null},
                {"EQ", "Equity", equity, null}};
            for (Object[] l : all) {
                if (i == 0) {
                    lines.add(l);
                }
                values.computeIfAbsent((String) l[0], k -> new BigDecimal[2])[i] = (BigDecimal) l[2];
            }
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("label", "Measure"), number("CURRENT", cur.label()),
                number("PRIOR", prior.label()), number("change", "Change"), text("formula", "Formula"));
        for (Object[] l : lines) {
            String code = (String) l[0];
            if (code.startsWith("H_")) {
                t.add("header", 0, true, Obj.of("_id", code, "label", l[1]), Map.of(), false);
                continue;
            }
            BigDecimal[] v = values.get(code);
            t.add("kpi", 1, false, Obj.of("_id", code, "label", l[1], "CURRENT", v[0] == null ? null : v[0].setScale(2,
                    RoundingMode.HALF_UP), "PRIOR", v[1] == null ? null : v[1].setScale(2, RoundingMode.HALF_UP),
                    "change", v[0] == null || v[1] == null ? null : v[0].subtract(v[1]).setScale(2,
                            RoundingMode.HALF_UP), "formula", l[3]), Map.of(), false);
        }
        t.meta("note", "Management measures from the ledger; balances at the period end, flows over the period.");
        return t.build(false);
    }

    ReportBody breakEven(ReportContext ctx) {
        Figures x = figures(ctx, ctx.current());
        BigDecimal revenue = x.p("revenue", "contra_revenue");
        BigDecimal cogs = x.p("cogs").negate();
        BigDecimal opex = x.p("operating_expense", "other_expense").negate().subtract(x.p("other_income"));
        BigDecimal varPct = ctx.request().decimalOption("variableOpexPct");
        BigDecimal variableOpex = opex.multiply(varPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal variable = cogs.add(variableOpex);
        BigDecimal fixed = opex.subtract(variableOpex);
        BigDecimal cm = revenue.subtract(variable);
        BigDecimal cmRatio = Tables.div(cm, revenue, 6);
        BigDecimal breakEven = cmRatio == null || cmRatio.signum() <= 0 ? null : fixed.divide(cmRatio, 2,
                RoundingMode.HALF_UP);
        BigDecimal safety = breakEven == null ? null : revenue.subtract(breakEven);
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("label", "Measure"), money("amount", "Amount"),
                text("note", "Basis"));
        Object[][] rows = {{"REV", "Net revenue", revenue, null}, {"VAR", "Variable costs", variable,
            "Cost of sales + " + varPct.stripTrailingZeros().toPlainString() + "% of operating expenses"},
            {"CM", "Contribution margin", cm, "Revenue − variable costs"},
            {"CMR", "Contribution margin ratio %", cmRatio == null ? null : cmRatio.multiply(BigDecimal.valueOf(100)),
                "Contribution / revenue"},
            {"FIX", "Fixed costs", fixed, "Remaining operating and other expenses, net of other income"},
            {"PROFIT", "Profit (contribution − fixed)", cm.subtract(fixed), null},
            {"BE", "Break-even revenue", breakEven, "Fixed costs / contribution margin ratio"},
            {"MOS", "Margin of safety", safety, "Revenue − break-even revenue"},
            {"MOSP", "Margin of safety %", Tables.pctOf(safety, revenue), null}};
        for (Object[] r : rows) {
            Map<String, Object> v = Obj.of("_id", r[0], "label", r[1], "amount", r[2], "note", r[3]);
            if ("CMR".equals(r[0]) || "MOSP".equals(r[0])) {
                v.put("_format", "percent");
            }
            t.add("kpi", 0, List.of("CM", "BE", "PROFIT").contains(r[0]), v, Map.of(), false);
        }
        return t.build(false);
    }

    // ---------------------------------------------------------------- revenue and profitability

    /** Net sales lines of the period (invoices and debit notes, credit notes negative, POS invoices). */
    List<Map<String, Object>> salesLines(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String df = "";
        String inf = "";
        if (!ctx.request().branchIds().isEmpty()) {
            df += " AND d.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        if (!ctx.request().partnerIds().isEmpty()) {
            df += " AND d.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        p.addAll(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        if (!ctx.request().branchIds().isEmpty()) {
            inf += " AND i.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        if (!ctx.request().partnerIds().isEmpty()) {
            inf += " AND i.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        return SqlRows.list(jdbc.sql("SELECT x.*, coalesce(pr.name, x.description, 'Other') AS product,"
                + " coalesce(pc.name, 'Uncategorised') AS category, coalesce(pt.name, 'Walk-in / cash') AS customer,"
                + " coalesce(b.name, 'Unassigned') AS branch FROM (SELECT d.\"partnerId\", d.\"branchId\","
                + " d.\"issueDate\", dl.\"productId\", dl.description, CASE WHEN d.\"documentType\"::text = 'credit_note'"
                + " THEN -1 ELSE 1 END * dl.quantity * coalesce(dl.\"uomFactor\", 1) AS qty, CASE WHEN"
                + " d.\"documentType\"::text = 'credit_note' THEN -1 ELSE 1 END * dl.subtotal AS net FROM \"DocumentLine\""
                + " dl JOIN \"Document\" d ON d.id = dl.\"documentId\" WHERE d.\"organizationId\" = ? AND d.\"issueDate\""
                + " >= ? AND d.\"issueDate\" < ? AND d.status::text IN ('posted', 'paid') AND d.\"documentType\"::text IN"
                + " ('sales_invoice', 'debit_note', 'credit_note')" + df + " UNION ALL SELECT i.\"partnerId\","
                + " i.\"branchId\", i.\"issueDate\", ii.\"productId\", ii.description, ii.quantity *"
                + " coalesce(ii.\"uomFactor\", 1), ii.subtotal FROM \"InvoiceItem\" ii JOIN \"Invoice\" i ON i.id ="
                + " ii.\"invoiceId\" WHERE i.\"organizationId\" = ? AND i.\"issueDate\" >= ? AND i.\"issueDate\" < ? AND"
                + " i.status::text IN ('posted', 'paid')" + inf + ") x LEFT JOIN \"Product\" pr ON pr.id = x.\"productId\""
                + " LEFT JOIN \"ProductCategory\" pc ON pc.id = pr.\"categoryId\" LEFT JOIN \"Partner\" pt ON pt.id ="
                + " x.\"partnerId\" LEFT JOIN \"Branch\" b ON b.id = x.\"branchId\"").params(p));
    }

    ReportBody revenue(ReportContext ctx, String groupBy) {
        String key = groupBy == null ? "category" : groupBy;
        Map<String, Map<String, Object>> agg = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : salesLines(ctx)) {
            String k = "month".equals(key) ? Tables.day(l.get("issueDate"), ctx.zone()).substring(0, 7)
                    : String.valueOf(l.get(key));
            Map<String, Object> a = agg.computeIfAbsent(k, x -> new LinkedHashMap<>(Obj.of("_id", x, "label", x,
                    "quantity", BigDecimal.ZERO, "net", BigDecimal.ZERO)));
            a.put("quantity", dec(a.get("quantity")).add(dec(l.get("qty"))));
            a.put("net", dec(a.get("net")).add(dec(l.get("net"))));
            total = total.add(dec(l.get("net")));
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("label", Character.toUpperCase(key.charAt(0))
                + key.substring(1)), number("quantity", "Quantity"), money("net", "Net sales"), pct("share",
                "Share %"));
        List<Map<String, Object>> rows = new ArrayList<>(agg.values());
        if (!"month".equals(key)) {
            rows.sort((a, b) -> dec(b.get("net")).compareTo(dec(a.get("net"))));
        }
        for (Map<String, Object> r : rows) {
            r.put("share", Tables.pctOf(dec(r.get("net")), total));
            t.line(r);
        }
        return t.build();
    }

    ReportBody productProfitability(ReportContext ctx) {
        Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
        for (Map<String, Object> l : salesLines(ctx)) {
            String k = l.get("productId") == null ? "other:" + l.get("product") : (String) l.get("productId");
            Map<String, Object> a = agg.computeIfAbsent(k, x -> new LinkedHashMap<>(Obj.of("_id", x, "product",
                    l.get("product"), "category", l.get("category"), "quantity", BigDecimal.ZERO, "net",
                    BigDecimal.ZERO, "cost", BigDecimal.ZERO)));
            a.put("quantity", dec(a.get("quantity")).add(dec(l.get("qty"))));
            a.put("net", dec(a.get("net")).add(dec(l.get("net"))));
        }
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String b = "";
        if (!ctx.request().branchIds().isEmpty()) {
            b = " AND loc.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"productId\", sum(abs(coalesce(l.\"totalValue\","
                + " 0))) AS cost FROM \"InventoryLedger\" l LEFT JOIN \"InventoryLocation\" loc ON loc.id = l.\"locationId\""
                + " WHERE l.\"organizationId\" = ? AND l.\"createdAt\" >= ? AND l.\"createdAt\" < ? AND l.type::text ="
                + " 'issue'" + b + " GROUP BY 1").params(p))) {
            Map<String, Object> a = agg.get((String) r.get("productId"));
            if (a != null) {
                a.put("cost", dec(r.get("cost")));
            }
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("product", "Product"), text("category",
                "Category"), number("quantity", "Quantity"), money("net", "Net sales"), money("cost",
                "Cost of stock issued"), money("margin", "Gross margin"), pct("marginPct", "Margin %"));
        List<Map<String, Object>> rows = new ArrayList<>(agg.values());
        for (Map<String, Object> r : rows) {
            r.put("margin", dec(r.get("net")).subtract(dec(r.get("cost"))));
            r.put("marginPct", Tables.pctOf(dec(r.get("margin")), dec(r.get("net"))));
        }
        rows.sort((a, c) -> dec(c.get("margin")).compareTo(dec(a.get("margin"))));
        rows.forEach(t::line);
        return t.build();
    }

    ReportBody customerProfitability(ReportContext ctx) {
        Map<String, BigDecimal> avg = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"productId\", sum(quantity *"
                + " coalesce(\"runningAverageCost\", 0)) / nullif(sum(quantity), 0) AS cost FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? AND quantity > 0 GROUP BY 1").param(ctx.organizationId()))) {
            avg.put((String) r.get("productId"), dec(r.get("cost")));
        }
        Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : salesLines(ctx)) {
            String k = String.valueOf(l.get("customer"));
            Map<String, Object> a = agg.computeIfAbsent(k, x -> new LinkedHashMap<>(Obj.of("_id", x, "customer", x,
                    "net", BigDecimal.ZERO, "cost", BigDecimal.ZERO)));
            a.put("net", dec(a.get("net")).add(dec(l.get("net"))));
            BigDecimal unit = avg.get((String) l.get("productId"));
            if (unit != null) {
                a.put("cost", dec(a.get("cost")).add(unit.multiply(dec(l.get("qty")))));
            }
            total = total.add(dec(l.get("net")));
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("customer", "Customer"), money("net", "Net sales"),
                pct("share", "Share %"), money("cost", "Estimated cost"), money("margin", "Estimated margin"),
                pct("marginPct", "Margin %"));
        List<Map<String, Object>> rows = new ArrayList<>(agg.values());
        for (Map<String, Object> r : rows) {
            r.put("share", Tables.pctOf(dec(r.get("net")), total));
            r.put("margin", dec(r.get("net")).subtract(dec(r.get("cost"))));
            r.put("marginPct", Tables.pctOf(dec(r.get("margin")), dec(r.get("net"))));
        }
        rows.sort((a, c) -> dec(c.get("net")).compareTo(dec(a.get("net"))));
        rows.forEach(t::line);
        t.meta("note", "Cost is estimated at today's average cost of the products sold.");
        return t.build();
    }

    ReportBody expenses(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, Map<String, BigDecimal[]>> byMonth = new TreeMap<>(ledger.balancesBy(ctx, ctx.current(),
                Segment.MONTH));
        List<String> months = BudgetFigures.months(ctx.current().from(), ctx.current().to());
        List<Column> cols = new ArrayList<>(List.of(text("code", "Code"), text("account", "Account")));
        months.forEach(m -> cols.add(money("M" + m, m)));
        cols.add(money("total", "Total"));
        cols.add(pct("share", "Share %"));
        Tables.Builder t = new Tables.Builder(ctx.zone(), cols);
        BigDecimal grand = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AccountInfo a : accounts.values()) {
            if (!List.of("operating_expense", "other_expense", "cogs").contains(a.section())) {
                continue;
            }
            Map<String, Object> v = new LinkedHashMap<>(Obj.of("_id", a.id(), "accountId", a.id(), "code", a.code(),
                    "account", a.name()));
            BigDecimal total = BigDecimal.ZERO;
            for (String m : months) {
                BigDecimal[] x = GlReports.get(byMonth.getOrDefault(m, Map.of()), a.id());
                BigDecimal amt = x[0].subtract(x[1]);
                v.put("M" + m, amt.signum() == 0 ? null : amt);
                total = total.add(amt);
            }
            if (total.signum() == 0) {
                continue;
            }
            v.put("total", total);
            grand = grand.add(total);
            rows.add(v);
        }
        for (Map<String, Object> v : rows) {
            v.put("share", Tables.pctOf(dec(v.get("total")), grand));
            t.line(v, Map.of("total", GlReports.lines(ctx, ctx.current().from(), ctx.current().to(), List.of(
                    (String) v.get("accountId")))));
        }
        return t.build();
    }
}
