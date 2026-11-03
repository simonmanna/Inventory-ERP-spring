package com.inventoryerp.reporting.application;

import static com.inventoryerp.reporting.application.Tables.dec;
import static com.inventoryerp.reporting.application.Tables.money;
import static com.inventoryerp.reporting.application.Tables.r2;
import static com.inventoryerp.reporting.application.Tables.text;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.reporting.application.LedgerAggregator.AccountInfo;
import com.inventoryerp.reporting.application.LedgerAggregator.Segment;
import com.inventoryerp.reporting.application.StatementLayoutService.Layout;
import com.inventoryerp.reporting.application.StatementLayoutService.Line;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.OptionSpec;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportBody.Column;
import com.inventoryerp.reporting.spi.ReportBody.Drill;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.reporting.spi.ReportProvider;
import com.inventoryerp.reporting.spi.ReportRequest;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * General-ledger reports built on the shared primitives: trial balance, direct cash flow, changes in equity, general
 * ledger, journal register, account roll-forwards (account activity, prepayments, accruals, deferred revenue,
 * provisions, suspense, tax liabilities, capital work in progress, retained earnings), cash / bank / mobile-money books
 * and cash position, statements by branch / cost center / project / month, and foreign-currency balances.
 */
@Component
public class GlReports implements ReportProvider {

    static final List<FilterKey> PERIOD_GL = List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.COST_CENTER,
            FilterKey.DIMENSION, FilterKey.POSTING_STATUS);
    static final List<FilterKey> LINES_GL = List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.COST_CENTER,
            FilterKey.PARTNER, FilterKey.ACCOUNT, FilterKey.JOURNAL, FilterKey.SOURCE, FilterKey.DIMENSION,
            FilterKey.POSTING_STATUS);
    static final int MAX_LINES = 5000;
    static final String ACC = Permissions.REPORT__ACCOUNTING;

    /** A roll-forward report: which accounts it covers. */
    record RollForward(String id, ReportCategory category, String title, String description,
            Predicate<AccountInfo> accounts) {}

    static boolean nameHas(AccountInfo a, String... words) {
        String n = a.name() == null ? "" : a.name().toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (n.contains(w)) {
                return true;
            }
        }
        return false;
    }

    static boolean cat(AccountInfo a, String... cats) {
        return List.of(cats).contains(a.category());
    }

    static final List<RollForward> ROLL_FORWARDS = List.of(
            new RollForward("gl.account-activity", ReportCategory.GENERAL_LEDGER, "Account Activity",
                    "Opening balance, debits, credits and closing balance per account.", a -> true),
            new RollForward("adv.prepayments", ReportCategory.ADVANCED, "Prepayments Register",
                    "Expenses paid in advance: opening, additions, releases and closing per account.",
                    a -> cat(a, "prepaid_expense") || nameHas(a, "prepaid", "prepayment")),
            new RollForward("adv.accruals", ReportCategory.ADVANCED, "Accruals Register",
                    "Accrued expenses and income: opening, accrued, reversed / settled and closing.",
                    a -> nameHas(a, "accru")),
            new RollForward("adv.deferred-revenue", ReportCategory.ADVANCED, "Deferred Revenue",
                    "Revenue received but not yet earned (unearned revenue, customer deposits, gift cards, store"
                    + " credit).", a -> cat(a, "deferred_revenue") || List.of("2400", "2175", "2170", "2300")
                    .contains(a.code()) || nameHas(a, "unearned", "deferred revenue")),
            new RollForward("adv.provisions", ReportCategory.ADVANCED, "Provisions Roll-forward",
                    "Provisions and estimated liabilities (IAS 37), including quarry rehabilitation.",
                    a -> nameHas(a, "provision", "rehabilitation", "restoration")),
            new RollForward("audit.suspense", ReportCategory.AUDIT, "Suspense and Clearing Accounts",
                    "Unresolved balances on suspense and clearing accounts.",
                    a -> List.of("1900", "1950").contains(a.code()) || nameHas(a, "suspense", "clearing")),
            new RollForward("tax.liability", ReportCategory.TAX, "Tax Liability Roll-forward",
                    "VAT, withholding tax, PAYE and other statutory payables and receivables: opening, charged,"
                    + " paid and closing.", a -> cat(a, "tax") || List.of("1450", "2160", "2200", "2210", "2220",
                    "2230", "2240").contains(a.code()) || nameHas(a, "vat", "withholding", "paye")),
            new RollForward("fa.cwip", ReportCategory.FIXED_ASSETS, "Capital Work in Progress",
                    "Assets under construction or installation.", a -> cat(a, "construction_in_progress")
                    || nameHas(a, "work in progress") && !cat(a, "work_in_progress")),
            new RollForward("fa.gl-balances", ReportCategory.FIXED_ASSETS, "Fixed Asset GL Balances",
                    "Cost and accumulated depreciation accounts: opening, additions, depreciation and closing.",
                    a -> cat(a, "non_current_asset", "contra_asset", "accumulated_depreciation",
                            "accumulated_amortization", "construction_in_progress")),
            new RollForward("ar.bad-debt", ReportCategory.ACCOUNTS_RECEIVABLE, "Bad Debt Expense",
                    "Written-off receivables and bad debt expense.", a -> "5500".equals(a.code())
                    || nameHas(a, "bad debt", "doubtful")),
            new RollForward("cash.bank-charges", ReportCategory.CASH_AND_BANK, "Bank Charges",
                    "Bank fees and charges.", a -> nameHas(a, "bank charge", "bank fee", "charges")),
            new RollForward("fx.gain-loss", ReportCategory.CONSOLIDATION, "Exchange Gains and Losses",
                    "Realised and revaluation exchange differences.", a -> List.of("7100", "7200").contains(a.code())
                    || nameHas(a, "exchange")));

    /** A book: journal lines of one family of accounts, by account, with opening and running balance. */
    record Book(String id, ReportCategory category, String title, String description, List<String> categories) {}

    static final List<Book> BOOKS = List.of(
            new Book("cash.cash-book", ReportCategory.CASH_AND_BANK, "Cash Book",
                    "All cash receipts and payments with running balance.", List.of("cash")),
            new Book("cash.bank-book", ReportCategory.CASH_AND_BANK, "Bank Book",
                    "All transactions by bank account with running balance.", List.of("bank")),
            new Book("cash.mobile-money-book", ReportCategory.CASH_AND_BANK, "Mobile Money Book",
                    "MTN MoMo, Airtel Money and other wallets with running balance.", List.of("mobile_money")),
            new Book("cash.petty-cash", ReportCategory.CASH_AND_BANK, "Petty Cash Book",
                    "Petty cash transactions and balances.", List.of("petty_cash")));

    /** Statements split into columns. */
    record Matrix(String id, ReportCategory category, String title, String description, String kind,
            Segment segment) {}

    static final List<Matrix> MATRICES = List.of(
            new Matrix("branch.profit-and-loss", ReportCategory.CONSOLIDATION, "Branch Profit and Loss",
                    "Statement of profit or loss with one column per branch and the company total.", "PL",
                    Segment.BRANCH),
            new Matrix("branch.balance-sheet", ReportCategory.CONSOLIDATION, "Branch Balance Sheet",
                    "Statement of financial position with one column per branch.", "BS", Segment.BRANCH),
            new Matrix("mgmt.cost-center-pl", ReportCategory.COST_ACCOUNTING, "Departmental / Cost Center P&L",
                    "Profit or loss with one column per cost center and the unassigned remainder.", "PL",
                    Segment.COST_CENTER),
            new Matrix("mgmt.project-pl", ReportCategory.ANALYSIS, "Project Profitability (GL)",
                    "Profit or loss with one column per project dimension.", "PL", Segment.PROJECT),
            new Matrix("mgmt.monthly-pl", ReportCategory.ANALYSIS, "Monthly Profit and Loss Trend",
                    "Profit or loss month by month across the period.", "PL", Segment.MONTH));

    private final LedgerAggregator ledger;
    private final StatementLayoutService layouts;
    private final LedgerReports statements;
    private final JdbcClient jdbc;

    public GlReports(LedgerAggregator ledger, StatementLayoutService layouts, LedgerReports statements,
            JdbcClient jdbc) {
        this.ledger = ledger;
        this.layouts = layouts;
        this.statements = statements;
        this.jdbc = jdbc;
    }

    @Override
    public List<ReportDefinition> definitions() {
        List<ReportDefinition> out = new ArrayList<>(List.of(
                ReportDefinition.of("fs.trial-balance", ReportCategory.FINANCIAL_STATEMENTS, "Trial Balance",
                        "Opening, period and closing debit / credit balances of every account; checks that debits"
                        + " equal credits.", "FINANCIAL", ACC, List.of(FilterKey.PERIOD, FilterKey.BRANCH,
                        FilterKey.COST_CENTER, FilterKey.DIMENSION, FilterKey.POSTING_STATUS, FilterKey.ACCOUNT),
                        OptionSpec.select("includeZero", "Accounts without balance", "no", "no", "yes")),
                ReportDefinition.of("fs.cash-flow-direct", ReportCategory.FINANCIAL_STATEMENTS,
                        "Statement of Cash Flows (direct)", "Cash receipts and payments by nature, from the"
                        + " counterpart of every cash, bank and mobile-money posting.", "FINANCIAL", ACC,
                        List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.POSTING_STATUS)),
                ReportDefinition.of("fs.changes-in-equity", ReportCategory.FINANCIAL_STATEMENTS,
                        "Statement of Changes in Equity", "Opening equity, profit for the period, other"
                        + " comprehensive income, other movements and closing equity by component.", "FINANCIAL",
                        ACC, List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.POSTING_STATUS)),
                ReportDefinition.of("gl.general-ledger", ReportCategory.GENERAL_LEDGER, "General Ledger",
                        "Every journal line by account with opening, running and closing balance, users,"
                        + " dimensions and source documents.", "FINANCIAL", ACC, LINES_GL),
                ReportDefinition.of("gl.journal-register", ReportCategory.GENERAL_LEDGER, "Journal Register",
                        "Journal entries with type, source, totals, preparer, poster and reversal references;"
                        + " checks every entry balances.", "FINANCIAL", ACC, List.of(FilterKey.PERIOD,
                        FilterKey.BRANCH, FilterKey.JOURNAL, FilterKey.SOURCE, FilterKey.POSTING_STATUS)),
                ReportDefinition.of("cash.position", ReportCategory.CASH_AND_BANK, "Cash Position",
                        "Cash, bank and mobile-money balances by account and branch at a date.", "FINANCIAL", ACC,
                        List.of(FilterKey.AS_OF, FilterKey.BRANCH, FilterKey.POSTING_STATUS)),
                ReportDefinition.of("branch.trial-balance", ReportCategory.CONSOLIDATION, "Branch Trial Balance",
                        "Closing balance of every account per branch; branches add up to the company.", "FINANCIAL",
                        ACC, List.of(FilterKey.AS_OF, FilterKey.BRANCH, FilterKey.POSTING_STATUS)),
                ReportDefinition.of("fx.currency-balances", ReportCategory.CONSOLIDATION,
                        "Foreign Currency Balances", "Account balances by transaction currency with their base"
                        + " currency value and implied rate.", "FINANCIAL", ACC, List.of(FilterKey.AS_OF,
                        FilterKey.BRANCH, FilterKey.ACCOUNT, FilterKey.POSTING_STATUS)),
                ReportDefinition.of("fx.rate-history", ReportCategory.CONSOLIDATION, "Exchange Rate History",
                        "Exchange rates recorded in the period.", "OPERATIONAL", ACC, List.of(FilterKey.PERIOD)),
                ReportDefinition.of("adv.retained-earnings", ReportCategory.ADVANCED,
                        "Retained Earnings Reconciliation", "Opening retained earnings, profit for the period,"
                        + " distributions and other movements to closing retained earnings.", "FINANCIAL", ACC,
                        List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.POSTING_STATUS))));
        for (RollForward rf : ROLL_FORWARDS) {
            out.add(ReportDefinition.of(rf.id(), rf.category(), rf.title(), rf.description(), "FINANCIAL", ACC,
                    List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.COST_CENTER, FilterKey.ACCOUNT,
                            FilterKey.DIMENSION, FilterKey.POSTING_STATUS)));
        }
        for (Book b : BOOKS) {
            out.add(ReportDefinition.of(b.id(), b.category(), b.title(), b.description(), "FINANCIAL", ACC,
                    List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.ACCOUNT, FilterKey.PARTNER,
                            FilterKey.POSTING_STATUS)));
        }
        for (Matrix m : MATRICES) {
            out.add(ReportDefinition.of(m.id(), m.category(), m.title(), m.description(), "BS".equals(m.kind())
                    ? "FINANCIAL" : "MANAGEMENT", ACC, "BS".equals(m.kind()) ? List.of(FilterKey.AS_OF,
                    FilterKey.BRANCH, FilterKey.POSTING_STATUS, FilterKey.LAYOUT) : List.of(FilterKey.PERIOD,
                    FilterKey.BRANCH, FilterKey.COST_CENTER, FilterKey.DIMENSION, FilterKey.POSTING_STATUS,
                    FilterKey.LAYOUT)));
        }
        return out;
    }

    @Override
    public ReportBody run(ReportContext ctx) {
        String id = ctx.definition().id();
        for (RollForward rf : ROLL_FORWARDS) {
            if (rf.id().equals(id)) {
                return rollForward(ctx, rf.accounts());
            }
        }
        for (Book b : BOOKS) {
            if (b.id().equals(id)) {
                return book(ctx, a -> b.categories().contains(a.category()));
            }
        }
        for (Matrix m : MATRICES) {
            if (m.id().equals(id)) {
                return matrix(ctx, m.kind(), m.segment());
            }
        }
        return switch (id) {
            case "fs.trial-balance" -> trialBalance(ctx);
            case "fs.cash-flow-direct" -> directCashFlow(ctx);
            case "fs.changes-in-equity" -> changesInEquity(ctx);
            case "gl.general-ledger" -> book(ctx, a -> true);
            case "gl.journal-register" -> journalRegister(ctx);
            case "cash.position" -> cashPosition(ctx);
            case "branch.trial-balance" -> branchTrialBalance(ctx);
            case "fx.currency-balances" -> currencyBalances(ctx);
            case "fx.rate-history" -> rateHistory(ctx);
            case "adv.retained-earnings" -> retainedEarnings(ctx);
            default -> throw ApiException.notFound("Report " + id + " not found");
        };
    }

    // ---------------------------------------------------------------- helpers

    static Drill lines(ReportContext ctx, LocalDate from, LocalDate to, List<String> accountIds) {
        ReportRequest r = ctx.request();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", from == null ? LedgerReports.EARLIEST : from.toString());
        p.put("to", to.toString());
        if (accountIds != null && !accountIds.isEmpty()) {
            p.put("accountIds", accountIds);
        }
        if (!r.branchIds().isEmpty()) {
            p.put("branchIds", r.branchIds());
        }
        if (!r.costCenterIds().isEmpty()) {
            p.put("costCenterIds", r.costCenterIds());
        }
        if (!r.partnerIds().isEmpty()) {
            p.put("partnerIds", r.partnerIds());
        }
        if (!r.dimensions().isEmpty()) {
            p.put("dimensions", r.dimensions());
        }
        if (r.postingStatus() != null) {
            p.put("postingStatus", r.postingStatus());
        }
        return Drill.report(LedgerReports.JOURNAL_LINES, p);
    }

    /** Balance shown in the account's normal sign (credit-normal accounts as credit − debit). */
    static BigDecimal natural(AccountInfo a, BigDecimal debit, BigDecimal credit) {
        return "credit".equals(a.normalBalance()) ? credit.subtract(debit) : debit.subtract(credit);
    }

    static BigDecimal[] get(Map<String, BigDecimal[]> m, String id) {
        BigDecimal[] v = m.get(id);
        return v == null ? new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO} : v;
    }

    private ReportPeriod before(ReportContext ctx) {
        return new ReportPeriod("OPEN", "", null, ctx.current().from().minusDays(1));
    }

    private Map<String, String> names(String table, String org) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM \"" + table + "\" WHERE \"organizationId\" = ?").param(org)
                .query((rs, n) -> out.put(rs.getString("id"), rs.getString("name"))).list();
        return out;
    }

    // ---------------------------------------------------------------- trial balance

    ReportBody trialBalance(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal[]> open = ledger.balances(ctx, before(ctx));
        Map<String, BigDecimal[]> mov = ledger.balances(ctx, ctx.current());
        boolean zero = "yes".equals(ctx.request().option("includeZero"));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("code", "Code"), text("account", "Account"),
                text("category", "Category"), money("openingDebit", "Opening Dr"), money("openingCredit",
                        "Opening Cr"), money("debit", "Period Dr"), money("credit", "Period Cr"),
                money("closingDebit", "Closing Dr"), money("closingCredit", "Closing Cr"));
        for (AccountInfo a : accounts.values()) {
            if (!ctx.request().accountIds().isEmpty() && !ctx.request().accountIds().contains(a.id())) {
                continue;
            }
            BigDecimal[] o = get(open, a.id());
            BigDecimal[] m = get(mov, a.id());
            BigDecimal ob = o[0].subtract(o[1]);
            BigDecimal cb = ob.add(m[0]).subtract(m[1]);
            if (!zero && ob.signum() == 0 && m[0].signum() == 0 && m[1].signum() == 0) {
                continue;
            }
            t.line(Obj.of("_id", a.id(), "accountId", a.id(), "code", a.code(), "account", a.name(), "category",
                    a.category(), "openingDebit", ob.max(BigDecimal.ZERO), "openingCredit", ob.negate().max(
                            BigDecimal.ZERO), "debit", m[0], "credit", m[1], "closingDebit", cb.max(BigDecimal.ZERO),
                    "closingCredit", cb.negate().max(BigDecimal.ZERO)), Map.of("debit", lines(ctx, ctx.current()
                    .from(), ctx.current().to(), List.of(a.id())), "credit", lines(ctx, ctx.current().from(),
                    ctx.current().to(), List.of(a.id())), "closingDebit", lines(ctx, null, ctx.current().to(),
                    List.of(a.id())), "closingCredit", lines(ctx, null, ctx.current().to(), List.of(a.id()))));
        }
        boolean all = ctx.request().accountIds().isEmpty();
        if (all) {
            t.tie("OPENING_BALANCES", "Opening debits equal opening credits", t.total("openingDebit"),
                    t.total("openingCredit"));
            t.tie("PERIOD_BALANCES", "Period debits equal period credits", t.total("debit"), t.total("credit"));
            t.tie("CLOSING_BALANCES", "Closing debits equal closing credits", t.total("closingDebit"),
                    t.total("closingCredit"));
        }
        return t.build();
    }

    // ---------------------------------------------------------------- direct cash flow

    static final String[][] DIRECT_LINES = {
        {"OP", "R_CUST", "Cash receipts from customers"},
        {"OP", "R_OTHER", "Other operating receipts"},
        {"OP", "P_SUPP", "Cash paid to suppliers"},
        {"OP", "P_EMP", "Cash paid to and on behalf of employees"},
        {"OP", "P_EXP", "Operating expenses paid"},
        {"OP", "P_TAX", "Taxes paid (net)"},
        {"OP", "P_OTHER", "Other operating payments and receipts"},
        {"INV", "CAPEX", "Purchase of property, plant and equipment (net)"},
        {"FIN", "BORROW", "Borrowings received / (repaid)"},
        {"FIN", "EQUITY", "Capital contributed / (distributions paid)"}};

    static String directClass(AccountInfo a) {
        if (a == null) {
            return "P_OTHER";
        }
        if (List.of("2180", "2210", "2220", "2230", "2240", "5710", "1350", "1355").contains(a.code())
                || nameHas(a, "salar", "wage", "payroll", "pension", "paye", "net pay")) {
            return "P_EMP";
        }
        return switch (String.valueOf(a.category())) {
            case "receivable", "revenue", "contra_revenue", "deferred_revenue" -> "R_CUST";
            case "other_income" -> "R_OTHER";
            case "payable", "inventory", "cost_of_goods_sold", "work_in_progress" -> "P_SUPP";
            case "operating_expense", "other_expense", "prepaid_expense" -> "P_EXP";
            case "tax" -> "P_TAX";
            case "non_current_asset", "construction_in_progress", "contra_asset", "accumulated_depreciation",
                    "accumulated_amortization" -> "CAPEX";
            case "non_current_liability", "contra_liability" -> "BORROW";
            case "equity", "other_comprehensive_income" -> "EQUITY";
            default -> "2150".equals(a.code()) ? "P_SUPP" : "P_OTHER";
        };
    }

    ReportBody directCashFlow(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Set<String> cash = new HashSet<>();
        accounts.values().stream().filter(a -> StatementLayoutService.CASH_CATEGORIES.contains(a.category()))
                .forEach(a -> cash.add(a.id()));
        Map<String, BigDecimal> byClass = new LinkedHashMap<>();
        Map<String, Set<String>> classAccounts = new LinkedHashMap<>();
        if (!cash.isEmpty()) {
            LedgerAggregator.Where w = ledger.where(ctx.organizationId(), ctx.request().withBranches(List.of()),
                    ctx.zone(), ctx.current().from(), ctx.current().to());
            List<Object> params = new ArrayList<>(w.params());
            params.add(cash.toArray(String[]::new));
            String branchSql = "";
            if (!ctx.request().branchIds().isEmpty()) {
                branchSql = " AND coalesce(cl.\"branchId\", e.\"branchId\") = ANY(?::text[])";
                params.add(ctx.request().branchIds().toArray(String[]::new));
            }
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseCredit\" -"
                    + " l.\"baseDebit\") AS v FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                    + " l.\"journalEntryId\"" + w.sql() + " AND l.\"journalEntryId\" IN (SELECT cl.\"journalEntryId\""
                    + " FROM \"JournalLine\" cl JOIN \"JournalEntry\" e ON e.id = cl.\"journalEntryId\" WHERE"
                    + " cl.\"accountId\" = ANY(?::text[])" + branchSql + ") GROUP BY l.\"accountId\"").params(params))) {
                String acct = (String) r.get("accountId");
                if (cash.contains(acct)) {
                    continue;
                }
                String k = directClass(accounts.get(acct));
                byClass.merge(k, SqlRows.dec(r, "v"), BigDecimal::add);
                classAccounts.computeIfAbsent(k, x -> new java.util.LinkedHashSet<>()).add(acct);
            }
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("label", "Description"), money("amount",
                ctx.current().label()));
        Map<String, BigDecimal> sections = new LinkedHashMap<>();
        String[][] heads = {{"OP", "Cash flows from operating activities", "Net cash from operating activities"},
            {"INV", "Cash flows from investing activities", "Net cash from investing activities"},
            {"FIN", "Cash flows from financing activities", "Net cash from financing activities"}};
        for (String[] h : heads) {
            t.add("header", 0, true, Obj.of("_id", "H_" + h[0], "label", h[1]), Map.of(), false);
            BigDecimal sum = BigDecimal.ZERO;
            for (String[] l : DIRECT_LINES) {
                if (!l[0].equals(h[0])) {
                    continue;
                }
                BigDecimal v = byClass.getOrDefault(l[1], BigDecimal.ZERO);
                sum = sum.add(v);
                List<String> accts = new ArrayList<>(classAccounts.getOrDefault(l[1], Set.of()));
                t.add("line", 1, false, Obj.of("_id", l[1], "label", l[2], "amount", v), accts.isEmpty() ? Map.of()
                        : Map.of("amount", lines(ctx, ctx.current().from(), ctx.current().to(), accts)), false);
            }
            sections.put(h[0], sum);
            t.add("total", 0, true, Obj.of("_id", h[0], "label", h[2], "amount", sum), Map.of(), false);
        }
        BigDecimal net = sections.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal opening = cashBalance(ctx, accounts, ctx.current().from().minusDays(1));
        BigDecimal closing = cashBalance(ctx, accounts, ctx.current().to());
        t.add("total", 0, true, Obj.of("_id", "NET", "label", "Net increase / (decrease) in cash", "amount", net),
                Map.of(), false);
        t.add("subtotal", 0, true, Obj.of("_id", "OPENING_CASH", "label", "Cash at beginning of period", "amount",
                opening), Map.of(), false);
        t.add("total", 0, true, Obj.of("_id", "CLOSING_CASH", "label", "Cash at end of period", "amount",
                closing), Map.of(), false);
        t.tie("CASH_RECONCILES", "Opening cash plus net cash flow equals closing cash", opening.add(net), closing);
        t.meta("netCashFlow", r2(net));
        return t.build(false);
    }

    private BigDecimal cashBalance(ReportContext ctx, Map<String, AccountInfo> accounts, LocalDate to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> b : ledger.balances(ctx, new ReportPeriod("C", "", null, to))
                .entrySet()) {
            AccountInfo a = accounts.get(b.getKey());
            if (a != null && StatementLayoutService.CASH_CATEGORIES.contains(a.category())) {
                sum = sum.add(b.getValue()[0]).subtract(b.getValue()[1]);
            }
        }
        return sum;
    }

    // ---------------------------------------------------------------- changes in equity

    /** accountId → [Σ credit − debit] excluding (false) or only (true) closing-journal entries. */
    private Map<String, BigDecimal> equityMovements(ReportContext ctx, ReportPeriod p, boolean closing) {
        LedgerAggregator.Where w = ledger.where(ctx.organizationId(), ctx.request(), ctx.zone(), p.from(), p.to());
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseCredit\" -"
                + " l.\"baseDebit\") AS v FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + " LEFT JOIN \"Journal\" j ON j.id = e.\"journalId\"" + w.sql() + " AND (coalesce(j.\"journalType\"::text,"
                + " '') = 'closing' OR coalesce(e.\"postingType\"::text, '') IN ('closing', 'year_end_close')) = "
                + closing + " GROUP BY l.\"accountId\"").params(w.params()))) {
            out.put((String) r.get("accountId"), SqlRows.dec(r, "v"));
        }
        return out;
    }

    ReportBody changesInEquity(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal[]> open = ledger.balances(ctx, before(ctx));
        Map<String, BigDecimal> mov = equityMovements(ctx, ctx.current(), false);
        Predicate<AccountInfo> pl = a -> StatementLayoutService.PL_SECTIONS.contains(a.section());
        Predicate<AccountInfo> re = a -> "3100".equals(a.code()) || nameHas(a, "retained");
        Predicate<AccountInfo> oci = a -> cat(a, "other_comprehensive_income");
        Predicate<AccountInfo> capital = a -> "equity".equals(a.section()) && !re.test(a) && !oci.test(a);
        BigDecimal[] opening = new BigDecimal[3];
        BigDecimal[] profit = new BigDecimal[3];
        BigDecimal[] ociMov = new BigDecimal[3];
        BigDecimal[] other = new BigDecimal[3];
        for (int i = 0; i < 3; i++) {
            opening[i] = BigDecimal.ZERO;
            profit[i] = BigDecimal.ZERO;
            ociMov[i] = BigDecimal.ZERO;
            other[i] = BigDecimal.ZERO;
        }
        for (AccountInfo a : accounts.values()) {
            BigDecimal[] o = get(open, a.id());
            BigDecimal ob = o[1].subtract(o[0]);
            BigDecimal m = mov.getOrDefault(a.id(), BigDecimal.ZERO);
            int col = capital.test(a) ? 0 : (re.test(a) || pl.test(a)) ? 1 : oci.test(a) ? 2 : -1;
            if (col < 0) {
                continue;
            }
            opening[col] = opening[col].add(ob);
            if (pl.test(a)) {
                profit[col] = profit[col].add(m);
            } else if (oci.test(a)) {
                ociMov[col] = ociMov[col].add(m);
            } else {
                other[col] = other[col].add(m);
            }
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("label", "Description"), money("capital",
                "Share capital and reserves"), money("retained", "Retained earnings"), money("oci", "OCI reserve"),
                money("total", "Total equity"));
        Object[][] rows = {{"OPENING", "Balance at " + ctx.current().from(), opening, "subtotal"},
            {"PROFIT", "Profit for the period", profit, "line"},
            {"OCI", "Other comprehensive income", ociMov, "line"},
            {"OTHER", "Contributions, distributions and other movements", other, "line"}};
        BigDecimal[] closing = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Object[] r : rows) {
            BigDecimal[] v = (BigDecimal[]) r[2];
            for (int i = 0; i < 3; i++) {
                closing[i] = closing[i].add(v[i]);
            }
            t.add((String) r[3], 0, "subtotal".equals(r[3]), Obj.of("_id", r[0], "label", r[1], "capital", v[0],
                    "retained", v[1], "oci", v[2], "total", v[0].add(v[1]).add(v[2])), Map.of(), false);
        }
        BigDecimal total = closing[0].add(closing[1]).add(closing[2]);
        t.add("total", 0, true, Obj.of("_id", "CLOSING", "label", "Balance at " + ctx.current().to(), "capital",
                closing[0], "retained", closing[1], "oci", closing[2], "total", total), Map.of(), false);
        Map<String, BigDecimal[]> close = ledger.balances(ctx, new ReportPeriod("C", "", null, ctx.current().to()));
        BigDecimal gl = BigDecimal.ZERO;
        for (AccountInfo a : accounts.values()) {
            if (capital.test(a) || re.test(a) || pl.test(a) || oci.test(a)) {
                BigDecimal[] c = get(close, a.id());
                gl = gl.add(c[1]).subtract(c[0]);
            }
        }
        t.tie("TIES_TO_BALANCE_SHEET", "Closing equity equals equity on the statement of financial position", total,
                gl);
        return t.build(false);
    }

    ReportBody retainedEarnings(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal[]> open = ledger.balances(ctx, before(ctx));
        Map<String, BigDecimal> mov = equityMovements(ctx, ctx.current(), false);
        Map<String, BigDecimal> closingEntries = equityMovements(ctx, ctx.current(), true);
        BigDecimal reOpen = BigDecimal.ZERO;
        BigDecimal unclosedOpen = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal otherRe = BigDecimal.ZERO;
        BigDecimal transferred = BigDecimal.ZERO;
        for (AccountInfo a : accounts.values()) {
            BigDecimal[] o = get(open, a.id());
            boolean re = "3100".equals(a.code()) || nameHas(a, "retained");
            boolean pl = StatementLayoutService.PL_SECTIONS.contains(a.section());
            if (re) {
                reOpen = reOpen.add(o[1]).subtract(o[0]);
                otherRe = otherRe.add(mov.getOrDefault(a.id(), BigDecimal.ZERO));
                transferred = transferred.add(closingEntries.getOrDefault(a.id(), BigDecimal.ZERO));
            } else if (pl) {
                unclosedOpen = unclosedOpen.add(o[1]).subtract(o[0]);
                profit = profit.add(mov.getOrDefault(a.id(), BigDecimal.ZERO));
            }
        }
        BigDecimal closing = reOpen.add(unclosedOpen).add(profit).add(otherRe);
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("label", "Description"), money("amount", "Amount"));
        t.add("line", 0, false, Obj.of("_id", "RE_OPEN", "label", "Retained earnings account at beginning",
                "amount", reOpen), Map.of(), false);
        t.add("line", 0, false, Obj.of("_id", "UNCLOSED", "label", "Profit of earlier periods not yet closed",
                "amount", unclosedOpen), Map.of(), false);
        t.add("subtotal", 0, true, Obj.of("_id", "OPENING", "label", "Opening retained earnings", "amount",
                reOpen.add(unclosedOpen)), Map.of(), false);
        t.add("line", 0, false, Obj.of("_id", "PROFIT", "label", "Profit for the period", "amount", profit),
                Map.of(), false);
        t.add("line", 0, false, Obj.of("_id", "OTHER", "label", "Dividends, transfers and other direct movements",
                "amount", otherRe), Map.of(), false);
        t.add("line", 0, false, Obj.of("_id", "CLOSE_TRANSFER", "label", "Year-end closing entries (moved from"
                + " profit or loss, net nil)", "amount", transferred), Map.of(), false);
        t.add("total", 0, true, Obj.of("_id", "CLOSING", "label", "Closing retained earnings", "amount", closing),
                Map.of(), false);
        return t.build(false);
    }

    // ---------------------------------------------------------------- general ledger and books

    ReportBody book(ReportContext ctx, Predicate<AccountInfo> family) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        List<String> ids = accounts.values().stream().filter(family).filter(a -> ctx.request().accountIds()
                .isEmpty() || ctx.request().accountIds().contains(a.id())).map(AccountInfo::id).toList();
        Tables.Builder t = new Tables.Builder(ctx.zone(), Tables.date("postingDate", "Date"), text("entryNumber",
                "Entry"), text("journal", "Journal"), text("description", "Description"), text("partner", "Partner"),
                text("branch", "Branch"), text("costCenter", "Cost center"), text("currency", "Currency"),
                money("debit", "Debit"), money("credit", "Credit"), money("balance", "Balance"), text("source",
                        "Source"), text("createdBy", "Prepared by"), text("postedBy", "Posted by"));
        if (ids.isEmpty()) {
            return t.build(false);
        }
        ReportRequest r = ctx.request().withAccounts(ids);
        Map<String, BigDecimal[]> open = ledger.balances(new ReportContext(ctx.organizationId(), ctx.definition(), r,
                ctx.zone(), ctx.current(), List.of(), false, false, false, ctx.today(), ctx.fiscalStart()),
                before(ctx));
        List<Map<String, Object>> lines = ledger.linesByAccount(ctx.organizationId(), r, ctx.zone(), ctx.current()
                .from(), ctx.current().to(), MAX_LINES + 1);
        boolean truncated = lines.size() > MAX_LINES;
        if (truncated) {
            lines = lines.subList(0, MAX_LINES);
        }
        Map<String, List<Map<String, Object>>> byAccount = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            byAccount.computeIfAbsent((String) l.get("accountId"), k -> new ArrayList<>()).add(l);
        }
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (String id : ids) {
            AccountInfo a = accounts.get(id);
            BigDecimal[] o = get(open, id);
            List<Map<String, Object>> ls = byAccount.getOrDefault(id, List.of());
            if (ls.isEmpty() && o[0].compareTo(o[1]) == 0) {
                continue;
            }
            BigDecimal running = natural(a, o[0], o[1]);
            t.add("header", 0, true, Obj.of("_id", "A:" + id, "postingDate", null, "description", a.code() + " "
                    + a.name() + " — opening balance", "balance", running), Map.of("balance", lines(ctx, null,
                    ctx.current().from().minusDays(1), List.of(id))), false);
            BigDecimal d = BigDecimal.ZERO;
            BigDecimal c = BigDecimal.ZERO;
            for (Map<String, Object> l : ls) {
                BigDecimal ld = SqlRows.dec(l, "debit");
                BigDecimal lc = SqlRows.dec(l, "credit");
                d = d.add(ld);
                c = c.add(lc);
                running = running.add(natural(a, ld, lc));
                Map<String, Object> v = new LinkedHashMap<>(l);
                v.put("description", l.get("description") != null ? l.get("description") : l.get(
                        "entryDescription"));
                v.put("balance", running);
                v.put("source", l.get("sourceType") == null ? null : l.get("sourceType") + (l.get("sourceId") == null
                        ? "" : " " + l.get("sourceId")));
                Map<String, Drill> drill = new LinkedHashMap<>();
                drill.put("_row", Drill.route("/journal-entries/" + l.get("journalEntryId")));
                t.add("line", 1, false, v, drill, false);
            }
            totalDebit = totalDebit.add(d);
            totalCredit = totalCredit.add(c);
            t.add("subtotal", 0, true, Obj.of("_id", "C:" + id, "description", a.code() + " " + a.name()
                    + " — closing balance", "debit", d, "credit", c, "balance", running), Map.of(), false);
        }
        t.add("total", 0, true, Obj.of("_id", "total", "description", "Total", "debit", totalDebit, "credit",
                totalCredit), Map.of(), false);
        if (truncated) {
            t.check("COMPLETE", "All lines shown", false, "Only the first " + MAX_LINES + " lines are shown: narrow"
                    + " the period or accounts");
        }
        t.meta("truncated", truncated);
        return t.build(false);
    }

    ReportBody journalRegister(ReportContext ctx) {
        LedgerAggregator.Where w = ledger.where(ctx.organizationId(), ctx.request(), ctx.zone(), ctx.current()
                .from(), ctx.current().to());
        List<Object> params = new ArrayList<>(w.params());
        params.add(MAX_LINES + 1);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("""
                SELECT e.id, e."postingDate", e."entryNumber", j.name AS journal, j."journalType"::text AS "journalType",
                       e.description, e."sourceType", e."sourceId", e.status::text AS status,
                       sum(l."baseDebit") AS debit, sum(l."baseCredit") AS credit, count(*) AS lines,
                       coalesce(nullif(concat_ws(' ', cu."firstName", cu."lastName"), ''), e."createdBy") AS "createdBy",
                       coalesce(nullif(concat_ws(' ', pu."firstName", pu."lastName"), ''), e."postedBy") AS "postedBy",
                       e."postedAt", e."createdAt", rv."entryNumber" AS "reversalOf", rd."entryNumber" AS "reversedBy"
                FROM "JournalLine" l
                JOIN "JournalEntry" e ON e.id = l."journalEntryId"
                LEFT JOIN "Journal" j ON j.id = e."journalId"
                LEFT JOIN "User" cu ON cu.id = e."createdBy"
                LEFT JOIN "User" pu ON pu.id = e."postedBy"
                LEFT JOIN "JournalEntry" rv ON rv.id = e."reversalOfId"
                LEFT JOIN "JournalEntry" rd ON rd.id = e."reversedEntryId"
                """ + w.sql() + " " + """
                 GROUP BY e.id, j.name, j."journalType", cu."firstName", cu."lastName", pu."firstName", pu."lastName",
                          rv."entryNumber", rd."entryNumber"
                 ORDER BY e."postingDate", e."entryNumber" LIMIT ?""").params(params));
        boolean truncated = rows.size() > MAX_LINES;
        Tables.Builder t = new Tables.Builder(ctx.zone(), Tables.date("postingDate", "Date"), text("entryNumber",
                "Entry"), text("journal", "Journal"), text("journalType", "Type"), text("description",
                "Narration"), text("source", "Source"), text("status", "Status"), money("debit", "Debit"),
                money("credit", "Credit"), Tables.number("lines", "Lines"), text("createdBy", "Prepared by"),
                text("postedBy", "Posted by"), Tables.date("postedAt", "Posted on"), text("reversalOf",
                        "Reverses"), text("reversedBy", "Reversed by"));
        int unbalanced = 0;
        for (Map<String, Object> r : truncated ? rows.subList(0, MAX_LINES) : rows) {
            Map<String, Object> v = new LinkedHashMap<>(r);
            v.put("source", r.get("sourceType") == null ? null : r.get("sourceType") + (r.get("sourceId") == null
                    ? "" : " " + r.get("sourceId")));
            if (SqlRows.dec(r, "debit").compareTo(SqlRows.dec(r, "credit")) != 0) {
                unbalanced++;
            }
            t.line(v, Map.of("_row", Drill.route("/journal-entries/" + r.get("id"))));
        }
        t.check("ALL_BALANCED", "Every journal entry balances", unbalanced == 0, unbalanced + " entries do not"
                + " balance");
        if (truncated) {
            t.check("COMPLETE", "All entries shown", false, "Only the first " + MAX_LINES + " entries are shown");
        }
        return t.build();
    }

    // ---------------------------------------------------------------- roll-forwards

    ReportBody rollForward(ReportContext ctx, Predicate<AccountInfo> family) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal[]> open = ledger.balances(ctx, before(ctx));
        Map<String, BigDecimal[]> mov = ledger.balances(ctx, ctx.current());
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("code", "Code"), text("account", "Account"),
                text("normal", "Normal"), money("opening", "Opening"), money("debit", "Debits"), money("credit",
                        "Credits"), money("closing", "Closing"));
        for (AccountInfo a : accounts.values()) {
            if (!family.test(a) || !ctx.request().accountIds().isEmpty() && !ctx.request().accountIds().contains(
                    a.id())) {
                continue;
            }
            BigDecimal[] o = get(open, a.id());
            BigDecimal[] m = get(mov, a.id());
            if (o[0].compareTo(o[1]) == 0 && m[0].signum() == 0 && m[1].signum() == 0) {
                continue;
            }
            BigDecimal opening = natural(a, o[0], o[1]);
            BigDecimal closing = opening.add(natural(a, m[0], m[1]));
            Drill d = lines(ctx, ctx.current().from(), ctx.current().to(), List.of(a.id()));
            t.line(Obj.of("_id", a.id(), "accountId", a.id(), "code", a.code(), "account", a.name(), "normal",
                    a.normalBalance(), "opening", opening, "debit", m[0], "credit", m[1], "closing", closing),
                    Map.of("debit", d, "credit", d, "closing", lines(ctx, null, ctx.current().to(), List.of(a.id()))));
        }
        return t.build();
    }

    // ---------------------------------------------------------------- cash position, branches, currencies

    ReportBody cashPosition(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, Map<String, BigDecimal[]>> byBranch = ledger.balancesBy(ctx, ctx.current(), Segment.BRANCH);
        Map<String, String> branchNames = names("Branch", ctx.organizationId());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal[]>> b : byBranch.entrySet()) {
            for (Map.Entry<String, BigDecimal[]> e : b.getValue().entrySet()) {
                AccountInfo a = accounts.get(e.getKey());
                if (a == null || !StatementLayoutService.CASH_CATEGORIES.contains(a.category())) {
                    continue;
                }
                BigDecimal bal = e.getValue()[0].subtract(e.getValue()[1]);
                if (bal.signum() == 0) {
                    continue;
                }
                rows.add(Obj.of("_id", a.id() + ":" + b.getKey(), "accountId", a.id(), "type", a.category(),
                        "account", a.code() + " " + a.name(), "branch", b.getKey().isEmpty() ? "Unassigned"
                                : branchNames.getOrDefault(b.getKey(), b.getKey()), "balance", bal));
            }
        }
        rows.sort((x, y) -> (x.get("type") + "" + x.get("account")).compareTo(y.get("type") + "" + y.get(
                "account")));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("account", "Account"), text("type", "Type"),
                text("branch", "Branch"), money("balance", "Balance"));
        t.grouped(rows, "type", "type", r -> Map.of("balance", lines(ctx, null, ctx.current().to(), List.of(
                (String) r.get("accountId")))));
        return t.build();
    }

    ReportBody branchTrialBalance(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, Map<String, BigDecimal[]>> byBranch = new TreeMap<>(ledger.balancesBy(ctx, ctx.current(),
                Segment.BRANCH));
        Map<String, String> branchNames = names("Branch", ctx.organizationId());
        List<Column> cols = new ArrayList<>(List.of(text("code", "Code"), text("account", "Account")));
        for (String b : byBranch.keySet()) {
            cols.add(money("B_" + (b.isEmpty() ? "NONE" : b), b.isEmpty() ? "Unassigned" : branchNames.getOrDefault(
                    b, b)));
        }
        cols.add(money("total", "Total"));
        Tables.Builder t = new Tables.Builder(ctx.zone(), cols);
        Map<String, BigDecimal[]> company = ledger.balances(ctx, ctx.current());
        BigDecimal check = BigDecimal.ZERO;
        for (AccountInfo a : accounts.values()) {
            Map<String, Object> v = new LinkedHashMap<>(Obj.of("_id", a.id(), "accountId", a.id(), "code", a.code(),
                    "account", a.name()));
            BigDecimal total = BigDecimal.ZERO;
            for (Map.Entry<String, Map<String, BigDecimal[]>> b : byBranch.entrySet()) {
                BigDecimal[] x = get(b.getValue(), a.id());
                BigDecimal bal = x[0].subtract(x[1]);
                v.put("B_" + (b.getKey().isEmpty() ? "NONE" : b.getKey()), bal.signum() == 0 ? null : bal);
                total = total.add(bal);
            }
            if (total.signum() == 0 && v.values().stream().noneMatch(x -> x instanceof BigDecimal)) {
                continue;
            }
            v.put("total", total);
            BigDecimal[] c = get(company, a.id());
            check = check.add(total.subtract(c[0].subtract(c[1])).abs());
            t.line(v, Map.of("total", lines(ctx, null, ctx.current().to(), List.of(a.id()))));
        }
        t.tie("BRANCHES_ADD_UP", "Branch balances add up to the company trial balance", check, BigDecimal.ZERO);
        t.tie("BALANCED", "The trial balance nets to zero", t.total("total"), BigDecimal.ZERO);
        return t.build();
    }

    ReportBody matrix(ReportContext ctx, String kind, Segment seg) {
        String code = ctx.request().layoutCode() == null || ctx.request().layoutCode().isBlank() ? ("BS".equals(kind)
                ? StatementLayoutService.DEFAULT_BS : StatementLayoutService.DEFAULT_PL) : ctx.request().layoutCode();
        Layout layout = layouts.get(code);
        if (!kind.equals(layout.kind())) {
            throw ApiException.badRequest("Layout " + code + " is a " + layout.kind() + " layout");
        }
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, Map<String, BigDecimal[]>> parts = new TreeMap<>(ledger.balancesBy(ctx, ctx.current(), seg));
        if (seg == Segment.MONTH) {
            for (String m : BudgetFigures.months(ctx.current().from(), ctx.current().to())) {
                parts.putIfAbsent(m, Map.of());
            }
        }
        Map<String, String> labels = switch (seg) {
            case BRANCH -> names("Branch", ctx.organizationId());
            case COST_CENTER -> names("CostCenter", ctx.organizationId());
            case PROJECT -> projectNames(ctx.organizationId());
            default -> Map.of();
        };
        List<Column> cols = new ArrayList<>(List.of(text("label", "Description")));
        Map<String, LedgerReports.Column> evaluated = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal[]>> p : parts.entrySet()) {
            String key = "S_" + (p.getKey().isEmpty() ? "NONE" : p.getKey());
            evaluated.put(key, statements.evaluate(layout, accounts, p.getValue()));
            cols.add(money(key, p.getKey().isEmpty() ? "Unassigned" : labels.getOrDefault(p.getKey(), p.getKey())));
        }
        LedgerReports.Column total = statements.evaluate(layout, accounts, ledger.balances(ctx, ctx.current()));
        cols.add(money("TOTAL", "Total"));
        Tables.Builder t = new Tables.Builder(ctx.zone(), cols);
        BigDecimal mismatch = BigDecimal.ZERO;
        for (Line line : layout.lines()) {
            if ("HEADER".equals(line.lineType())) {
                t.add("header", line.indent(), true, Obj.of("_id", line.code(), "label", line.label()), Map.of(),
                        false);
                continue;
            }
            Map<String, Object> v = new LinkedHashMap<>(Obj.of("_id", line.code(), "label", line.label()));
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<String, LedgerReports.Column> e : evaluated.entrySet()) {
                BigDecimal x = e.getValue().lines().get(line.code());
                v.put(e.getKey(), x);
                if (x != null && !line.percent()) {
                    sum = sum.add(x);
                }
            }
            BigDecimal tot = total.lines().get(line.code());
            v.put("TOTAL", tot);
            if (line.percent()) {
                v.put("_format", "percent");
            } else if (tot != null && !line.kpi()) {
                mismatch = mismatch.add(sum.subtract(tot).abs());
            }
            String kindRow = line.kpi() ? "kpi" : "FORMULA".equals(line.lineType()) ? "total" : "line";
            t.add(kindRow, line.indent(), line.bold(), v, Map.of(), false);
        }
        t.tie("SEGMENTS_ADD_UP", "Columns add up to the total", mismatch, BigDecimal.ZERO);
        t.meta("layoutCode", layout.code());
        return t.build(false);
    }

    private Map<String, String> projectNames(String org) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            jdbc.sql("SELECT id, name FROM \"Project\" WHERE \"organizationId\" = ?").param(org)
                    .query((rs, n) -> out.put(rs.getString("id"), rs.getString("name"))).list();
        } catch (RuntimeException e) {
            // projects not installed: show ids
        }
        return out;
    }

    ReportBody currencyBalances(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, Map<String, BigDecimal[]>> byCur = ledger.balancesBy(ctx, ctx.current(), Segment.CURRENCY);
        Map<String, String> codes = new LinkedHashMap<>();
        jdbc.sql("SELECT id, code FROM \"Currency\"").query((rs, n) -> codes.put(rs.getString("id"), rs.getString(
                "code"))).list();
        String base = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(
                ctx.organizationId()).query(String.class).optional().orElse("");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal[]>> c : byCur.entrySet()) {
            String code = c.getKey().isEmpty() ? base : codes.getOrDefault(c.getKey(), c.getKey());
            for (Map.Entry<String, BigDecimal[]> e : c.getValue().entrySet()) {
                AccountInfo a = accounts.get(e.getKey());
                if (a == null) {
                    continue;
                }
                BigDecimal baseBal = e.getValue()[0].subtract(e.getValue()[1]);
                BigDecimal txn = e.getValue()[2].subtract(e.getValue()[3]);
                if (baseBal.signum() == 0 && txn.signum() == 0) {
                    continue;
                }
                rows.add(Obj.of("_id", a.id() + ":" + code, "accountId", a.id(), "currency", code, "account",
                        a.code() + " " + a.name(), "foreign", txn, "base", baseBal, "rate", Tables.div(baseBal, txn,
                                6), "isForeign", !code.equals(base)));
            }
        }
        rows.sort((x, y) -> (x.get("currency") + "" + x.get("account")).compareTo(y.get("currency") + "" + y.get(
                "account")));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("account", "Account"), text("currency",
                "Currency"), Tables.number("foreign", "Balance (transaction currency)"), money("base",
                "Balance (" + base + ")"), Tables.number("rate", "Implied rate"));
        t.grouped(rows, "currency", "currency", r -> Map.of("base", lines(ctx, null, ctx.current().to(), List.of(
                (String) r.get("accountId")))));
        return t.build();
    }

    ReportBody rateHistory(ReportContext ctx) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT id, \"fromCode\", \"toCode\", \"asOf\", rate,"
                + " source FROM \"CurrencyRate\" WHERE \"asOf\" >= ? AND \"asOf\" < ? AND (\"organizationId\" IS NULL"
                + " OR \"organizationId\" = ?) ORDER BY \"fromCode\", \"toCode\", \"asOf\"").params(
                SqlRows.ts(ctx.start(ctx.current().from())), SqlRows.ts(ctx.endExclusive(ctx.current().to())),
                ctx.organizationId()));
        Tables.Builder t = new Tables.Builder(ctx.zone(), Tables.date("asOf", "Date"), text("fromCode", "From"),
                text("toCode", "To"), Tables.number("rate", "Rate"), text("source", "Source"));
        rows.forEach(t::line);
        return t.build(false);
    }
}
