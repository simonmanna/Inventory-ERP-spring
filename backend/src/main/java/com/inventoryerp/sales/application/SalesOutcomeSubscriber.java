package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Invoicing → sales outcome (ADR-S094 §4.4, §7.3): a receipt allocated or voided, or a credit note issued, on a document
 * of a sale recomputes the sale's credited quantities, outcome and status. Sales' own commands do this in their
 * transaction; this covers the invoicing routes (absolute recompute, at-least-once safe; the nightly reconciliation
 * catches what an event missed, such as a credit allocation).
 */
@Component
public class SalesOutcomeSubscriber {

    private static final Logger log = LoggerFactory.getLogger(SalesOutcomeSubscriber.class);

    private final EventBus events;
    private final SalesOutcomeService outcome;
    private final SalesBillingService billing;
    private final SalesFulfilmentService fulfilment;

    public SalesOutcomeSubscriber(EventBus events, SalesOutcomeService outcome, SalesBillingService billing,
            SalesFulfilmentService fulfilment) {
        this.events = events;
        this.outcome = outcome;
        this.billing = billing;
        this.fulfilment = fulfilment;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("payment.allocated", p -> on("payment.allocated", p));
        events.subscribe("payment.voided", p -> on("payment.voided", p));
        events.subscribe("creditnote.issued", p -> on("creditnote.issued", p));
    }

    void on(String name, JsonNode p) {
        String org = p.path("organizationId").asString("");
        String doc = p.path("documentId").asString("");
        String payment = p.path("paymentId").asString("");
        if (org.isEmpty() || (doc.isEmpty() && payment.isEmpty())) {
            return;
        }
        TenantContext.run(TenantStore.organization(org), () -> {
            try {
                Set<String> orders = new LinkedHashSet<>();
                if (!doc.isEmpty()) {
                    orders.addAll(outcome.ordersOf(doc));
                } else {
                    outcome.documentsOfPayment(payment).forEach(d -> orders.addAll(outcome.ordersOf(d)));
                }
                // gross invoiced and credited quantities first (a credit note may carry sale-line links), then status
                orders.forEach(billing::recomputeInvoiced);
            } catch (RuntimeException e) {
                log.warn("Sales outcome projection failed for {} on {}: {}", doc.isEmpty() ? payment : doc, name,
                        e.toString());
                fulfilment.logEvent(name, doc.isEmpty() ? "payment" : "document", doc.isEmpty() ? payment : doc, null,
                        p.toString(), "failed", e.toString());
            }
        });
    }
}
