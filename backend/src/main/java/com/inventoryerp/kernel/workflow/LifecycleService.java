package com.inventoryerp.kernel.workflow;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guarded lifecycle transitions with an append-only {@code LifecycleEvent} log (port of
 * {@code lifecycle.service.ts}); joins the caller's transaction.
 */
@Service
public class LifecycleService {

    private final JdbcClient jdbc;
    private final LifecycleRegistry registry;

    public LifecycleService(JdbcClient jdbc, LifecycleRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    public void assertTransition(String defKey, String from, String to, String action) {
        registry.assertTransition(defKey, from, to, action);
    }

    /** Validate and record; returns the event id. */
    @Transactional
    public String transition(String defKey, String entityType, String entityId, String from, String to, String action,
            String refType, String refId, Map<String, Object> metadata) {
        registry.assertTransition(defKey, from, to, action);
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO "LifecycleEvent" (id, "organizationId", "defKey", "entityType", "entityId", "fromState",
                          "toState", action, "refType", "refId", "byUserId", metadata, "createdAt")
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())""")
                .params(id, TenantContext.organizationId(), defKey, entityType, entityId, from, to, action, refType, refId,
                        TenantContext.userId(), EntityJson.toJson(metadata == null ? Map.of() : metadata))
                .update();
        return id;
    }
}
