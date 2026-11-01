package com.inventoryerp.customization.application;

import com.inventoryerp.customization.application.AutomationCatalog.Trigger;
import com.inventoryerp.kernel.events.EventBus;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Feeds every catalogued domain event to the {@link AutomationEngine} (outbox, after commit, at-least-once). */
@Component
public class AutomationSubscriber {

    private final EventBus events;
    private final AutomationEngine engine;

    public AutomationSubscriber(EventBus events, AutomationEngine engine) {
        this.events = events;
        this.engine = engine;
    }

    @PostConstruct
    void subscribe() {
        for (Trigger t : AutomationCatalog.TRIGGERS.values()) {
            events.subscribe(t.event(), p -> onEvent(t, p));
        }
    }

    void onEvent(Trigger t, JsonNode p) {
        engine.onEvent(t, p.path("organizationId").asString(""), p.path(t.idField()).asString(""));
    }
}
