package com.inventoryerp.procurement.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.procurement.application.ProcurementReplenishmentService.Line;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Picks up {@code inventory.replenishment.requested} from the outbox (at-least-once). Runs without a request tenant, so
 * it enters the payload's organization, as the requesting user when there was one (a scheduled scan has none).
 */
@Component
public class ProcurementReplenishmentSubscriber {

    private final EventBus events;
    private final ProcurementReplenishmentService replenishment;

    public ProcurementReplenishmentSubscriber(EventBus events, ProcurementReplenishmentService replenishment) {
        this.events = events;
        this.replenishment = replenishment;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.INVENTORY_REPLENISHMENT_REQUESTED, this::onRequested);
    }

    void onRequested(JsonNode p) {
        String orgId = p.path("organizationId").asString("");
        if (orgId.isEmpty()) {
            return;
        }
        List<Line> lines = new ArrayList<>();
        for (JsonNode l : p.path("lines")) {
            String supplier = l.path("supplierId").asString("");
            String uom = l.path("uomId").asString("");
            lines.add(new Line(l.path("alertId").asString(""), l.path("productId").asString(""),
                    l.path("description").asString(""), new BigDecimal(l.path("quantity").asString("0")),
                    uom.isEmpty() ? null : uom, supplier.isEmpty() ? null : supplier, l.path("neededBy").asString("")));
        }
        String userId = p.path("requestedBy").asString("");
        TenantStore store = userId.isEmpty() ? TenantStore.organization(orgId) : TenantStore.user(orgId, userId);
        TenantContext.run(store, () -> replenishment.raise(lines));
    }
}
