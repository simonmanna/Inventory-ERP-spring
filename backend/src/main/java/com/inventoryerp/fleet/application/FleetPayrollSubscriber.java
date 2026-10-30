package com.inventoryerp.fleet.application;

import com.inventoryerp.hr.application.HrPayrollInputService;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Follows hr payroll for traffic fines charged to drivers (fleet 1.9 wiring of 1.7): when an approved payroll run
 * includes a fine's deduction input ({@code hr.payroll.inputs_included}) the deduction request and the fine are
 * settled; when that run is reversed ({@code hr.payroll.inputs_reopened}) they wait for the next run again. Outbox,
 * at-least-once, idempotent.
 */
@Component
public class FleetPayrollSubscriber {

    private final EventBus events;
    private final FleetFineService fines;

    public FleetPayrollSubscriber(EventBus events, FleetFineService fines) {
        this.events = events;
        this.fines = fines;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(HrPayrollInputService.EVENT_INCLUDED, p -> run(p, true));
        events.subscribe(HrPayrollInputService.EVENT_REOPENED, p -> run(p, false));
    }

    void run(JsonNode p, boolean included) {
        String orgId = p.path("organizationId").asString("");
        if (orgId.isEmpty() || !p.path("inputs").isArray()) {
            return;
        }
        String runNumber = p.path("runNumber").isString() ? p.path("runNumber").asString() : null;
        TenantContext.run(TenantStore.organization(orgId), () -> {
            for (JsonNode in : p.path("inputs")) {
                if (!FleetFineService.PAYROLL_SOURCE.equals(in.path("source").asString(""))) {
                    continue;
                }
                String deductionId = in.path("sourceId").asString("");
                if (included) {
                    fines.payrollIncluded(deductionId, runNumber);
                } else {
                    fines.payrollReopened(deductionId);
                }
            }
        });
    }
}
