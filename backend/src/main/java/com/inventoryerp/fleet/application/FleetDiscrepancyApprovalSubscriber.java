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
 * Applies approval-engine decisions on delivery discrepancies ({@code approval.decided} from the outbox,
 * at-least-once): a decided {@value FleetDiscrepancyService#APPROVAL_ENTITY} request approves (billable quantity
 * adjusted, credit-note request) or rejects its discrepancy (fleet 1.3). Idempotent — a discrepancy that is no longer
 * pending is left alone.
 */
@Component
public class FleetDiscrepancyApprovalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(FleetDiscrepancyApprovalSubscriber.class);

    private final EventBus events;
    private final FleetDiscrepancyService discrepancies;
    private final FleetMeterService meters;

    public FleetDiscrepancyApprovalSubscriber(EventBus events, FleetDiscrepancyService discrepancies,
            FleetMeterService meters) {
        this.events = events;
        this.discrepancies = discrepancies;
        this.meters = meters;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!FleetDiscrepancyService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String id = p.path("entityId").asString("");
        boolean approved = "approved".equals(p.path("status").asString(""));
        if (orgId.isEmpty() || id.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            String decider = meters.lastApprover(requestId);
            TenantContext.run(TenantStore.user(orgId, decider), () -> {
                var out = discrepancies.applyDecision(id, approved, decider);
                log.info("Delivery discrepancy {} {} after approval request {}", id, out.get("status"), requestId);
            });
        });
    }
}
