package com.inventoryerp.invoicing.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Registers the customer deposit of a posted down-payment invoice ({@code invoice.posted}, at-least-once, idempotent). */
@Component
public class CustomerDepositSubscriber {

    private final EventBus events;
    private final CustomerDepositService deposits;

    public CustomerDepositSubscriber(EventBus events, CustomerDepositService deposits) {
        this.events = events;
        this.deposits = deposits;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("invoice.posted", this::onPosted);
    }

    void onPosted(JsonNode p) {
        String org = p.path("organizationId").asString("");
        String doc = p.path("documentId").asString("");
        if (org.isEmpty() || doc.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(org), () -> {
            deposits.registerDownPayment(doc);
        });
    }
}
