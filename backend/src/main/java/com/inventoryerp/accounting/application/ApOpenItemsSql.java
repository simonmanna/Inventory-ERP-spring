package com.inventoryerp.accounting.application;

/**
 * One measure of what is owed to suppliers (purchase readiness fix B2, owner-approved 2026-11-27), shared by the AP
 * aging ({@code invoicing.ApAgingService}), its nightly snapshot ({@link ReportSnapshotService}), the AP open items of
 * the reports ({@code reporting.OpenItems}) and the supplier-overpayment cap ({@code invoicing.PaymentService}): open
 * vendor bills at their carrying rate, open receipt-voucher payables at the receipts' rate, less unapplied supplier
 * payments at their payment rate — the finance audit A5 tie-out measure, in base currency, which ties to the payable
 * control accounts per partner.
 */
public final class ApOpenItemsSql {

    private ApOpenItemsSql() {}

    /**
     * A document's currency code, null for the organisation's base currency. Needs {@code JOIN "Organization" org} and
     * {@code LEFT JOIN "Currency" c ON c.id = <alias>."currencyId"} ({@code currencyId} holds the code; a
     * {@code Currency} id is read through the join for older rows).
     */
    public static String currencyOf(String alias) {
        return "nullif(upper(coalesce(c.code, nullif(trim(" + alias + ".\"currencyId\"), ''))), upper(org.\"currencyCode\"))";
    }

    /**
     * The rate a document's base residual is measured at, its carrying rate ({@code invoicing.DocumentFxRates}): 1 for a
     * base-currency document; a stated rate (anything but 1) as stored; the default 1 ("not stated", FX-1) at the rate
     * of the document's own journal entry (its rate snapshot, else Σ base ÷ Σ transaction amount of its lines). Same
     * joins as {@link #currencyOf}.
     */
    public static String carryingRate(String alias) {
        String entry = alias + ".\"journalEntryId\"";
        return "(CASE WHEN " + currencyOf(alias) + " IS NULL THEN 1 WHEN coalesce(" + alias + ".\"exchangeRate\", 1) <> 1"
                + " OR " + entry + " IS NULL THEN coalesce(" + alias + ".\"exchangeRate\", 1) ELSE coalesce((SELECT r.rate"
                + " FROM \"JournalEntryFxRate\" r JOIN \"JournalEntry\" je ON je.id = r.\"journalEntryId\" WHERE"
                + " r.\"journalEntryId\" = " + entry + " ORDER BY (upper(r.\"fromCode\") = upper(coalesce(je.\"currencyId\","
                + " ''))) DESC, r.\"createdAt\" LIMIT 1), (SELECT sum(jl.\"baseDebit\" + jl.\"baseCredit\")"
                + " / nullif(sum(jl.debit + jl.credit), 0) FROM \"JournalLine\" jl WHERE jl.\"journalEntryId\" = " + entry
                + "), 1) END)";
    }

    /** Receipt-priced value of a GRN line incl. the PO line's tax, over {@code GoodsReceiptLine l} / {@code pl}. */
    private static final String LINE_VALUE = "l.quantity * l.\"unitCost\" * (1 + COALESCE(pl.\"taxRate\", 0) / 100)";

    /**
     * Payables raised by purchase-order receipts (receipt vouchers, P1-7) that no supplier bill has taken over yet:
     * unbilled receipts less the PO payments not applied to a bill, aged from the first receipt (ACR-2: a billed receipt
     * is the bill's open item). Only the lines a receipt vouchers count: inventory-tracked goods, not services (the
     * {@code STOCKED_SQL} predicate of the PO pay route and of {@code GoodsReceiptsService.post}); a received service
     * line raises no AP until its bill. {@code amountResidual} is in the PO currency; {@code rate} is the receipts'
     * value-weighted rate (the PO rate before any receipt rate was recorded, 1 for a functional-currency PO), so
     * {@code amountResidual × rate} is what AP carries. Columns: po, orderNumber, partnerId, partnerName, dueDate,
     * issueDate, currency, branchId, amountResidual, rate. Two organisation parameters.
     */
    public static final String RECEIPT_VOUCHER_PAYABLES = "SELECT po.id AS po, po.\"orderNumber\", po.\"partnerId\","
            + " p.name AS \"partnerName\", v.at AS \"dueDate\", v.at AS \"issueDate\", nullif(upper(po.\"currencyCode\"),"
            + " upper(org.\"currencyCode\")) AS currency, NULL::text AS \"branchId\", LEAST(po.\"totalAmount\", v.amt) -"
            + " v.billed - (GREATEST(COALESCE(pp.paid, 0), COALESCE(po.\"totalPaid\", 0)) - COALESCE(ap.applied, 0)) AS"
            + " \"amountResidual\", (CASE WHEN po.\"currencyCode\" IS NULL OR org.\"currencyCode\" IS NULL OR"
            + " upper(po.\"currencyCode\") = upper(org.\"currencyCode\") THEN 1 ELSE coalesce(v.rate, CASE WHEN"
            + " po.\"exchangeRate\" > 0 THEN po.\"exchangeRate\" ELSE 1 END) END) AS rate FROM (SELECT"
            + " g.\"purchaseOrderId\" AS po, MIN(g.\"receivedAt\") AS at, SUM(" + LINE_VALUE + ") AS amt,"
            + " SUM(LEAST(l.\"billedQuantity\", l.quantity) * l.\"unitCost\" * (1 + COALESCE(pl.\"taxRate\", 0) / 100))"
            + " AS billed, SUM(CASE WHEN g.\"exchangeRate\" IS NOT NULL THEN l.quantity * l.\"unitCost\" *"
            + " g.\"exchangeRate\" END) / nullif(SUM(CASE WHEN g.\"exchangeRate\" IS NOT NULL THEN l.quantity *"
            + " l.\"unitCost\" END), 0) AS rate FROM \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON g.id ="
            + " l.\"goodsReceiptId\" JOIN \"Product\" pr ON pr.id = l.\"productId\" AND pr.\"organizationId\" ="
            + " g.\"organizationId\" LEFT JOIN \"PurchaseOrderLine\" pl ON pl.id = l.\"purchaseOrderLineId\" WHERE"
            + " g.\"organizationId\" = ? AND g.status::text = 'posted' AND g.\"deletedAt\" IS NULL AND"
            + " g.\"purchaseOrderId\" IS NOT NULL AND pr.\"trackInventory\" AND COALESCE(pr.\"productType\"::text, '') <>"
            + " 'service' GROUP BY g.\"purchaseOrderId\") v JOIN \"PurchaseOrder\" po ON po.id = v.po JOIN"
            + " \"Organization\" org ON org.id = po.\"organizationId\" LEFT JOIN \"Partner\" p ON p.id = po.\"partnerId\""
            + " LEFT JOIN (SELECT \"purchaseOrderId\", SUM(amount) AS paid FROM \"PurchasePayment\" WHERE"
            + " \"organizationId\" = ? GROUP BY \"purchaseOrderId\") pp ON pp.\"purchaseOrderId\" = po.id LEFT JOIN LATERAL"
            + " (SELECT SUM(a.amount) AS applied FROM \"PurchaseOrderBillApplication\" a WHERE a.\"organizationId\" ="
            + " po.\"organizationId\" AND a.\"purchaseOrderId\" = po.id AND a.\"releasedAt\" IS NULL) ap ON true WHERE"
            + " po.status::text <> 'cancelled' AND LEAST(po.\"totalAmount\", v.amt) - v.billed - (GREATEST(COALESCE(pp.paid,"
            + " 0), COALESCE(po.\"totalPaid\", 0)) - COALESCE(ap.applied, 0)) > 0.005";

    /**
     * Open posted vendor bills: id, partnerId, partnerName, dueDate, issueDate, currency (null = base), residual
     * (document currency), rate (carrying), base (residual × rate, 2 dp). One organisation parameter.
     */
    public static final String OPEN_BILLS = "SELECT d.id, d.\"partnerId\", pt.name AS \"partnerName\", d.\"dueDate\","
            + " d.\"issueDate\", " + currencyOf("d") + " AS currency, d.\"amountResidual\" AS residual, "
            + carryingRate("d") + " AS rate, round(d.\"amountResidual\" * " + carryingRate("d") + ", 2) AS base FROM"
            + " \"Document\" d JOIN \"Organization\" org ON org.id = d.\"organizationId\" LEFT JOIN \"Partner\" pt ON"
            + " pt.id = d.\"partnerId\" LEFT JOIN \"Currency\" c ON c.id = d.\"currencyId\" WHERE d.\"organizationId\" = ?"
            + " AND d.\"documentType\"::text = 'vendor_bill' AND d.status::text IN ('posted', 'paid') AND"
            + " d.\"amountResidual\" > 0";

    /**
     * Unapplied supplier payments now, per partner and payment currency: posted, not voided, not a refund, and posted
     * with the partner's leg on a payable control account (finance audit A5: a customer refund is no supplier payment).
     * Columns: partnerId, currency (null = base), open (payment currency), base (at the payment rate). One organisation
     * parameter.
     */
    public static final String UNAPPLIED_SUPPLIER_PAYMENTS = "SELECT p.\"partnerId\", nullif(upper(nullif(trim("
            + "p.\"currencyId\"), '')), upper(org.\"currencyCode\")) AS currency, sum(p.\"unallocatedAmount\") AS open,"
            + " sum(p.\"unallocatedAmount\" * coalesce(p.\"exchangeRate\", 1)) AS base FROM \"Payment\" p JOIN"
            + " \"Organization\" org ON org.id = p.\"organizationId\" WHERE p.\"organizationId\" = ? AND"
            + " p.direction::text = 'outbound' AND p.status::text <> 'cancelled' AND p.\"voidedAt\" IS NULL AND"
            + " p.\"refundOfId\" IS NULL AND p.\"unallocatedAmount\" > 0 AND (p.\"journalEntryId\" IS NULL OR EXISTS"
            + " (SELECT 1 FROM \"JournalLine\" jl JOIN \"Account\" ja ON ja.id = jl.\"accountId\" JOIN \"AccountCategory\""
            + " jc ON jc.id = ja.\"categoryId\" WHERE jl.\"journalEntryId\" = p.\"journalEntryId\" AND jc.key = 'payable'"
            + " AND jl.\"partnerId\" IS NOT DISTINCT FROM p.\"partnerId\")) GROUP BY 1, 2";
}
