package com.inventoryerp.reporting.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.reporting.spi.ReportContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Open receivable and payable items at a date. Receivables: posted sales invoices and debit notes, credit notes as
 * negative items, and POS invoices. Payables: posted vendor bills and receipt-voucher payables (the same measure as
 * the AP aging, P1-7). At today or later the stored residual is used; before today the residual is rebuilt as total
 * less payment allocations and deposit applications made up to the date (a voided payment counts until it was voided).
 * Receipt-voucher payables are always current.
 */
@Component("reportingOpenItems")
public class OpenItems {

    /** One open item; {@code residual} is positive for amounts owed (a credit note is negative). */
    public record Item(String id, String kind, String number, String partnerId, String partnerName,
            LocalDate issueDate, LocalDate dueDate, BigDecimal total, BigDecimal residual, String currency,
            String branchId, String route) {}

    private final JdbcClient jdbc;

    public OpenItems(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static final String DOC_RESIDUAL_AT = "(d.\"totalAmount\" - coalesce((SELECT sum(pa.amount) FROM"
            + " \"PaymentAllocation\" pa JOIN \"Payment\" p ON p.id = pa.\"paymentId\" WHERE pa.\"documentId\" = d.id"
            + " AND p.\"paymentDate\" < ? AND p.status::text <> 'cancelled' AND (p.\"voidedAt\" IS NULL OR p.\"voidedAt\""
            + " >= ?)), 0) - coalesce((SELECT sum(da.amount) FROM \"DepositApplication\" da WHERE da.\"documentId\" ="
            + " d.id AND da.\"createdAt\" < ? AND (da.\"reversedAt\" IS NULL OR da.\"reversedAt\" >= ?)), 0))";
    static final String INV_RESIDUAL_AT = "(i.\"totalAmount\" - coalesce((SELECT sum(pa.amount) FROM"
            + " \"PaymentAllocation\" pa JOIN \"Payment\" p ON p.id = pa.\"paymentId\" WHERE pa.\"invoiceId\" = i.id"
            + " AND p.\"paymentDate\" < ? AND p.status::text <> 'cancelled' AND (p.\"voidedAt\" IS NULL OR p.\"voidedAt\""
            + " >= ?)), 0))";

    /** Open items of one side ({@code AR} or {@code AP}) at the context's as-of date, with partner / branch filters. */
    public List<Item> at(ReportContext ctx, String side, LocalDate asOf) {
        boolean current = !asOf.isBefore(ctx.today());
        Timestamp end = SqlRows.ts(ctx.endExclusive(asOf));
        List<Item> out = new ArrayList<>();
        String partners = ctx.request().partnerIds().isEmpty() ? "" : " AND d.\"partnerId\" = ANY(?::text[])";
        String branches = ctx.request().branchIds().isEmpty() ? "" : " AND d.\"branchId\" = ANY(?::text[])";
        String types = "AR".equals(side) ? "('sales_invoice', 'debit_note', 'credit_note')" : "('vendor_bill')";
        String residual = current ? "d.\"amountResidual\"" : DOC_RESIDUAL_AT;
        List<Object> p = new ArrayList<>();
        if (!current) {
            p.addAll(List.of(end, end, end, end));
        }
        p.add(ctx.organizationId());
        p.add(end);
        addFilters(ctx, p);
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT d.id, d.\"documentType\"::text AS kind,"
                + " d.\"documentNumber\" AS number, d.\"partnerId\", pt.name AS \"partnerName\", d.\"issueDate\","
                + " d.\"dueDate\", d.\"totalAmount\" AS total, " + residual + " AS residual, c.code AS currency,"
                + " d.\"branchId\" FROM \"Document\" d LEFT JOIN \"Partner\" pt ON pt.id = d.\"partnerId\" LEFT JOIN"
                + " \"Currency\" c ON c.id = d.\"currencyId\" WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text"
                + " IN " + types + " AND d.status::text IN ('posted', 'paid') AND d.\"issueDate\" < ?" + partners
                + branches).params(p))) {
            BigDecimal res = SqlRows.dec(r, "residual");
            if (res.abs().compareTo(new BigDecimal("0.005")) < 0) {
                continue;
            }
            String kind = (String) r.get("kind");
            boolean credit = "credit_note".equals(kind);
            out.add(item(ctx, r, kind, credit ? res.abs().negate() : res, route(kind, (String) r.get("id"))));
        }
        if ("AR".equals(side)) {
            String ip = partners.replace("d.", "i.");
            String ib = branches.replace("d.", "i.");
            List<Object> q = new ArrayList<>();
            if (!current) {
                q.addAll(List.of(end, end));
            }
            q.add(ctx.organizationId());
            q.add(end);
            addFilters(ctx, q);
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT i.id, 'pos_invoice' AS kind, i.\"invoiceNumber\""
                    + " AS number, i.\"partnerId\", pt.name AS \"partnerName\", i.\"issueDate\", i.\"dueDate\","
                    + " i.\"totalAmount\" AS total, " + (current ? "i.\"amountResidual\"" : INV_RESIDUAL_AT)
                    + " AS residual, c.code AS currency, i.\"branchId\" FROM \"Invoice\" i LEFT JOIN \"Partner\" pt ON"
                    + " pt.id = i.\"partnerId\" LEFT JOIN \"Currency\" c ON c.id = i.\"currencyId\" WHERE"
                    + " i.\"organizationId\" = ? AND i.status::text IN ('posted', 'paid') AND i.\"issueDate\" < ?" + ip
                    + ib).params(q))) {
                BigDecimal res = SqlRows.dec(r, "residual");
                if (res.abs().compareTo(new BigDecimal("0.005")) >= 0) {
                    out.add(item(ctx, r, "pos_invoice", res, null));
                }
            }
        } else if (ctx.request().branchIds().isEmpty()) {
            List<Object> q = new ArrayList<>(List.of(ctx.organizationId(), ctx.organizationId()));
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM (" + RECEIPT_VOUCHER_PAYABLES + ") rv"
                    + (ctx.request().partnerIds().isEmpty() ? "" : " WHERE rv.\"partnerId\" = ANY(?::text[])"))
                    .params(ctx.request().partnerIds().isEmpty() ? q : append(q, ctx.request().partnerIds().toArray(
                            String[]::new))))) {
                r.put("id", "PO:" + r.get("po"));
                r.put("number", r.get("orderNumber"));
                r.put("total", r.get("amountResidual"));
                r.put("residual", r.get("amountResidual"));
                out.add(item(ctx, r, "receipt_voucher", SqlRows.dec(r, "amountResidual"), "/procurement/purchase-orders/"
                        + r.get("po")));
            }
        }
        out.sort((a, b) -> {
            int c = String.valueOf(a.partnerName()).compareToIgnoreCase(String.valueOf(b.partnerName()));
            return c != 0 ? c : String.valueOf(a.dueDate()).compareTo(String.valueOf(b.dueDate()));
        });
        return out;
    }

    private static List<Object> append(List<Object> l, Object o) {
        List<Object> out = new ArrayList<>(l);
        out.add(o);
        return out;
    }

    private static void addFilters(ReportContext ctx, List<Object> p) {
        if (!ctx.request().partnerIds().isEmpty()) {
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        if (!ctx.request().branchIds().isEmpty()) {
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
    }

    private static Item item(ReportContext ctx, Map<String, Object> r, String kind, BigDecimal residual,
            String route) {
        String issue = Tables.day(r.get("issueDate"), ctx.zone());
        String due = Tables.day(r.get("dueDate"), ctx.zone());
        return new Item((String) r.get("id"), kind, (String) r.get("number"), (String) r.get("partnerId"),
                r.get("partnerName") == null ? "(no partner)" : (String) r.get("partnerName"), issue == null ? null
                        : LocalDate.parse(issue), due == null ? (issue == null ? null : LocalDate.parse(issue))
                        : LocalDate.parse(due), SqlRows.dec(r, "total"), residual, (String) r.get("currency"),
                (String) r.get("branchId"), route);
    }

    static String route(String kind, String id) {
        return switch (kind) {
            case "sales_invoice", "debit_note" -> "/invoices/" + id;
            case "credit_note" -> "/credit-notes/" + id;
            case "vendor_bill" -> "/vendor-bills/" + id;
            default -> null;
        };
    }

    /** Payables raised by purchase-order receipts (same measure as the AP aging); two organization parameters. */
    static final String RECEIPT_VOUCHER_PAYABLES = "SELECT po.id AS po, po.\"orderNumber\", po.\"partnerId\","
            + " p.name AS \"partnerName\", v.at AS \"dueDate\", v.at AS \"issueDate\", NULL::text AS currency,"
            + " NULL::text AS \"branchId\", LEAST(po.\"totalAmount\", v.amt) - GREATEST(COALESCE(pp.paid, 0),"
            + " COALESCE(po.\"totalPaid\", 0)) AS \"amountResidual\" FROM (SELECT g.\"purchaseOrderId\" AS po,"
            + " MIN(g.\"receivedAt\") AS at, SUM(l.quantity * l.\"unitCost\" * (1 + COALESCE(pl.\"taxRate\", 0) / 100))"
            + " AS amt FROM \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON g.id = l.\"goodsReceiptId\""
            + " LEFT JOIN \"PurchaseOrderLine\" pl ON pl.id = l.\"purchaseOrderLineId\" WHERE g.\"organizationId\" = ?"
            + " AND g.status::text = 'posted' AND g.\"deletedAt\" IS NULL AND l.\"productId\" IS NOT NULL"
            + " AND g.\"purchaseOrderId\" IS NOT NULL GROUP BY g.\"purchaseOrderId\") v"
            + " JOIN \"PurchaseOrder\" po ON po.id = v.po LEFT JOIN \"Partner\" p ON p.id = po.\"partnerId\""
            + " LEFT JOIN (SELECT \"purchaseOrderId\", SUM(amount) AS paid FROM \"PurchasePayment\" WHERE"
            + " \"organizationId\" = ? GROUP BY \"purchaseOrderId\") pp ON pp.\"purchaseOrderId\" = po.id"
            + " WHERE po.status::text <> 'cancelled' AND LEAST(po.\"totalAmount\", v.amt) - GREATEST(COALESCE(pp.paid, 0),"
            + " COALESCE(po.\"totalPaid\", 0)) > 0.005";

    /** partnerId → payments not yet allocated at the date ({@code inbound} for customers). */
    public Map<String, BigDecimal> unapplied(ReportContext ctx, String direction, LocalDate asOf) {
        Timestamp end = SqlRows.ts(ctx.endExclusive(asOf));
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        List<Object> p = new ArrayList<>(List.of(end, ctx.organizationId(), direction, end, end));
        String partners = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            partners = " AND p.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        if (!ctx.request().branchIds().isEmpty()) {
            partners += " AND p.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT p.\"partnerId\", sum(p.amount - coalesce((SELECT"
                + " sum(pa.amount) FROM \"PaymentAllocation\" pa WHERE pa.\"paymentId\" = p.id AND pa.\"createdAt\" < ?),"
                + " 0)) AS open FROM \"Payment\" p WHERE p.\"organizationId\" = ? AND p.direction::text = ? AND"
                + " p.\"paymentDate\" < ? AND p.status::text <> 'cancelled' AND (p.\"voidedAt\" IS NULL OR p.\"voidedAt\""
                + " >= ?) AND p.\"refundOfId\" IS NULL" + partners + " GROUP BY p.\"partnerId\"").params(p))) {
            BigDecimal v = SqlRows.dec(r, "open");
            if (v.signum() > 0) {
                out.put(r.get("partnerId") == null ? "" : (String) r.get("partnerId"), v);
            }
        }
        return out;
    }
}
