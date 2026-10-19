package com.inventoryerp.kernel.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ships pending {@code EventOutbox} rows to in-process handlers (port of {@code outbox.worker.ts}).
 * Multi-replica safe: rows are claimed with a unique token via {@code FOR UPDATE SKIP LOCKED}; a claim
 * older than 30 s is considered stale and re-claimable. Polls every {@code OUTBOX_POLL_MS} (1 s).
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final long STALE_CLAIM_MS = 30_000;

    private record Claimed(String id, String eventName, String payload) {}

    private final JdbcClient jdbc;
    private final OutboxHandlers handlers;
    private final JsonMapper mapper;
    private final int batchSize;

    public OutboxWorker(JdbcClient jdbc, OutboxHandlers handlers, JsonMapper mapper,
            @Value("${OUTBOX_BATCH:50}") int batchSize) {
        this.jdbc = jdbc;
        this.handlers = handlers;
        this.mapper = mapper;
        this.batchSize = batchSize;
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
                          WHERE ("status" = 'pending' OR ("status" = 'claimed' AND "claimedAt" < ?))
                          ORDER BY "createdAt" ASC
                          LIMIT ?
                          FOR UPDATE SKIP LOCKED
                        )
                        RETURNING "id", "eventName", "payload"::text AS payload""")
                .params(claimToken, staleBefore, batchSize)
                .query((rs, n) -> new Claimed(rs.getString("id"), rs.getString("eventName"), rs.getString("payload")))
                .list();
        int shipped = 0;
        for (Claimed row : claimed) {
            int attempts = jdbc.sql("SELECT attempts FROM \"EventOutbox\" WHERE id = ?").param(row.id())
                    .query(Integer.class).optional().orElse(0);
            try {
                JsonNode payload = row.payload() == null ? mapper.nullNode() : mapper.readTree(row.payload());
                handlers.dispatch(row.eventName(), payload);
                jdbc.sql("""
                                UPDATE "EventOutbox" SET status = 'shipped', "shippedAt" = now(), "claimToken" = NULL,
                                       "claimedAt" = NULL WHERE id = ?""")
                        .param(row.id())
                        .update();
                shipped++;
            } catch (RuntimeException err) {
                String msg = err.toString();
                msg = msg.length() > 500 ? msg.substring(0, 500) : msg;
                log.warn("Outbox row {} failed (attempt {}): {}", row.id(), attempts + 1, msg);
                jdbc.sql("""
                                UPDATE "EventOutbox" SET attempts = attempts + 1, "lastError" = ?, "claimToken" = NULL,
                                       "claimedAt" = NULL WHERE id = ?""")
                        .params(msg, row.id())
                        .update();
            }
        }
        return shipped;
    }
}
