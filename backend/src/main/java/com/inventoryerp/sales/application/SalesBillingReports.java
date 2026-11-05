package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesReportKit.bar;
import static com.inventoryerp.sales.application.SalesReportKit.line;
import static com.inventoryerp.sales.application.SalesReportKit.money;
import static com.inventoryerp.sales.application.SalesReportKit.pct;
import static com.inventoryerp.sales.application.SalesReportKit.route;
import static com.inventoryerp.sales.application.SalesReportKit.table;
import static com.inventoryerp.sales.application.SalesReportSet.DOC;
import static com.inventoryerp.sales.application.SalesReportSet.SNAPSHOT;
import static com.inventoryerp.sales.application.SalesReportSet.def;
import static com.inventoryerp.sales.application.SalesReportSet.with;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.sales.application.SalesReportKit.Bucket;
import com.inventoryerp.sales.application.SalesReportKit.Def;
import com.inventoryerp.sales.application.SalesReportKit.Filter;
import com.inventoryerp.sales.application.SalesReportKit.Report;
import com.inventoryerp.sales.application.SalesReportKit.Scope;
import com.inventoryerp.sales.application.SalesReportKit.Sql;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Billing, collection and return reports of the report center (ADR-S057). They read the posted sales documents,
 * receipts and allocations; Accounting stays the authority for posted balances (these are operational views of the
 * same rows, not GL reports).
 */
@Component
class SalesBillingReports implements SalesReportSet {

    static final String INV = "invoices";
    static final String COL = "collections";
    static final String DISC = SalesRevenueReports.DISC;
    static final String TEAM = SalesRevenueReports.TEAM;

    private static final String SP = "COALESCE(d.\"salespersonId\", so.\"ownerId\", p.\"salespersonId\")";

    private static final String INVOICES = """
            SELECT d.id, d."documentNumber" AS number, d."documentType"::text AS type, d."issueDate"::date AS date,
                   d."dueDate"::date AS "dueDate", d.status::text AS status, d."paymentStatus"::text AS "paymentStatus",
                   d."paymentMode"::text AS "paymentMode", d."partnerId", p.name AS customer,
                   trim(u."firstName" || ' ' || COALESCE(u."lastName", '')) AS salesperson, b.name AS branch,
                   d.subtotal, d."taxAmount", d."totalAmount", d."amountPaid", d."amountResidual",
                   d."cancelledAt"::date AS "cancelledOn", d.notes, d."sourceType", d.reference,
                   CASE WHEN ss.id IS NOT NULL THEN 'branch_cash' WHEN so.id IS NOT NULL THEN 'sales_order'
                        WHEN d."tableId" IS NOT NULL OR d."sourceType" LIKE 'pos%' THEN 'pos' ELSE 'direct' END AS channel
              FROM "Document" d
              LEFT JOIN "Partner" p ON p.id = d."partnerId"
              LEFT JOIN "SalesOrder" so ON d."sourceType" = 'sales_order' AND so.id = d."sourceId"
              LEFT JOIN "SalesSubmission" ss ON ss."organizationId" = d."organizationId" AND ss."currentInvoiceId" = d.id
              LEFT JOIN "User" u ON u.id = COALESCE(d."salespersonId", so."ownerId", p."salespersonId")
              LEFT JOIN "Branch" b ON b.id = d."branchId"
             WHERE d."organizationId" = ? AND d."documentType" = ?""";

    private static final Scope INVOICE_SCOPE = new Scope("d.\"issueDate\"::date", "d.\"branchId\"", SP,
            "d.\"partnerId\"",
            "EXISTS (SELECT 1 FROM \"DocumentLine\" x WHERE x.\"documentId\" = d.id AND x.\"productId\" = ?)",
            "EXISTS (SELECT 1 FROM \"DocumentLine\" x JOIN \"Product\" xp ON xp.id = x.\"productId\" WHERE"
                    + " x.\"documentId\" = d.id AND xp.\"categoryId\" = ?)", "p.\"categoryId\"");

    private static final String PAYMENTS = """
            SELECT pm.id, pm."paymentNumber" AS number, pm."paymentDate"::date AS date, pm."paymentMethod" AS method,
                   pm.amount, pm."allocatedAmount", pm."unallocatedAmount", pm."refundedAmount", pm.status::text AS status,
                   pm."partnerId", p.name AS customer, b.name AS branch, pm.reference, pm."refundOfId",
                   pm."withholdingAmount"
              FROM "Payment" pm
              LEFT JOIN "Partner" p ON p.id = pm."partnerId"
              LEFT JOIN "Branch" b ON b.id = pm."branchId"
             WHERE pm."organizationId" = ? AND pm."voidedAt" IS NULL AND pm.status NOT IN ('draft', 'cancelled')""";

    private static final Scope PAYMENT_SCOPE = new Scope("pm.\"paymentDate\"::date", "pm.\"branchId\"",
            "p.\"salespersonId\"", "pm.\"partnerId\"", null, null, "p.\"categoryId\"");

    private final SalesDb db;
    private final SalesCommissionService commissions;

    SalesBillingReports(SalesDb db, SalesCommissionService commissions) {
        this.db = db;
        this.commissions = commissions;
    }

    @Override
    public List<Def> definitions() {
        List<Def> d = new ArrayList<>();
        List<String> inv = with(DOC, "channel");
        // ---- invoices
        d.add(def("invoice-register", INV, "Sales Invoice Register", "All sales invoices issued in the period.",
                with(inv, "status"), f -> invoices(f, " AND d.status NOT IN ('draft', 'cancelled')"
                        + (f.status() == null ? "" : " AND d.\"paymentStatus\"::text = '" + safe(f.status()) + "'"),
                        true, "register")));
        d.add(def("paid-invoices", INV, "Paid Invoices", "Fully paid invoices.", inv,
                f -> invoices(f, " AND d.status NOT IN ('draft', 'cancelled') AND d.\"paymentStatus\" IN ('paid',"
                        + " 'overpaid')", true, "register")));
        d.add(def("unpaid-invoices", INV, "Unpaid Invoices", "Invoices with no payment yet.", inv,
                f -> invoices(f, " AND d.status = 'posted' AND d.\"paymentStatus\" = 'not_paid'", true, "open")));
        d.add(def("partially-paid-invoices", INV, "Partially Paid Invoices", "Invoices with an outstanding balance"
                + " after part payment.", inv, f -> invoices(f, " AND d.status = 'posted' AND d.\"paymentStatus\" ="
                        + " 'partial'", true, "open")));
        d.add(def("overdue-invoices", INV, "Overdue Invoices", "Open invoices past their due date.",
                with(SNAPSHOT, "asOf", "channel"), f -> invoices(f, " AND d.status = 'posted' AND d.\"amountResidual\" > 0"
                        + " AND d.\"dueDate\"::date < '" + f.asOf() + "'", false, "overdue")));
        d.add(def("credit-sales", INV, "Credit Sales", "Invoices sold on credit terms (not paid at sale).", inv,
                f -> invoices(f, " AND d.status NOT IN ('draft', 'cancelled') AND (d.\"paymentMode\" IS NULL OR"
                        + " d.\"paymentMode\" = 'credit') AND ss.id IS NULL", true, "register")));
        d.add(def("cash-sales", INV, "Cash Sales", "Sales paid at the time of sale (cash, card, mobile money, branch"
                + " cash sales).", inv, f -> invoices(f, " AND d.status NOT IN ('draft', 'cancelled') AND"
                        + " (d.\"paymentMode\" IN ('cash', 'card', 'mobile_money', 'mixed') OR ss.id IS NOT NULL)", true,
                        "register")));
        d.add(def("invoice-aging", INV, "Invoice Aging", "Outstanding invoices by customer in aging buckets.",
                with(SNAPSHOT, "asOf"), this::aging));
        d.add(def("cancelled-invoices", INV, "Cancelled Invoices", "Cancelled or voided invoices.", DOC,
                f -> invoices(f, " AND (d.status = 'cancelled' OR d.\"cancelledAt\" IS NOT NULL)", true, "cancelled")));
        d.add(def("sales-tax", INV, "Sales Tax Report", "Taxable sales and tax by tax rate (credit notes deducted).",
                DOC, this::salesTax));
        d.add(def("invoice-due-dates", INV, "Invoice Due Date Report", "Open invoices falling due within N days"
                + " (default 14).", with(SNAPSHOT, "days", "asOf"), f -> invoices(f, " AND d.status = 'posted' AND"
                        + " d.\"amountResidual\" > 0 AND d.\"dueDate\"::date >= '" + f.asOf() + "' AND d.\"dueDate\"::date"
                        + " <= '" + f.asOf().plusDays(f.days(14)) + "'", false, "due")));
        d.add(def("billing-summary", INV, "Sales Billing Summary", "Invoiced, credited, paid and outstanding by period.",
                with(DOC, "groupBy"), this::billingSummary));
        d.add(def("credit-notes", INV, "Credit Note Register", "Credit notes issued to customers.", DOC,
                this::creditNotes));
        // ---- collections
        d.add(def("collection-summary", COL, "Sales Collection Summary", "Receipts by period and payment method.",
                with(PAYMENT_FILTERS, "groupBy"), f -> collections(f, Bucket.of(f.groupBy(), Bucket.MONTH))));
        d.add(def("daily-collections", COL, "Daily Collection Report", "Receipts per day by payment method.",
                PAYMENT_FILTERS, f -> collections(f, Bucket.DAY)));
        d.add(def("customer-payments", COL, "Customer Payment History", "Every receipt from customers.",
                PAYMENT_FILTERS, f -> payments(f, " AND pm.direction = 'inbound'", "register")));
        d.add(def("payment-methods", COL, "Payment Method Analysis", "Receipts by cash, bank, mobile money and other"
                + " methods.", PAYMENT_FILTERS, this::methods));
        d.add(def("outstanding-balances", COL, "Outstanding Customer Balances",
                "Open invoices, unapplied credits and payments and the net amount each customer owes.",
                List.of("branch", "salesperson", "customer", "customerCategory", "asOf"), this::balances));
        d.add(def("customer-receivables", COL, "Customer Receivables",
                "Invoiced and collected in the period, outstanding and overdue now, per customer.",
                List.of("period", "branch", "salesperson", "customer", "customerCategory"), this::receivables));
        d.add(def("collection-performance", COL, "Collection Performance",
                "Amounts falling due vs collected per period and the collection rate.",
                List.of("period", "branch", "customer", "groupBy"), this::collectionPerformance));
        d.add(def("advance-payments", COL, "Advance Payments", "Customer deposits and advances with their applied and"
                + " remaining balance.", List.of("period", "branch", "customer"), this::deposits));
        d.add(def("unallocated-payments", COL, "Unallocated Customer Payments", "Receipts not yet allocated to"
                + " invoices.", PAYMENT_FILTERS, f -> payments(f, " AND pm.direction = 'inbound' AND"
                        + " pm.\"unallocatedAmount\" > 0", "unallocated")));
        d.add(def("refunds", COL, "Refund Report", "Refunds paid to customers.", PAYMENT_FILTERS,
                f -> payments(f, " AND pm.direction = 'outbound' AND (pm.\"refundOfId\" IS NOT NULL OR p.\"isCustomer\")",
                        "refund")));
        d.add(def("payment-allocations", COL, "Payment Allocation Report", "Receipts allocated against invoices and"
                + " the days the customer took to pay.", PAYMENT_FILTERS, this::allocations));
        d.add(def("salesperson-collections", TEAM, "Salesperson Collection Performance",
                "Customer payments allocated to each representative's invoices.", List.of("period", "branch",
                        "salesperson"), this::collectionsBySalesperson));
        d.add(def("commissions", TEAM, "Sales Commission Report", "Commission earned under the approved rules.",
                Permissions.SALES__COMMISSION_READ, List.of("period", "salesperson"), this::commissionReport));
        // ---- discounts & returns
        d.add(def("discount-approvals", DISC, "Discount Approval Report",
                "Orders and quotations that needed approval, with their maximum discount.", DOC, this::approvals));
        d.add(def("return-register", DISC, "Sales Returns Register", "All customer returns.", with(DOC, "status"),
                this::returnRegister));
        d.add(def("returns-by-product", DISC, "Sales Return by Product", "Returned quantities per product.", DOC,
                f -> returnGroup(f, "product", "Product")));
        d.add(def("returns-by-customer", DISC, "Sales Return by Customer", "Returns per customer.", DOC,
                f -> returnGroup(f, "customer", "Customer")));
        d.add(def("return-reasons", DISC, "Sales Return Reasons", "Returns by reason (wrong grade, damaged, short"
                + " quantity, …).", DOC, f -> returnGroup(f, "reasonLabel", "Reason")));
        d.add(def("return-value", DISC, "Sales Return Value", "Credit-note value of returns by period.",
                with(DOC, "groupBy"), this::returnValue));
        return d;
    }

    static final List<String> PAYMENT_FILTERS = List.of("period", "branch", "salesperson", "customer",
            "customerCategory");

    private static String safe(String v) {
        return v.replaceAll("[^a-z_]", "");
    }

    // ------------------------------------------------------------------ invoices

    List<Map<String, Object>> invoiceRows(Filter f, String type, String where, boolean period) {
        Sql s = new Sql(INVOICES, org(), type);
        SalesReportKit.apply(s, f, INVOICE_SCOPE, period);
        if (f.channel() != null) {
            s.add(" AND CASE WHEN ss.id IS NOT NULL THEN 'branch_cash' WHEN so.id IS NOT NULL THEN 'sales_order' WHEN"
                    + " d.\"tableId\" IS NOT NULL OR d.\"sourceType\" LIKE 'pos%' THEN 'pos' ELSE 'direct' END = ?",
                    f.channel());
        }
        s.add(where == null ? "" : where).add(" ORDER BY d.\"issueDate\" DESC, d.\"documentNumber\" DESC LIMIT 3000");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        for (Map<String, Object> r : rows) {
            Long od = SalesReportKit.daysBetween(r.get("dueDate"), f.asOf());
            r.put("daysOverdue", od != null && od > 0 && dec(r.get("amountResidual")).signum() > 0 ? od : null);
            r.put("daysToDue", SalesReportKit.daysBetween(f.asOf(), r.get("dueDate")));
            r.put("channelLabel", SalesReportFacts.CHANNELS.get(str(r.get("channel"))));
            route(r, ("credit_note".equals(type) ? "/credit-notes/" : "/invoices/") + r.get("id"));
        }
        return rows;
    }

    Report invoices(Filter f, String where, boolean period, String variant) {
        List<Map<String, Object>> rows = invoiceRows(f, "sales_invoice", where, period);
        List<String> spec = new ArrayList<>(List.of("number|Invoice|text", "date|Date|date", "customer|Customer|text",
                "salesperson|Salesperson|text", "branch|Branch|text"));
        switch (variant) {
            case "overdue" -> spec.addAll(List.of("dueDate|Due|date", "daysOverdue|Days overdue|days",
                    "totalAmount|Total|money", "amountPaid|Paid|money", "amountResidual|Outstanding|money"));
            case "due" -> spec.addAll(List.of("dueDate|Due|date", "daysToDue|Days to due|days",
                    "totalAmount|Total|money", "amountResidual|Outstanding|money"));
            case "cancelled" -> spec.addAll(List.of("cancelledOn|Cancelled|date", "status|Status|status",
                    "totalAmount|Total|money", "notes|Notes|text"));
            default -> spec.addAll(List.of("dueDate|Due|date", "channelLabel|Channel|text",
                    "paymentStatus|Payment|status", "subtotal|Net|money", "taxAmount|Tax|money",
                    "totalAmount|Total|money", "amountPaid|Paid|money", "amountResidual|Outstanding|money"));
        }
        Report rep = table(rows, spec.toArray(String[]::new)).sumTotals("subtotal", "taxAmount", "totalAmount",
                "amountPaid", "amountResidual");
        rep.total("number", "Total");
        rep.kpi("count", "Invoices", rows.size(), "count").kpi("total", "Invoiced", rep.totals.get("totalAmount"),
                "money").kpi("paid", "Paid", rep.totals.get("amountPaid"), "money")
                .kpi("outstanding", "Outstanding", rep.totals.get("amountResidual"), "money");
        if ("overdue".equals(variant)) {
            rep.kpi("avgLate", "Avg days overdue", SalesDocumentReports.avg(rows, "daysOverdue"), "days");
            List<Map<String, Object>> byCustomer = SalesReportKit.group(rows, r -> r.get("customer"), "amountResidual");
            byCustomer.sort(SalesReportKit.byDesc("amountResidual"));
            rep.chart("hbar", "label", bar("amountResidual", "Overdue")).chartData(byCustomer.subList(0,
                    Math.min(15, byCustomer.size())));
        } else {
            List<Map<String, Object>> byStatus = SalesReportKit.group(rows, r -> SalesReportKit.label(str(r.get(
                    "paymentStatus"))), "totalAmount", "amountResidual");
            rep.chart("pie", "label", bar("totalAmount", "Invoiced")).chartData(byStatus);
        }
        return rep;
    }

    Report creditNotes(Filter f) {
        List<Map<String, Object>> rows = invoiceRows(f, "credit_note", " AND d.status NOT IN ('draft', 'cancelled')",
                true);
        Map<String, String> originals = new java.util.HashMap<>();
        for (Map<String, Object> r : db.rows("SELECT c.id, o.\"documentNumber\" AS original, sr.number AS ret FROM"
                + " \"Document\" c LEFT JOIN \"Document\" o ON o.id = c.\"reversedDocumentId\" LEFT JOIN \"SalesReturn\" sr ON"
                + " sr.\"creditNoteId\" = c.id WHERE c.\"organizationId\" = ? AND c.\"documentType\" = 'credit_note'",
                org())) {
            originals.put(str(r.get("id")), SalesReportFacts.fmt(r.get("original")) + (r.get("ret") == null ? ""
                    : " · return " + r.get("ret")));
        }
        rows.forEach(r -> r.put("against", originals.get(str(r.get("id")))));
        Report rep = table(rows, "number|Credit note|text", "date|Date|date", "customer|Customer|text",
                "against|Against|text", "salesperson|Salesperson|text", "subtotal|Net|money", "taxAmount|Tax|money",
                "totalAmount|Total|money", "amountResidual|Unapplied|money", "notes|Reason / notes|text")
                .sumTotals("subtotal", "taxAmount", "totalAmount", "amountResidual");
        rep.total("number", "Total");
        rep.kpi("count", "Credit notes", rows.size(), "count").kpi("value", "Credited", rep.totals.get("totalAmount"),
                "money").kpi("unapplied", "Not yet applied", rep.totals.get("amountResidual"), "money");
        rep.chart("bar", "label", bar("value", "Credited")).chartData(monthly(rows, f, "totalAmount"));
        return rep;
    }

    /** Monthly sums of {@code key} by the row {@code date}. */
    static List<Map<String, Object>> monthly(List<Map<String, Object>> rows, Filter f, String key) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : Bucket.MONTH.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", k, "value", 0, "count", 0));
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> b = m.get(String.valueOf(r.get("date")).substring(0, 7));
            if (b != null) {
                b.put("value", money(dec(b.get("value")).add(dec(r.get(key)))));
                b.put("count", ((Number) b.get("count")).intValue() + 1);
            }
        }
        return new ArrayList<>(m.values());
    }

    Report aging(Filter f) {
        List<Map<String, Object>> rows = invoiceRows(f, "sales_invoice", " AND d.status = 'posted' AND"
                + " d.\"amountResidual\" > 0", false);
        String[] buckets = {"current", "d1_30", "d31_60", "d61_90", "d90"};
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> c = g.computeIfAbsent(String.valueOf(r.get("partnerId")), k -> {
                Map<String, Object> n = Obj.of("partnerId", r.get("partnerId"), "customer", r.get("customer"),
                        "invoices", 0, "total", 0);
                for (String b : buckets) {
                    n.put(b, 0);
                }
                return n;
            });
            Long od = SalesReportKit.daysBetween(r.get("dueDate"), f.asOf());
            long late = od == null ? 0 : od;
            String b = late <= 0 ? "current" : late <= 30 ? "d1_30" : late <= 60 ? "d31_60" : late <= 90 ? "d61_90"
                    : "d90";
            BigDecimal v = dec(r.get("amountResidual"));
            c.put(b, money(dec(c.get(b)).add(v)));
            c.put("total", money(dec(c.get("total")).add(v)));
            c.put("invoices", ((Number) c.get("invoices")).intValue() + 1);
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        out.forEach(r -> {
            r.put("overduePercent", pct(dec(r.get("total")).subtract(dec(r.get("current"))), dec(r.get("total"))));
            SalesReportKit.drill(r, "customer-history", "partnerId", r.get("partnerId"));
        });
        out.sort(SalesReportKit.byDesc("total"));
        Report rep = table(out, "customer|Customer|text", "invoices|Invoices|count", "current|Current / not due|money",
                "d1_30|1–30 days|money", "d31_60|31–60 days|money", "d61_90|61–90 days|money",
                "d90|Over 90 days|money", "total|Outstanding|money", "overduePercent|Overdue %|percent")
                .sumTotals("invoices", "current", "d1_30", "d31_60", "d61_90", "d90", "total");
        rep.total("customer", "Total");
        rep.total("overduePercent", pct(dec(rep.totals.get("total")).subtract(dec(rep.totals.get("current"))),
                dec(rep.totals.get("total"))));
        rep.kpi("total", "Outstanding", rep.totals.get("total"), "money")
                .kpi("current", "Not yet due", rep.totals.get("current"), "money")
                .kpi("overdue", "Overdue", money(dec(rep.totals.get("total")).subtract(dec(rep.totals.get("current")))),
                        "money", SalesReportFacts.fmt(rep.totals.get("overduePercent")) + "% of balance")
                .kpi("d90", "Over 90 days", rep.totals.get("d90"), "money");
        List<Map<String, Object>> data = new ArrayList<>();
        String[] labels = {"Current", "1–30", "31–60", "61–90", "90+"};
        for (int i = 0; i < buckets.length; i++) {
            data.add(Obj.of("label", labels[i], "value", rep.totals.get(buckets[i])));
        }
        rep.chart("bar", "label", bar("value", "Outstanding")).chartData(data);
        rep.note("Aged by due date as of " + f.asOf() + ".");
        return rep;
    }

    Report salesTax(Filter f) {
        Sql s = new Sql("""
                SELECT COALESCE(t.name, 'No tax') AS tax, t.rate, t.code,
                       sum(CASE WHEN d."documentType" = 'credit_note' THEN -l.subtotal ELSE l.subtotal END) AS base,
                       sum(CASE WHEN d."documentType" = 'credit_note' THEN -COALESCE(l."taxAmount", 0)
                                ELSE COALESCE(l."taxAmount", 0) END) AS tax_amount,
                       count(DISTINCT d.id) AS documents
                  FROM "DocumentLine" l JOIN "Document" d ON d.id = l."documentId"
                  LEFT JOIN "Tax" t ON t.id = l."taxId"
                  LEFT JOIN "Partner" p ON p.id = d."partnerId"
                  LEFT JOIN "SalesOrder" so ON d."sourceType" = 'sales_order' AND so.id = d."sourceId"
                 WHERE d."organizationId" = ? AND d."documentType" IN ('sales_invoice', 'credit_note')
                   AND d.status IN ('posted', 'paid')""", org());
        SalesReportKit.apply(s, f, INVOICE_SCOPE, true);
        s.add(" GROUP BY 1, 2, 3 ORDER BY 4 DESC");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        rows.forEach(r -> {
            r.put("taxAmount", money(dec(r.get("tax_amount"))));
            r.remove("tax_amount");
            r.put("base", money(dec(r.get("base"))));
            r.put("gross", money(dec(r.get("base")).add(dec(r.get("taxAmount")))));
            r.put("effective", pct(dec(r.get("taxAmount")), dec(r.get("base"))));
        });
        Report rep = table(rows, "tax|Tax|text", "code|Code|text", "rate|Rate %|percent", "documents|Documents|count",
                "base|Taxable sales|money", "taxAmount|Tax|money", "gross|Sales incl. tax|money",
                "effective|Effective rate %|percent").sumTotals("base", "taxAmount", "gross");
        rep.total("tax", "Total");
        rep.kpi("base", "Taxable sales", rep.totals.get("base"), "money")
                .kpi("tax", "Output tax", rep.totals.get("taxAmount"), "money")
                .kpi("effective", "Effective rate", pct(dec(rep.totals.get("taxAmount")), dec(rep.totals.get("base"))),
                        "percent");
        rep.chart("pie", "tax", bar("taxAmount", "Tax"));
        rep.note("Operational view of the sales documents; the tax return comes from Accounting → Tax.");
        return rep;
    }

    Report billingSummary(Filter f) {
        Bucket b = Bucket.of(f.groupBy(), Bucket.MONTH);
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : b.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", b.label(k), "invoices", 0, "invoiced", 0, "credited", 0, "paid", 0,
                    "outstanding", 0));
        }
        for (Map<String, Object> r : invoiceRows(f, "sales_invoice", " AND d.status NOT IN ('draft', 'cancelled')",
                true)) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x != null) {
                x.put("invoices", ((Number) x.get("invoices")).intValue() + 1);
                x.put("invoiced", money(dec(x.get("invoiced")).add(dec(r.get("totalAmount")))));
                x.put("paid", money(dec(x.get("paid")).add(dec(r.get("amountPaid")))));
                x.put("outstanding", money(dec(x.get("outstanding")).add(dec(r.get("amountResidual")))));
            }
        }
        for (Map<String, Object> r : invoiceRows(f, "credit_note", " AND d.status NOT IN ('draft', 'cancelled')",
                true)) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x != null) {
                x.put("credited", money(dec(x.get("credited")).add(dec(r.get("totalAmount")))));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(m.values());
        out.forEach(r -> {
            r.put("net", money(dec(r.get("invoiced")).subtract(dec(r.get("credited")))));
            r.put("collected", pct(dec(r.get("paid")), dec(r.get("invoiced"))));
        });
        Report rep = table(out, "label|Period|text", "invoices|Invoices|count", "invoiced|Invoiced|money",
                "credited|Credit notes|money", "net|Net billed|money", "paid|Paid to date|money",
                "outstanding|Outstanding|money", "collected|Collected %|percent")
                .sumTotals("invoices", "invoiced", "credited", "net", "paid", "outstanding");
        rep.total("label", "Total");
        rep.total("collected", pct(dec(rep.totals.get("paid")), dec(rep.totals.get("invoiced"))));
        rep.kpi("invoiced", "Total invoiced", rep.totals.get("invoiced"), "money")
                .kpi("paid", "Paid", rep.totals.get("paid"), "money")
                .kpi("outstanding", "Outstanding", rep.totals.get("outstanding"), "money")
                .kpi("collected", "Collected", rep.totals.get("collected"), "percent");
        rep.chart("combo", "label", bar("invoiced", "Invoiced"), bar("paid", "Paid"), line("outstanding",
                "Outstanding"));
        return rep;
    }

    // ------------------------------------------------------------------ collections

    List<Map<String, Object>> paymentRows(Filter f, String where) {
        Sql s = new Sql(PAYMENTS, org());
        SalesReportKit.apply(s, f, PAYMENT_SCOPE, true);
        s.add(where).add(" ORDER BY pm.\"paymentDate\" DESC, pm.\"paymentNumber\" DESC LIMIT 3000");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        rows.forEach(r -> {
            r.put("methodLabel", methodLabel(str(r.get("method"))));
            route(r, "/payments/" + r.get("id"));
        });
        return rows;
    }

    static String methodLabel(String m) {
        if (m == null) {
            return "Other";
        }
        return switch (m) {
            case "cash" -> "Cash";
            case "bank", "bank_transfer", "transfer" -> "Bank";
            case "mobile_money", "momo", "mtn_momo", "airtel_money" -> "Mobile money";
            case "card" -> "Card";
            case "cheque", "check" -> "Cheque";
            default -> SalesReportKit.label(m);
        };
    }

    Report payments(Filter f, String where, String variant) {
        List<Map<String, Object>> rows = paymentRows(f, where);
        List<String> spec = new ArrayList<>(List.of("number|Receipt|text", "date|Date|date", "customer|Customer|text",
                "branch|Branch|text", "methodLabel|Method|text", "reference|Reference|text"));
        spec.addAll("unallocated".equals(variant) ? List.of("amount|Amount|money", "allocatedAmount|Allocated|money",
                "unallocatedAmount|Unallocated|money") : "refund".equals(variant) ? List.of("amount|Refunded|money",
                        "status|Status|status") : List.of("amount|Amount|money", "allocatedAmount|Allocated|money",
                                "unallocatedAmount|Unallocated|money", "status|Status|status"));
        Report rep = table(rows, spec.toArray(String[]::new)).sumTotals("amount", "allocatedAmount",
                "unallocatedAmount");
        rep.total("number", "Total");
        rep.kpi("count", "refund".equals(variant) ? "Refunds" : "Receipts", rows.size(), "count")
                .kpi("amount", "refund".equals(variant) ? "Refunded" : "Received", rep.totals.get("amount"), "money");
        if (!"refund".equals(variant)) {
            rep.kpi("unallocated", "Unallocated", rep.totals.get("unallocatedAmount"), "money");
        }
        rep.chart("bar", "label", bar("value", "Amount")).chartData(monthly(rows, f, "amount"));
        return rep;
    }

    Report collections(Filter f, Bucket b) {
        List<Map<String, Object>> rows = paymentRows(f, " AND pm.direction = 'inbound'");
        List<String> methods = rows.stream().map(r -> str(r.get("methodLabel"))).distinct().sorted().toList();
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : b.keys(f.from(), f.to())) {
            Map<String, Object> x = Obj.of("label", b.label(k), "receipts", 0, "total", 0);
            methods.forEach(me -> x.put("m_" + me, 0));
            m.put(k, x);
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x != null) {
                String k = "m_" + r.get("methodLabel");
                x.put(k, money(dec(x.get(k)).add(dec(r.get("amount")))));
                x.put("total", money(dec(x.get("total")).add(dec(r.get("amount")))));
                x.put("receipts", ((Number) x.get("receipts")).intValue() + 1);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(m.values());
        Report rep = new Report().col("label", "Period", "text").col("receipts", "Receipts", "count");
        List<SalesReportKit.Series> series = new ArrayList<>();
        for (String me : methods) {
            rep.col("m_" + me, me, "money");
            series.add(bar("m_" + me, me));
        }
        rep.col("total", "Total collected", "money").rows(out);
        List<String> sums = new ArrayList<>(methods.stream().map(me -> "m_" + me).toList());
        sums.add("total");
        sums.add("receipts");
        rep.sumTotals(sums.toArray(String[]::new));
        rep.total("label", "Total");
        rep.kpi("total", "Collected", rep.totals.get("total"), "money").kpi("receipts", "Receipts",
                rep.totals.get("receipts"), "count");
        int periods = Math.max(1, out.size());
        rep.kpi("avg", "Average per " + b.name().toLowerCase(java.util.Locale.ROOT), money(dec(rep.totals.get("total"))
                .divide(BigDecimal.valueOf(periods), 2, RoundingMode.HALF_UP)), "money");
        rep.chart = new SalesReportKit.Chart("bar", "label", series, true);
        return rep;
    }

    Report methods(Filter f) {
        List<Map<String, Object>> rows = paymentRows(f, " AND pm.direction = 'inbound'");
        List<Map<String, Object>> g = SalesReportKit.group(rows, r -> r.get("methodLabel"), "amount");
        BigDecimal total = SalesReportKit.sum(g, "amount");
        g.forEach(r -> {
            r.put("share", pct(dec(r.get("amount")), total));
            r.put("avg", money(dec(r.get("amount")).divide(dec(r.get("count")), 2, RoundingMode.HALF_UP)));
        });
        g.sort(SalesReportKit.byDesc("amount"));
        Report rep = table(g, "label|Method|text", "count|Receipts|count", "amount|Amount|money",
                "avg|Average receipt|money", "share|Share %|percent").sumTotals("count", "amount");
        rep.total("label", "Total");
        rep.kpi("amount", "Collected", rep.totals.get("amount"), "money").kpi("methods", "Methods used", g.size(),
                "count");
        if (!g.isEmpty()) {
            rep.kpi("top", "Most used", g.getFirst().get("share"), "percent", str(g.getFirst().get("label")));
        }
        rep.chart("pie", "label", bar("amount", "Amount"));
        return rep;
    }

    Report balances(Filter f) {
        Sql s = new Sql("""
                SELECT p.id AS "partnerId", p.name AS customer, p."creditLimit",
                       trim(u."firstName" || ' ' || COALESCE(u."lastName", '')) AS salesperson,
                       COALESCE((SELECT sum(d."amountResidual") FROM "Document" d WHERE d."organizationId" = p."organizationId"
                           AND d."partnerId" = p.id AND d."documentType" = 'sales_invoice' AND d.status = 'posted'), 0)
                           AS invoices,
                       COALESCE((SELECT sum(d."amountResidual") FROM "Document" d WHERE d."organizationId" = p."organizationId"
                           AND d."partnerId" = p.id AND d."documentType" = 'sales_invoice' AND d.status = 'posted'
                           AND d."dueDate"::date < ?), 0) AS overdue,
                       COALESCE((SELECT sum(d."amountResidual") FROM "Document" d WHERE d."organizationId" = p."organizationId"
                           AND d."partnerId" = p.id AND d."documentType" = 'credit_note' AND d.status = 'posted'), 0)
                           AS credits,
                       COALESCE((SELECT sum(pm."unallocatedAmount") FROM "Payment" pm WHERE
                           pm."organizationId" = p."organizationId" AND pm."partnerId" = p.id AND pm.direction = 'inbound'
                           AND pm."voidedAt" IS NULL AND pm.status NOT IN ('draft', 'cancelled')), 0) AS unallocated,
                       COALESCE((SELECT sum(cd.amount - cd."appliedAmount" - cd."refundedAmount") FROM "CustomerDeposit" cd
                           WHERE cd."organizationId" = p."organizationId" AND cd."partnerId" = p.id
                           AND cd.status = 'open'), 0) AS deposits
                  FROM "Partner" p LEFT JOIN "User" u ON u.id = p."salespersonId"
                 WHERE p."organizationId" = ? AND p."isCustomer" AND p."deletedAt" IS NULL""", f.asOf(), org());
        s.eq("p.\"salespersonId\"", f.salespersonId());
        s.eq("p.id", f.partnerId());
        s.eq("p.\"categoryId\"", f.customerCategoryId());
        if (!f.branchIds().isEmpty()) {
            s.add(" AND EXISTS (SELECT 1 FROM \"Document\" d WHERE d.\"partnerId\" = p.id AND d.\"branchId\" IN ("
                    + SalesDb.marks(f.branchIds().size()) + "))", f.branchIds().toArray());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : db.rows(s.toString(), s.params)) {
            BigDecimal net = dec(r.get("invoices")).subtract(dec(r.get("credits"))).subtract(dec(r.get("unallocated")))
                    .subtract(dec(r.get("deposits")));
            if (dec(r.get("invoices")).signum() == 0 && net.signum() == 0) {
                continue;
            }
            r.put("net", money(net));
            BigDecimal limit = dec(r.get("creditLimit"));
            r.put("limitUsed", limit.signum() == 0 ? null : pct(net, limit));
            rows.add(SalesReportKit.drill(r, "customer-history", "partnerId", r.get("partnerId")));
        }
        rows.sort(SalesReportKit.byDesc("net"));
        Report rep = table(rows, "customer|Customer|text", "salesperson|Salesperson|text",
                "invoices|Open invoices|money", "overdue|of which overdue|money", "credits|Unapplied credit notes|money",
                "unallocated|Unallocated receipts|money", "deposits|Deposits held|money", "net|Net owed|money",
                "creditLimit|Credit limit|money", "limitUsed|Limit used %|percent")
                .sumTotals("invoices", "overdue", "credits", "unallocated", "deposits", "net");
        rep.total("customer", "Total");
        rep.kpi("net", "Net owed by customers", rep.totals.get("net"), "money")
                .kpi("overdue", "Overdue", rep.totals.get("overdue"), "money")
                .kpi("customers", "Customers with a balance", rows.size(), "count")
                .kpi("credits", "Credits & deposits held", money(dec(rep.totals.get("credits"))
                        .add(dec(rep.totals.get("unallocated"))).add(dec(rep.totals.get("deposits")))), "money");
        rep.chart("hbar", "customer", bar("net", "Net owed"), bar("overdue", "Overdue"))
                .chartData(rows.subList(0, Math.min(15, rows.size())));
        rep.note("Balances as of now from the posted documents; Accounting → Receivables is the ledger authority.");
        return rep;
    }

    Report receivables(Filter f) {
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        for (Map<String, Object> r : invoiceRows(f, "sales_invoice", " AND d.status NOT IN ('draft', 'cancelled')",
                true)) {
            Map<String, Object> c = cust(g, r);
            c.put("invoiced", money(dec(c.get("invoiced")).add(dec(r.get("totalAmount")))));
            c.put("invoices", ((Number) c.get("invoices")).intValue() + 1);
        }
        Filter all = f.withPeriod(LocalDate.of(1970, 1, 1), f.to());
        for (Map<String, Object> r : invoiceRows(all, "sales_invoice", " AND d.status = 'posted' AND"
                + " d.\"amountResidual\" > 0", false)) {
            Map<String, Object> c = cust(g, r);
            c.put("outstanding", money(dec(c.get("outstanding")).add(dec(r.get("amountResidual")))));
            if (r.get("daysOverdue") != null) {
                c.put("overdue", money(dec(c.get("overdue")).add(dec(r.get("amountResidual")))));
            }
            String due = String.valueOf(r.get("dueDate"));
            if (c.get("oldestDue") == null || due.compareTo(String.valueOf(c.get("oldestDue"))) < 0) {
                c.put("oldestDue", r.get("dueDate"));
            }
        }
        Sql s = new Sql("SELECT pm.\"partnerId\", sum(pm.amount) AS amount FROM \"Payment\" pm LEFT JOIN \"Partner\" p ON"
                + " p.id = pm.\"partnerId\" WHERE pm.\"organizationId\" = ? AND pm.direction = 'inbound' AND"
                + " pm.\"voidedAt\" IS NULL AND pm.status NOT IN ('draft', 'cancelled')", org());
        SalesReportKit.apply(s, f, PAYMENT_SCOPE, true);
        s.add(" GROUP BY 1");
        for (Map<String, Object> r : db.rows(s.toString(), s.params)) {
            Map<String, Object> c = g.get(str(r.get("partnerId")));
            if (c != null) {
                c.put("collected", money(dec(r.get("amount"))));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        out.forEach(r -> {
            r.put("collectionRate", pct(dec(r.get("collected")), dec(r.get("invoiced"))));
            SalesReportKit.drill(r, "customer-history", "partnerId", r.get("partnerId"));
        });
        out.sort(SalesReportKit.byDesc("outstanding"));
        Report rep = table(out, "customer|Customer|text", "invoices|Invoices|count", "invoiced|Invoiced in period|money",
                "collected|Collected in period|money", "collectionRate|Collected %|percent",
                "outstanding|Outstanding now|money", "overdue|Overdue now|money", "oldestDue|Oldest due date|date")
                .sumTotals("invoices", "invoiced", "collected", "outstanding", "overdue");
        rep.total("customer", "Total");
        rep.kpi("invoiced", "Invoiced", rep.totals.get("invoiced"), "money")
                .kpi("collected", "Collected", rep.totals.get("collected"), "money")
                .kpi("outstanding", "Outstanding", rep.totals.get("outstanding"), "money")
                .kpi("overdue", "Overdue", rep.totals.get("overdue"), "money");
        rep.chart("hbar", "customer", bar("outstanding", "Outstanding"), bar("overdue", "Overdue"))
                .chartData(out.subList(0, Math.min(15, out.size())));
        return rep;
    }

    private static Map<String, Object> cust(Map<String, Map<String, Object>> g, Map<String, Object> r) {
        return g.computeIfAbsent(String.valueOf(r.get("partnerId")), k -> Obj.of("partnerId", r.get("partnerId"),
                "customer", r.get("customer"), "invoices", 0, "invoiced", 0, "collected", 0, "outstanding", 0,
                "overdue", 0, "oldestDue", null));
    }

    Report collectionPerformance(Filter f) {
        Bucket b = Bucket.of(f.groupBy(), Bucket.MONTH);
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : b.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", b.label(k), "due", 0, "collectedOnDue", 0, "collected", 0));
        }
        Sql due = new Sql("SELECT d.\"dueDate\"::date AS date, d.\"totalAmount\" AS amount, d.\"amountResidual\" AS open"
                + " FROM \"Document\" d LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" = ?"
                + " AND d.\"documentType\" = 'sales_invoice' AND d.status IN ('posted', 'paid') AND d.\"dueDate\"::date"
                + " >= ? AND d.\"dueDate\"::date <= ?", org(), f.from(), f.to());
        due.in("d.\"branchId\"", f.branchIds()).eq("d.\"partnerId\"", f.partnerId());
        for (Map<String, Object> r : db.rows(due.toString(), due.params)) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x != null) {
                x.put("due", money(dec(x.get("due")).add(dec(r.get("amount")))));
                x.put("collectedOnDue", money(dec(x.get("collectedOnDue")).add(dec(r.get("amount"))
                        .subtract(dec(r.get("open"))))));
            }
        }
        Sql paid = new Sql("SELECT a.\"createdAt\"::date AS date, a.amount - COALESCE(a.\"refundedAmount\", 0) AS amount"
                + " FROM \"PaymentAllocation\" a JOIN \"Document\" d ON d.id = COALESCE(a.\"documentId\", a.\"invoiceId\")"
                + " WHERE a.\"organizationId\" = ? AND d.\"documentType\" = 'sales_invoice' AND a.\"createdAt\" >= ? AND"
                + " a.\"createdAt\" < ?", org(), f.from(), f.to().plusDays(1));
        paid.in("d.\"branchId\"", f.branchIds()).eq("d.\"partnerId\"", f.partnerId());
        for (Map<String, Object> r : db.rows(paid.toString(), paid.params)) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x != null) {
                x.put("collected", money(dec(x.get("collected")).add(dec(r.get("amount")))));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(m.values());
        out.forEach(r -> r.put("rate", pct(dec(r.get("collectedOnDue")), dec(r.get("due")))));
        Report rep = table(out, "label|Period|text", "due|Falling due|money", "collectedOnDue|Of which paid|money",
                "rate|Collection rate %|percent", "collected|Allocated in period|money")
                .sumTotals("due", "collectedOnDue", "collected");
        rep.total("label", "Total");
        rep.total("rate", pct(dec(rep.totals.get("collectedOnDue")), dec(rep.totals.get("due"))));
        rep.kpi("due", "Falling due", rep.totals.get("due"), "money")
                .kpi("paid", "Paid of that", rep.totals.get("collectedOnDue"), "money")
                .kpi("rate", "Collection rate", rep.totals.get("rate"), "percent")
                .kpi("collected", "Allocated in period", rep.totals.get("collected"), "money");
        rep.chart("combo", "label", bar("due", "Falling due"), bar("collected", "Collected"), line("rate",
                "Collection rate %"));
        return rep;
    }

    Report deposits(Filter f) {
        Sql s = new Sql("""
                SELECT cd.id, cd.number, cd."receivedDate" AS date, p.name AS customer, so.number AS "order",
                       cd."paymentMethod" AS method, cd.amount, cd."appliedAmount", cd."refundedAmount",
                       cd.amount - cd."appliedAmount" - cd."refundedAmount" AS balance, cd.status, cd.source,
                       cd."salesOrderId"
                  FROM "CustomerDeposit" cd
                  LEFT JOIN "Partner" p ON p.id = cd."partnerId"
                  LEFT JOIN "SalesOrder" so ON so.id = cd."salesOrderId"
                 WHERE cd."organizationId" = ? AND cd."receivedDate" >= ? AND cd."receivedDate" <= ?""", org(), f.from(),
                f.to());
        s.in("cd.\"branchId\"", f.branchIds()).eq("cd.\"partnerId\"", f.partnerId());
        s.add(" ORDER BY cd.\"receivedDate\" DESC");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        rows.forEach(r -> {
            r.put("methodLabel", methodLabel(str(r.get("method"))));
            if (r.get("salesOrderId") != null) {
                route(r, "/sales/orders/" + r.get("salesOrderId"));
            }
        });
        Report rep = table(rows, "number|Deposit|text", "date|Received|date", "customer|Customer|text",
                "order|Sales order|text", "methodLabel|Method|text", "amount|Amount|money",
                "appliedAmount|Applied|money", "refundedAmount|Refunded|money", "balance|Balance held|money",
                "status|Status|status").sumTotals("amount", "appliedAmount", "refundedAmount", "balance");
        rep.total("number", "Total");
        rep.kpi("count", "Deposits", rows.size(), "count").kpi("amount", "Received", rep.totals.get("amount"), "money")
                .kpi("balance", "Still held", rep.totals.get("balance"), "money");
        rep.chart("bar", "label", bar("value", "Deposits")).chartData(monthly(rows, f, "amount"));
        return rep;
    }

    Report allocations(Filter f) {
        Sql s = new Sql("""
                SELECT a.id, pm.id AS "paymentId", pm."paymentNumber" AS payment, a."createdAt"::date AS date,
                       p.name AS customer, d."documentNumber" AS invoice, d."issueDate"::date AS "invoiceDate",
                       d."dueDate"::date AS "dueDate", a.amount, COALESCE(a."refundedAmount", 0) AS refunded
                  FROM "PaymentAllocation" a JOIN "Payment" pm ON pm.id = a."paymentId"
                  JOIN "Document" d ON d.id = COALESCE(a."documentId", a."invoiceId")
                  LEFT JOIN "Partner" p ON p.id = pm."partnerId"
                 WHERE a."organizationId" = ? AND pm.direction = 'inbound' AND d."documentType" = 'sales_invoice'
                   AND a."createdAt" >= ? AND a."createdAt" < ?""", org(), f.from(), f.to().plusDays(1));
        s.in("d.\"branchId\"", f.branchIds()).eq("pm.\"partnerId\"", f.partnerId())
                .eq("p.\"salespersonId\"", f.salespersonId()).eq("p.\"categoryId\"", f.customerCategoryId());
        s.add(" ORDER BY a.\"createdAt\" DESC LIMIT 3000");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        rows.forEach(r -> {
            r.put("daysToPay", SalesReportKit.daysBetween(r.get("invoiceDate"), r.get("date")));
            Long late = SalesReportKit.daysBetween(r.get("dueDate"), r.get("date"));
            r.put("daysLate", late != null && late > 0 ? late : null);
            route(r, "/payments/" + r.get("paymentId"));
        });
        Report rep = table(rows, "date|Allocated|date", "payment|Receipt|text", "customer|Customer|text",
                "invoice|Invoice|text", "invoiceDate|Invoice date|date", "dueDate|Due|date", "amount|Allocated|money",
                "daysToPay|Days to pay|days", "daysLate|Days late|days").sumTotals("amount");
        rep.total("date", "Total");
        rep.kpi("count", "Allocations", rows.size(), "count").kpi("amount", "Allocated", rep.totals.get("amount"),
                "money").kpi("days", "Avg days to pay", SalesDocumentReports.avg(rows, "daysToPay"), "days")
                .kpi("late", "Paid late", rows.stream().filter(r -> r.get("daysLate") != null).count(), "count");
        rep.chart("bar", "label", bar("value", "Allocated")).chartData(monthly(rows, f, "amount"));
        return rep;
    }

    Report collectionsBySalesperson(Filter f) {
        Sql s = new Sql("SELECT " + SP + " AS sp, sum(a.amount - COALESCE(a.\"refundedAmount\", 0)) AS collected,"
                + " count(DISTINCT d.id) AS invoices, count(DISTINCT d.\"partnerId\") AS customers FROM"
                + " \"PaymentAllocation\" a JOIN \"Document\" d ON d.id = COALESCE(a.\"documentId\", a.\"invoiceId\") LEFT"
                + " JOIN \"Partner\" p ON p.id = d.\"partnerId\" LEFT JOIN \"SalesOrder\" so ON d.\"sourceType\" ="
                + " 'sales_order' AND so.id = d.\"sourceId\" WHERE a.\"organizationId\" = ? AND d.\"documentType\" ="
                + " 'sales_invoice' AND a.\"createdAt\" >= ? AND a.\"createdAt\" < ?", org(), f.from(),
                f.to().plusDays(1));
        s.in("d.\"branchId\"", f.branchIds()).eq(SP, f.salespersonId());
        s.add(" GROUP BY 1");
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows(s.toString(), s.params)) {
            g.put(String.valueOf(r.get("sp")), Obj.of("sp", r.get("sp"), "collected", money(dec(r.get("collected"))),
                    "invoices", r.get("invoices"), "customers", r.get("customers"), "outstanding", 0, "overdue", 0));
        }
        Sql o = new Sql("SELECT " + SP + " AS sp, sum(d.\"amountResidual\") AS outstanding, sum(d.\"amountResidual\")"
                + " FILTER (WHERE d.\"dueDate\" < CURRENT_DATE) AS overdue FROM \"Document\" d LEFT JOIN \"Partner\" p ON"
                + " p.id = d.\"partnerId\" LEFT JOIN \"SalesOrder\" so ON d.\"sourceType\" = 'sales_order' AND so.id ="
                + " d.\"sourceId\" WHERE d.\"organizationId\" = ? AND d.\"documentType\" = 'sales_invoice' AND d.status ="
                + " 'posted' AND d.\"amountResidual\" > 0", org());
        o.in("d.\"branchId\"", f.branchIds()).eq(SP, f.salespersonId());
        o.add(" GROUP BY 1");
        for (Map<String, Object> r : db.rows(o.toString(), o.params)) {
            Map<String, Object> x = g.computeIfAbsent(String.valueOf(r.get("sp")), k -> Obj.of("sp", r.get("sp"),
                    "collected", 0, "invoices", 0, "customers", 0));
            x.put("outstanding", money(dec(r.get("outstanding"))));
            x.put("overdue", money(dec(r.get("overdue"))));
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        Map<String, String> names = new java.util.HashMap<>();
        List<String> ids = out.stream().map(r -> str(r.get("sp"))).filter(i -> i != null).toList();
        if (!ids.isEmpty()) {
            List<Object> p = new ArrayList<>();
            p.add(org());
            p.addAll(ids);
            for (Map<String, Object> u : db.rows("SELECT id, trim(\"firstName\" || ' ' || COALESCE(\"lastName\", '')) AS n"
                    + " FROM \"User\" WHERE \"organizationId\" = ? AND id IN (" + SalesDb.marks(ids.size()) + ")", p)) {
                names.put(str(u.get("id")), str(u.get("n")));
            }
        }
        out.forEach(r -> {
            r.put("salesperson", r.get("sp") == null ? "(none)" : names.getOrDefault(str(r.get("sp")), str(r.get("sp"))));
            BigDecimal base = dec(r.get("collected")).add(dec(r.get("outstanding")));
            r.put("rate", pct(dec(r.get("collected")), base));
        });
        out.sort(SalesReportKit.byDesc("collected"));
        Report rep = table(out, "salesperson|Salesperson|text", "invoices|Invoices paid|count",
                "customers|Customers|count", "collected|Collected|money", "outstanding|Still outstanding|money",
                "overdue|Overdue|money", "rate|Collected share %|percent")
                .sumTotals("collected", "outstanding", "overdue");
        rep.total("salesperson", "Total");
        rep.kpi("collected", "Collected", rep.totals.get("collected"), "money")
                .kpi("outstanding", "Outstanding", rep.totals.get("outstanding"), "money")
                .kpi("overdue", "Overdue", rep.totals.get("overdue"), "money");
        rep.chart("bar", "salesperson", bar("collected", "Collected"), bar("overdue", "Overdue"));
        return rep;
    }

    Report commissionReport(Filter f) {
        List<Map<String, Object>> lines = commissions.compute(f.salespersonId(), f.from(), f.to());
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            Map<String, Object> x = g.computeIfAbsent(String.valueOf(l.get("salespersonId")), k -> Obj.of("sp",
                    l.get("salespersonId"), "lines", 0, "revenue", 0, "margin", 0, "amount", 0));
            x.put("lines", ((Number) x.get("lines")).intValue() + 1);
            x.put("revenue", money(dec(x.get("revenue")).add(dec(l.get("revenue")))));
            x.put("margin", money(dec(x.get("margin")).add(dec(l.get("margin")))));
            x.put("amount", money(dec(x.get("amount")).add(dec(l.get("amount")))));
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        Map<String, String> names = new java.util.HashMap<>();
        List<String> ids = out.stream().map(r -> str(r.get("sp"))).filter(i -> i != null && !"null".equals(i)).toList();
        if (!ids.isEmpty()) {
            List<Object> p = new ArrayList<>();
            p.add(org());
            p.addAll(ids);
            for (Map<String, Object> u : db.rows("SELECT id, trim(\"firstName\" || ' ' || COALESCE(\"lastName\", '')) AS n"
                    + " FROM \"User\" WHERE \"organizationId\" = ? AND id IN (" + SalesDb.marks(ids.size()) + ")", p)) {
                names.put(str(u.get("id")), str(u.get("n")));
            }
        }
        out.forEach(r -> {
            r.put("salesperson", names.getOrDefault(str(r.get("sp")), "(none)"));
            r.put("effective", pct(dec(r.get("amount")), dec(r.get("revenue"))));
        });
        out.sort(SalesReportKit.byDesc("amount"));
        Report rep = table(out, "salesperson|Salesperson|text", "lines|Commissionable lines|count",
                "revenue|Revenue|money", "margin|Margin|money", "amount|Commission|money",
                "effective|Commission / revenue %|percent").sumTotals("lines", "revenue", "margin", "amount");
        rep.total("salesperson", "Total");
        rep.kpi("amount", "Commission earned", rep.totals.get("amount"), "money")
                .kpi("revenue", "Commissionable revenue", rep.totals.get("revenue"), "money")
                .kpi("people", "Earners", out.size(), "count");
        rep.chart("bar", "salesperson", bar("amount", "Commission"));
        rep.note("Computed live from the active commission rules; approved statements are under Commissions & Targets.");
        return rep;
    }

    // ------------------------------------------------------------------ approvals & returns

    Report approvals(Filter f) {
        Sql s = new Sql("""
                SELECT * FROM (
                  SELECT o.id, 'Sales order' AS kind, o.number, COALESCE(o."confirmedAt", o."createdAt")::date AS date,
                         p.name AS customer, trim(u."firstName" || ' ' || COALESCE(u."lastName", '')) AS salesperson,
                         o.status, o."maxDiscountPercent", array_to_string(o."approvalReasons", ', ') AS reasons, o.total,
                         '/sales/orders/' || o.id AS route, o."branchId", o."ownerId", o."partnerId", p."categoryId"
                    FROM "SalesOrder" o LEFT JOIN "Partner" p ON p.id = o."partnerId" LEFT JOIN "User" u ON u.id = o."ownerId"
                   WHERE o."organizationId" = ? AND o."deletedAt" IS NULL AND cardinality(o."approvalReasons") > 0
                  UNION ALL
                  SELECT q.id, 'Quotation', q.number, q."issueDate", p.name,
                         trim(u."firstName" || ' ' || COALESCE(u."lastName", '')), q.status, q."maxDiscountPercent",
                         array_to_string(q."approvalReasons", ', '), q.total, '/crm/quotations/' || q.id, q."branchId",
                         q."ownerId", q."partnerId", p."categoryId"
                    FROM "SalesQuotation" q LEFT JOIN "Partner" p ON p.id = q."partnerId" LEFT JOIN "User" u ON u.id = q."ownerId"
                   WHERE q."organizationId" = ? AND q."deletedAt" IS NULL AND cardinality(q."approvalReasons") > 0
                ) x WHERE x.date >= ? AND x.date <= ?""", org(), org(), f.from(), f.to());
        s.in("x.\"branchId\"", f.branchIds()).eq("x.\"ownerId\"", f.salespersonId()).eq("x.\"partnerId\"",
                f.partnerId()).eq("x.\"categoryId\"", f.customerCategoryId());
        s.add(" ORDER BY x.date DESC");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        rows.forEach(r -> {
            r.put("_route", r.remove("route"));
            String st = str(r.get("status"));
            r.put("approval", List.of("pending_approval").contains(st) ? "pending" : List.of("rejected", "cancelled")
                    .contains(st) ? "rejected" : "draft".equals(st) ? "draft" : "approved");
        });
        Report rep = table(rows, "kind|Document|status", "number|Number|text", "date|Date|date",
                "customer|Customer|text", "salesperson|Salesperson|text", "maxDiscountPercent|Max discount %|percent",
                "reasons|Approval reasons|text", "approval|Approval|status", "total|Value|money").sumTotals("total");
        rep.total("kind", "Total");
        rep.kpi("count", "Needed approval", rows.size(), "count")
                .kpi("pending", "Awaiting approval", rows.stream().filter(r -> "pending".equals(r.get("approval")))
                        .count(), "count")
                .kpi("approved", "Approved", rows.stream().filter(r -> "approved".equals(r.get("approval"))).count(),
                        "count")
                .kpi("maxDisc", "Highest discount", rows.stream().map(r -> dec(r.get("maxDiscountPercent")))
                        .max(BigDecimal::compareTo).map(SalesReportKit::num).orElse(null), "percent");
        rep.chart("pie", "label", bar("count", "Documents")).chartData(SalesReportKit.group(rows, r -> SalesReportKit
                .label(str(r.get("approval")))));
        return rep;
    }

    List<Map<String, Object>> returnRows(Filter f) {
        Sql s = new Sql("""
                SELECT r.id, r.number, r."createdAt"::date AS date, p.name AS customer, o.number AS "order", r.reason,
                       r.status, r.outcome, r."physicalReturn", cn."documentNumber" AS "creditNote",
                       COALESCE(cn."totalAmount", 0) AS value, rl.qty, rl.received, rl.credited,
                       rl.product, rl.lines
                  FROM "SalesReturn" r
                  LEFT JOIN "Partner" p ON p.id = r."partnerId"
                  LEFT JOIN "SalesOrder" o ON o.id = r."orderId"
                  LEFT JOIN "Document" cn ON cn.id = r."creditNoteId"
                  LEFT JOIN LATERAL (SELECT sum(l.quantity) AS qty, sum(COALESCE(l."receivedQty", 0)) AS received,
                                            sum(COALESCE(l."creditQty", 0)) AS credited, count(*) AS lines,
                                            min(COALESCE(pr.name, l.description)) AS product
                         FROM "SalesReturnLine" l LEFT JOIN "Product" pr ON pr.id = l."productId"
                        WHERE l."returnId" = r.id) rl ON true
                 WHERE r."organizationId" = ? AND r."createdAt" >= ? AND r."createdAt" < ?""", org(), f.from(),
                f.to().plusDays(1));
        s.in("o.\"branchId\"", f.branchIds()).eq("o.\"ownerId\"", f.salespersonId()).eq("r.\"partnerId\"",
                f.partnerId()).eq("p.\"categoryId\"", f.customerCategoryId()).eq("r.status", f.status());
        if (f.productId() != null) {
            s.add(" AND EXISTS (SELECT 1 FROM \"SalesReturnLine\" x WHERE x.\"returnId\" = r.id AND x.\"productId\" = ?)",
                    f.productId());
        }
        s.add(" ORDER BY r.\"createdAt\" DESC");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        rows.forEach(r -> {
            r.put("reasonLabel", SalesReportKit.label(str(r.get("reason"))));
            route(r, "/sales/returns/" + r.get("id"));
        });
        return rows;
    }

    Report returnRegister(Filter f) {
        List<Map<String, Object>> rows = returnRows(f);
        Report rep = table(rows, "number|Return|text", "date|Date|date", "customer|Customer|text",
                "order|Sales order|text", "product|Product|text", "reasonLabel|Reason|text", "qty|Qty|qty",
                "received|Received|qty", "status|Status|status", "outcome|Outcome|status",
                "creditNote|Credit note|text", "value|Credited|money").sumTotals("qty", "received", "value");
        rep.total("number", "Total");
        rep.kpi("count", "Returns", rows.size(), "count").kpi("value", "Credited", rep.totals.get("value"), "money")
                .kpi("open", "Still open", rows.stream().filter(r -> !List.of("closed", "rejected", "cancelled")
                        .contains(r.get("status"))).count(), "count");
        rep.chart("pie", "label", bar("count", "Returns")).chartData(SalesReportKit.group(rows,
                r -> r.get("reasonLabel")));
        return rep;
    }

    Report returnGroup(Filter f, String key, String title) {
        List<Map<String, Object>> rows = returnRows(f);
        if ("product".equals(key)) {
            Sql s = new Sql("""
                    SELECT COALESCE(pr.name, l.description) AS label, count(DISTINCT r.id) AS count,
                           sum(l.quantity) AS qty, sum(COALESCE(l."receivedQty", 0)) AS received,
                           sum(COALESCE(l."creditQty", 0)) AS credited
                      FROM "SalesReturnLine" l JOIN "SalesReturn" r ON r.id = l."returnId"
                      LEFT JOIN "Product" pr ON pr.id = l."productId"
                      LEFT JOIN "SalesOrder" o ON o.id = r."orderId"
                     WHERE r."organizationId" = ? AND r."createdAt" >= ? AND r."createdAt" < ?""", org(), f.from(),
                    f.to().plusDays(1));
            s.in("o.\"branchId\"", f.branchIds()).eq("r.\"partnerId\"", f.partnerId()).eq("l.\"productId\"",
                    f.productId());
            s.add(" GROUP BY 1 ORDER BY 3 DESC");
            List<Map<String, Object>> g = db.rows(s.toString(), s.params);
            Report rep = table(g, "label|Product|text", "count|Returns|count", "qty|Qty returned|qty",
                    "received|Received back|qty", "credited|Credited qty|qty").sumTotals("count", "qty", "received",
                            "credited");
            rep.total("label", "Total");
            rep.kpi("products", "Products returned", g.size(), "count").kpi("qty", "Qty returned", rep.totals.get(
                    "qty"), "qty");
            rep.chart("hbar", "label", bar("qty", "Qty returned"));
            return rep;
        }
        List<Map<String, Object>> g = SalesReportKit.group(rows, r -> r.get(key), "qty", "value");
        BigDecimal total = SalesReportKit.sum(g, "count");
        g.forEach(r -> r.put("share", pct(dec(r.get("count")), total)));
        g.sort(SalesReportKit.byDesc("count"));
        Report rep = table(g, "label|" + title + "|text", "count|Returns|count", "qty|Qty|qty",
                "value|Credited|money", "share|Share %|percent").sumTotals("count", "qty", "value");
        rep.total("label", "Total");
        rep.kpi("count", "Returns", rep.totals.get("count"), "count").kpi("value", "Credited", rep.totals.get("value"),
                "money");
        if (!g.isEmpty()) {
            rep.kpi("top", "Most frequent", g.getFirst().get("share"), "percent", str(g.getFirst().get("label")));
        }
        rep.chart("reasonLabel".equals(key) ? "pie" : "hbar", "label", bar("count", "Returns"));
        return rep;
    }

    Report returnValue(Filter f) {
        Bucket b = Bucket.of(f.groupBy(), Bucket.MONTH);
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : b.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", b.label(k), "returns", 0, "credited", 0, "invoiced", 0));
        }
        for (Map<String, Object> r : returnRows(f)) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x != null) {
                x.put("returns", ((Number) x.get("returns")).intValue() + 1);
            }
        }
        for (Map<String, Object> r : invoiceRows(f, "credit_note", " AND d.status NOT IN ('draft', 'cancelled')",
                true)) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x != null) {
                x.put("credited", money(dec(x.get("credited")).add(dec(r.get("subtotal")))));
            }
        }
        for (Map<String, Object> r : invoiceRows(f, "sales_invoice", " AND d.status NOT IN ('draft', 'cancelled')",
                true)) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x != null) {
                x.put("invoiced", money(dec(x.get("invoiced")).add(dec(r.get("subtotal")))));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(m.values());
        out.forEach(r -> r.put("rate", pct(dec(r.get("credited")), dec(r.get("invoiced")))));
        Report rep = table(out, "label|Period|text", "returns|Returns logged|count", "credited|Credit notes (net)|money",
                "invoiced|Invoiced (net)|money", "rate|Returns % of sales|percent")
                .sumTotals("returns", "credited", "invoiced");
        rep.total("label", "Total");
        rep.total("rate", pct(dec(rep.totals.get("credited")), dec(rep.totals.get("invoiced"))));
        rep.kpi("credited", "Credited", rep.totals.get("credited"), "money")
                .kpi("rate", "Returns % of sales", rep.totals.get("rate"), "percent")
                .kpi("returns", "Returns logged", rep.totals.get("returns"), "count");
        rep.chart("combo", "label", bar("credited", "Credited"), line("rate", "% of sales"));
        return rep;
    }
}
