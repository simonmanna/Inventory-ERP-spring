package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.workflow.FulfillmentRegistry;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Stock-posting timing reactions (port of {@code inventory-posting.subscriber.ts}; the KDS cancellation
 * subscriber lives in {@link PosKdsService}).
 * Handlers run without a tenant scope, so each derives {@code organizationId} from the payload.
 */
@Component
public class PosEventSubscribers {

    private final EventBus events;
    private final PosInvoiceService billing;
    private final FulfillmentRegistry fulfillment;

    public PosEventSubscribers(EventBus events, PosInvoiceService billing, FulfillmentRegistry fulfillment) {
        this.events = events;
        this.billing = billing;
        this.fulfillment = fulfillment;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.ORDER_CONFIRMED, p -> enqueueIf(p, "at_confirmation"));
        events.subscribe(DomainEvents.ORDER_CLOSED, p -> enqueueIf(p, "at_payment"));
        events.subscribe(DomainEvents.FULFILLMENT_COMPLETED, this::onFulfillmentCompleted);
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }

    /** {@code at_invoice} is enqueued inside {@code generateInvoice}; this owns the other timings. */
    private void enqueueIf(JsonNode p, String timing) {
        TenantContext.run(TenantStore.organization(text(p, "organizationId")), () -> {
            if (!timing.equals(billing.stockPostingTiming())) {
                return;
            }
            billing.enqueueStockPosting(text(p, "orderId"), null, null, timing);
        });
    }

    private void onFulfillmentCompleted(JsonNode p) {
        TenantContext.run(TenantStore.organization(text(p, "organizationId")), () -> {
            if (!"at_fulfillment_complete".equals(billing.stockPostingTiming())) {
                return;
            }
            String orderId = text(p, "orderId");
            String code = text(p, "strategy");
            FulfillmentRegistry.Strategy strategy = code == null ? null : fulfillment.get(code);
            if (strategy != null && !strategy.isComplete().test(orderId)) {
                return;
            }
            billing.enqueueStockPosting(orderId, null, null, "at_fulfillment_complete");
        });
    }
}
