package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * ADR-S094 C4b (AD-5): the fiscal documents of a Sale's invoices and credit notes. Each is queued on the fiscal outbox
 * ({@link CashSaleFiscalService}, owner {@code document}) inside the transaction that posts it, so it commits with the
 * posting or not at all; the provider is called afterwards.
 * <ul>
 *   <li>A posted invoice of an order-type Sale ({@code sale}, {@code sale_down_payment} or {@code sale_milestone},
 *       including a zero-total final invoice) is registered ({@code issue}) with the provider of the sale's branch. A
 *       cash Sale's invoice is not: its confirm registers the sale's one fiscal document (C3a).
 *   <li>A posted credit note on a registered sale invoice is registered ({@code credit_note}) with the provider that
 *       registered the invoice, once that registration has succeeded. A cash Sale's reversal credit note is not: the
 *       reversal cancels the sale's document instead (C3b).
 *   <li>A registered invoice is credited, not cancelled. Its cancel is refused while the registration is open and, once
 *       an outside authority registered it, for good. A local provider records the cancel.
 * </ul>
 */
@Service
class SaleDocumentFiscalService {

    /** Source kinds of a Sale's invoices (ADR-S094 §8.2). */
    static final Set<String> INVOICE_KINDS = Set.of("sale", "sale_down_payment", "sale_milestone");

    private static final Set<String> LIVE = Set.of("pending", "sent", "unknown", "review");

    private final SalesDb db;
    private final CashSaleFiscalService fiscal;

    SaleDocumentFiscalService(SalesDb db, CashSaleFiscalService fiscal) {
        this.db = db;
        this.fiscal = fiscal;
    }

    /** Register a posted invoice of an order-type Sale (inside its posting transaction). */
    void invoicePosted(String documentId) {
        Map<String, Object> d = db.row("SELECT d.\"documentNumber\", d.\"sourceKind\", d.\"sourceId\", d.subtotal,"
                + " d.\"taxAmount\", d.\"totalAmount\", p.name AS \"partnerName\", p.\"taxNumber\" FROM \"Document\" d LEFT"
                + " JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.id = ? AND d.\"organizationId\" = ? AND"
                + " d.\"documentType\"::text = 'sales_invoice' AND d.\"sourceType\" = 'sales_order'", documentId, org());
        if (d == null || !INVOICE_KINDS.contains(str(d.get("sourceKind")))) {
            return;
        }
        Map<String, Object> sale = sale(d.get("sourceId"));
        if (sale == null || "cash".equals(str(sale.get("saleType")))) {
            return;
        }
        String number = str(d.get("documentNumber"));
        fiscal.requestForDocument(sale, "issue", documentId, fiscal.providerFor(sale), Obj.of("invoiceNumber", number,
                "saleNumber", str(sale.get("number")), "kind", str(d.get("sourceKind")), "subtotal", amount(d,
                        "subtotal"), "taxAmount", amount(d, "taxAmount"), "total", amount(d, "totalAmount"),
                "customerName", str(d.get("partnerName")), "customerTin", str(d.get("taxNumber"))), "Invoice " + number);
    }

    /** Register a posted credit note on a registered sale invoice (inside its posting transaction). */
    void creditNotePosted(String creditNoteId) {
        Map<String, Object> cn = db.row("SELECT c.\"documentNumber\", c.\"reversedDocumentId\", c.subtotal,"
                + " c.\"taxAmount\", c.\"totalAmount\", i.\"documentNumber\" AS \"invoiceNumber\" FROM \"Document\" c JOIN"
                + " \"Document\" i ON i.id = c.\"reversedDocumentId\" WHERE c.id = ? AND c.\"organizationId\" = ? AND"
                + " c.\"documentType\"::text = 'credit_note'", creditNoteId, org());
        if (cn == null) {
            return;
        }
        String invoiceId = str(cn.get("reversedDocumentId"));
        Map<String, Object> issue = fiscal.latestIssue(invoiceId);
        if (issue == null || "failed".equals(str(issue.get("status")))
                || !Set.of("sale", "document").contains(str(issue.get("ownerKind")))) {
            return; // the credited invoice is not a registered sale invoice: nothing to credit at the authority
        }
        Map<String, Object> sale = sale(issue.get("salesOrderId"));
        if (sale == null) {
            return;
        }
        if ("cash".equals(str(sale.get("saleType"))) && (sale.get("reversalRequestedAt") != null
                || Set.of("cancellation_pending", "cancelled").contains(str(sale.get("fiscalStatus"))))) {
            return; // C3b: the reversal cancels the sale's one document instead of crediting it
        }
        String number = str(cn.get("documentNumber"));
        String invoiceNumber = str(cn.get("invoiceNumber"));
        fiscal.requestForDocument(sale, "credit_note", creditNoteId, str(issue.get("provider")), Obj.of(
                "creditNoteNumber", number, "originalInvoiceId", invoiceId, "originalInvoiceNumber", invoiceNumber,
                "saleNumber", str(sale.get("number")), "subtotal", amount(cn, "subtotal"), "taxAmount", amount(cn,
                        "taxAmount"), "total", amount(cn, "totalAmount")), "Credit note " + number + " on invoice "
                                + invoiceNumber);
    }

    /**
     * Gate the cancel of a posted sale invoice (inside the cancelling transaction; throwing rolls the cancel back). An
     * invoice registered with an outside authority is credited, not cancelled; a cash Sale's invoice goes with its sale
     * (Reverse). A local provider records the cancel as an operation of its own.
     */
    void invoiceCancelled(String documentId) {
        Map<String, Object> issue = fiscal.latestIssue(documentId);
        if (issue == null || "failed".equals(str(issue.get("status")))) {
            return;
        }
        String owner = str(issue.get("ownerKind"));
        if ("sale".equals(owner)) {
            throw ApiException.badRequest("This invoice belongs to a cash sale: reverse the sale instead of cancelling"
                    + " its invoice");
        }
        if (!"document".equals(owner)) {
            return; // a legacy cash sale gates its own invoice cancel (ADR-S056)
        }
        if (LIVE.contains(str(issue.get("status")))) {
            throw ApiException.conflict("The invoice's fiscal registration is still open; retry once it is registered"
                    + " or has failed");
        }
        if (!fiscal.localProvider(str(issue.get("provider")))) {
            throw ApiException.badRequest("This invoice is registered with the fiscal authority: credit it with a credit"
                    + " note instead of cancelling it");
        }
        Map<String, Object> sale = sale(issue.get("salesOrderId"));
        String number = str(db.row("SELECT \"documentNumber\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?",
                documentId, org()).get("documentNumber"));
        fiscal.requestForDocument(sale, "cancel", documentId, str(issue.get("provider")), Obj.of("invoiceNumber",
                number, "saleNumber", str(sale.get("number")), "originalRequestRef", str(issue.get("requestRef"))),
                "Invoice " + number + " cancelled");
    }

    private Map<String, Object> sale(Object id) {
        return id == null ? null : db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", id,
                org());
    }

    private static Number amount(Map<String, Object> row, String column) {
        return JsNumbers.js(dec(row.get(column)));
    }
}
