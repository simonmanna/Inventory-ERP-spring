package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Sales readiness INV-11: a credit note raised from a delivery claim is a draft with a temporary reference; when it is
 * posted it takes its legal number, which this copies onto the claim that shows it.
 */
@Component
public class FleetCreditNoteNumberSubscriber {

    private static final Logger log = LoggerFactory.getLogger(FleetCreditNoteNumberSubscriber.class);

    private final EventBus events;
    private final JdbcClient jdbc;

    public FleetCreditNoteNumberSubscriber(EventBus events, JdbcClient jdbc) {
        this.events = events;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("creditnote.issued", this::on);
    }

    void on(JsonNode p) {
        String org = p.path("organizationId").asString("");
        String doc = p.path("documentId").asString("");
        String number = p.path("documentNumber").asString("");
        if (org.isEmpty() || doc.isEmpty() || number.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(org), () -> {
            try {
                jdbc.sql("UPDATE \"FleetDeliveryDiscrepancy\" SET \"creditNoteNumber\" = ? WHERE \"organizationId\" = ?"
                        + " AND \"creditNoteId\" = ? AND \"creditNoteNumber\" IS DISTINCT FROM ?")
                        .params(number, org, doc, number).update();
            } catch (RuntimeException e) {
                log.warn("Delivery claim credit note number refresh failed for {}: {}", doc, e.toString());
            }
        });
    }
}
