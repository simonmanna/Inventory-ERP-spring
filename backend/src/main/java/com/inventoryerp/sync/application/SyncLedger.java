package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.shared.web.EntityJson;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 C1: the permanent applied-operation ledger ({@code SyncAppliedOp}).
 *
 * <p>Owner decision (2026-11-20): the ledger row is anchored to the op's <em>first business commit</em>. Sync ops call
 * the online services, which deliberately commit in more than one transaction (ADR-S072 R-B commits each stock line
 * on its own), so one transaction per op would undo that fix. While an op runs, the first new write transaction that
 * commits inserts the row ({@code state = committed}, opId + payload hash) in a {@code beforeCommit} callback: the row
 * and those business rows commit or roll back together. When the op returns, the row is completed with the original
 * result ({@code state = applied}).
 *
 * <ul>
 *   <li>Row present → something committed → the op is never run again (replay, or reconcile if incomplete).</li>
 *   <li>No row → nothing committed through a transaction → the same opId may safely run again.</li>
 * </ul>
 */
@Component
public class SyncLedger implements TransactionExecutionListener, SmartInitializingSingleton {

    /** One op being applied on this thread. */
    public static final class Context {
        final String organizationId;
        final String opId;
        final String payloadHash;
        final String opType;
        final String deviceId;
        final Integer deviceSeq;
        final List<String> originChain;
        final String supersedesOpId;
        volatile boolean anchored;

        public Context(String organizationId, String opId, String payloadHash, String opType, String deviceId,
                Integer deviceSeq, List<String> originChain, String supersedesOpId) {
            this.organizationId = organizationId;
            this.opId = opId;
            this.payloadHash = payloadHash;
            this.opType = opType;
            this.deviceId = deviceId;
            this.deviceSeq = deviceSeq;
            this.originChain = originChain == null ? List.of() : List.copyOf(originChain);
            this.supersedesOpId = supersedesOpId;
        }

        /** True once a business transaction of this op has committed (with its ledger row). */
        public boolean anchored() {
            return anchored;
        }
    }

    /** A ledger row. {@code result} is null until the op completed. */
    public record Entry(String payloadHash, String state, Integer httpStatus, JsonNode result) {
        public boolean applied() {
            return "applied".equals(state);
        }
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private final JdbcClient jdbc;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final String serverId;

    public SyncLedger(JdbcClient jdbc, ObjectProvider<PlatformTransactionManager> managers,
            @Value("${erp.sync.server-id:hq}") String serverId) {
        this.jdbc = jdbc;
        this.managers = managers;
        this.serverId = serverId;
    }

    /** This server's identity in {@code originChain} (ADR-S083 invariant 6). */
    public String serverId() {
        return serverId;
    }

    @Override
    public void afterSingletonsInstantiated() {
        managers.orderedStream().forEach(m -> {
            if (m instanceof ConfigurableTransactionManager c && !c.getTransactionExecutionListeners().contains(this)) {
                c.addListener(this);
            }
        });
    }

    /** Run {@code body} as the application of {@code ctx}; its first business commit carries the ledger row. */
    public <T> T run(Context ctx, Supplier<T> body) {
        Context previous = CURRENT.get();
        CURRENT.set(ctx);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        Context ctx = CURRENT.get();
        if (ctx == null || ctx.anchored || beginFailure != null || !transaction.isNewTransaction()
                || transaction.isReadOnly() || BusinessOutcome.RETRY_SAFE_TX.equals(transaction.getTransactionName())
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private boolean wrote;

            @Override
            public void beforeCommit(boolean readOnly) {
                if (!ctx.anchored && !readOnly) {
                    // Same connection as the business writes: if this insert fails, the commit fails with it.
                    wrote = insertCommitted(ctx) > 0;
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (wrote && status == STATUS_COMMITTED) {
                    ctx.anchored = true;
                }
            }
        });
    }

    private int insertCommitted(Context ctx) {
        return jdbc.sql("""
                        INSERT INTO "SyncAppliedOp" (id, "organizationId", "opId", "payloadHash", "opType", "deviceId",
                          "deviceSeq", "serverId", "originChain", "supersedesOpId", state, "committedAt")
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'committed', CURRENT_TIMESTAMP)
                        ON CONFLICT ("organizationId", "opId") DO NOTHING""")
                .params(UUID.randomUUID().toString(), ctx.organizationId, ctx.opId, ctx.payloadHash, ctx.opType,
                        ctx.deviceId, ctx.deviceSeq, serverId, EntityJson.toJson(ctx.originChain), ctx.supersedesOpId)
                .update();
    }

    /**
     * Complete the row with the op's original result. Inserts it when no transaction anchored it (an op whose writes
     * were all auto-committed, or a replay of a pre-ledger idempotency record).
     */
    public void complete(Context ctx, int httpStatus, Object result) {
        jdbc.sql("""
                        INSERT INTO "SyncAppliedOp" (id, "organizationId", "opId", "payloadHash", "opType", "deviceId",
                          "deviceSeq", "serverId", "originChain", "supersedesOpId", state, "httpStatus", "resultJson",
                          "committedAt", "completedAt")
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'applied', ?, ?::jsonb, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP)
                        ON CONFLICT ("organizationId", "opId") DO UPDATE SET state = 'applied',
                          "httpStatus" = EXCLUDED."httpStatus", "resultJson" = EXCLUDED."resultJson",
                          "completedAt" = EXCLUDED."completedAt"
                        WHERE "SyncAppliedOp"."payloadHash" = EXCLUDED."payloadHash"
                          AND "SyncAppliedOp".state = 'committed'""")
                .params(UUID.randomUUID().toString(), ctx.organizationId, ctx.opId, ctx.payloadHash, ctx.opType,
                        ctx.deviceId, ctx.deviceSeq, serverId, EntityJson.toJson(ctx.originChain), ctx.supersedesOpId,
                        httpStatus, EntityJson.toJson(result == null ? Map.of() : result))
                .update();
    }

    /** The ledger row of {@code opId} in {@code organizationId}, if any. */
    public Entry find(String organizationId, String opId) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("""
                        SELECT "payloadHash", state, "httpStatus", "resultJson"::text AS "resultJson"
                        FROM "SyncAppliedOp" WHERE "organizationId" = ? AND "opId" = ?""")
                .params(organizationId, opId));
        if (row == null) {
            return null;
        }
        Object raw = row.get("resultJson");
        JsonNode result = raw == null ? null : EntityJson.tree(raw.toString());
        Object status = row.get("httpStatus");
        return new Entry((String) row.get("payloadHash"), (String) row.get("state"),
                status == null ? null : ((Number) status).intValue(), result);
    }
}
