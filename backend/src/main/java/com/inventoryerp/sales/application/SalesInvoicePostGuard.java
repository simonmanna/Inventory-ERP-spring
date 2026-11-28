package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;

import com.inventoryerp.invoicing.application.InvoicePostGuard;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Phase A A4: a sales-order invoice may not post while an order line it carries is invoiced (drafts included) beyond
 * what the line may be invoiced for — e.g. a claim raised after the draft lowered the accepted quantity.
 *
 * <p>Sales readiness R1 (B1): the guard is the final boundary for every generated invoice. A sales-order invoice's lines
 * must equal the order lines they are linked to; a branch cash sale's invoice lines must equal the sale's frozen lines.
 */
@Component
class SalesInvoicePostGuard implements InvoicePostGuard {

    private final SalesBillingService billing;
    private final SalesDb db;

    SalesInvoicePostGuard(SalesBillingService billing, SalesDb db) {
        this.billing = billing;
        this.db = db;
    }

    @Override
    public void beforePost(String documentId) {
        List<String> problems = new ArrayList<>(billing.postProblems(documentId));
        problems.addAll(cashSaleProblems(documentId));
        if (!problems.isEmpty()) {
            throw ApiException.conflict(problems.getFirst() + "; cancel this draft and invoice the order again");
        }
    }

    /** ADR-S094 C1b: register a down payment's deposit, or consume the deposits a final invoice deducts. */
    @Override
    public void afterPost(String documentId) {
        billing.afterPost(documentId);
    }

    /** ADR-S094 C2b: the revenue a cancelled invoice deferred is dropped with its journal entry. */
    @Override
    public void afterCancel(String documentId) {
        billing.afterCancel(documentId);
    }

    /** The invoice of a branch cash sale carries the sale's frozen lines, one for one and in order. */
    List<String> cashSaleProblems(String documentId) {
        Map<String, Object> d = db.row("SELECT \"sourceId\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"sourceType\" = 'cash_sale'", documentId, org());
        if (d == null) {
            return List.of();
        }
        List<Map<String, Object>> sale = db.rows("SELECT \"productId\", \"variantId\", quantity, \"unitPrice\","
                + " \"discountPercent\", \"taxId\" FROM \"SalesSubmissionLine\" WHERE \"submissionId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"sortOrder\", id", d.get("sourceId"), org());
        List<Map<String, Object>> inv = db.rows("SELECT \"productId\", \"variantId\", quantity, \"unitPrice\","
                + " \"discountPercent\", \"taxId\" FROM \"DocumentLine\" WHERE \"documentId\" = ? AND \"organizationId\" = ?"
                + " ORDER BY \"lineNumber\", id", documentId, org());
        if (sale.size() != inv.size()) {
            return List.of("The cash sale invoice has " + inv.size() + " line(s) but the sale has " + sale.size());
        }
        for (int i = 0; i < sale.size(); i++) {
            Map<String, Object> s = sale.get(i);
            Map<String, Object> l = inv.get(i);
            if (!Objects.equals(s.get("productId"), l.get("productId"))
                    || !Objects.equals(s.get("variantId"), l.get("variantId"))
                    || !Objects.equals(s.get("taxId"), l.get("taxId"))
                    || dec(s.get("quantity")).compareTo(dec(l.get("quantity"))) != 0
                    || dec(s.get("unitPrice")).compareTo(dec(l.get("unitPrice"))) != 0
                    || dec(s.get("discountPercent")).compareTo(dec(l.get("discountPercent"))) != 0) {
                return List.of("Cash sale invoice line " + (i + 1) + " differs from the sale");
            }
        }
        return List.of();
    }
}
