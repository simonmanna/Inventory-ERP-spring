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
 * Applies approval-engine decisions on driver claims ({@code approval.decided} from the outbox, at-least-once): a
 * decided {@value FleetClaimService#APPROVAL_ENTITY} request approves or rejects its claim (fleet 1.2). Runs without a
 * request tenant, so it enters the payload's organization as the deciding approver. Idempotent — a claim that is no
 * longer {@code submitted} is left alone (the approve / reject routes already applied it).
 */
@Component
public class FleetClaimApprovalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(FleetClaimApprovalSubscriber.class);

    private final EventBus events;
    private final FleetClaimService claims;
    private final FleetMeterService meters;

    public FleetClaimApprovalSubscriber(EventBus events, FleetClaimService claims, FleetMeterService meters) {
        this.events = events;
        this.claims = claims;
        this.meters = meters;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!FleetClaimService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String claimId = p.path("entityId").asString("");
        boolean approved = "approved".equals(p.path("status").asString(""));
        if (orgId.isEmpty() || claimId.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            String decider = meters.lastApprover(requestId);
            TenantContext.run(TenantStore.user(orgId, decider), () -> {
                var out = claims.applyDecision(claimId, approved, decider);
                log.info("Driver claim {} {} after approval request {}", claimId, out.get("status"), requestId);
            });
        });
    }
}
