package com.inventoryerp.sales.application;

import com.inventoryerp.invoicing.application.InvoicePostGuard;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Phase A A4: a sales-order invoice may not post while an order line it carries is invoiced (drafts included) beyond
 * what the line may be invoiced for — e.g. a claim raised after the draft lowered the accepted quantity.
 */
@Component
class SalesInvoicePostGuard implements InvoicePostGuard {

    private final SalesBillingService billing;

    SalesInvoicePostGuard(SalesBillingService billing) {
        this.billing = billing;
    }

    @Override
    public void beforePost(String documentId) {
        List<String> problems = billing.postProblems(documentId);
        if (!problems.isEmpty()) {
            throw ApiException.conflict(problems.getFirst() + "; cancel this draft and invoice the order again");
        }
    }
}
