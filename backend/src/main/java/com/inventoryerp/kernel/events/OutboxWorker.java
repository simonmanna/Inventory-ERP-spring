package com.inventoryerp.kernel.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ships pending {@code EventOutbox} rows to in-process handlers (port of {@code outbox.worker.ts}).
 * Multi-replica safe: rows are claimed with a unique token via {@code FOR UPDATE SKIP LOCKED}; a claim
 * older than 30 s is considered stale and re-claimable. Polls every {@code OUTBOX_POLL_MS} (1 s).
 * The per-row ship / fail marks commit with {@code synchronous_commit = off}: losing one in a database crash only
 * re-delivers that row, which at-least-once delivery already allows, and it saves a WAL flush per event.
 *
 * <p>Deviation from Nest (PR-1): a failed row waits {@code nextAttemptAt} (5 s doubling to 15 min) and becomes
 * {@code dead} after {@code OUTBOX_MAX_ATTEMPTS} (10) failures. In Nest a failing row was picked again on every poll
 * and stayed pending forever, so fifty broken rows stalled delivery for every other event.
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final long STALE_CLAIM_MS = 30_000;
    private static final long FIRST_BACKOFF_MS = 5_000;
    private static final long MAX_BACKOFF_MS = 15 * 60_000;

    private record Claimed(String id, String eventName, String payload, int attempts) {}

    private final JdbcClient jdbc;
    private final OutboxHandlers handlers;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxWorker(JdbcClient jdbc, OutboxHandlers handlers, JsonMapper mapper, PlatformTransactionManager txm,
            @Value("${OUTBOX_BATCH:50}") int batchSize, @Value("${OUTBOX_MAX_ATTEMPTS:10}") int maxAttempts) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.handlers = handlers;
        this.mapper = mapper;
        this.batchSize = batchSize;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** Wait before attempt {@code failures + 1}: 5 s, 10 s, 20 s, ... capped at 15 min. */
    static long backoffMs(int failures) {
        long ms = FIRST_BACKOFF_MS << Math.min(Math.max(failures - 1, 0), 20);
        return Math.min(ms, MAX_BACKOFF_MS);
    }

    @Scheduled(fixedDelayString = "${OUTBOX_POLL_MS:1000}", initialDelayString = "${OUTBOX_POLL_MS:1000}")
    public void poll() {
        try {
            tick();
        } catch (RuntimeException err) {
            log.error("Outbox worker tick failed: {}", err.toString());
        }
    }

    /** Drain a batch of pending rows. Returns the number shipped. */
    public int tick() {
        String claimToken = UUID.randomUUID().toString();
        Timestamp staleBefore = Timestamp.from(Instant.now().minusMillis(STALE_CLAIM_MS));
        List<Claimed> claimed = jdbc.sql("""
                        UPDATE "EventOutbox"
                        SET "claimToken" = ?, "claimedAt" = NOW()
                        WHERE "id" IN (
                          SELECT "id" FROM "EventOutbox"
                          WHERE (("status" = 'pending' AND ("nextAttemptAt" IS NULL OR "nextAttemptAt" <= NOW()))
                                 OR ("status" = 'claimed' AND "claimedAt" < ?))
                          ORDER BY "createdAt" ASC
                          LIMIT ?
                          FOR UPDATE SKIP LOCKED
                        )
                        RETURNING "id", "eventName", "payload"::text AS payload, attempts""")
                .params(claimToken, staleBefore, batchSize)
                .query((rs, n) -> new Claimed(rs.getString("id"), rs.getString("eventName"), rs.getString("payload"),
                        rs.getInt("attempts")))
                .list();
        // Dispatched rows are marked shipped together (one statement / commit per batch instead of per row). A crash
        // before that leaves them claimed; the stale-claim rule re-ships them, as it would a crash mid-dispatch.
        List<String> shipped = new ArrayList<>();
        try {
            for (Claimed row : claimed) {
                try {
                    JsonNode payload = row.payload() == null ? mapper.nullNode() : mapper.readTree(row.payload());
                    handlers.dispatch(row.eventName(), payload);
                    shipped.add(row.id());
                } catch (RuntimeException err) {
                    String msg = err.toString();
                    msg = msg.length() > 500 ? msg.substring(0, 500) : msg;
                    int failures = row.attempts() + 1;
                    if (failures >= maxAttempts) {
                        log.error("Outbox row {} ({}) is dead after {} attempts: {}", row.id(), row.eventName(),
                                failures, msg);
                        mark("""
                                        UPDATE "EventOutbox" SET status = 'dead', attempts = ?, "lastError" = ?,
                                               "claimToken" = NULL, "claimedAt" = NULL, "nextAttemptAt" = NULL
                                        WHERE id = ?""", failures, msg, row.id());
                    } else {
                        log.warn("Outbox row {} failed (attempt {}): {}", row.id(), failures, msg);
                        mark("""
                                        UPDATE "EventOutbox" SET attempts = ?, "lastError" = ?, "claimToken" = NULL,
                                               "claimedAt" = NULL, "nextAttemptAt" = ? WHERE id = ?""", failures, msg,
                                Timestamp.from(Instant.now().plusMillis(backoffMs(failures))), row.id());
                    }
                }
            }
        } finally {
            if (!shipped.isEmpty()) {
                tx.executeWithoutResult(s -> {
                    jdbc.sql("SET LOCAL synchronous_commit = off").update();
                    jdbc.sql("""
                                    UPDATE "EventOutbox" SET status = 'shipped', "shippedAt" = now(), "claimToken" = NULL,
                                           "claimedAt" = NULL WHERE id IN (:ids)""")
                            .param("ids", shipped)
                            .update();
                });
            }
        }
        return shipped.size();
    }

    private void mark(String sql, Object... params) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("SET LOCAL synchronous_commit = off").update();
            jdbc.sql(sql).params(params).update();
        });
    }
}
