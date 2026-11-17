package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The journal sources a customer / supplier sub-ledger owns (owner decision 2026-11-17, "document reversals"): an entry
 * posted by an invoice, bill, credit / debit note, payment, deposit, POS sale, supplier claim, goods-receipt voucher,
 * opening-balance batch or supplier-side expense is reversed by that document's own workflow, never by the generic
 * {@code POST /journal-entries/{id}/reverse} — a bare GL reversal would leave the document posted with its residual,
 * allocations and partner balance out of step with the control account.
 *
 * <p>The internal {@link PostingService#reverse} stays unrestricted: it is what those document workflows call. Only
 * user-facing generic reversal routes ({@link JournalEntryService#reverse}, the {@code journal_entry} workflow) consult
 * {@link #assertGenericReversible}. A {@code reversal} entry is followed back to its original: undoing the reversal of
 * an invoice would re-open its receivable behind the cancelled document's back, so it is refused the same way.
 */
@Component
public class SubledgerSources {

    /**
     * One kind of sub-ledger document.
     *
     * @param documentType stable key ({@code sales_invoice}, {@code vendor_bill}, …)
     * @param label        human name of the document
     * @param table        table holding the document ({@code null}: no single row to name)
     * @param numberColumn its number column
     * @param action       the document-level action that reverses it
     * @param route        API route of that action ({@code {id}} = the document id), {@code null} when there is none
     * @param webPath      web page of the document ({@code {id}} = the document id), {@code null} when there is none
     */
    public record Kind(String documentType, String label, String table, String numberColumn, String action,
            String route, String webPath) {}

    /** The document behind one journal entry. */
    public record SourceDocument(Kind kind, String sourceType, String documentId, String documentNumber,
            boolean viaReversal) {

        /** Additive {@code sourceDocument} block of the journal-entry detail (web: hide Reverse, link the document). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentType", kind.documentType());
            m.put("label", kind.label());
            m.put("id", documentId);
            m.put("number", documentNumber);
            m.put("sourceType", sourceType);
            m.put("action", kind.action());
            m.put("route", kind.route() == null || documentId == null ? null : kind.route().replace("{id}", documentId));
            m.put("path", kind.webPath() == null || documentId == null ? null : kind.webPath().replace("{id}", documentId));
            m.put("viaReversal", viaReversal);
            m.put("genericReversal", false);
            return m;
        }
    }

    static final Kind SALES_INVOICE = new Kind("sales_invoice", "sales invoice", "Document", "documentNumber",
            "cancel the invoice", "POST /invoices/{id}/cancel", "/invoices/{id}");
    static final Kind CREDIT_NOTE = new Kind("credit_note", "credit note", "Document", "documentNumber",
            "reverse the credit note", "POST /credit-notes/{id}/reverse", "/credit-notes/{id}");
    static final Kind VENDOR_BILL = new Kind("vendor_bill", "supplier bill", "Document", "documentNumber",
            "void the bill", "POST /vendor-bills/{id}/cancel", "/vendor-bills/{id}");
    static final Kind VENDOR_BILL_WRITEOFF = new Kind("vendor_bill_writeoff", "supplier bill write-off", "Document",
            "documentNumber", "reverse the write-off on the bill (Write-offs → Reverse)",
            "POST /vendor-bills/{id}/write-offs/{entryId}/reverse", "/vendor-bills/{id}");
    static final Kind DEBIT_NOTE = new Kind("debit_note", "debit note", "DebitNote", "noteNumber",
            "reverse the debit note", "PATCH /procurement/debit-notes/{id}/reverse", "/procurement/debit-notes");
    static final Kind PAYMENT = new Kind("payment", "payment", "Payment", "paymentNumber",
            "void the payment (a deposit application is reversed from its customer deposit:"
            + " POST /customer-deposits/{depositId}/applications/{paymentId}/reverse)",
            "POST /payments/{id}/void", "/payments/{id}");
    static final Kind CUSTOMER_DEPOSIT = new Kind("customer_deposit", "customer deposit", "CustomerDeposit", "number",
            "void or refund the deposit", "POST /customer-deposits/{id}/void", null);
    static final Kind POS_INVOICE = new Kind("pos_invoice", "POS invoice", "Invoice", "invoiceNumber",
            "refund the POS invoice", "POST /pos/invoices/{id}/refund", null);
    static final Kind POS_REFUND = new Kind("pos_refund", "POS refund", "PosRefund", null,
            "issue a new sale (a POS refund is itself the reversal of its invoice)", null, null);
    static final Kind SUPPLIER_CLAIM = new Kind("supplier_claim", "supplier claim", "SupplierClaim", "claimNumber",
            "correct the supplier balance with a debit note or a control-account adjustment", null, null);
    static final Kind GOODS_RECEIPT = new Kind("goods_receipt", "goods receipt", "GoodsReceiptNote", "receiptNumber",
            "reverse the goods receipt", "PATCH /procurement/goods-receipts/{id}/reverse",
            "/procurement/goods-receipts/{id}");
    static final Kind PURCHASE_ORDER = new Kind("purchase_order", "purchase order payment", "PurchaseOrder",
            "orderNumber", "correct it on the purchase order or with a control-account adjustment", null,
            "/procurement/purchase-orders/{id}");
    static final Kind CASH_SALE = new Kind("cash_sale", "cash sale", "SalesSubmission", "number",
            "void the cash sale", "POST /sales/cash-sales/{id}/void", "/sales/cash-sales/{id}");
    static final Kind EXPENSE = new Kind("expense", "expense", "Expense", "expenseCode", "void the expense",
            "POST /expenses/{id}/void", "/expenses");
    static final Kind OPENING_BALANCE = new Kind("opening_balance", "opening-balance batch", null, null,
            "post a control-account adjustment naming the partner", null, "/opening-balances");

    /** sourceType → document kind. Every sourceType a customer / supplier sub-ledger posts under. */
    private static final Map<String, Kind> SOURCES = Map.ofEntries(
            Map.entry("invoice", SALES_INVOICE),
            Map.entry("sales_invoice", SALES_INVOICE),
            Map.entry("sales_invoice_cancel", SALES_INVOICE),
            Map.entry("credit_note", CREDIT_NOTE),
            Map.entry("credit_note_fx", CREDIT_NOTE),
            Map.entry("credit_note_cancel", CREDIT_NOTE),
            Map.entry("vendor_bill", VENDOR_BILL),
            Map.entry("vendor_bill_cancel", VENDOR_BILL),
            Map.entry("vendor_bill_writeoff", VENDOR_BILL_WRITEOFF),
            Map.entry("debit_note", DEBIT_NOTE),
            Map.entry("debit_note_reversal", DEBIT_NOTE),
            Map.entry("payment", PAYMENT),
            Map.entry("customer_deposit", CUSTOMER_DEPOSIT),
            Map.entry("pos_invoice", POS_INVOICE),
            Map.entry("pos_invoice_writeoff", POS_INVOICE),
            Map.entry("pos_refund", POS_REFUND),
            Map.entry("supplier_claim", SUPPLIER_CLAIM),
            Map.entry("goods_receipt", GOODS_RECEIPT),
            Map.entry("purchase_order", PURCHASE_ORDER),
            Map.entry("cash_sale", CASH_SALE),
            Map.entry("expense_accrual", EXPENSE),
            Map.entry("expense_payment", EXPENSE),
            Map.entry("opening_balance", OPENING_BALANCE));

    /** Every sub-ledger sourceType (for reports and tests). */
    public static Set<String> sourceTypes() {
        return SOURCES.keySet();
    }

    /** The document kind posting under {@code sourceType}; {@code null} for a non-sub-ledger source. */
    public static Kind kindOf(String sourceType) {
        return sourceType == null ? null : SOURCES.get(sourceType);
    }

    /** Whether {@code sourceType} belongs to a customer / supplier sub-ledger. */
    public static boolean isSubledger(String sourceType) {
        return kindOf(sourceType) != null;
    }

    /** The document-level action hint for {@code sourceType}; {@code null} when it is no sub-ledger source. */
    public static String documentAction(String sourceType) {
        Kind k = kindOf(sourceType);
        return k == null ? null : k.action();
    }

    private final JdbcClient jdbc;

    public SubledgerSources(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The sub-ledger document behind entry {@code journalEntryId} (following a {@code reversal} entry back to its
     * original, at most a few hops), or {@code null} when the entry is a manual / control-adjustment / recurring /
     * non-sub-ledger system entry.
     */
    public SourceDocument resolve(String journalEntryId) {
        String org = TenantContext.organizationId();
        String id = journalEntryId;
        boolean viaReversal = false;
        for (int hop = 0; hop < 8 && id != null; hop++) {
            Map<String, Object> e = jdbc.sql("SELECT \"sourceType\", \"sourceId\", \"reversalOfId\" FROM \"JournalEntry\""
                            + " WHERE id = ? AND \"organizationId\" = ?").params(id, org).query().listOfRows().stream()
                    .findFirst().orElse(null);
            if (e == null) {
                return null;
            }
            String sourceType = (String) e.get("sourceType");
            if ("reversal".equals(sourceType) || (sourceType == null && e.get("reversalOfId") != null)) {
                id = (String) e.get("reversalOfId");
                viaReversal = true;
                continue;
            }
            Kind kind = kindOf(sourceType);
            if (kind == null) {
                return null;
            }
            String sourceId = (String) e.get("sourceId");
            return new SourceDocument(kind, sourceType, sourceId, number(kind, sourceId, org), viaReversal);
        }
        return null;
    }

    /**
     * Refuse a generic reversal of a sub-ledger entry (400), naming the document and the action that reverses it.
     * Manual entries, control-account adjustments, recurring / deferral entries and other system entries pass.
     */
    public void assertGenericReversible(String journalEntryId) {
        SourceDocument doc = resolve(journalEntryId);
        if (doc == null) {
            return;
        }
        String entryNumber = jdbc.sql("SELECT \"entryNumber\" FROM \"JournalEntry\" WHERE id = ? AND \"organizationId\" = ?")
                .params(journalEntryId, TenantContext.organizationId()).query(String.class).optional()
                .orElse(journalEntryId);
        String document = doc.kind().label() + (doc.documentNumber() != null ? " " + doc.documentNumber()
                : doc.documentId() != null ? " " + doc.documentId() : "");
        String route = doc.kind().route() == null || doc.documentId() == null ? ""
                : " (" + doc.kind().route().replace("{id}", doc.documentId()) + ")";
        if (doc.viaReversal()) {
            throw ApiException.badRequest("Journal entry " + entryNumber + " is the reversal of an entry posted by "
                    + document + "; the document's own reversal workflow posted it and it cannot be reversed generically."
                    + " Issue a new document instead.");
        }
        throw ApiException.badRequest("Journal entry " + entryNumber + " was posted by " + document + " and cannot be"
                + " reversed generically: a bare journal reversal would leave the customer / supplier sub-ledger out of"
                + " step with the control account. Use the document-level action instead: " + doc.kind().action()
                + route + ".");
    }

    /** The document's number, read only from tables known to exist; {@code null} when there is none. */
    private String number(Kind kind, String sourceId, String org) {
        if (kind.table() == null || kind.numberColumn() == null || sourceId == null) {
            return null;
        }
        return jdbc.sql("SELECT \"" + kind.numberColumn() + "\"::text FROM \"" + kind.table() + "\" WHERE id = ? AND"
                        + " \"organizationId\" = ?").params(sourceId, org).query(String.class).optional().orElse(null);
    }
}
