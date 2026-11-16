package com.inventoryerp.fleet.application;

import com.inventoryerp.invoicing.application.InvoicePostGuard;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Phase A A4: a Fleet goods invoice may not post while it invoices more than its delivery orders accepted. */
@Component
class FleetInvoicePostGuard implements InvoicePostGuard {

    private final DeliveryInvoicing ledger;

    FleetInvoicePostGuard(DeliveryInvoicing ledger) {
        this.ledger = ledger;
    }

    @Override
    public void beforePost(String documentId) {
        List<String> problems = ledger.postProblems(documentId);
        if (!problems.isEmpty()) {
            throw ApiException.conflict(problems.getFirst() + "; cancel this draft and invoice the delivery order again");
        }
    }
}
