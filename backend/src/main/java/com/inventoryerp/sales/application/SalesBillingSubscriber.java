package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Invoicing → sales projections (ADR-S011 S6): {@code invoice.posted} / {@code invoice.cancelled} of an invoice
 * linked to a sales order recompute the order's gross invoiced quantities (absolute, at-least-once safe).
 */
@Component
public class SalesBillingSubscriber {

    private static final Logger log = LoggerFactory.getLogger(SalesBillingSubscriber.class);

    private final EventBus events;
    private final SalesBillingService billing;
    private final SalesFulfilmentService fulfilment;

    public SalesBillingSubscriber(EventBus events, SalesBillingService billing, SalesFulfilmentService fulfilment) {
        this.events = events;
        this.billing = billing;
        this.fulfilment = fulfilment;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("invoice.posted", p -> on("invoice.posted", p, true));
        events.subscribe("invoice.cancelled", p -> on("invoice.cancelled", p, false));
    }

    void on(String name, JsonNode p, boolean posted) {
        String org = p.path("organizationId").asString("");
        String doc = p.path("documentId").asString("");
        if (org.isEmpty() || doc.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(org), () -> {
            try {
                billing.onInvoiceChanged(doc, posted);
            } catch (RuntimeException e) {
                log.warn("Sales invoice projection failed for {} on {}: {}", doc, name, e.toString());
                fulfilment.logEvent(name, "invoice", doc, null, p.toString(), "failed", e.toString());
            }
        });
    }
}
