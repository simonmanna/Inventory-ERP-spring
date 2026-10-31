package com.inventoryerp.kernel.events;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator view of the organization's dead outbox rows (PR-1): events whose subscriber failed every attempt, so the
 * side effect they drive (a stock post, a notification, a gate update) did not happen. After fixing the cause an
 * operator retries them; a retried row goes back to {@code pending} with a fresh attempt count.
 */
@Service
public class OutboxAdminService {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public OutboxAdminService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Dead rows and backlog counts of the current organization, newest first (200 max). */
    @Transactional(readOnly = true)
    public Map<String, Object> dead(String eventName) {
        String org = TenantContext.organizationId();
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT id, "eventName", attempts, "lastError", "createdAt", payload::text AS payload
                        FROM "EventOutbox" WHERE "organizationId" = ? AND status = 'dead'
                          AND (?::text IS NULL OR "eventName" = ?)
                        ORDER BY "createdAt" DESC LIMIT 200""")
                .params(org, eventName, eventName)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("eventName", rs.getString("eventName"));
                    m.put("attempts", rs.getInt("attempts"));
                    m.put("lastError", rs.getString("lastError"));
                    m.put("createdAt", rs.getTimestamp("createdAt").toInstant().toString());
                    m.put("payload", rs.getString("payload"));
                    return m;
                })
                .list();
        Map<String, Object> counts = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status IN ('pending', 'claimed')) AS pending,
                               count(*) FILTER (WHERE status = 'dead') AS dead
                        FROM "EventOutbox" WHERE "organizationId" = ? AND status <> 'shipped'""")
                .param(org)
                .query((rs, n) -> Map.<String, Object>of("pending", rs.getLong("pending"), "dead", rs.getLong("dead")))
                .single();
        Map<String, Object> out = new LinkedHashMap<>(counts);
        out.put("data", rows);
        return out;
    }

    /** Put one dead row of the organization back in the queue. */
    @Transactional
    public Map<String, Object> retry(String id) {
        String org = TenantContext.organizationId();
        int n = jdbc.sql("""
                        UPDATE "EventOutbox" SET status = 'pending', attempts = 0, "nextAttemptAt" = NULL,
                               "claimToken" = NULL, "claimedAt" = NULL
                        WHERE id = ? AND "organizationId" = ? AND status = 'dead'""")
                .params(id, org)
                .update();
        if (n == 0) {
            throw ApiException.notFound("No dead outbox event with this id");
        }
        audit.recordInTx(AuditInput.of("EventOutbox", id, "update", Map.of("status", "dead"), Map.of("status", "pending")));
        return Map.of("id", id, "status", "pending");
    }

    /** Put every dead row of the organization (optionally one event name) back in the queue. */
    @Transactional
    public Map<String, Object> retryAll(String eventName) {
        String org = TenantContext.organizationId();
        int n = jdbc.sql("""
                        UPDATE "EventOutbox" SET status = 'pending', attempts = 0, "nextAttemptAt" = NULL,
                               "claimToken" = NULL, "claimedAt" = NULL
                        WHERE "organizationId" = ? AND status = 'dead' AND (?::text IS NULL OR "eventName" = ?)""")
                .params(org, eventName, eventName)
                .update();
        audit.recordInTx(AuditInput.of("EventOutbox", eventName == null ? "*" : eventName, "update", Map.of("status", "dead"),
                Map.of("status", "pending", "count", n)));
        return Map.of("retried", n);
    }
}
