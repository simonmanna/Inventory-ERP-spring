package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Keeps traffic fines charged to the company in step with their expense (outbox, at-least-once, idempotent):
 * {@code expense.paid} settles the fine, {@code expense.voided} (voided in Expenses) reopens it.
 */
@Component
public class FleetExpenseSubscriber {

    private final EventBus events;
    private final FleetFineService fines;

    public FleetExpenseSubscriber(EventBus events, FleetFineService fines) {
        this.events = events;
        this.fines = fines;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("expense.paid", p -> run(p, true));
        events.subscribe("expense.voided", p -> run(p, false));
    }

    void run(JsonNode p, boolean paid) {
        String orgId = p.path("organizationId").asString("");
        String expenseId = p.path("expenseId").asString("");
        if (orgId.isEmpty() || expenseId.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            if (paid) {
                fines.expensePaid(expenseId);
            } else {
                fines.expenseVoided(expenseId);
            }
        });
    }
}
