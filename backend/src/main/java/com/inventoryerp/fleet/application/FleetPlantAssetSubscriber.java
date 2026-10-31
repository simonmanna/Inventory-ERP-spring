package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Disposal stays in fixedasset; a linked fixed-plant unit follows it (CMMS-1, outbox, at-least-once, idempotent). */
@Component
public class FleetPlantAssetSubscriber {

    private final EventBus events;
    private final FleetPlantService plant;

    public FleetPlantAssetSubscriber(EventBus events, FleetPlantService plant) {
        this.events = events;
        this.plant = plant;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.ASSET_DISPOSED, this::run);
    }

    void run(JsonNode p) {
        String orgId = p.path("organizationId").asString("");
        String assetId = p.path("assetId").asString("");
        if (orgId.isEmpty() || assetId.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> plant.assetDisposed(assetId));
    }
}
