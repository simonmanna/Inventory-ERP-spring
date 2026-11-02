package com.inventoryerp.reporting.application;

import static com.inventoryerp.reporting.application.Tables.dec;
import static com.inventoryerp.reporting.application.Tables.date;
import static com.inventoryerp.reporting.application.Tables.money;
import static com.inventoryerp.reporting.application.Tables.number;
import static com.inventoryerp.reporting.application.Tables.pct;
import static com.inventoryerp.reporting.application.Tables.text;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.reporting.application.LedgerAggregator.AccountInfo;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.OptionSpec;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportBody.Drill;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportProvider;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tax and cash (FR-3). VAT from document and POS invoice lines (sales: invoices and debit notes, credit notes negative;
 * purchases: vendor bills) by tax code and VAT category, a VAT return in the URA layout (standard-rated, zero-rated
 * and exempt supplies, output and input VAT, net payable) checked against the VAT accounts, withholding tax and its
 * certificate register, PAYE from payroll runs, tax payments, EFRIS fiscalisation status of POS invoices, payment
 * method analysis (cash, bank, MTN MoMo, Airtel Money...), receipts, payments, transfers and undeposited takings.
 */
@Component
public class TaxCashReports implements ReportProvider {

    static final String ACC = Permissions.REPORT__ACCOUNTING;
    static final List<FilterKey> PERIOD_BRANCH = List.of(FilterKey.PERIOD, FilterKey.BRANCH);
    static final List<FilterKey> PERIOD_PARTNER = List.of(FilterKey.PERIOD, FilterKey.BRANCH, FilterKey.PARTNER);

    private final JdbcClient jdbc;
    private final LedgerAggregator ledger;

    public TaxCashReports(JdbcClient jdbc, LedgerAggregator ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @Override
    public List<ReportDefinition> definitions() {
        ReportCategory tax = ReportCategory.TAX;
        ReportCategory cash = ReportCategory.CASH_AND_BANK;
        return List.of(
                ReportDefinition.of("tax.vat-summary", tax, "VAT Summary", "Output VAT, input VAT and net VAT"
                        + " payable by tax code and category.", "FINANCIAL", ACC, PERIOD_BRANCH),
                ReportDefinition.of("tax.vat-detail", tax, "VAT Detail", "Invoice-level taxable sales and"
                        + " purchases with partner TIN.", "FINANCIAL", ACC, PERIOD_PARTNER,
                        OptionSpec.select("side", "Side", "both", "both", "sales", "purchases")),
                ReportDefinition.of("tax.output-vat", tax, "Output VAT Report", "VAT charged on sales, credit"
                        + " notes deducted.", "FINANCIAL", ACC, PERIOD_PARTNER),
                ReportDefinition.of("tax.input-vat", tax, "Input VAT Report", "Recoverable VAT on supplier bills.",
                        "FINANCIAL", ACC, PERIOD_PARTNER),
                ReportDefinition.of("tax.vat-return", tax, "VAT Return (URA format)", "Standard-rated, zero-rated"
                        + " and exempt supplies, output and input VAT and net VAT payable; checked against the VAT"
                        + " accounts.", "FINANCIAL", ACC, PERIOD_BRANCH),
                ReportDefinition.of("tax.audit-trail", tax, "Tax Audit Trail", "Every taxed line with document,"
                        + " product, tax code, base and tax.", "FINANCIAL", ACC, PERIOD_PARTNER),
                ReportDefinition.of("tax.reconciliation", tax, "Tax Reconciliation", "VAT per documents against"
                        + " the VAT accounts in the ledger, by tax code.", "FINANCIAL", ACC, PERIOD_BRANCH),
                ReportDefinition.of("tax.withholding", tax, "Withholding Tax Report", "Tax withheld on supplier"
                        + " payments (payable) and suffered on customer receipts (credit).", "FINANCIAL", ACC,
                        PERIOD_PARTNER),
                ReportDefinition.of("tax.wht-certificates", tax, "WHT Certificate Register", "Withholding by"
                        + " partner and month, the basis of certificates issued and received.", "OPERATIONAL", ACC,
                        PERIOD_PARTNER),
                ReportDefinition.of("tax.paye", tax, "PAYE Summary", "Gross pay, PAYE, pension and social security"
                        + " by payroll run; checked against the PAYE account.", "FINANCIAL", ACC,
                        List.of(FilterKey.PERIOD)),
                ReportDefinition.of("tax.payments", tax, "Tax Payment Report", "Payments of VAT, PAYE, WHT and other"
                        + " taxes from cash and bank.", "FINANCIAL", ACC, PERIOD_BRANCH),
                ReportDefinition.of("tax.efris", tax, "E-invoicing (EFRIS) Reconciliation", "POS and tax invoices"
                        + " by fiscalisation status; lists invoices without a fiscal document number.", "OPERATIONAL",
                        ACC, PERIOD_BRANCH),
                ReportDefinition.of("cash.payment-methods", cash, "Payment Method Analysis", "Receipts and payments"
                        + " by method: cash, bank, MTN MoMo, Airtel Money, card and others.", "OPERATIONAL", ACC,
                        PERIOD_BRANCH),
                ReportDefinition.of("cash.receipts", cash, "Cash Receipts Report", "All money received, by method"
                        + " and account.", "OPERATIONAL", ACC, PERIOD_PARTNER),
                ReportDefinition.of("cash.payments", cash, "Cash Payments Report", "All money paid out: supplier"
                        + " payments and expense payments by category.", "OPERATIONAL", ACC, PERIOD_PARTNER),
                ReportDefinition.of("cash.transfers", cash, "Bank and Cash Transfers", "Transfers between cash, bank"
                        + " and mobile-money accounts.", "FINANCIAL", ACC, PERIOD_BRANCH),
                ReportDefinition.of("cash.undeposited", cash, "Undeposited Funds", "Takings counted at cash sessions"
                        + " and not yet banked, and the cash clearing balance.", "OPERATIONAL", ACC,
                        PERIOD_BRANCH));
    }

    @Override
    public ReportBody run(ReportContext ctx) {
        return switch (ctx.definition().id()) {
            case "tax.vat-summary" -> vatSummary(ctx);
            case "tax.vat-detail" -> vatDetail(ctx, ctx.request().option("side"));
            case "tax.output-vat" -> vatDetail(ctx, "sales");
            case "tax.input-vat" -> vatDetail(ctx, "purchases");
            case "tax.vat-return" -> vatReturn(ctx);
            case "tax.audit-trail" -> auditTrail(ctx);
            case "tax.reconciliation" -> taxReconciliation(ctx);
            case "tax.withholding" -> withholding(ctx, false);
            case "tax.wht-certificates" -> withholding(ctx, true);
            case "tax.paye" -> paye(ctx);
            case "tax.payments" -> taxPayments(ctx);
            case "tax.efris" -> efris(ctx);
            case "cash.payment-methods" -> paymentMethods(ctx);
            case "cash.receipts" -> receiptsOrPayments(ctx, true);
            case "cash.payments" -> receiptsOrPayments(ctx, false);
            case "cash.transfers" -> transfers(ctx);
            case "cash.undeposited" -> undeposited(ctx);
            default -> throw ApiException.notFound("Report " + ctx.definition().id() + " not found");
        };
    }

    private Timestamp from(ReportContext ctx) {
        return SqlRows.ts(ctx.start(ctx.current().from()));
    }

    private Timestamp to(ReportContext ctx) {
        return SqlRows.ts(ctx.endExclusive(ctx.current().to()));
    }

    /**
     * Taxed lines of the period: side ({@code S}ales / {@code P}urchases), sign (credit notes −1), document, partner,
     * tax code, VAT category, base and tax. Lines without a tax are exempt / untaxed supplies.
     */
    List<Map<String, Object>> taxLines(ReportContext ctx, boolean lineLevel) {
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
        String lineCols = lineLevel ? ", x.\"lineId\", x.description, x.product" : "";
        return SqlRows.list(jdbc.sql("SELECT x.side, x.\"docId\", x.kind, x.number, x.\"issueDate\", x.partner,"
                + " x.tin, coalesce(t.code, 'NONE') AS \"taxCode\", coalesce(t.name, 'No tax') AS \"taxName\","
                + " coalesce(t.\"vatCategory\", 'exempt') AS category, t.rate, t.\"accountId\" AS \"taxAccountId\","
                + " sum(x.sign * x.base) AS base, sum(x.sign * x.tax) AS tax" + lineCols + " FROM ("
                + "SELECT CASE WHEN d.\"documentType\"::text = 'vendor_bill' THEN 'P' ELSE 'S' END AS side,"
                + " CASE WHEN d.\"documentType\"::text = 'credit_note' THEN -1 ELSE 1 END AS sign, d.id AS \"docId\","
                + " d.\"documentType\"::text AS kind, d.\"documentNumber\" AS number, d.\"issueDate\", pt.name AS partner,"
                + " pt.\"taxNumber\" AS tin, dl.\"taxId\", dl.subtotal AS base, coalesce(dl.\"taxAmount\", 0) AS tax,"
                + " dl.id AS \"lineId\", dl.description, pr.name AS product FROM \"DocumentLine\" dl JOIN \"Document\" d ON"
                + " d.id = dl.\"documentId\" LEFT JOIN \"Partner\" pt ON pt.id = d.\"partnerId\" LEFT JOIN \"Product\" pr"
                + " ON pr.id = dl.\"productId\" WHERE d.\"organizationId\" = ? AND d.\"issueDate\" >= ? AND"
                + " d.\"issueDate\" < ? AND d.status::text IN ('posted', 'paid') AND d.\"documentType\"::text IN"
                + " ('sales_invoice', 'debit_note', 'credit_note', 'vendor_bill')" + df
                + " UNION ALL SELECT 'S', 1, i.id, 'pos_invoice', i.\"invoiceNumber\", i.\"issueDate\", pt.name,"
                + " pt.\"taxNumber\", ii.\"taxId\", ii.subtotal, coalesce(ii.\"taxAmount\", 0), ii.id, ii.description,"
                + " pr.name FROM \"InvoiceItem\" ii JOIN \"Invoice\" i ON i.id = ii.\"invoiceId\" LEFT JOIN \"Partner\" pt"
                + " ON pt.id = i.\"partnerId\" LEFT JOIN \"Product\" pr ON pr.id = ii.\"productId\" WHERE"
                + " i.\"organizationId\" = ? AND i.\"issueDate\" >= ? AND i.\"issueDate\" < ? AND i.status::text IN"
                + " ('posted', 'paid')" + inf + ") x LEFT JOIN \"Tax\" t ON t.id = x.\"taxId\" GROUP BY x.side, x.\"docId\","
                + " x.kind, x.number, x.\"issueDate\", x.partner, x.tin, t.code, t.name, t.\"vatCategory\", t.rate,"
                + " t.\"accountId\"" + (lineLevel ? ", x.\"lineId\", x.description, x.product" : "")
                + " ORDER BY x.\"issueDate\", x.number").params(p));
    }

    ReportBody vatSummary(ReportContext ctx) {
        Map<String, Map<String, Object>> byTax = new LinkedHashMap<>();
        for (Map<String, Object> l : taxLines(ctx, false)) {
            String k = (String) l.get("taxCode");
            Map<String, Object> r = byTax.computeIfAbsent(k, x -> new LinkedHashMap<>(Obj.of("_id", k, "taxCode", k,
                    "taxName", l.get("taxName"), "category", l.get("category"), "rate", l.get("rate"), "salesBase",
                    BigDecimal.ZERO, "outputVat", BigDecimal.ZERO, "purchaseBase", BigDecimal.ZERO, "inputVat",
                    BigDecimal.ZERO)));
            boolean sales = "S".equals(l.get("side"));
            r.put(sales ? "salesBase" : "purchaseBase", dec(r.get(sales ? "salesBase" : "purchaseBase")).add(dec(l.get(
                    "base"))));
            r.put(sales ? "outputVat" : "inputVat", dec(r.get(sales ? "outputVat" : "inputVat")).add(dec(l.get(
                    "tax"))));
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("taxCode", "Code"), text("taxName", "Tax"),
                text("category", "VAT category"), number("rate", "Rate %"), money("salesBase", "Taxable sales"),
                money("outputVat", "Output VAT"), money("purchaseBase", "Taxable purchases"), money("inputVat",
                        "Input VAT"), money("net", "Net VAT"));
        for (Map<String, Object> r : byTax.values()) {
            r.put("net", dec(r.get("outputVat")).subtract(dec(r.get("inputVat"))));
            t.line(r, Map.of("outputVat", Drill.report("tax.output-vat", Obj.of("from", ctx.current().from()
                    .toString(), "to", ctx.current().to().toString())), "inputVat", Drill.report("tax.input-vat",
                    Obj.of("from", ctx.current().from().toString(), "to", ctx.current().to().toString()))));
        }
        return t.build();
    }

    ReportBody vatDetail(ReportContext ctx, String side) {
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("issueDate", "Date"), text("number", "Document"),
                text("kind", "Type"), text("partner", "Partner"), text("tin", "TIN"), text("taxCode", "Tax"),
                text("category", "Category"), money("base", "Taxable amount"), money("tax", "VAT"), money("gross",
                        "Gross"));
        for (Map<String, Object> l : taxLines(ctx, false)) {
            boolean sales = "S".equals(l.get("side"));
            if ("sales".equals(side) && !sales || "purchases".equals(side) && sales) {
                continue;
            }
            l.put("gross", dec(l.get("base")).add(dec(l.get("tax"))));
            l.put("_id", l.get("docId") + ":" + l.get("taxCode"));
            String route = OpenItems.route((String) l.get("kind"), (String) l.get("docId"));
            t.line(l, route == null ? Map.of() : Map.of("_row", Drill.route(route)));
        }
        return t.build();
    }

    ReportBody auditTrail(ReportContext ctx) {
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("issueDate", "Date"), text("number", "Document"),
                text("kind", "Type"), text("partner", "Partner"), text("tin", "TIN"), text("product", "Product"),
                text("description", "Line"), text("taxCode", "Tax"), number("rate", "Rate %"), money("base",
                        "Base"), money("tax", "Tax"));
        for (Map<String, Object> l : taxLines(ctx, true)) {
            l.put("_id", l.get("lineId"));
            t.line(l);
        }
        return t.build();
    }

    ReportBody vatReturn(ReportContext ctx) {
        BigDecimal stdSales = BigDecimal.ZERO;
        BigDecimal outVat = BigDecimal.ZERO;
        BigDecimal zeroSales = BigDecimal.ZERO;
        BigDecimal exemptSales = BigDecimal.ZERO;
        BigDecimal stdPurch = BigDecimal.ZERO;
        BigDecimal inVat = BigDecimal.ZERO;
        BigDecimal otherPurch = BigDecimal.ZERO;
        java.util.Set<String> vatAccounts = new java.util.HashSet<>();
        for (Map<String, Object> l : taxLines(ctx, false)) {
            String cat = String.valueOf(l.get("category"));
            BigDecimal base = dec(l.get("base"));
            BigDecimal tax = dec(l.get("tax"));
            if (l.get("taxAccountId") != null) {
                vatAccounts.add((String) l.get("taxAccountId"));
            }
            if ("S".equals(l.get("side"))) {
                switch (cat) {
                    case "standard" -> {
                        stdSales = stdSales.add(base);
                        outVat = outVat.add(tax);
                    }
                    case "zero_rated" -> zeroSales = zeroSales.add(base);
                    default -> exemptSales = exemptSales.add(base);
                }
            } else if ("standard".equals(cat)) {
                stdPurch = stdPurch.add(base);
                inVat = inVat.add(tax);
            } else {
                otherPurch = otherPurch.add(base);
            }
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"accountId\" FROM \"Tax\" WHERE \"organizationId\""
                + " = ? AND type::text = 'vat' AND \"accountId\" IS NOT NULL").param(ctx.organizationId()))) {
            vatAccounts.add((String) r.get("accountId"));
        }
        BigDecimal net = outVat.subtract(inVat);
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("box", "Box"), text("label", "Description"),
                money("amount", "Amount"));
        Object[][] rows = {
            {"H1", null, "Supplies (sales)", null, "header"},
            {"1", "1", "Standard-rated supplies (excluding VAT)", stdSales, "line"},
            {"2", "2", "Output VAT on standard-rated supplies", outVat, "line"},
            {"3", "3", "Zero-rated supplies", zeroSales, "line"},
            {"4", "4", "Exempt and out-of-scope supplies", exemptSales, "line"},
            {"5", "5", "Total supplies (1 + 3 + 4)", stdSales.add(zeroSales).add(exemptSales), "subtotal"},
            {"H2", null, "Purchases", null, "header"},
            {"6", "6", "Standard-rated purchases (excluding VAT)", stdPurch, "line"},
            {"7", "7", "Input VAT claimable", inVat, "line"},
            {"8", "8", "Zero-rated, exempt and untaxed purchases", otherPurch, "line"},
            {"9", "9", "Net VAT payable / (refundable) (2 − 7)", net, "total"}};
        for (Object[] r : rows) {
            t.add((String) r[4], "line".equals(r[4]) ? 1 : 0, !"line".equals(r[4]), Obj.of("_id", "BOX" + r[0], "box",
                    r[1], "label", r[2], "amount", r[3]), Map.of(), false);
        }
        if (!vatAccounts.isEmpty()) {
            Map<String, BigDecimal[]> mov = ledger.balances(ctx, ctx.current());
            BigDecimal gl = BigDecimal.ZERO;
            for (String a : vatAccounts) {
                BigDecimal[] m = GlReports.get(mov, a);
                gl = gl.add(m[1]).subtract(m[0]);
            }
            t.add("kpi", 0, false, Obj.of("_id", "GL", "label", "Net credit movement on the VAT accounts in the"
                    + " ledger", "amount", gl), Map.of("amount", GlReports.lines(ctx, ctx.current().from(),
                    ctx.current().to(), new ArrayList<>(vatAccounts))), false);
            t.tie("TIES_TO_GL", "Net VAT equals the movement on the VAT accounts", net, gl);
        }
        t.meta("format", "URA VAT return (domestic supplies); imports and reverse charge are filed separately");
        return t.build(false);
    }

    ReportBody taxReconciliation(ReportContext ctx) {
        Map<String, Map<String, Object>> byAccount = new LinkedHashMap<>();
        for (Map<String, Object> l : taxLines(ctx, false)) {
            if (l.get("taxAccountId") == null || dec(l.get("tax")).signum() == 0) {
                continue;
            }
            String a = (String) l.get("taxAccountId");
            Map<String, Object> r = byAccount.computeIfAbsent(a, x -> new LinkedHashMap<>(Obj.of("_id", a,
                    "documents", BigDecimal.ZERO)));
            BigDecimal tax = dec(l.get("tax"));
            r.put("documents", dec(r.get("documents")).add("S".equals(l.get("side")) ? tax : tax.negate()));
        }
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal[]> mov = ledger.balances(ctx, ctx.current());
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("account", "VAT account"), money("documents",
                "Per documents (output − input)"), money("gl", "Ledger (credit − debit)"), money("difference",
                "Difference"));
        int diffs = 0;
        for (Map.Entry<String, Map<String, Object>> e : byAccount.entrySet()) {
            AccountInfo a = accounts.get(e.getKey());
            BigDecimal[] m = GlReports.get(mov, e.getKey());
            BigDecimal gl = m[1].subtract(m[0]);
            BigDecimal d = dec(e.getValue().get("documents")).subtract(gl);
            if (d.abs().compareTo(new BigDecimal("0.01")) > 0) {
                diffs++;
            }
            t.line(Obj.of("_id", e.getKey(), "account", a == null ? e.getKey() : a.code() + " " + a.name(),
                    "documents", e.getValue().get("documents"), "gl", gl, "difference", d), Map.of("gl",
                    GlReports.lines(ctx, ctx.current().from(), ctx.current().to(), List.of(e.getKey()))));
        }
        t.check("RECONCILED", "Every VAT account agrees with the documents", diffs == 0, diffs
                + " account(s) differ");
        return t.build();
    }

    @SuppressWarnings("unchecked")
    ReportBody withholding(ReportContext ctx, boolean certificates) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String f = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            f += " AND p.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        if (!ctx.request().branchIds().isEmpty()) {
            f += " AND p.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT p.id, p.\"paymentNumber\", p.\"paymentDate\","
                + " p.direction::text AS direction, pt.name AS partner, pt.\"taxNumber\" AS tin, p.\"partnerId\","
                + " p.amount + p.\"withholdingAmount\" AS gross, p.\"withholdingAmount\" AS wht, p.amount AS net,"
                + " to_char(p.\"paymentDate\", 'YYYY-MM') AS month FROM \"Payment\" p LEFT JOIN \"Partner\" pt ON pt.id ="
                + " p.\"partnerId\" WHERE p.\"organizationId\" = ? AND p.\"paymentDate\" >= ? AND p.\"paymentDate\" < ?"
                + " AND coalesce(p.\"withholdingAmount\", 0) <> 0 AND p.status::text <> 'cancelled' AND p.\"voidedAt\""
                + " IS NULL" + f + " ORDER BY p.direction, pt.name, p.\"paymentDate\"").params(p));
        for (Map<String, Object> r : rows) {
            r.put("type", "outbound".equals(r.get("direction")) ? "Deducted from suppliers (payable)"
                    : "Suffered on receipts (credit)");
        }
        if (certificates) {
            Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                String k = r.get("direction") + "|" + r.get("partnerId") + "|" + r.get("month");
                Map<String, Object> a = agg.computeIfAbsent(k, x -> new LinkedHashMap<>(Obj.of("_id", k, "type",
                        r.get("type"), "partner", r.get("partner"), "tin", r.get("tin"), "month", r.get("month"),
                        "payments", new ArrayList<String>(), "gross", BigDecimal.ZERO, "wht", BigDecimal.ZERO)));
                List<String> refs = (List<String>) a.get("payments");
                refs.add((String) r.get("paymentNumber"));
                a.put("gross", dec(a.get("gross")).add(dec(r.get("gross"))));
                a.put("wht", dec(a.get("wht")).add(dec(r.get("wht"))));
            }
            Tables.Builder t = new Tables.Builder(ctx.zone(), text("type", "Type"), text("partner", "Partner"),
                    text("tin", "TIN"), text("month", "Month"), text("refs", "Payments"), money("gross",
                            "Gross paid"), money("wht", "Tax withheld"));
            for (Map<String, Object> a : agg.values()) {
                a.put("refs", String.join(", ", (List<String>) a.get("payments")));
                t.line(a);
            }
            return t.build();
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("type", "Type"), date("paymentDate", "Date"),
                text("paymentNumber", "Payment"), text("partner", "Partner"), text("tin", "TIN"), money("gross",
                        "Gross"), money("wht", "Tax withheld"), money("net", "Net paid"));
        t.grouped(rows, "type", "type", r -> Map.of("_row", Drill.route("/payments/" + r.get("id"))));
        return t.build();
    }

    ReportBody paye(ReportContext ctx) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT r.id, r.\"runNumber\", r.\"paymentDate\","
                + " r.status::text AS status, pp.\"periodCode\", count(i.id) AS employees, sum(i.\"grossPay\") AS gross,"
                + " sum(i.\"taxAmount\") AS paye, sum(i.\"pensionAmount\") AS pension, sum(i.\"socialSecurityAmount\") AS"
                + " social, sum(i.\"totalDeductions\") AS deductions, sum(i.\"netPay\") AS net FROM \"HrPayrollRun\" r"
                + " LEFT JOIN \"HrPayrollPeriod\" pp ON pp.id = r.\"periodId\" LEFT JOIN \"HrPayrollItem\" i ON i.\"runId\""
                + " = r.id AND i.\"deletedAt\" IS NULL WHERE r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL AND"
                + " coalesce(r.\"paymentDate\", r.\"createdAt\") >= ? AND coalesce(r.\"paymentDate\", r.\"createdAt\") < ?"
                + " GROUP BY r.id, pp.\"periodCode\" ORDER BY coalesce(r.\"paymentDate\", r.\"createdAt\")")
                .params(ctx.organizationId(), from(ctx), to(ctx)));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("runNumber", "Payroll run"), text("periodCode",
                "Period"), date("paymentDate", "Paid"), text("status", "Status"), number("employees", "Employees"),
                money("gross", "Gross pay"), money("paye", "PAYE"), money("pension", "Pension"), money("social",
                        "Social security"), money("deductions", "Total deductions"), money("net", "Net pay"));
        rows.forEach(t::line);
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal[]> mov = ledger.balances(ctx, ctx.current());
        BigDecimal gl = BigDecimal.ZERO;
        boolean found = false;
        for (AccountInfo a : accounts.values()) {
            if ("2210".equals(a.code()) || GlReports.nameHas(a, "paye")) {
                gl = gl.add(GlReports.get(mov, a.id())[1]);
                found = true;
            }
        }
        if (found) {
            t.tie("TIES_TO_GL", "PAYE on payroll runs equals PAYE credited to the PAYE account", t.total("paye"), gl);
        }
        return t.build();
    }

    ReportBody taxPayments(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        List<String> taxAccounts = accounts.values().stream().filter(a -> "tax".equals(a.category()) || List.of(
                "2160", "2200", "2210", "2220", "2230", "2240").contains(a.code())).map(AccountInfo::id).toList();
        List<String> cash = accounts.values().stream().filter(a -> StatementLayoutService.CASH_CATEGORIES.contains(
                a.category())).map(AccountInfo::id).toList();
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("postingDate", "Date"), text("entryNumber", "Entry"),
                text("account", "Tax account"), text("description", "Description"), money("amount", "Paid"));
        if (taxAccounts.isEmpty() || cash.isEmpty()) {
            return t.build();
        }
        LedgerAggregator.Where w = ledger.where(ctx.organizationId(), ctx.request().withAccounts(taxAccounts),
                ctx.zone(), ctx.current().from(), ctx.current().to());
        List<Object> p = new ArrayList<>(w.params());
        p.add(cash.toArray(String[]::new));
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.id, e.id AS \"entryId\", e.\"postingDate\","
                + " e.\"entryNumber\", a.code || ' ' || a.name AS account, coalesce(l.description, e.description) AS"
                + " description, l.\"baseDebit\" - l.\"baseCredit\" AS amount FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                + " ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\"" + w.sql()
                + " AND l.\"baseDebit\" > 0 AND EXISTS (SELECT 1 FROM \"JournalLine\" c WHERE c.\"journalEntryId\" = e.id"
                + " AND c.\"accountId\" = ANY(?::text[]) AND c.\"baseCredit\" > 0) ORDER BY e.\"postingDate\"")
                .params(p));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route("/journal-entries/" + r.get("entryId")))));
        return t.build();
    }

    ReportBody efris(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String f = "";
        if (!ctx.request().branchIds().isEmpty()) {
            f = " AND i.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT i.id, i.\"invoiceNumber\", i.\"issueDate\","
                + " i.\"totalAmount\", i.\"taxAmount\", coalesce(i.\"fiscalStatus\", 'not_submitted') AS \"fiscalStatus\","
                + " i.\"fiscalCode\", i.\"fiscalizedAt\", b.name AS branch FROM \"Invoice\" i LEFT JOIN \"Branch\" b ON"
                + " b.id = i.\"branchId\" WHERE i.\"organizationId\" = ? AND i.\"issueDate\" >= ? AND i.\"issueDate\" < ?"
                + " AND i.status::text IN ('posted', 'paid')" + f + " ORDER BY i.\"issueDate\"").params(p));
        Map<String, Map<String, Object>> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String s = r.get("fiscalCode") != null ? "fiscalised" : (String) r.get("fiscalStatus");
            r.put("state", s);
            Map<String, Object> a = byStatus.computeIfAbsent(s, k -> new LinkedHashMap<>(Obj.of("_id", "S:" + k,
                    "state", k, "count", 0L, "totalAmount", BigDecimal.ZERO, "taxAmount", BigDecimal.ZERO)));
            a.put("count", (Long) a.get("count") + 1);
            a.put("totalAmount", dec(a.get("totalAmount")).add(dec(r.get("totalAmount"))));
            a.put("taxAmount", dec(a.get("taxAmount")).add(dec(r.get("taxAmount"))));
        }
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("state", "Fiscal status"), text("invoiceNumber",
                "Invoice"), date("issueDate", "Date"), text("branch", "Branch"), number("count", "Invoices"),
                money("totalAmount", "Total"), money("taxAmount", "VAT"), text("fiscalCode", "Fiscal document no."));
        byStatus.values().forEach(a -> t.add("subtotal", 0, true, a, Map.of(), false));
        long missing = 0;
        for (Map<String, Object> r : rows) {
            if (!"fiscalised".equals(r.get("state"))) {
                missing++;
                t.add("line", 1, false, r, Map.of(), false);
            }
        }
        t.check("ALL_FISCALISED", "Every invoice carries a fiscal document number", missing == 0, missing
                + " invoice(s) not fiscalised");
        t.meta("note", "Sales documents raised outside the POS are reconciled against the EFRIS portal export.");
        return t.build(false);
    }

    // ---------------------------------------------------------------- cash

    static String methodGroup(String m) {
        String s = m == null ? "" : m.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("mtn") || s.contains("momo")) {
            return "MTN Mobile Money";
        }
        if (s.contains("airtel")) {
            return "Airtel Money";
        }
        if (s.contains("mobile")) {
            return "Mobile money (other)";
        }
        if (s.contains("card") || s.contains("visa") || s.contains("master")) {
            return "Card";
        }
        if (s.contains("bank") || s.contains("transfer") || s.contains("cheque") || s.contains("check")
                || s.contains("eft") || s.contains("rtgs")) {
            return "Bank";
        }
        if (s.contains("cash")) {
            return "Cash";
        }
        return s.isEmpty() ? "Unspecified" : m;
    }

    ReportBody paymentMethods(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String f = "";
        if (!ctx.request().branchIds().isEmpty()) {
            f = " AND p.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        Map<String, Map<String, Object>> agg = new java.util.TreeMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT p.\"paymentMethod\", p.direction::text AS"
                + " direction, count(*) AS n, sum(p.amount) AS amount FROM \"Payment\" p WHERE p.\"organizationId\" = ?"
                + " AND p.\"paymentDate\" >= ? AND p.\"paymentDate\" < ? AND p.status::text <> 'cancelled' AND"
                + " p.\"voidedAt\" IS NULL" + f + " GROUP BY 1, 2").params(p))) {
            String g = methodGroup((String) r.get("paymentMethod"));
            Map<String, Object> a = agg.computeIfAbsent(g, k -> new LinkedHashMap<>(Obj.of("_id", k, "method", k,
                    "receipts", BigDecimal.ZERO, "receiptCount", 0L, "payments", BigDecimal.ZERO, "paymentCount",
                    0L)));
            boolean in = "inbound".equals(r.get("direction"));
            a.put(in ? "receipts" : "payments", dec(a.get(in ? "receipts" : "payments")).add(dec(r.get("amount"))));
            a.put(in ? "receiptCount" : "paymentCount", (Long) a.get(in ? "receiptCount" : "paymentCount")
                    + ((Number) r.get("n")).longValue());
        }
        BigDecimal totalIn = agg.values().stream().map(a -> dec(a.get("receipts"))).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("method", "Method"), number("receiptCount",
                "Receipts"), money("receipts", "Received"), pct("share", "Share of receipts %"), number("paymentCount",
                "Payments"), money("payments", "Paid"), money("net", "Net"));
        for (Map<String, Object> a : agg.values()) {
            a.put("share", Tables.pctOf(dec(a.get("receipts")), totalIn));
            a.put("net", dec(a.get("receipts")).subtract(dec(a.get("payments"))));
            t.line(a);
        }
        return t.build();
    }

    ReportBody receiptsOrPayments(ReportContext ctx, boolean receipts) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), receipts ? "inbound" : "outbound", from(ctx),
                to(ctx)));
        String f = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            f += " AND p.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        if (!ctx.request().branchIds().isEmpty()) {
            f += " AND p.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT p.id, p.\"paymentDate\" AS date,"
                + " p.\"paymentNumber\" AS number, p.\"paymentMethod\" AS method, pt.name AS party, a.name AS account,"
                + " p.reference, p.amount, 'payment' AS kind FROM \"Payment\" p LEFT JOIN \"Partner\" pt ON pt.id ="
                + " p.\"partnerId\" LEFT JOIN \"Account\" a ON a.id = p.\"accountId\" WHERE p.\"organizationId\" = ? AND"
                + " p.direction::text = ? AND p.\"paymentDate\" >= ? AND p.\"paymentDate\" < ? AND p.status::text <>"
                + " 'cancelled' AND p.\"voidedAt\" IS NULL" + f).params(p));
        if (!receipts && ctx.request().partnerIds().isEmpty()) {
            rows.addAll(SqlRows.list(jdbc.sql("SELECT ep.id, ep.\"createdAt\" AS date, e.\"expenseCode\" AS number,"
                    + " ep.\"paymentMethod\" AS method, coalesce(e.\"categoryName\", 'Expense') AS party, a.name AS"
                    + " account, ep.reference, ep.amount, 'expense' AS kind FROM \"ExpensePayment\" ep JOIN \"Expense\" e"
                    + " ON e.id = ep.\"expenseId\" LEFT JOIN \"Account\" a ON a.id = ep.\"accountId\" WHERE"
                    + " ep.\"organizationId\" = ? AND ep.\"createdAt\" >= ? AND ep.\"createdAt\" < ? AND"
                    + " coalesce(ep.status, 'posted') <> 'voided'").params(ctx.organizationId(), from(ctx), to(ctx))));
        }
        for (Map<String, Object> r : rows) {
            r.put("group", methodGroup((String) r.get("method")));
        }
        rows.sort((a, b) -> (a.get("group") + "" + Tables.day(a.get("date"), ctx.zone())).compareTo(b.get("group")
                + "" + Tables.day(b.get("date"), ctx.zone())));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("date", "Date"), text("number", "Number"),
                text("party", receipts ? "From" : "To / category"), text("method", "Method"), text("account",
                        "Account"), text("reference", "Reference"), money("amount", "Amount"));
        t.grouped(rows, "group", "group", r -> "payment".equals(r.get("kind")) ? Map.of("_row", Drill.route(
                "/payments/" + r.get("id"))) : Map.of());
        return t.build();
    }

    ReportBody transfers(ReportContext ctx) {
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        List<String> cash = accounts.values().stream().filter(a -> StatementLayoutService.CASH_CATEGORIES.contains(
                a.category())).map(AccountInfo::id).toList();
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("postingDate", "Date"), text("entryNumber", "Entry"),
                text("fromAccount", "From"), text("toAccount", "To"), text("description", "Description"),
                money("amount", "Amount"));
        if (cash.size() < 2) {
            return t.build();
        }
        LedgerAggregator.Where w = ledger.where(ctx.organizationId(), ctx.request().withAccounts(cash), ctx.zone(),
                ctx.current().from(), ctx.current().to());
        List<Object> p = new ArrayList<>(w.params());
        p.add(cash.toArray(String[]::new));
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT e.id, e.\"postingDate\", e.\"entryNumber\","
                + " e.description, string_agg(DISTINCT CASE WHEN l.\"baseCredit\" > 0 THEN a.name END, ', ') AS"
                + " \"fromAccount\", string_agg(DISTINCT CASE WHEN l.\"baseDebit\" > 0 THEN a.name END, ', ') AS"
                + " \"toAccount\", sum(l.\"baseDebit\") AS amount FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                + " l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\"" + w.sql() + " AND NOT EXISTS"
                + " (SELECT 1 FROM \"JournalLine\" o WHERE o.\"journalEntryId\" = e.id AND NOT (o.\"accountId\" ="
                + " ANY(?::text[]))) GROUP BY e.id ORDER BY e.\"postingDate\"").params(p));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route("/journal-entries/" + r.get("id")))));
        return t.build();
    }

    ReportBody undeposited(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String f = "";
        if (!ctx.request().branchIds().isEmpty()) {
            f = " AND s.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT s.id, s.\"closedAt\", s.\"businessDate\","
                + " b.name AS branch, coalesce(nullif(concat_ws(' ', u.\"firstName\", u.\"lastName\"), ''), s.\"userId\")"
                + " AS cashier, s.\"closingExpected\" AS expected, s.\"closingCounted\" AS counted,"
                + " s.\"closingDifference\" AS difference, coalesce(s.\"bankedAmount\", 0) AS banked,"
                + " coalesce(s.\"closingCounted\", 0) - coalesce(s.\"bankedAmount\", 0) AS undeposited, s.\"bankName\""
                + " FROM \"CashSession\" s LEFT JOIN \"Branch\" b ON b.id = s.\"branchId\" LEFT JOIN \"User\" u ON u.id ="
                + " s.\"userId\" WHERE s.\"organizationId\" = ? AND s.\"closedAt\" >= ? AND s.\"closedAt\" < ?" + f
                + " ORDER BY s.\"closedAt\"").params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("closedAt", "Closed"), text("branch", "Branch"),
                text("cashier", "Cashier"), money("expected", "Expected"), money("counted", "Counted"), money(
                        "difference", "Over / (short)"), money("banked", "Banked"), money("undeposited",
                        "Not banked"), text("bankName", "Bank"));
        rows.forEach(t::line);
        Map<String, AccountInfo> accounts = ledger.accounts(ctx.organizationId());
        Map<String, BigDecimal[]> bal = ledger.balances(ctx, new com.inventoryerp.reporting.spi.ReportPeriod("C", "",
                null, ctx.current().to()));
        for (AccountInfo a : accounts.values()) {
            if ("1900".equals(a.code()) || GlReports.nameHas(a, "cash clearing", "undeposited")) {
                BigDecimal[] b = GlReports.get(bal, a.id());
                t.add("kpi", 0, false, Obj.of("_id", "GL:" + a.id(), "branch", a.code() + " " + a.name()
                        + " balance", "undeposited", b[0].subtract(b[1])), Map.of("undeposited", GlReports.lines(ctx,
                        null, ctx.current().to(), List.of(a.id()))), false);
            }
        }
        return t.build();
    }
}
