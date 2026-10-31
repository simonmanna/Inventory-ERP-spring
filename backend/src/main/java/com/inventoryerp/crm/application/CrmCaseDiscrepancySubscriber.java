package com.inventoryerp.crm.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Writes fleet's decision on a delivery discrepancy to the linked case's timeline
 * ({@code fleet.delivery.discrepancy_decided} from the outbox, at-least-once; the service skips a repeat). Runs without
 * a request tenant, so it enters the payload's organization.
 */
@Component
public class CrmCaseDiscrepancySubscriber {

    private final EventBus events;
    private final CrmCaseService cases;

    public CrmCaseDiscrepancySubscriber(EventBus events, CrmCaseService cases) {
        this.events = events;
        this.cases = cases;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.FLEET_DELIVERY_DISCREPANCY_DECIDED, this::onDecided);
    }

    void onDecided(JsonNode p) {
        String orgId = p.path("organizationId").asString("");
        String discrepancyId = p.path("discrepancyId").asString("");
        if (orgId.isEmpty() || discrepancyId.isEmpty()) {
            return;
        }
        String code = p.path("code").asString("");
        String status = p.path("status").asString("");
        boolean creditNote = !p.path("creditNoteId").asString("").isEmpty();
        TenantContext.run(TenantStore.organization(orgId),
                () -> cases.recordDiscrepancyDecision(discrepancyId, code, status, creditNote));
    }
}
