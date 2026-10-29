package com.inventoryerp.crm.application;

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
 * Applies kernel approval-engine decisions on {@value CrmQuotationService#APPROVAL_ENTITY} requests
 * ({@code approval.decided}, at-least-once): the quotation becomes approved or rejected. Runs without a request
 * tenant, so it enters the payload's organization as the deciding approver; idempotent.
 */
@Component
public class CrmApprovalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(CrmApprovalSubscriber.class);

    private final EventBus events;
    private final JdbcClient jdbc;
    private final CrmQuotationService quotations;

    public CrmApprovalSubscriber(EventBus events, JdbcClient jdbc, CrmQuotationService quotations) {
        this.events = events;
        this.jdbc = jdbc;
        this.quotations = quotations;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.decided", this::onDecided);
    }

    void onDecided(JsonNode p) {
        if (!CrmQuotationService.APPROVAL_ENTITY.equals(p.path("entityType").asString(""))) {
            return;
        }
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String quotationId = p.path("entityId").asString("");
        boolean approved = "approved".equals(p.path("status").asString(""));
        if (orgId.isEmpty() || quotationId.isEmpty()) {
            return;
        }
        String decider = jdbc.sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
        String comment = jdbc.sql("SELECT comment FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
        TenantContext.run(TenantStore.user(orgId, decider), () -> {
            quotations.applyDecision(quotationId, approved, comment);
            log.info("Quotation {} {} by approval request {}", quotationId, approved ? "approved" : "rejected",
                    requestId);
        });
    }
}
