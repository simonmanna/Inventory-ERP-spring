package com.inventoryerp.procurement.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Applies approval-engine decisions taken in the approvals inbox to purchase requests (outbox, at least once). */
@Component
public class PurchaseRequestApprovalSubscriber {

    private final EventBus events;
    private final PurchaseRequestsService requests;

    public PurchaseRequestApprovalSubscriber(EventBus events, PurchaseRequestsService requests) {
        this.events = events;
        this.requests = requests;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!PurchaseRequestsService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String id = p.path("entityId").asString("");
        if (orgId.isEmpty() || id.isEmpty()) {
            return;
        }
        boolean approved = "approved".equals(p.path("status").asString(""));
        TenantContext.run(TenantStore.organization(orgId), () -> requests.applyDecision(id, approved, requestId));
    }
}
