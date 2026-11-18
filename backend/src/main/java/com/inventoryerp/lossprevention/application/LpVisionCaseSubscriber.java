package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * When a case opens, asks the edge agents for its video (LP1): the clips of the observations behind the finding, and
 * for a POS finding at a till with cameras, a clip of the till around the event. Runs after the case's transaction,
 * from the outbox worker; a failure is logged and never touches the case.
 */
@Component
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LpVisionCaseSubscriber {

    private static final Logger log = LoggerFactory.getLogger(LpVisionCaseSubscriber.class);

    private final EventBus events;
    private final LpEvidenceService evidence;

    public LpVisionCaseSubscriber(EventBus events, LpEvidenceService evidence) {
        this.events = events;
        this.evidence = evidence;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.LP_CASE_OPENED, this::onCaseOpened);
    }

    void onCaseOpened(JsonNode payload) {
        JsonNode org = payload == null ? null : payload.get("organizationId");
        JsonNode caseId = payload == null ? null : payload.get("caseId");
        if (org == null || !org.isString() || caseId == null || !caseId.isString()) {
            return;
        }
        try {
            TenantContext.run(TenantStore.organization(org.asString()), () -> {
                int linked = evidence.forNewCase(caseId.asString());
                if (linked > 0) {
                    log.info("loss prevention case {}: {} clip(s) linked", caseId.asString(), linked);
                }
            });
        } catch (RuntimeException err) {
            log.warn("loss-prevention evidence for case {} failed: {}", caseId.asString(), err.toString());
        }
    }
}
