package com.inventoryerp.dms.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Applies approval-engine decisions on DMS documents ({@code approval.decided}, at-least-once): the final outcome
 * moves a pending document to approved / rejected. Idempotent.
 */
@Component
public class DmsApprovalSubscriber {

    private final EventBus events;
    private final DmsDocumentService documents;
    private final JdbcClient jdbc;

    public DmsApprovalSubscriber(EventBus events, DmsDocumentService documents, JdbcClient jdbc) {
        this.events = events;
        this.documents = documents;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!DmsDocumentService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String docId = p.path("entityId").asString("");
        String status = p.path("status").asString("");
        if (orgId.isEmpty() || docId.isEmpty() || !(status.equals("approved") || status.equals("rejected"))) {
            return;
        }
        String decider = jdbc.sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(p.path("requestId").asString("")).query(String.class)
                .optional().orElse(null);
        TenantContext.run(TenantStore.user(orgId, decider), () ->
                documents.applyDecision(docId, "approved".equals(status), p.path("comment").asString(null)));
    }
}
