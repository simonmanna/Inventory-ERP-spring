package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Drains the {@code MessageDelivery} work queue (port of {@code outbound/message-dispatch.worker.ts}).
 * Multi-instance safe: rows are claimed with {@code FOR UPDATE SKIP LOCKED} plus a 60 s stale-claim window. The
 * claim skips channels/conversations whose {@code nextSendAt} is in the future (coarse pacing); a provider's own
 * pacing returns {@code Deferred} at send time. Scheduled by {@link MessageDispatchScheduler}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class MessageDispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatchWorker.class);
    private static final long STALE_CLAIM_MS = 60_000;

    private record Claimed(String id, String organizationId) {}

    private final JdbcClient jdbc;
    private final MessageDispatchService dispatch;
    private final int batchSize;
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public MessageDispatchWorker(JdbcClient jdbc, MessageDispatchService dispatch,
            @Value("${COMM_DISPATCH_BATCH:50}") int batchSize) {
        this.jdbc = jdbc;
        this.dispatch = dispatch;
        this.batchSize = batchSize;
    }

    /** Claim and process one batch. Returns the number of claimed rows. */
    public int tick() {
        String claimToken = UUID.randomUUID().toString();
        Timestamp staleBefore = CommTime.ts(Instant.now().minusMillis(STALE_CLAIM_MS));
        List<Claimed> claimed = jdbc.sql("""
                        UPDATE "MessageDelivery"
                        SET "claimToken" = ?, "claimedAt" = NOW(), "status" = 'sending'
                        WHERE "id" IN (
                          SELECT x."id"
                          FROM "MessageDelivery" x
                          LEFT JOIN "CommunicationChannel" ch ON ch."id" = x."channelId"
                          LEFT JOIN "ConversationChannel" cc ON cc."id" = x."conversationChannelId"
                          WHERE ((x."status" = 'queued' AND x."nextAttemptAt" <= NOW())
                                 OR (x."status" = 'sending' AND x."claimedAt" < ?))
                            AND (ch."disabledAt" IS NULL)
                            AND (ch."nextSendAt" IS NULL OR ch."nextSendAt" <= NOW())
                            AND (cc."nextSendAt" IS NULL OR cc."nextSendAt" <= NOW())
                          ORDER BY x."nextAttemptAt" ASC, x."createdAt" ASC
                          LIMIT ?
                          FOR UPDATE OF x SKIP LOCKED
                        )
                        RETURNING "id", "organizationId\"""")
                .params(claimToken, staleBefore, batchSize)
                .query((rs, n) -> new Claimed(rs.getString(1), rs.getString(2)))
                .list();
        for (Claimed row : claimed) {
            TenantContext.run(TenantContext.TenantStore.organization(row.organizationId()), () -> {
                try {
                    dispatch.processDelivery(row.id());
                } catch (RuntimeException err) {
                    log.error("delivery {} failed: {}", row.id(), err.toString());
                }
            });
        }
        return claimed.size();
    }

    /** Keep draining while the queue yields full batches, so a burst is not paced by the poll interval. */
    public void drain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            int n;
            do {
                n = tick();
            } while (n >= batchSize);
        } catch (RuntimeException err) {
            log.error("dispatch tick failed: {}", err.toString());
        } finally {
            draining.set(false);
        }
    }
}
