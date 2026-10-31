package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** An approved delivery discrepancy on a sales-order delivery opens a quantity complaint (ADR-S011 S7). */
@Component
public class SalesComplaintSubscriber {

    private final EventBus events;
    private final SalesComplaintService complaints;

    public SalesComplaintSubscriber(EventBus events, SalesComplaintService complaints) {
        this.events = events;
        this.complaints = complaints;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.FLEET_DELIVERY_DISCREPANCY_DECIDED, this::on);
    }

    void on(JsonNode p) {
        String org = p.path("organizationId").asString("");
        if (org.isEmpty() || !"approved".equals(p.path("status").asString(""))) {
            return;
        }
        TenantContext.run(TenantStore.organization(org), () -> complaints.fromDiscrepancy(p.path("discrepancyId")
                .asString(""), p.path("deliveryOrderId").asString(""), p.path("code").asString(""), p.path(
                        "differenceQty").asString("")));
    }
}
