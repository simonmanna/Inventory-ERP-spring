package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.notifications.NotificationsService;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Wires approval events to in-app notifications (port of {@code approvals-notifications.subscriber.ts}):
 * requested / step_advanced → every user holding one of the current step's approver permissions;
 * decided → the requester. Runs in the outbox worker (no tenant context), deduplicated by
 * {@code payload.dedupeKey}.
 */
@Component
public class ApprovalsNotificationsSubscriber {

    private static final Logger log = LoggerFactory.getLogger(ApprovalsNotificationsSubscriber.class);

    private final EventBus events;
    private final JdbcClient jdbc;
    private final NotificationsService notifications;

    public ApprovalsNotificationsSubscriber(EventBus events, JdbcClient jdbc, NotificationsService notifications) {
        this.events = events;
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("approval.requested", this::onPending);
        events.subscribe("approval.step_advanced", this::onPending);
        events.subscribe("approval.decided", this::onDecided);
    }

    void onPending(JsonNode p) {
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String entityType = p.path("entityType").asString("");
        try {
            record Req(String status, Integer currentStep, String createdById, String workflowId) {}
            Req req = jdbc.sql("""
                            SELECT status::text AS status, "currentStep", "createdById", "workflowId" FROM "ApprovalRequest"
                            WHERE id = ? AND "organizationId" = ?""")
                    .params(requestId, orgId)
                    .query((rs, n) -> new Req(rs.getString("status"), rs.getInt("currentStep"), rs.getString("createdById"),
                            rs.getString("workflowId")))
                    .optional()
                    .orElse(null);
            if (req == null || !"pending".equals(req.status())) {
                return;
            }
            record Step(String name, List<String> perms) {}
            Step step = req.workflowId() == null ? null : jdbc.sql("""
                            SELECT name, "approverPermissions" FROM "ApprovalStep" WHERE "workflowId" = ? AND "stepOrder" = ? LIMIT 1""")
                    .params(req.workflowId(), req.currentStep())
                    .query((rs, n) -> new Step(rs.getString("name"),
                            com.inventoryerp.kernel.auth.UserAccounts.array(rs, "approverPermissions")))
                    .optional()
                    .orElse(null);
            List<String> perms = step == null ? List.of() : step.perms();
            if (perms.isEmpty()) {
                return;
            }
            List<String> approvers = jdbc.sql("""
                            SELECT u.id FROM "User" u WHERE u."organizationId" = :org AND EXISTS (
                              SELECT 1 FROM "_UserRoles" ur JOIN "Role" r ON r.id = ur."A"
                              WHERE ur."B" = u.id AND r.permissions && CAST(:perms AS text[]))""")
                    .param("org", orgId)
                    .param("perms", "{" + String.join(",", perms.stream().map(s -> "\"" + s.replace("\"", "\\\"") + "\"").toList()) + "}")
                    .query(String.class)
                    .list();
            for (String userId : approvers) {
                if (userId.equals(req.createdById())) {
                    continue;
                }
                String dedupeKey = "approval.pending:" + requestId + ":" + req.currentStep() + ":" + userId;
                if (alreadyNotified(orgId, dedupeKey)) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("dedupeKey", dedupeKey);
                payload.put("requestId", requestId);
                payload.put("stepOrder", req.currentStep());
                payload.put("href", "/approvals");
                String name = step.name() != null && !step.name().isEmpty() ? " — " + step.name() : "";
                notifications.send(new NotificationsService.SendInput(orgId, userId, "in_app", "approval",
                        "Approval needed: " + humanize(entityType),
                        "A " + humanize(entityType) + " is awaiting your approval (step " + req.currentStep() + name + ").",
                        payload));
            }
        } catch (RuntimeException err) {
            log.warn("onPending failed for {}: {}", requestId, err.toString());
        }
    }

    void onDecided(JsonNode p) {
        String orgId = p.path("organizationId").asString("");
        String requestId = p.path("requestId").asString("");
        String status = p.path("status").asString("");
        String entityType = p.path("entityType").asString("");
        try {
            String createdById = jdbc.sql("SELECT \"createdById\" FROM \"ApprovalRequest\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(requestId, orgId)
                    .query((rs, n) -> new String[] {rs.getString(1)})
                    .optional()
                    .map(a -> a[0])
                    .orElse(null);
            if (createdById == null) {
                return;
            }
            String dedupeKey = "approval.decided:" + requestId + ":" + status;
            if (alreadyNotified(orgId, dedupeKey)) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dedupeKey", dedupeKey);
            payload.put("requestId", requestId);
            payload.put("href", "/approvals");
            notifications.send(new NotificationsService.SendInput(orgId, createdById, "in_app", "approval",
                    "Approval " + status + ": " + humanize(entityType),
                    "Your " + humanize(entityType) + " request was " + status + ".", payload));
        } catch (RuntimeException err) {
            log.warn("onDecided failed for {}: {}", requestId, err.toString());
        }
    }

    private boolean alreadyNotified(String orgId, String dedupeKey) {
        return jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND payload->>'dedupeKey' = ?")
                .params(orgId, dedupeKey)
                .query(Long.class)
                .single() > 0;
    }

    private static String humanize(String entityType) {
        return entityType.replace("_", " ");
    }
}
