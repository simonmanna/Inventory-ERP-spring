package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Applies approval-engine decisions on legacy Fleet invoice reviews ({@value SalesLegacyInvoiceService#APPROVAL_ENTITY},
 * {@code approval.decided}, at-least-once) made outside the review routes, e.g. in the generic approvals inbox. Enters
 * the payload's organization as the deciding approver; {@link SalesLegacyInvoiceService#applyDecision} is idempotent,
 * so a decision already applied by the review route is a no-op.
 */
@Component
public class SalesLegacyInvoiceApprovalSubscriber {

    private final EventBus events;
    private final JdbcClient jdbc;
    private final SalesLegacyInvoiceService legacy;

    public SalesLegacyInvoiceApprovalSubscriber(EventBus events, JdbcClient jdbc, SalesLegacyInvoiceService legacy) {
        this.events = events;
        this.jdbc = jdbc;
        this.legacy = legacy;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!SalesLegacyInvoiceService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String reviewId = p.path("entityId").asString("");
        if (orgId.isEmpty() || requestId.isEmpty() || reviewId.isEmpty()) {
            return;
        }
        boolean approved = "approved".equals(p.path("status").asString(""));
        String decider = jdbc.sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
        String comment = jdbc.sql("SELECT comment FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
        TenantContext.run(TenantStore.user(orgId, decider),
                () -> legacy.applyDecision(reviewId, requestId, approved, decider, comment));
    }
}
