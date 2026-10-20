package com.inventoryerp.documents.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Phase 8 DMS workflow subscriptions (port of {@code dms-workflow.subscriber.ts}): {@code document.posted /
 * reversed / amended / issued} write an idempotent {@code DMSWorkflowLedger} row when the type opts in through
 * {@code security.workflowHooks} containing {@code pos_inventory}.
 */
@Component
public class DmsWorkflowSubscriber {

    private static final Logger log = LoggerFactory.getLogger(DmsWorkflowSubscriber.class);

    private final EventBus events;
    private final JdbcClient jdbc;
    private final DmsStore store;

    public DmsWorkflowSubscriber(EventBus events, JdbcClient jdbc, DmsStore store) {
        this.events = events;
        this.jdbc = jdbc;
        this.store = store;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("document.posted", p -> {
            if ("posted".equals(p.path("toStatus").asString(""))) {
                hook(p, "posted", Obj.of("fromStatus", text(p, "fromStatus"), "toStatus", text(p, "toStatus"),
                        "counterpartId", text(p, "counterpartId")));
            }
        });
        events.subscribe("document.reversed", p -> hook(p, "reversed", Obj.of("counterpartId", text(p, "counterpartId"))));
        events.subscribe("document.amended", p -> hook(p, "amended", Obj.of("counterpartId", text(p, "counterpartId"))));
        events.subscribe("document.issued", p -> hook(p, "issued", Obj.of("fromStatus", text(p, "fromStatus"),
                "toStatus", text(p, "toStatus"))));
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }

    @SuppressWarnings("unchecked")
    private List<String> workflowHooks(String code) {
        Object security = jdbc.sql("SELECT security FROM \"DocumentTypeDef\" WHERE code = ?").param(code).query()
                .listOfRows().stream().findFirst().map(r -> r.get("security")).orElse(null);
        Object hooks = store.jsonMap(security).get("workflowHooks");
        return hooks instanceof List<?> l ? (List<String>) l : List.of();
    }

    private void hook(JsonNode p, String action, Map<String, Object> payload) {
        String org = p.path("organizationId").asString("");
        String documentId = p.path("documentId").asString("");
        String code = p.path("documentTypeCode").asString("");
        if (!workflowHooks(code).contains("pos_inventory")) {
            return;
        }
        try {
            jdbc.sql("INSERT INTO \"DMSWorkflowLedger\" (id, \"organizationId\", \"documentId\", \"documentTypeCode\","
                            + " action, payload) VALUES (?, ?, ?, ?, ?, ?::jsonb)")
                    .params(UUID.randomUUID().toString(), org, documentId, code, action, store.toJson(payload)).update();
        } catch (DuplicateKeyException replay) {
            // (documentId, action) unique → idempotent replay, safe.
        } catch (RuntimeException err) {
            log.error("DMS ledger write failed: {}", err.toString());
        }
    }
}
