package com.inventoryerp.reporting.application;

import static com.inventoryerp.reporting.application.Tables.date;
import static com.inventoryerp.reporting.application.Tables.money;
import static com.inventoryerp.reporting.application.Tables.number;
import static com.inventoryerp.reporting.application.Tables.pct;
import static com.inventoryerp.reporting.application.Tables.text;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.reporting.application.LedgerAggregator.AccountInfo;
import com.inventoryerp.reporting.application.OpenItems.Item;
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
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Accounts receivable and payable (FR-2): aging summary and detail on due or document date with configurable buckets,
 * open items, partner ledgers and statements from the control accounts, collections and payment history, credit
 * utilisation, unallocated receipts and payments, customer deposits and advances, credit / debit notes, upcoming
 * supplier payments, payment and cash forecasts, and subledger-to-GL reconciliations.
 */
@Component
public class ReceivablesPayablesReports implements ReportProvider {

    static final String AR = Permissions.REPORT__AR;
    static final OptionSpec BASIS = OptionSpec.select("basis", "Aging basis", "DUE_DATE", "DUE_DATE",
            "DOCUMENT_DATE");
    static final OptionSpec BUCKETS = OptionSpec.text("buckets", "Bucket limits in days", "30,60,90");
    static final List<FilterKey> AGING = List.of(FilterKey.AS_OF, FilterKey.BRANCH, FilterKey.PARTNER);
    static final List<FilterKey> PERIOD_PARTNER = List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.PARTNER);

    private final OpenItems open;
    private final LedgerAggregator ledger;
    private final JdbcClient jdbc;

    public ReceivablesPayablesReports(OpenItems open, LedgerAggregator ledger, JdbcClient jdbc) {
        this.open = open;
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @Override
    public List<ReportDefinition> definitions() {
        ReportCategory ar = ReportCategory.ACCOUNTS_RECEIVABLE;
        ReportCategory ap = ReportCategory.ACCOUNTS_PAYABLE;
        return List.of(
                ReportDefinition.of("ar.aging-summary", ar, "AR Aging Summary", "Customer balances by aging bucket"
                        + " at a date, net of unapplied receipts; ties to the receivable control accounts.",
                        "FINANCIAL", AR, AGING, BASIS, BUCKETS),
                ReportDefinition.of("ar.aging-detail", ar, "AR Aging Detail", "Every open invoice and credit note by"
                        + " customer with days overdue and bucket.", "FINANCIAL", AR, AGING, BASIS, BUCKETS),
                ReportDefinition.of("ar.open-invoices", ar, "Customer Outstanding Invoices", "Unpaid and partially"
                        + " paid invoices with due status.", "OPERATIONAL", AR, AGING),
                ReportDefinition.of("ar.overdue", ar, "Overdue Receivables", "Past-due invoices only, oldest first.",
                        "OPERATIONAL", AR, AGING, OptionSpec.number("minDays", "Overdue by at least (days)", "1")),
                ReportDefinition.of("ar.customer-ledger", ar, "Customer Ledger", "Receivable control account lines"
                        + " by customer with opening, running and closing balance.", "FINANCIAL", AR,
                        PERIOD_PARTNER),
                ReportDefinition.of("ar.customer-statement", ar, "Customer Statement", "Opening balance, invoices,"
                        + " receipts and adjustments, closing balance and aging for one customer.", "FINANCIAL", AR,
                        PERIOD_PARTNER),
                ReportDefinition.of("ar.summary", ar, "Accounts Receivable Summary", "Receivables by customer,"
                        + " branch and currency.", "FINANCIAL", AR, AGING),
                ReportDefinition.of("ar.collections", ar, "Collection Report", "Customer receipts by date, method,"
                        + " cashier, branch or customer.", "OPERATIONAL", AR, PERIOD_PARTNER,
                        OptionSpec.select("groupBy", "Group by", "method", "method", "customer", "cashier", "branch",
                                "day")),
                ReportDefinition.of("ar.payment-history", ar, "Customer Payment History", "Invoiced, paid, average"
                        + " days to pay and last payment per customer.", "MANAGEMENT", AR, PERIOD_PARTNER),
                ReportDefinition.of("ar.credit-utilization", ar, "Credit Limit Utilization", "Credit limit,"
                        + " outstanding balance, available credit and holds.", "OPERATIONAL", AR, AGING),
                ReportDefinition.of("ar.unallocated-receipts", ar, "Unallocated Customer Receipts", "Receipts not"
                        + " yet matched to invoices.", "FINANCIAL", AR, AGING),
                ReportDefinition.of("ar.customer-advances", ar, "Customer Deposits and Advances", "Deposits and"
                        + " advance payments received, applied, refunded and open.", "FINANCIAL", AR, AGING),
                ReportDefinition.of("ar.credit-notes", ar, "Customer Credit Notes", "Credit notes issued in the"
                        + " period with applied and open amounts.", "OPERATIONAL", AR, PERIOD_PARTNER),
                ReportDefinition.of("ar.reconciliation", ar, "Receivables Reconciliation", "AR subledger against the"
                        + " receivable control accounts, by customer.", "FINANCIAL", AR, List.of(FilterKey.AS_OF)),
                ReportDefinition.of("ap.aging-summary", ap, "AP Aging Summary", "Supplier balances by aging bucket,"
                        + " net of unapplied payments; ties to the payable control accounts.", "FINANCIAL", AR,
                        AGING, BASIS, BUCKETS),
                ReportDefinition.of("ap.aging-detail", ap, "AP Aging Detail", "Every open bill by supplier with"
                        + " days overdue and bucket.", "FINANCIAL", AR, AGING, BASIS, BUCKETS),
                ReportDefinition.of("ap.open-bills", ap, "Supplier Outstanding Invoices", "Unpaid and partially"
                        + " paid supplier bills.", "OPERATIONAL", AR, AGING),
                ReportDefinition.of("ap.supplier-ledger", ap, "Supplier Ledger (GL)", "Payable control account"
                        + " lines by supplier with opening, running and closing balance.", "FINANCIAL", AR,
                        PERIOD_PARTNER),
                ReportDefinition.of("ap.supplier-statement", ap, "Supplier Statement", "Opening, bills, payments,"
                        + " credits and closing balance for one supplier.", "FINANCIAL", AR, PERIOD_PARTNER),
                ReportDefinition.of("ap.payment-history", ap, "Supplier Payment History", "Supplier payments in the"
                        + " period by supplier and method.", "OPERATIONAL", AR, PERIOD_PARTNER),
                ReportDefinition.of("ap.upcoming-payments", ap, "Upcoming Payment Obligations", "Bills due within"
                        + " the window (and overdue), with scheduled dates and holds.", "OPERATIONAL", AR, AGING,
                        OptionSpec.number("days", "Due within (days)", "30")),
                ReportDefinition.of("ap.payment-forecast", ap, "Supplier Payment Forecast", "Expected supplier cash"
                        + " requirements by week.", "MANAGEMENT", AR, AGING, OptionSpec.number("weeks", "Weeks",
                                "8")),
                ReportDefinition.of("ap.unallocated-payments", ap, "Supplier Advances and Unallocated Payments",
                        "Payments to suppliers not yet allocated to bills.", "FINANCIAL", AR, AGING),
                ReportDefinition.of("ap.debit-notes", ap, "Supplier Credit / Debit Notes", "Supplier returns and"
                        + " credit adjustments in the period.", "OPERATIONAL", AR, PERIOD_PARTNER),
                ReportDefinition.of("ap.liability-summary", ap, "Supplier Liability Summary", "Outstanding"
                        + " obligations by supplier, currency and branch.", "FINANCIAL", AR, AGING),
                ReportDefinition.of("ap.reconciliation", ap, "Payables Reconciliation", "AP subledger against the"
                        + " payable control accounts, by supplier.", "FINANCIAL", AR, List.of(FilterKey.AS_OF)),
                ReportDefinition.of("cash.forecast", ReportCategory.CASH_AND_BANK, "Cash Flow Forecast", "Opening"
                        + " cash plus expected customer receipts less supplier payments, week by week.",
                        "MANAGEMENT", Permissions.REPORT__ACCOUNTING, List.of(FilterKey.AS_OF, FilterKey.BRANCH),
                        OptionSpec.number("weeks", "Weeks", "8")));
    }

    @Override
    public ReportBody run(ReportContext ctx) {
        return switch (ctx.definition().id()) {
            case "ar.aging-summary" -> agingSummary(ctx, "AR");
            case "ap.aging-summary" -> agingSummary(ctx, "AP");
            case "ar.aging-detail" -> agingDetail(ctx, "AR", 0, false);
            case "ap.aging-detail" -> agingDetail(ctx, "AP", 0, false);
            case "ar.open-invoices" -> agingDetail(ctx, "AR", 0, true);
            case "ap.open-bills" -> agingDetail(ctx, "AP", 0, true);
            case "ar.overdue" -> agingDetail(ctx, "AR", Math.max(1, ctx.request().intOption("minDays")), true);
            case "ar.customer-ledger" -> partnerLedger(ctx, "receivable", false);
            case "ap.supplier-ledger" -> partnerLedger(ctx, "payable", false);
            case "ar.customer-statement" -> partnerLedger(ctx, "receivable", true);
            case "ap.supplier-statement" -> partnerLedger(ctx, "payable", true);
            case "ar.summary" -> summary(ctx, "AR");
            case "ap.liability-summary" -> summary(ctx, "AP");
            case "ar.collections" -> payments(ctx, "inbound", ctx.request().option("groupBy"));
            case "ap.payment-history" -> payments(ctx, "outbound", "supplier");
            case "ar.payment-history" -> paymentHistory(ctx);
            case "ar.credit-utilization" -> creditUtilization(ctx);
            case "ar.unallocated-receipts" -> unallocated(ctx, "inbound");
            case "ap.unallocated-payments" -> unallocated(ctx, "outbound");
            case "ar.customer-advances" -> deposits(ctx);
            case "ar.credit-notes" -> notes(ctx, "credit_note");
            case "ap.debit-notes" -> notes(ctx, "debit_note");
            case "ar.reconciliation" -> reconciliation(ctx, "AR");
            case "ap.reconciliation" -> reconciliation(ctx, "AP");
            case "ap.upcoming-payments" -> upcoming(ctx);
            case "ap.payment-forecast" -> forecast(ctx, false);
            case "cash.forecast" -> forecast(ctx, true);
            default -> throw ApiException.notFound("Report " + ctx.definition().id() + " not found");
        };
    }

    // ---------------------------------------------------------------- aging

    static int[] limits(String spec) {
        try {
            int[] out = Arrays.stream((spec == null || spec.isBlank() ? "30,60,90" : spec).split(",")).map(String::trim)
                    .mapToInt(Integer::parseInt).sorted().distinct().toArray();
            if (out.length == 0 || out.length > 8 || out[0] < 1) {
                throw new NumberFormatException();
            }
            return out;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("options.buckets must be 1 to 8 positive day limits, e.g. 30,60,90");
        }
    }

    static List<String[]> buckets(int[] limits) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"current", "Current"});
        int prev = 0;
        for (int l : limits) {
            out.add(new String[] {"b" + l, (prev + 1) + "–" + l + " days"});
            prev = l;
        }
        out.add(new String[] {"over", "Over " + prev + " days"});
        return out;
    }

    static String bucket(long days, int[] limits) {
        if (days <= 0) {
            return "current";
        }
        for (int l : limits) {
            if (days <= l) {
                return "b" + l;
            }
        }
        return "over";
    }

    static long days(Item i, LocalDate asOf, String basis) {
        LocalDate ref = "DOCUMENT_DATE".equals(basis) || i.dueDate() == null ? i.issueDate() : i.dueDate();
        return ref == null ? 0 : ChronoUnit.DAYS.between(ref, asOf);
    }

    private String control(String side) {
        return "AR".equals(side) ? "receivable" : "payable";
    }

    /** Debit-normal (AR) or credit-normal (AP) balance of the control accounts at the date. */
    BigDecimal controlBalance(ReportContext ctx, String side, LocalDate asOf) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> b : ledger.balances(ctx, new ReportPeriod("C", "", null, asOf))
                .entrySet()) {
            AccountInfo a = accounts.get(b.getKey());
            if (a != null && control(side).equals(a.category())) {
                sum = sum.add("AR".equals(side) ? b.getValue()[0].subtract(b.getValue()[1]) : b.getValue()[1]
                        .subtract(b.getValue()[0]));
            }
        }
        return sum;
    }

    ReportBody agingSummary(ReportContext ctx, String side) {
        LocalDate asOf = ctx.asOf();
        int[] lim = limits(ctx.request().option("buckets"));
        String basis = ctx.request().option("basis");
        List<String[]> bks = buckets(lim);
        Map<String, Map<String, Object>> byPartner = new LinkedHashMap<>();
        for (Item i : open.at(ctx, side, asOf)) {
            Map<String, Object> row = byPartner.computeIfAbsent(i.partnerId() == null ? "" : i.partnerId(), k -> {
                Map<String, Object> m = new LinkedHashMap<>(Obj.of("_id", k, "partnerId", k, "partner",
                        i.partnerName()));
                bks.forEach(b -> m.put(b[0], BigDecimal.ZERO));
                m.put("unapplied", BigDecimal.ZERO);
                m.put("total", BigDecimal.ZERO);
                return m;
            });
            String b = bucket(days(i, asOf, basis), lim);
            row.put(b, ((BigDecimal) row.get(b)).add(i.residual()));
            row.put("total", ((BigDecimal) row.get("total")).add(i.residual()));
        }
        for (Map.Entry<String, BigDecimal> u : open.unapplied(ctx, "AR".equals(side) ? "inbound" : "outbound",
                asOf).entrySet()) {
            Map<String, Object> row = byPartner.computeIfAbsent(u.getKey(), k -> {
                Map<String, Object> m = new LinkedHashMap<>(Obj.of("_id", k, "partnerId", k, "partner",
                        partnerName(k)));
                bks.forEach(b -> m.put(b[0], BigDecimal.ZERO));
                m.put("unapplied", BigDecimal.ZERO);
                m.put("total", BigDecimal.ZERO);
                return m;
            });
            row.put("unapplied", u.getValue().negate());
            row.put("total", ((BigDecimal) row.get("total")).subtract(u.getValue()));
        }
        List<Column> cols = new ArrayList<>(List.of(text("partner", "AR".equals(side) ? "Customer" : "Supplier")));
        bks.forEach(b -> cols.add(money(b[0], b[1])));
        cols.add(money("unapplied", "AR".equals(side) ? "Unapplied receipts" : "Unapplied payments"));
        cols.add(money("total", "Total"));
        Tables.Builder t = new Tables.Builder(ctx.zone(), cols);
        byPartner.values().stream().sorted((a, b) -> String.valueOf(a.get("partner")).compareToIgnoreCase(
                String.valueOf(b.get("partner")))).forEach(r -> t.line(r, Map.of("total", Drill.report(
                "AR".equals(side) ? "ar.aging-detail" : "ap.aging-detail", Obj.of("asOf", asOf.toString(),
                        "partnerIds", List.of(r.get("partnerId")))))));
        if (ctx.request().partnerIds().isEmpty() && ctx.request().branchIds().isEmpty()) {
            t.tie("TIES_TO_GL", "Aging total equals the " + ("AR".equals(side) ? "receivable" : "payable")
                    + " control accounts", t.total("total"), controlBalance(ctx, side, asOf));
        }
        t.meta("basis", basis);
        return t.build();
    }

    ReportBody agingDetail(ReportContext ctx, String side, int minDays, boolean flat) {
        LocalDate asOf = ctx.asOf();
        int[] lim = limits(ctx.request().option("buckets"));
        String basis = ctx.request().option("basis");
        Map<String, String> labels = new LinkedHashMap<>();
        buckets(lim).forEach(b -> labels.put(b[0], b[1]));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Item i : open.at(ctx, side, asOf)) {
            long d = days(i, asOf, basis);
            if (minDays > 0 && d < minDays) {
                continue;
            }
            rows.add(Obj.of("_id", i.id(), "partnerId", i.partnerId(), "partner", i.partnerName(), "number",
                    i.number(), "kind", i.kind(), "issueDate", i.issueDate(), "dueDate", i.dueDate(), "currency",
                    i.currency(), "total", i.total(), "residual", i.residual(), "days", Math.max(0, d), "bucket",
                    labels.get(bucket(d, lim)), "status", d > 0 ? "overdue" : "not due", "_route", i.route()));
        }
        if (minDays > 0) {
            rows.sort((a, b) -> Long.compare((Long) b.get("days"), (Long) a.get("days")));
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("partner", "AR".equals(side) ? "Customer"
                : "Supplier"), text("number", "Document"), text("kind", "Type"), date("issueDate", "Date"),
                date("dueDate", "Due"), text("currency", "Currency"), money("total", "Amount"), money("residual",
                        "Open"), number("days", "Days overdue"), text("bucket", "Bucket"), text("status", "Status"));
        if (flat) {
            rows.forEach(r -> t.line(r, r.get("_route") == null ? Map.of() : Map.of("_row", Drill.route((String) r
                    .get("_route")))));
        } else {
            t.grouped(rows, "partnerId", "partner", r -> r.get("_route") == null ? Map.of() : Map.of("_row",
                    Drill.route((String) r.get("_route"))));
        }
        return t.build();
    }

    ReportBody summary(ReportContext ctx, String side) {
        Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
        Map<String, String> branches = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM \"Branch\" WHERE \"organizationId\" = ?").param(ctx.organizationId())
                .query((rs, n) -> branches.put(rs.getString("id"), rs.getString("name"))).list();
        for (Item i : open.at(ctx, side, ctx.asOf())) {
            String key = i.partnerId() + "|" + i.branchId() + "|" + i.currency();
            Map<String, Object> r = agg.computeIfAbsent(key, k -> new LinkedHashMap<>(Obj.of("_id", key,
                    "partnerId", i.partnerId(), "partner", i.partnerName(), "branch", i.branchId() == null ? "—"
                            : branches.getOrDefault(i.branchId(), i.branchId()), "currency", i.currency() == null
                            ? "base" : i.currency(), "documents", 0L, "residual", BigDecimal.ZERO)));
            r.put("documents", (Long) r.get("documents") + 1);
            r.put("residual", ((BigDecimal) r.get("residual")).add(i.residual()));
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("partner", "AR".equals(side) ? "Customer"
                : "Supplier"), text("branch", "Branch"), text("currency", "Currency"), number("documents",
                "Open documents"), money("residual", "Outstanding"));
        agg.values().forEach(t::line);
        return t.build();
    }

    // ---------------------------------------------------------------- ledgers and statements

    ReportBody partnerLedger(ReportContext ctx, String category, boolean statement) {
        if (statement && ctx.request().partnerIds().size() != 1) {
            throw ApiException.badRequest("Select exactly one partner for a statement");
        }
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        List<String> control = accounts.values().stream().filter(a -> category.equals(a.category())).map(
                AccountInfo::id).toList();
        boolean ar = "receivable".equals(category);
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("postingDate", "Date"), text("entryNumber", "Entry"),
                text("description", "Description"), text("source", "Document"), money("debit", "Debit"),
                money("credit", "Credit"), money("balance", "Balance"));
        if (control.isEmpty()) {
            return t.build(false);
        }
        var r = ctx.request().withAccounts(control);
        LedgerAggregator.Where wOpen = ledger.where(ctx.organizationId(), r, ctx.zone(), null, ctx.current().from()
                .minusDays(1));
        Map<String, BigDecimal> openings = new LinkedHashMap<>();
        for (Map<String, Object> o : SqlRows.list(jdbc.sql("SELECT l.\"partnerId\", sum(l.\"baseDebit\" -"
                + " l.\"baseCredit\") AS v FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + wOpen.sql() + " GROUP BY l.\"partnerId\"").params(wOpen.params()))) {
            openings.put(o.get("partnerId") == null ? "" : (String) o.get("partnerId"), SqlRows.dec(o, "v"));
        }
        LedgerAggregator.Where w = ledger.where(ctx.organizationId(), r, ctx.zone(), ctx.current().from(),
                ctx.current().to());
        List<Object> params = new ArrayList<>(w.params());
        params.add(GlReports.MAX_LINES);
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.id, l.\"partnerId\", p.name AS partner,"
                + " e.id AS \"journalEntryId\", e.\"postingDate\", e.\"entryNumber\", coalesce(l.description,"
                + " e.description) AS description, e.\"sourceType\", e.\"sourceId\", l.\"baseDebit\" AS debit,"
                + " l.\"baseCredit\" AS credit FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                + " l.\"journalEntryId\" LEFT JOIN \"Partner\" p ON p.id = l.\"partnerId\"" + w.sql()
                + " ORDER BY p.name NULLS LAST, l.\"partnerId\", e.\"postingDate\", e.\"entryNumber\" LIMIT ?")
                .params(params));
        Map<String, List<Map<String, Object>>> byPartner = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            byPartner.computeIfAbsent(l.get("partnerId") == null ? "" : (String) l.get("partnerId"),
                    k -> new ArrayList<>()).add(l);
        }
        for (String pid : openings.keySet()) {
            if (openings.get(pid).signum() != 0) {
                byPartner.putIfAbsent(pid, new ArrayList<>());
            }
        }
        for (Map.Entry<String, List<Map<String, Object>>> p : byPartner.entrySet()) {
            BigDecimal opening = openings.getOrDefault(p.getKey(), BigDecimal.ZERO);
            BigDecimal running = ar ? opening : opening.negate();
            String name = p.getValue().isEmpty() ? partnerName(p.getKey()) : (String) p.getValue().getFirst().get(
                    "partner");
            t.add("header", 0, true, Obj.of("_id", "P:" + p.getKey(), "description", (name == null ? "(no partner)"
                    : name) + " — opening balance", "balance", running), Map.of(), false);
            BigDecimal d = BigDecimal.ZERO;
            BigDecimal c = BigDecimal.ZERO;
            for (Map<String, Object> l : p.getValue()) {
                BigDecimal ld = SqlRows.dec(l, "debit");
                BigDecimal lc = SqlRows.dec(l, "credit");
                d = d.add(ld);
                c = c.add(lc);
                running = running.add(ar ? ld.subtract(lc) : lc.subtract(ld));
                Map<String, Object> v = new LinkedHashMap<>(l);
                v.put("balance", running);
                v.put("source", l.get("sourceType") == null ? null : l.get("sourceType") + " " + l.get("sourceId"));
                t.add("line", 1, false, v, Map.of("_row", Drill.route("/journal-entries/" + l.get(
                        "journalEntryId"))), false);
            }
            t.add("subtotal", 0, true, Obj.of("_id", "C:" + p.getKey(), "description", (name == null ? "" : name)
                    + " — closing balance", "debit", d, "credit", c, "balance", running), Map.of(), false);
        }
        if (statement) {
            String side = ar ? "AR" : "AP";
            int[] lim = {30, 60, 90};
            Map<String, BigDecimal> ag = new LinkedHashMap<>();
            buckets(lim).forEach(b -> ag.put(b[1], BigDecimal.ZERO));
            Map<String, String> labels = new LinkedHashMap<>();
            buckets(lim).forEach(b -> labels.put(b[0], b[1]));
            for (Item i : open.at(ctx, side, ctx.current().to())) {
                ag.merge(labels.get(bucket(days(i, ctx.current().to(), "DUE_DATE"), lim)), i.residual(),
                        BigDecimal::add);
            }
            ag.forEach((k, v) -> t.add("kpi", 0, false, Obj.of("_id", "AGE:" + k, "description", "Aging — " + k,
                    "balance", v), Map.of(), false));
        }
        return t.build(false);
    }

    private String partnerName(String id) {
        if (id == null || id.isEmpty()) {
            return "(no partner)";
        }
        return jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ?").param(id).query(String.class).optional()
                .orElse(id);
    }

    // ---------------------------------------------------------------- payments

    private List<Map<String, Object>> paymentRows(ReportContext ctx, String direction) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), direction, SqlRows.ts(ctx.start(ctx.current()
                .from())), SqlRows.ts(ctx.endExclusive(ctx.current().to()))));
        StringBuilder f = new StringBuilder();
        if (!ctx.request().partnerIds().isEmpty()) {
            f.append(" AND p.\"partnerId\" = ANY(?::text[])");
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        if (!ctx.request().branchIds().isEmpty()) {
            f.append(" AND p.\"branchId\" = ANY(?::text[])");
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        return SqlRows.list(jdbc.sql("SELECT p.id, p.\"paymentNumber\", p.\"paymentDate\", p.\"paymentMethod\","
                + " p.amount, p.\"allocatedAmount\", p.\"unallocatedAmount\", p.\"withholdingAmount\", p.reference,"
                + " pt.name AS partner, p.\"partnerId\", b.name AS branch, a.name AS account, coalesce(nullif("
                + "concat_ws(' ', u.\"firstName\", u.\"lastName\"), ''), p.\"createdBy\") AS cashier FROM \"Payment\" p"
                + " LEFT JOIN \"Partner\" pt ON pt.id = p.\"partnerId\" LEFT JOIN \"Branch\" b ON b.id = p.\"branchId\""
                + " LEFT JOIN \"Account\" a ON a.id = p.\"accountId\" LEFT JOIN \"User\" u ON u.id = p.\"createdBy\""
                + " WHERE p.\"organizationId\" = ? AND p.direction::text = ? AND p.\"paymentDate\" >= ? AND"
                + " p.\"paymentDate\" < ? AND p.status::text <> 'cancelled' AND p.\"voidedAt\" IS NULL" + f
                + " ORDER BY p.\"paymentDate\", p.\"paymentNumber\"").params(p));
    }

    ReportBody payments(ReportContext ctx, String direction, String groupBy) {
        List<Map<String, Object>> rows = paymentRows(ctx, direction);
        String key = switch (groupBy == null ? "method" : groupBy) {
            case "customer", "supplier" -> "partner";
            case "cashier" -> "cashier";
            case "branch" -> "branch";
            case "day" -> "day";
            default -> "paymentMethod";
        };
        for (Map<String, Object> r : rows) {
            r.put("day", Tables.day(r.get("paymentDate"), ctx.zone()));
            r.putIfAbsent(key, null);
            if (r.get(key) == null) {
                r.put(key, "—");
            }
        }
        rows.sort((a, b) -> String.valueOf(a.get(key)).compareTo(String.valueOf(b.get(key))));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("paymentDate", "Date"), text("paymentNumber",
                "Number"), text("partner", "inbound".equals(direction) ? "Customer" : "Supplier"),
                text("paymentMethod", "Method"), text("account", "Account"), text("branch", "Branch"),
                text("cashier", "Recorded by"), text("reference", "Reference"), money("amount", "Amount"),
                money("withholdingAmount", "Withholding"), money("unallocatedAmount", "Unallocated"));
        t.grouped(rows, key, key, r -> Map.of("_row", Drill.route("/payments/" + r.get("id"))));
        return t.build();
    }

    ReportBody paymentHistory(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(SqlRows.ts(ctx.start(ctx.current().from())), SqlRows.ts(
                ctx.endExclusive(ctx.current().to())), ctx.organizationId()));
        String f = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            f = " AND d.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("""
                SELECT d."partnerId", pt.name AS partner, count(DISTINCT d.id) AS invoices, sum(pa.amount) AS paid,
                       sum(pa.amount * greatest(0, extract(epoch FROM (p."paymentDate" - d."issueDate")) / 86400))
                         / nullif(sum(pa.amount), 0) AS "avgDays",
                       max(p."paymentDate") AS "lastPayment"
                FROM "PaymentAllocation" pa
                JOIN "Payment" p ON p.id = pa."paymentId"
                JOIN "Document" d ON d.id = pa."documentId"
                LEFT JOIN "Partner" pt ON pt.id = d."partnerId"
                WHERE p."paymentDate" >= ? AND p."paymentDate" < ? AND d."organizationId" = ?
                  AND d."documentType"::text = 'sales_invoice' AND p.status::text <> 'cancelled' AND p."voidedAt" IS NULL
                """ + f + " GROUP BY d.\"partnerId\", pt.name ORDER BY pt.name").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("partner", "Customer"), number("invoices",
                "Invoices paid"), money("paid", "Paid in period"), number("avgDays", "Average days to pay"),
                date("lastPayment", "Last payment"));
        rows.forEach(r -> t.line(r, Map.of("paid", Drill.report("ar.collections", Obj.of("from", ctx.current().from()
                .toString(), "to", ctx.current().to().toString(), "partnerIds", List.of(String.valueOf(r.get(
                        "partnerId"))), "options", Map.of("groupBy", "day"))))));
        return t.build();
    }

    ReportBody creditUtilization(ReportContext ctx) {
        Map<String, BigDecimal> exposure = new LinkedHashMap<>();
        for (Item i : open.at(ctx, "AR", ctx.asOf())) {
            exposure.merge(i.partnerId() == null ? "" : i.partnerId(), i.residual(), BigDecimal::add);
        }
        open.unapplied(ctx, "inbound", ctx.asOf()).forEach((k, v) -> exposure.merge(k, v.negate(), BigDecimal::add));
        List<Object> p = new ArrayList<>(List.of(ctx.asOf(), ctx.asOf(), ctx.organizationId()));
        String f = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            f = " AND pt.id = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT pt.id, pt.name AS partner, coalesce((SELECT"
                + " cl.amount FROM \"CreditLimit\" cl WHERE cl.\"partnerId\" = pt.id AND cl.\"branchId\" IS NULL AND"
                + " (cl.\"validFrom\" IS NULL OR cl.\"validFrom\" <= ?) AND (cl.\"validTo\" IS NULL OR cl.\"validTo\" >= ?)"
                + " ORDER BY cl.\"createdAt\" DESC LIMIT 1), pt.\"creditLimit\") AS \"creditLimit\", pt.\"creditHold\","
                + " pt.\"creditRiskCategory\" AS risk FROM \"Partner\" pt WHERE pt.\"organizationId\" = ? AND"
                + " pt.\"deletedAt\" IS NULL AND pt.\"isCustomer\"" + f + " ORDER BY pt.name").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("partner", "Customer"), text("risk", "Risk"),
                money("creditLimit", "Credit limit"), money("outstanding", "Outstanding"), money("available",
                        "Available"), pct("utilization", "Utilisation %"), text("hold", "On hold"));
        int over = 0;
        for (Map<String, Object> r : rows) {
            BigDecimal limit = r.get("creditLimit") == null ? null : SqlRows.dec(r, "creditLimit");
            BigDecimal out = exposure.getOrDefault((String) r.get("id"), BigDecimal.ZERO);
            if ((limit == null || limit.signum() == 0) && out.signum() == 0) {
                continue;
            }
            BigDecimal avail = limit == null ? null : limit.subtract(out);
            if (avail != null && avail.signum() < 0) {
                over++;
            }
            t.line(Obj.of("_id", r.get("id"), "partnerId", r.get("id"), "partner", r.get("partner"), "risk",
                    r.get("risk"), "creditLimit", limit, "outstanding", out, "available", avail, "utilization",
                    Tables.pctOf(out, limit), "hold", Boolean.TRUE.equals(r.get("creditHold")) ? "yes" : "no"),
                    Map.of("outstanding", Drill.report("ar.aging-detail", Obj.of("asOf", ctx.asOf().toString(),
                            "partnerIds", List.of(r.get("id"))))));
        }
        t.check("NONE_OVER_LIMIT", "No customer is over the credit limit", over == 0, over + " customer(s) over"
                + " limit");
        return t.build();
    }

    ReportBody unallocated(ReportContext ctx, String direction) {
        java.sql.Timestamp end = SqlRows.ts(ctx.endExclusive(ctx.asOf()));
        List<Object> p = new ArrayList<>(List.of(end, ctx.organizationId(), direction, end, end));
        String f = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            f += " AND p.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        if (!ctx.request().branchIds().isEmpty()) {
            f += " AND p.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM (SELECT p.id, p.\"paymentNumber\","
                + " p.\"paymentDate\", p.\"paymentMethod\", pt.name AS partner, p.\"partnerId\", p.amount, p.amount -"
                + " coalesce((SELECT sum(pa.amount) FROM \"PaymentAllocation\" pa WHERE pa.\"paymentId\" = p.id AND"
                + " pa.\"createdAt\" < ?), 0) AS open, p.reference FROM \"Payment\" p LEFT JOIN \"Partner\" pt ON pt.id ="
                + " p.\"partnerId\" WHERE p.\"organizationId\" = ? AND p.direction::text = ? AND p.\"paymentDate\" < ?"
                + " AND p.status::text <> 'cancelled' AND (p.\"voidedAt\" IS NULL OR p.\"voidedAt\" >= ?) AND"
                + " p.\"refundOfId\" IS NULL" + f + ") x WHERE open > 0.005 ORDER BY partner, \"paymentDate\"")
                .params(p));
        for (Map<String, Object> r : rows) {
            r.put("age", ChronoUnit.DAYS.between(LocalDate.parse(Tables.day(r.get("paymentDate"), ctx.zone())),
                    ctx.asOf()));
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("partner", "inbound".equals(direction) ? "Customer"
                : "Supplier"), text("paymentNumber", "Payment"), date("paymentDate", "Date"), text("paymentMethod",
                "Method"), text("reference", "Reference"), money("amount", "Amount"), money("open", "Unallocated"),
                number("age", "Age (days)"));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route("/payments/" + r.get("id")))));
        return t.build();
    }

    ReportBody deposits(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), ctx.asOf()));
        String f = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            f = " AND d.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT d.id, d.number, d.\"receivedDate\","
                + " pt.name AS partner, d.\"partnerId\", d.source, d.\"paymentMethod\", d.amount, d.\"appliedAmount\","
                + " d.\"refundedAmount\", d.amount - coalesce(d.\"appliedAmount\", 0) - coalesce(d.\"refundedAmount\", 0)"
                + " AS open, d.status FROM \"CustomerDeposit\" d LEFT JOIN \"Partner\" pt ON pt.id = d.\"partnerId\""
                + " WHERE d.\"organizationId\" = ? AND d.\"receivedDate\" <= ?" + f + " ORDER BY pt.name,"
                + " d.\"receivedDate\"").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("partner", "Customer"), text("number", "Deposit"),
                date("receivedDate", "Received"), text("source", "Source"), text("paymentMethod", "Method"),
                money("amount", "Amount"), money("appliedAmount", "Applied"), money("refundedAmount", "Refunded"),
                money("open", "Open"), text("status", "Status"));
        rows.forEach(t::line);
        List<Map<String, Object>> adv = SqlRows.list(jdbc.sql("SELECT a.id, a.\"projectId\", a.amount, a.recovered,"
                + " a.amount - coalesce(a.recovered, 0) AS open, a.status, a.\"createdAt\" FROM \"CustomerAdvance\" a"
                + " WHERE a.\"organizationId\" = ? AND a.\"createdAt\" < ?").params(ctx.organizationId(), SqlRows.ts(
                ctx.endExclusive(ctx.asOf()))));
        for (Map<String, Object> a : adv) {
            t.line(Obj.of("_id", a.get("id"), "partner", "Project advance " + a.get("projectId"), "number", "ADV",
                    "receivedDate", a.get("createdAt"), "source", "contract", "amount", a.get("amount"),
                    "appliedAmount", a.get("recovered"), "open", a.get("open"), "status", a.get("status")));
        }
        return t.build();
    }

    ReportBody notes(ReportContext ctx, String type) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), type, SqlRows.ts(ctx.start(ctx.current()
                .from())), SqlRows.ts(ctx.endExclusive(ctx.current().to()))));
        String f = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            f += " AND d.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        if (!ctx.request().branchIds().isEmpty()) {
            f += " AND d.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT d.id, d.\"documentNumber\", d.\"issueDate\","
                + " pt.name AS partner, d.reference, d.\"sourceDocument\", d.subtotal, d.\"taxAmount\","
                + " d.\"totalAmount\", d.\"amountPaid\", d.\"amountResidual\", d.status::text AS status FROM \"Document\" d"
                + " LEFT JOIN \"Partner\" pt ON pt.id = d.\"partnerId\" WHERE d.\"organizationId\" = ? AND"
                + " d.\"documentType\"::text = ? AND d.\"issueDate\" >= ? AND d.\"issueDate\" < ? AND d.status::text NOT"
                + " IN ('draft', 'cancelled')" + f + " ORDER BY d.\"issueDate\"").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("issueDate", "Date"), text("documentNumber",
                "Number"), text("partner", "credit_note".equals(type) ? "Customer" : "Supplier"), text(
                "sourceDocument", "Against"), text("reference", "Reference"), money("subtotal", "Net"), money(
                "taxAmount", "Tax"), money("totalAmount", "Total"), money("amountResidual", "Unapplied"),
                text("status", "Status"));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route(("credit_note".equals(type) ? "/credit-notes/"
                : "/vendor-bills/") + r.get("id")))));
        return t.build();
    }

    // ---------------------------------------------------------------- reconciliation and forecasts

    ReportBody reconciliation(ReportContext ctx, String side) {
        LocalDate asOf = ctx.asOf();
        boolean ar = "AR".equals(side);
        Map<String, BigDecimal> sub = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Item i : open.at(ctx, side, asOf)) {
            String k = i.partnerId() == null ? "" : i.partnerId();
            sub.merge(k, i.residual(), BigDecimal::add);
            names.put(k, i.partnerName());
        }
        open.unapplied(ctx, ar ? "inbound" : "outbound", asOf).forEach((k, v) -> sub.merge(k, v.negate(),
                BigDecimal::add));
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Set<String> control = new java.util.HashSet<>();
        accounts.values().stream().filter(a -> control(side).equals(a.category())).forEach(a -> control.add(a.id()));
        Map<String, BigDecimal> gl = new LinkedHashMap<>();
        if (!control.isEmpty()) {
            for (Map.Entry<String, Map<String, BigDecimal[]>> p : ledger.balancesBy(ctx, new ReportPeriod("C", "",
                    null, asOf), LedgerAggregator.Segment.PARTNER).entrySet()) {
                for (Map.Entry<String, BigDecimal[]> e : p.getValue().entrySet()) {
                    if (control.contains(e.getKey())) {
                        BigDecimal v = ar ? e.getValue()[0].subtract(e.getValue()[1]) : e.getValue()[1].subtract(
                                e.getValue()[0]);
                        gl.merge(p.getKey(), v, BigDecimal::add);
                    }
                }
            }
        }
        Set<String> keys = new java.util.LinkedHashSet<>(sub.keySet());
        keys.addAll(gl.keySet());
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("partner", ar ? "Customer" : "Supplier"), money(
                "subledger", "Subledger (open items − unapplied)"), money("gl", "General ledger"), money("difference",
                "Difference"));
        int diffs = 0;
        for (String k : keys) {
            BigDecimal s = sub.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal g = gl.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal d = s.subtract(g);
            if (s.signum() == 0 && g.signum() == 0) {
                continue;
            }
            if (d.abs().compareTo(new BigDecimal("0.01")) > 0) {
                diffs++;
            }
            t.line(Obj.of("_id", k, "partnerId", k, "partner", names.getOrDefault(k, partnerName(k)), "subledger", s,
                    "gl", g, "difference", d), Map.of("gl", GlReports.lines(ctx, null, asOf, new ArrayList<>(
                    control))));
        }
        t.tie("TOTAL_RECONCILES", "Subledger total equals the control accounts", t.total("subledger"),
                t.total("gl"));
        t.check("PARTNERS_RECONCILE", "Every partner reconciles", diffs == 0, diffs + " partner(s) differ");
        return t.build();
    }

    ReportBody upcoming(ReportContext ctx) {
        LocalDate until = ctx.asOf().plusDays(Math.max(0, ctx.request().intOption("days")));
        Map<String, Map<String, Object>> sched = new LinkedHashMap<>();
        for (Map<String, Object> s : SqlRows.list(jdbc.sql("SELECT \"documentId\", \"plannedDate\", \"plannedAmount\","
                + " \"onHold\" FROM \"SupplierPaymentSchedule\" WHERE \"organizationId\" = ?").param(
                ctx.organizationId()))) {
            sched.put((String) s.get("documentId"), s);
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("partner", "Supplier"), text("number", "Bill"),
                date("dueDate", "Due"), date("plannedDate", "Scheduled"), money("residual", "Open"), money(
                        "plannedAmount", "Scheduled amount"), text("status", "Status"), text("hold", "On hold"));
        for (Item i : open.at(ctx, "AP", ctx.asOf())) {
            Map<String, Object> s = sched.get(i.id());
            LocalDate when = s != null && s.get("plannedDate") != null ? LocalDate.parse(Tables.day(s.get(
                    "plannedDate"), ctx.zone())) : i.dueDate();
            if (when != null && when.isAfter(until)) {
                continue;
            }
            t.line(Obj.of("_id", i.id(), "partner", i.partnerName(), "number", i.number(), "dueDate", i.dueDate(),
                    "plannedDate", s == null ? null : s.get("plannedDate"), "residual", i.residual(), "plannedAmount",
                    s == null ? null : s.get("plannedAmount"), "status", when != null && when.isBefore(ctx.asOf())
                            ? "overdue" : "due", "hold", s != null && Boolean.TRUE.equals(s.get("onHold")) ? "yes"
                            : "no"), i.route() == null ? Map.of() : Map.of("_row", Drill.route(i.route())));
        }
        return t.build();
    }

    ReportBody forecast(ReportContext ctx, boolean withCash) {
        int weeks = Math.max(1, Math.min(26, ctx.request().intOption("weeks")));
        LocalDate start = ctx.asOf();
        BigDecimal[] in = new BigDecimal[weeks + 1];
        BigDecimal[] out = new BigDecimal[weeks + 1];
        Arrays.fill(in, BigDecimal.ZERO);
        Arrays.fill(out, BigDecimal.ZERO);
        if (withCash) {
            for (Item i : open.at(ctx, "AR", start)) {
                int w = week(i.dueDate(), start, weeks);
                if (w >= 0) {
                    in[w] = in[w].add(i.residual());
                }
            }
        }
        for (Item i : open.at(ctx, "AP", start)) {
            int w = week(i.dueDate(), start, weeks);
            if (w >= 0) {
                out[w] = out[w].add(i.residual());
            }
        }
        BigDecimal cash = BigDecimal.ZERO;
        if (withCash) {
            Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
            for (Map.Entry<String, BigDecimal[]> b : ledger.balances(ctx, new ReportPeriod("C", "", null, start))
                    .entrySet()) {
                AccountInfo a = accounts.get(b.getKey());
                if (a != null && StatementLayoutService.CASH_CATEGORIES.contains(a.category())) {
                    cash = cash.add(b.getValue()[0]).subtract(b.getValue()[1]);
                }
            }
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("week", "Week"), date("from", "From"), money(
                "opening", "Opening cash"), money("receipts", "Expected receipts"), money("payments",
                "Expected payments"), money("net", "Net"), money("closing", "Closing cash"));
        BigDecimal running = cash;
        for (int w = 0; w <= weeks; w++) {
            BigDecimal net = in[w].subtract(out[w]);
            BigDecimal opening = running;
            running = running.add(net);
            t.add("line", 0, false, Obj.of("_id", "W" + w, "week", w == 0 ? "Overdue / this week" : "Week " + (w + 1),
                    "from", w == 0 ? start : start.plusWeeks(w), "opening", withCash ? opening : null, "receipts",
                    withCash ? in[w] : null, "payments", out[w], "net", net, "closing", withCash ? running : null),
                    Map.of(), false);
        }
        t.meta("note", "Receipts and payments are open items placed in the week they fall due; overdue items in the"
                + " first week.");
        return t.build(false);
    }

    private static int week(LocalDate due, LocalDate start, int weeks) {
        if (due == null || !due.isAfter(start.plusDays(6))) {
            return 0;
        }
        long w = ChronoUnit.WEEKS.between(start, due);
        return w > weeks ? -1 : (int) w;
    }
}
