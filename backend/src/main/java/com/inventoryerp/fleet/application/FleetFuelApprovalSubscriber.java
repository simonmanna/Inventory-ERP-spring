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
 * Applies approval-engine decisions on fuel-tank reconciliations ({@code approval.decided} from the outbox,
 * at-least-once): a decided {@value FleetFuelTankService#APPROVAL_ENTITY} request posts or rejects the dip variance
 * adjustment. Runs as the deciding approver of the payload's organization. Idempotent — a dip that is no longer
 * pending is left alone.
 */
@Component
public class FleetFuelApprovalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(FleetFuelApprovalSubscriber.class);

    private final EventBus events;
    private final FleetFuelTankService tanks;
    private final FleetMeterService meters;

    public FleetFuelApprovalSubscriber(EventBus events, FleetFuelTankService tanks, FleetMeterService meters) {
        this.events = events;
        this.tanks = tanks;
        this.meters = meters;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!FleetFuelTankService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String dipId = p.path("entityId").asString("");
        boolean approved = "approved".equals(p.path("status").asString(""));
        if (orgId.isEmpty() || dipId.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            String decider = meters.lastApprover(requestId);
            TenantContext.run(TenantStore.user(orgId, decider), () -> {
                var out = tanks.applyDecision(dipId, approved, decider);
                log.info("Tank reconciliation {} {} after approval request {}", dipId, out.get("status"), requestId);
            });
        });
    }
}
