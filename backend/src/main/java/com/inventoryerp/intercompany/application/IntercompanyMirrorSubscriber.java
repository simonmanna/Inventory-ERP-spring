package com.inventoryerp.intercompany.application;

import com.inventoryerp.kernel.events.EventBus;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Mirrors posted intercompany sales invoices into vendor bills ({@code invoice.posted}, at-least-once, idempotent). */
@Component
public class IntercompanyMirrorSubscriber {

    private final EventBus events;
    private final IntercompanyMirrorService mirror;

    public IntercompanyMirrorSubscriber(EventBus events, IntercompanyMirrorService mirror) {
        this.events = events;
        this.mirror = mirror;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("invoice.posted", this::onPosted);
    }

    void onPosted(JsonNode p) {
        String org = p.path("organizationId").asString("");
        String doc = p.path("documentId").asString("");
        if (org.isEmpty() || doc.isEmpty()) {
            return;
        }
        mirror.onInvoicePosted(org, doc);
    }
}
