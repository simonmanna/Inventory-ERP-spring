package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Fleet → sales projections (ADR-S011 S5): on trip dispatched / delivered / closed / returned and discrepancy
 * decisions, rebuild the sales order the delivery order belongs to (absolute recompute, at-least-once safe) and log
 * the event in the inbox. A failure is logged as {@code failed}; the nightly reconciliation repairs it.
 */
@Component
public class SalesFulfilmentSubscriber {

    private static final Logger log = LoggerFactory.getLogger(SalesFulfilmentSubscriber.class);
    static final List<String> EVENTS = List.of(DomainEvents.FLEET_TRIP_DISPATCHED, DomainEvents.FLEET_TRIP_DELIVERED,
            DomainEvents.FLEET_TRIP_CLOSED, DomainEvents.FLEET_TRIP_RETURNED,
            DomainEvents.FLEET_DELIVERY_DISCREPANCY_DECIDED);

    private final EventBus events;
    private final SalesFulfilmentService fulfilment;

    public SalesFulfilmentSubscriber(EventBus events, SalesFulfilmentService fulfilment) {
        this.events = events;
        this.fulfilment = fulfilment;
    }

    @PostConstruct
    void subscribe() {
        for (String e : EVENTS) {
            events.subscribe(e, p -> onEvent(e, p));
        }
    }

    void onEvent(String name, JsonNode p) {
        String org = p.path("organizationId").asString("");
        String deliveryOrderId = p.path("deliveryOrderId").asString("");
        if (org.isEmpty() || deliveryOrderId.isEmpty()) {
            return;
        }
        String sourceId = p.has("loadId") ? p.path("loadId").asString("") : p.path("discrepancyId").asString("");
        TenantContext.run(TenantStore.organization(org), () -> {
            String orderId = fulfilment.orderOfDelivery(deliveryOrderId);
            if (orderId == null) {
                return;
            }
            try {
                fulfilment.rebuild(orderId);
                fulfilment.logEvent(name, "fleet", sourceId, orderId, p.toString(), "applied", null);
            } catch (RuntimeException e) {
                log.warn("Sales projection rebuild failed for order {} on {}: {}", orderId, name, e.toString());
                fulfilment.logEvent(name, "fleet", sourceId, orderId, p.toString(), "failed", e.toString());
            }
        });
    }
}
