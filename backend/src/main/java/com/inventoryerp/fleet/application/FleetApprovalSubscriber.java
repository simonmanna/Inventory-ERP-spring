package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Applies approval-engine decisions on fleet requests ({@code approval.decided} from the outbox, at-least-once): a
 * decided {@value FleetMeterService#APPROVAL_ENTITY} request applies or rejects its meter correction. Runs without a
 * request tenant, so it enters the payload's organization as the deciding approver. Idempotent — a correction that is
 * no longer pending is left alone.
 */
@Component
public class FleetApprovalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(FleetApprovalSubscriber.class);

    private final EventBus events;
    private final FleetMeterService meters;

    public FleetApprovalSubscriber(EventBus events, FleetMeterService meters) {
        this.events = events;
        this.meters = meters;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!FleetMeterService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String correctionId = p.path("entityId").asString("");
        boolean approved = "approved".equals(p.path("status").asString(""));
        if (orgId.isEmpty() || correctionId.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            String decider = meters.lastApprover(requestId);
            TenantContext.run(TenantStore.user(orgId, decider), () -> {
                var out = meters.applyDecision(correctionId, approved, decider);
                log.info("Meter correction {} {} after approval request {}", correctionId, out.get("status"),
                        requestId);
            });
        });
    }
}
