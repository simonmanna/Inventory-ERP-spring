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
 * Applies approval-engine decisions on weighbridge exceptions ({@code approval.decided} from the outbox,
 * at-least-once) decided in the approvals inbox: approved completes the ticket, rejected sends it back for a new second
 * weighing. Runs as the deciding approver; a decision by one of the ticket's operators is not accepted (separation of
 * duties). Idempotent — an exception that is no longer pending is left alone.
 */
@Component
public class WeighbridgeApprovalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(WeighbridgeApprovalSubscriber.class);

    private final EventBus events;
    private final WeighbridgeTicketService tickets;

    public WeighbridgeApprovalSubscriber(EventBus events, WeighbridgeTicketService tickets) {
        this.events = events;
        this.tickets = tickets;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!WeighbridgeTicketService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String exceptionId = p.path("entityId").asString("");
        boolean approved = "approved".equals(p.path("status").asString(""));
        if (orgId.isEmpty() || exceptionId.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            String decider = tickets.lastApprover(requestId);
            TenantContext.run(TenantStore.user(orgId, decider), () -> {
                var out = tickets.applyDecision(exceptionId, approved, decider);
                log.info("Weighbridge exception {} {} after approval request {}", exceptionId, out.get("status"),
                        requestId);
            });
        });
    }
}
