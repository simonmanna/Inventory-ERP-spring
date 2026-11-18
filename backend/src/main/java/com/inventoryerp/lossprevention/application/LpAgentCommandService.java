package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Work for edge agents (LP1): snapshots, calibration, clip uploads, live view and configuration changes. The agent only
 * calls out, so it fetches its work by long poll; a command queued here wakes a waiting poll on this instance at once
 * and any other instance within a few seconds.
 */
@Service
public class LpAgentCommandService {

    public static final List<String> KINDS = List.of("config_changed", "snapshot", "calibrate", "clip_upload",
            "live_start", "live_stop");
    /** A command delivered but not reported is delivered again after this long. */
    static final Duration REDELIVER_AFTER = Duration.ofMinutes(2);
    /** The longest a poll waits. */
    public static final int MAX_WAIT_SECONDS = 25;
    private static final long RECHECK_MILLIS = 5_000;
    private static final int BATCH = 20;

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, CompletableFuture<Void>> signals = new ConcurrentHashMap<>();

    public LpAgentCommandService(JdbcClient jdbc, LpScope scope, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.tx = new TransactionTemplate(txm);
    }

    /** Queues a command for the agent; a waiting poll receives it once the caller's transaction commits. */
    @Transactional
    public String queue(String agentId, String kind, Map<String, Object> payload, Duration ttl) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LpAgentCommand\" (id, \"organizationId\", \"agentId\", kind, payload, \"createdBy\","
                + " \"expiresAt\") VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)")
                .params(java.util.Arrays.asList(id, LpScope.org(), agentId, kind, scope.json(payload),
                        TenantContext.userId(), SqlRows.ts(clock.instant().plus(ttl))))
                .update();
        wakeAfterCommit(agentId);
        return id;
    }

    /** Tells the agent to read its configuration again. One pending notice per agent is enough. */
    @Transactional
    public void configChanged(String agentId, int configVersion) {
        int updated = jdbc.sql("UPDATE \"LpAgentCommand\" SET payload = ?::jsonb, \"expiresAt\" = ? WHERE"
                + " \"organizationId\" = ? AND \"agentId\" = ? AND kind = 'config_changed' AND state = 'pending'")
                .params(scope.json(Obj.of("configVersion", configVersion)),
                        SqlRows.ts(clock.instant().plus(Duration.ofDays(7))), LpScope.org(), agentId)
                .update();
        if (updated == 0) {
            queue(agentId, "config_changed", Obj.of("configVersion", configVersion), Duration.ofDays(7));
        } else {
            wakeAfterCommit(agentId);
        }
    }

    private void wakeAfterCommit(String agentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wake(agentId);
                }
            });
        } else {
            wake(agentId);
        }
    }

    private void wake(String agentId) {
        CompletableFuture<Void> signal = signals.remove(agentId);
        if (signal != null) {
            signal.complete(null);
        }
    }

    /**
     * The agent's commands that are due: pending, or delivered and not reported for {@link #REDELIVER_AFTER}. They are
     * marked delivered. Oldest first, at most 20.
     */
    @Transactional
    public List<Map<String, Object>> take(String agentId) {
        Instant now = clock.instant();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT id, kind, payload, \"createdAt\", \"expiresAt\""
                + " FROM \"LpAgentCommand\" WHERE \"organizationId\" = ? AND \"agentId\" = ? AND \"expiresAt\" > ? AND"
                + " (state = 'pending' OR (state = 'delivered' AND \"deliveredAt\" < ?)) ORDER BY \"createdAt\", id"
                + " LIMIT " + BATCH + " FOR UPDATE SKIP LOCKED")
                .params(LpScope.org(), agentId, SqlRows.ts(now), SqlRows.ts(now.minus(REDELIVER_AFTER))));
        if (rows.isEmpty()) {
            return List.of();
        }
        jdbc.sql("UPDATE \"LpAgentCommand\" SET state = 'delivered', \"deliveredAt\" = ? WHERE id = ANY(?::text[])")
                .params(SqlRows.ts(now), rows.stream().map(r -> (String) r.get("id")).toArray(String[]::new))
                .update();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(Obj.of("id", r.get("id"), "kind", r.get("kind"), "payload", LpJson.map(r.get("payload")),
                    "createdAt", r.get("createdAt"), "expiresAt", r.get("expiresAt")));
        }
        return out;
    }

    /**
     * Long poll: the due commands, waiting up to {@code waitSeconds} (at most 25) for one to be queued. No transaction
     * or connection is held while it waits.
     */
    public List<Map<String, Object>> poll(String agentId, int waitSeconds) {
        long deadline = System.currentTimeMillis() + Math.max(0, Math.min(MAX_WAIT_SECONDS, waitSeconds)) * 1000L;
        while (true) {
            CompletableFuture<Void> signal = signals.computeIfAbsent(agentId, k -> new CompletableFuture<>());
            List<Map<String, Object>> due = tx.execute(status -> take(agentId));
            long left = deadline - System.currentTimeMillis();
            if (due != null && !due.isEmpty() || left <= 0) {
                return due == null ? List.of() : due;
            }
            try {
                signal.get(Math.min(left, RECHECK_MILLIS), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Another instance may have queued it: look again.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (ExecutionException e) {
                return List.of();
            }
        }
    }

    /** A command of the agent, or 404. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String agentId, String commandId) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"LpAgentCommand\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"agentId\" = ?").params(commandId, LpScope.org(), agentId));
        if (row == null) {
            throw ApiException.notFound("Command not found");
        }
        return row;
    }

    /**
     * Records the agent's result. Reporting a finished command again changes nothing. Returns the command as it was
     * before, with {@code firstReport} true when this report is the one that finished it.
     */
    @Transactional
    public Map<String, Object> complete(String agentId, String commandId, String status, String message, String code) {
        if (!"done".equals(status) && !"failed".equals(status)) {
            throw ApiException.badRequest("status must be one of: done, failed");
        }
        Map<String, Object> row = get(agentId, commandId);
        boolean open = "pending".equals(row.get("state")) || "delivered".equals(row.get("state"));
        if (open) {
            jdbc.sql("UPDATE \"LpAgentCommand\" SET state = ?, result = ?::jsonb, \"completedAt\" = CURRENT_TIMESTAMP"
                    + " WHERE id = ?").params(status, scope.json(Obj.of("message", message, "code", code)), commandId)
                    .update();
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(row);
        out.put("payload", LpJson.map(row.get("payload")));
        out.put("firstReport", open);
        return out;
    }

    /** Commands past their expiry are expired. Returns the ids of the clip uploads that expired. */
    @Transactional
    public List<String> expire() {
        return jdbc.sql("UPDATE \"LpAgentCommand\" SET state = 'expired' WHERE \"organizationId\" = ? AND state IN"
                + " ('pending', 'delivered') AND \"expiresAt\" < CURRENT_TIMESTAMP RETURNING CASE WHEN kind ="
                + " 'clip_upload' THEN payload ->> 'evidenceId' END").param(LpScope.org()).query(String.class).list()
                .stream().filter(java.util.Objects::nonNull).toList();
    }

    /** Every open command of a revoked agent expires. */
    @Transactional
    public void expireAll(String agentId) {
        jdbc.sql("UPDATE \"LpAgentCommand\" SET state = 'expired' WHERE \"organizationId\" = ? AND \"agentId\" = ? AND"
                + " state IN ('pending', 'delivered')").params(LpScope.org(), agentId).update();
        wakeAfterCommit(agentId);
    }

    /** The open clip-upload commands of an evidence row, done once it is uploaded. */
    @Transactional
    public void finishUploads(String evidenceId) {
        jdbc.sql("UPDATE \"LpAgentCommand\" SET state = 'done', \"completedAt\" = CURRENT_TIMESTAMP, result ="
                + " '{\"message\":\"uploaded\"}'::jsonb WHERE \"organizationId\" = ? AND kind = 'clip_upload' AND state"
                + " IN ('pending', 'delivered') AND payload ->> 'evidenceId' = ?").params(LpScope.org(), evidenceId)
                .update();
    }

    /** Whether an upload of the evidence is already waiting for the agent. */
    @Transactional(readOnly = true)
    public boolean uploadPending(String evidenceId) {
        return jdbc.sql("SELECT count(*) FROM \"LpAgentCommand\" WHERE \"organizationId\" = ? AND kind = 'clip_upload'"
                + " AND state IN ('pending', 'delivered') AND payload ->> 'evidenceId' = ?")
                .params(LpScope.org(), evidenceId).query(Long.class).single() > 0;
    }
}
