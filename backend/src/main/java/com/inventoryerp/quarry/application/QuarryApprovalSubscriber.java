package com.inventoryerp.quarry.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Applies approval-engine decisions on explosive issues ({@code approval.decided} from the outbox, at-least-once): a
 * decided {@value QuarryExplosiveService#APPROVAL_ENTITY} request approves or rejects its issue. Runs without a request
 * tenant, so it enters the payload's organization as the deciding approver. Idempotent — an issue no longer pending
 * approval is left alone.
 */
@Component
public class QuarryApprovalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(QuarryApprovalSubscriber.class);

    private final EventBus events;
    private final QuarryExplosiveService explosives;

    public QuarryApprovalSubscriber(EventBus events, QuarryExplosiveService explosives) {
        this.events = events;
        this.explosives = explosives;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!QuarryExplosiveService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String issueId = p.path("entityId").asString("");
        boolean approved = "approved".equals(p.path("status").asString(""));
        if (orgId.isEmpty() || issueId.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            String decider = explosives.lastApprover(requestId);
            TenantContext.run(TenantStore.user(orgId, decider), () -> {
                var out = explosives.applyDecision(issueId, approved, decider);
                log.info("Explosive issue {} {} after approval request {}", issueId, out.get("status"), requestId);
            });
        });
    }
}
