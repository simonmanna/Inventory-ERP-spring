package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Readiness gaps h-02 F7: a scheduled cost policy or standard cost activation that fails no longer leaves only a log
 * line. It counts in {@code erp_job_failures_total{task}} and opens one {@code InventoryException} of kind
 * {@value #KIND} per policy / standard (listed in the Posting Monitor; like every open inventory exception it holds the
 * period close), kept while the job keeps failing. A later successful activation, or cancelling the policy / standard,
 * resolves it.
 */
@Component
class CostActivationFailures {

    /** {@code InventoryException.kind} of a failed activation. */
    static final String KIND = "cost_activation_failed";

    private static final Logger log = LoggerFactory.getLogger(CostActivationFailures.class);
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final JobMetrics metrics;
    private final TransactionTemplate tx;

    CostActivationFailures(JdbcClient jdbc, JobMetrics metrics, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txm);
    }

    /**
     * Record a failed activation of {@code entity} {@code id} ({@code code}, for the description) of organisation
     * {@code orgId}; called outside the failed transaction. Never throws.
     */
    void failed(String task, String orgId, String entity, String id, String code, String productId, RuntimeException e) {
        metrics.jobFailed(task);
        String reason = e.getMessage() == null ? e.toString() : e.getMessage();
        log.warn("[{}] activation of {} {} failed: {}", task, entity, id, reason);
        try {
            TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> tx.executeWithoutResult(st -> {
                long open = jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND kind"
                        + " = ? AND status = 'open' AND payload->>'sourceId' = ?").params(orgId, KIND, id)
                        .query(Long.class).single();
                if (open > 0) {
                    return;
                }
                jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", \"productId\", description, quantity,"
                                + " kind, reason, payload, status, \"createdAt\") VALUES (?, ?, ?, ?, 0, ?, ?, ?::jsonb, 'open', "
                                + NOW + ")")
                        .params(UUID.randomUUID().toString(), orgId, productId, entity + " " + code
                                + " could not be activated", KIND, reason.length() > 1000 ? reason.substring(0, 1000)
                                        : reason, EntityJson.toJson(Obj.of("sourceType", entity, "sourceId", id, "code",
                                                code)))
                        .update();
            }));
        } catch (RuntimeException re) {
            log.error("[{}] failed to record the activation failure of {}: {}", task, id, re.toString());
        }
    }

    /** Resolve the open failure record of a policy / standard (inside the caller's transaction and tenant). */
    void resolved(String id, String note) {
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved', \"resolvedById\" = ?, \"resolvedAt\" = " + NOW
                        + ", \"resolutionNote\" = ? WHERE \"organizationId\" = ? AND kind = ? AND status = 'open'"
                        + " AND payload->>'sourceId' = ?")
                .params(TenantContext.userId(), note, TenantContext.organizationId(), KIND, id).update();
    }
}
