package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The fast path of the projection: when POS, cash or kitchen announces a change, sweep that one source now instead of
 * waiting for the schedule. The handler runs after the announcing transaction, from the outbox worker, so it can
 * neither fail nor slow a POS request; whatever it misses the scheduled sweep picks up.
 */
@Component
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LpProjectionSubscribers {

    private static final Logger log = LoggerFactory.getLogger(LpProjectionSubscribers.class);

    private static final Map<String, String> SOURCE_BY_EVENT = Map.ofEntries(
            Map.entry(DomainEvents.POS_SALE_COMPLETED, LpProjectionService.SALES),
            Map.entry(DomainEvents.POS_ORDER_ITEM_VOIDED, LpProjectionService.VOIDS),
            Map.entry(DomainEvents.POS_ORDER_CANCELLED, LpProjectionService.CANCELLATIONS),
            Map.entry(DomainEvents.POS_OVERRIDE_APPROVED, LpProjectionService.OVERRIDES),
            Map.entry(DomainEvents.CASH_SESSION_OPENED, LpProjectionService.CASH_SESSIONS),
            Map.entry(DomainEvents.CASH_SESSION_CLOSED, LpProjectionService.CASH_SESSIONS),
            Map.entry(DomainEvents.CASH_SESSION_RECONCILED, LpProjectionService.CASH_SESSIONS),
            Map.entry("pos.kds.ticket_created", LpProjectionService.KITCHEN),
            Map.entry("pos.kds.ticket_updated", LpProjectionService.KITCHEN));

    private final EventBus events;
    private final LpProjectionService projection;

    public LpProjectionSubscribers(EventBus events, LpProjectionService projection) {
        this.events = events;
        this.projection = projection;
    }

    @PostConstruct
    void subscribe() {
        SOURCE_BY_EVENT.forEach((event, source) -> events.subscribe(event, payload -> sweep(event, source, payload)));
    }

    private void sweep(String event, String source, JsonNode payload) {
        try {
            JsonNode org = payload == null ? null : payload.get("organizationId");
            if (org == null || org.isNull()) {
                return;
            }
            TenantContext.run(TenantStore.organization(org.asString()), () -> {
                projection.sweep(source);
            });
        } catch (RuntimeException err) {
            log.warn("loss-prevention projection after {} failed: {}", event, err.toString());
        }
    }
}
