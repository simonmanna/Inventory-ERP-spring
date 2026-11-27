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
 *
 * <p>Finance audit A5 (P2-5): the base residual of a foreign-currency document uses its carrying rate, the rate its own
 * journal entry was posted at, when the document stores no stated rate (the default 1 of documents created through the
 * API, FX-1), as payments settle it ({@code invoicing.DocumentFxRates}); an item's currency is its code (it was always
 * null: {@code currencyId} holds the code, not a {@code Currency} id), null for the base currency; and a payment counts
 * as unapplied on a side only when its entry posted the partner's leg to that side's control accounts (a customer's
 * credit-note refund is no unapplied supplier payment).
 */
@Component("reportingOpenItems")
public class OpenItems {

    /**
     * One open item; {@code residual} is positive for amounts owed (a credit note is negative), in the document's
     * currency; {@code baseResidual} is the same at the document's rate, in base currency (what the control account
     * carries), so totals across currencies add up (review 2026-11-15, R-9).
     */
    public record Item(String id, String kind, String number, String partnerId, String partnerName,
            LocalDate issueDate, LocalDate dueDate, BigDecimal total, BigDecimal residual, String currency,
            String branchId, String route, BigDecimal baseResidual) {}

    private final JdbcClient jdbc;

    public OpenItems(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static final String DOC_RESIDUAL_AT = "(d.\"totalAmount\" - coalesce((SELECT sum(pa.amount) FROM"
            + " \"PaymentAllocation\" pa JOIN \"Payment\" p ON p.id = pa.\"paymentId\" WHERE pa.\"documentId\" = d.id"
            + " AND p.\"paymentDate\" < ? AND p.status::text <> 'cancelled' AND (p.\"voidedAt\" IS NULL OR p.\"voidedAt\""
            + " >= ?)), 0) - coalesce((SELECT sum(da.amount) FROM \"DepositApplication\" da WHERE da.\"documentId\" ="
            + " d.id AND da.\"createdAt\" < ? AND (da.\"reversedAt\" IS NULL OR da.\"reversedAt\" >= ?)), 0))";
    /**
     * Finance audit A5: a document's currency code, null for the organisation's base currency. {@code currencyId} holds
     * the code ({@code USD}); a {@code Currency} id is read through the join for older rows.
     */
    static String currencyOf(String alias) {
        return com.inventoryerp.accounting.application.ApOpenItemsSql.currencyOf(alias);
    }

    /**
     * Finance audit A5: the rate a document's base residual is measured at, its carrying rate as
     * {@code invoicing.DocumentFxRates} reads it. A base-currency document: 1. A foreign-currency document with a stated
     * rate (anything but 1): that rate, the one it posted at. A foreign-currency document storing the default 1
     * ("not stated", FX-1) posted at the rate table's rate, so the rate of its own journal entry: the entry's rate
     * snapshot (its own pair first), else Σ base ÷ Σ transaction amount of its lines.
     */
    static String carryingRate(String alias) {
        return com.inventoryerp.accounting.application.ApOpenItemsSql.carryingRate(alias);
    }

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
                + " d.\"dueDate\", d.\"totalAmount\" AS total, " + residual + " AS residual,"
                + " " + currencyOf("d") + " AS currency, " + carryingRate("d") + " AS rate,"
                + " d.\"branchId\" FROM \"Document\" d JOIN \"Organization\" org ON org.id = d.\"organizationId\""
                + " LEFT JOIN \"Partner\" pt ON pt.id = d.\"partnerId\" LEFT JOIN"
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
                    + " AS residual, " + currencyOf("i") + " AS currency, " + carryingRate("i") + " AS rate,"
                    + " i.\"branchId\""
                    + " FROM \"Invoice\" i JOIN \"Organization\" org ON org.id = i.\"organizationId\" LEFT JOIN \"Partner\" pt ON"
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
                (String) r.get("branchId"), route, r.get("rate") == null ? residual
                        : residual.multiply(SqlRows.dec(r, "rate")).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    static String route(String kind, String id) {
        return switch (kind) {
            case "sales_invoice", "debit_note" -> "/invoices/" + id;
            case "credit_note" -> "/credit-notes/" + id;
            case "vendor_bill" -> "/vendor-bills/" + id;
            default -> null;
        };
    }

    /**
     * Payables raised by purchase-order receipts (same measure as the AP aging, purchase readiness B2: stocked lines
     * only, with the receipts' rate in {@code rate}); two organization parameters.
     */
    static final String RECEIPT_VOUCHER_PAYABLES = com.inventoryerp.accounting.application.ApOpenItemsSql
            .RECEIPT_VOUCHER_PAYABLES;

    /**
     * partnerId → payments not yet allocated at the date ({@code inbound} for customers), in base currency. Finance
     * audit A5: only payments whose journal entry posted the partner's leg to the side's control accounts (receivable
     * for inbound, payable for outbound) count, or that have no entry: a customer's credit-note refund (outbound,
     * against the receivable) or a supplier refund (inbound, against the payable) is no unapplied payment of the other
     * side, and neither is a payment posted to an advance or deposit account.
     */
    public Map<String, BigDecimal> unapplied(ReportContext ctx, String direction, LocalDate asOf) {
        Timestamp end = SqlRows.ts(ctx.endExclusive(asOf));
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        String control = "inbound".equals(direction) ? "receivable" : "payable";
        List<Object> p = new ArrayList<>(List.of(end, ctx.organizationId(), direction, end, end, control));
        String partners = "";
        if (!ctx.request().partnerIds().isEmpty()) {
            partners = " AND p.\"partnerId\" = ANY(?::text[])";
            p.add(ctx.request().partnerIds().toArray(String[]::new));
        }
        if (!ctx.request().branchIds().isEmpty()) {
            partners += " AND p.\"branchId\" = ANY(?::text[])";
            p.add(ctx.request().branchIds().toArray(String[]::new));
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT p.\"partnerId\", sum((p.amount - coalesce((SELECT"
                + " sum(pa.amount) FROM \"PaymentAllocation\" pa WHERE pa.\"paymentId\" = p.id AND pa.\"createdAt\" < ?),"
                + " 0)) * coalesce(p.\"exchangeRate\", 1)) AS open FROM \"Payment\" p WHERE p.\"organizationId\" = ? AND p.direction::text = ? AND"
                + " p.\"paymentDate\" < ? AND p.status::text <> 'cancelled' AND (p.\"voidedAt\" IS NULL OR p.\"voidedAt\""
                + " >= ?) AND p.\"refundOfId\" IS NULL AND (p.\"journalEntryId\" IS NULL OR EXISTS (SELECT 1 FROM"
                + " \"JournalLine\" jl JOIN \"Account\" ja ON ja.id = jl.\"accountId\" JOIN \"AccountCategory\" jc ON"
                + " jc.id = ja.\"categoryId\" WHERE jl.\"journalEntryId\" = p.\"journalEntryId\" AND jc.key = ? AND"
                + " jl.\"partnerId\" IS NOT DISTINCT FROM p.\"partnerId\"))" + partners
                + " GROUP BY p.\"partnerId\"").params(p))) {
            BigDecimal v = SqlRows.dec(r, "open");
            if (v.signum() > 0) {
                out.put(r.get("partnerId") == null ? "" : (String) r.get("partnerId"), v);
            }
        }
        return out;
    }
}
