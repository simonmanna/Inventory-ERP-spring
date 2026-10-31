package com.inventoryerp.procurement.application;

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
 * Applies approval-engine decisions on purchase orders ({@code approval.decided}, at-least-once from the outbox): an
 * approved {@code purchase_order} request activates its draft order as the deciding approver, so buyers no longer have
 * to press "activate" after the approver signed (ADR-PROCUREMENT-001, P1-2). A rejection leaves the order in draft —
 * {@link PurchaseOrdersService#activate} then refuses it. Idempotent: an order that is no longer draft is left alone.
 */
@Component
public class PurchaseOrderApprovalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderApprovalSubscriber.class);

    private final EventBus events;
    private final PurchaseOrdersService orders;
    private final JdbcClient jdbc;

    public PurchaseOrderApprovalSubscriber(EventBus events, PurchaseOrdersService orders, JdbcClient jdbc) {
        this.events = events;
        this.orders = orders;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!"purchase_order".equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String orderId = p.path("entityId").asString("");
        boolean approved = "approved".equals(p.path("status").asString(""));
        if (orgId.isEmpty() || orderId.isEmpty() || !approved) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            String decider = jdbc.sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ?"
                    + " ORDER BY \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
            TenantContext.run(TenantStore.user(orgId, decider), () -> {
                boolean activated = orders.activateApproved(orderId, decider);
                if (activated) {
                    log.info("Purchase order {} activated after approval request {}", orderId, requestId);
                }
            });
        });
    }
}
