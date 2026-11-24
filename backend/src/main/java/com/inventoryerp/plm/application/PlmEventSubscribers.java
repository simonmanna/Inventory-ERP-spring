package com.inventoryerp.plm.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * PLM's reactions to manufacturing (ADR-S086): when a production order completes, record the revision it produced
 * (as-built) and open the inspection of a first article a change asked for; the evaluation or decision of that
 * inspection lot closes it. Handlers run after commit without a tenant scope, so the
 * organization comes from the payload; nothing happens where PLM is switched off.
 */
@Component
public class PlmEventSubscribers {

    private final EventBus events;
    private final PlmApp app;
    private final PlmRevisionService revisions;
    private final PlmFaiService fai;

    public PlmEventSubscribers(EventBus events, PlmApp app, PlmRevisionService revisions, PlmFaiService fai) {
        this.events = events;
        this.app = app;
        this.revisions = revisions;
        this.fai = fai;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.MANUFACTURING_ORDER_COMPLETED, this::onOrderCompleted);
        // A first-article lot closes on its evaluation (pass / fail) or a decision (e.g. cancelled).
        events.subscribe(DomainEvents.QUALITY_LOT_EVALUATED, this::onLotOutcome);
        events.subscribe(DomainEvents.QUALITY_LOT_DECIDED, this::onLotOutcome);
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }

    private void onOrderCompleted(JsonNode p) {
        String org = text(p, "organizationId");
        String orderId = text(p, "orderId");
        if (org == null || orderId == null) {
            return;
        }
        TenantContext.run(TenantStore.organization(org), () -> {
            if (!app.on(org)) {
                return;
            }
            revisions.recordAsBuilt(orderId);
            fai.onOrderCompleted(orderId);
        });
    }

    private void onLotOutcome(JsonNode p) {
        if (!PlmFaiService.SOURCE.equals(text(p, "sourceType"))) {
            return;
        }
        String org = text(p, "organizationId");
        String lotId = text(p, "lotId");
        String changeId = text(p, "sourceId");
        if (org == null || lotId == null || changeId == null) {
            return;
        }
        // Closed even while PLM is switched off: the inspection was opened while it was on.
        TenantContext.run(TenantStore.organization(org), () -> fai.onLotDecided(lotId, changeId, text(p, "result"),
                text(p, "status")));
    }
}
