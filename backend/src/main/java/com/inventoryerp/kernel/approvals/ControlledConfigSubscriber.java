package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Applies a held configuration change when its approval request is decided (outbox, at least once). */
@Component
public class ControlledConfigSubscriber {

    private final EventBus events;
    private final ControlledConfigService changes;

    public ControlledConfigSubscriber(EventBus events, ControlledConfigService changes) {
        this.events = events;
        this.changes = changes;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!ControlledConfigService.ENTITY_TYPE.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String changeId = p.path("entityId").asString("");
        if (orgId.isEmpty() || changeId.isEmpty()) {
            return;
        }
        boolean approved = "approved".equals(p.path("status").asString(""));
        String requestId = p.path("requestId").asString("");
        TenantContext.run(TenantStore.organization(orgId), () -> {
            String decider = changes.lastApprover(requestId);
            TenantContext.run(TenantStore.user(orgId, decider), () -> changes.onDecision(changeId, approved, decider));
        });
    }
}
