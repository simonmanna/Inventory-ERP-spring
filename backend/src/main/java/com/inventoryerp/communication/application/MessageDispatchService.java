package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.providers.MessagingProvider;
import com.inventoryerp.communication.application.providers.MessagingProvider.Deferred;
import com.inventoryerp.communication.application.providers.MessagingProvider.OutboundMessage;
import com.inventoryerp.communication.application.providers.MessagingProvider.Sent;
import com.inventoryerp.communication.application.providers.ProviderRegistry;
import com.inventoryerp.communication.application.providers.ProviderSendError;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.shared.events.DomainEvents;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbound delivery logic (port of {@code message-dispatch.service.ts}). {@link #enqueue} writes
 * {@code MessageDelivery} rows (the durable work queue) in the caller's transaction; {@link #processDelivery} runs
 * per claimed row. At-least-once internally; {@code providerRequestId} is minted once and replayed on every attempt.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class MessageDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatchService.class);

    /** Retry backoff by attempt number (attempt 1 = index 0). ±20% jitter applied. */
    static final long[] BACKOFF_MS = {0, 30_000, 120_000, 600_000, 3_600_000, 21_600_000};

    /** One delivery target. */
    public record DeliveryTarget(String conversationChannelId, String channelId, String providerId,
            String recipientAddress, String recipientUserId, String recipientExternalIdentityId) {

        public DeliveryTarget(String conversationChannelId, String channelId, String providerId,
                String recipientAddress) {
            this(conversationChannelId, channelId, providerId, recipientAddress, null, null);
        }
    }

    private final JdbcClient jdbc;
    private final EventOutboxService outbox;
    private final ProviderRegistry providers;
    private final DeliveryStatusService deliveryStatus;
    private final NotificationsService notifications;
    private final TransactionTemplate tx;

    public MessageDispatchService(JdbcClient jdbc, EventOutboxService outbox, ProviderRegistry providers,
            DeliveryStatusService deliveryStatus, NotificationsService notifications, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.providers = providers;
        this.deliveryStatus = deliveryStatus;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(txm);
    }

    public static String idempotencyKey(String providerId, String channelId, String messageId, String recipientAddress) {
        return providerId + ":" + channelId + ":" + messageId + ":" + recipientAddress;
    }

    /**
     * WhatsApp dedupes on a caller-supplied id, so a WA-shaped id (22 uppercase hex chars of sha256) is minted
     * deterministically from the idempotency key. Everywhere else the opaque key is used as-is.
     */
    public static String providerRequestId(String providerId, String idempotencyKey) {
        if ("whatsapp".equals(providerId)) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash).substring(0, 22).toUpperCase();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return idempotencyKey;
    }

    /** Create delivery rows for a message inside the message-creation transaction (double-enqueue is a no-op). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String organizationId, String messageId, List<DeliveryTarget> targets) {
        for (DeliveryTarget t : targets) {
            String key = idempotencyKey(t.providerId(), t.channelId(), messageId, t.recipientAddress());
            String requestId = providerRequestId(t.providerId(), key);
            jdbc.sql("""
                            INSERT INTO "MessageDelivery" (id, "organizationId", "messageId", "conversationChannelId",
                              "channelId", "providerId", "recipientAddress", "recipientUserId",
                              "recipientExternalIdentityId", status, "nextAttemptAt", "idempotencyKey",
                              "providerRequestId", "updatedAt")
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'queued', ?, ?, ?, ?)
                            ON CONFLICT ("organizationId", "idempotencyKey") DO NOTHING""")
                    .params(UUID.randomUUID().toString(), organizationId, messageId, t.conversationChannelId(),
                            t.channelId(), t.providerId(), t.recipientAddress(), t.recipientUserId(),
                            t.recipientExternalIdentityId(), CommTime.nowTs(), key, requestId, CommTime.nowTs())
                    .update();
        }
    }

    private record Delivery(String id, String organizationId, String messageId, String conversationChannelId,
            String channelId, String providerId, String recipientAddress, String status, int attempts,
            int maxAttempts, String providerRequestId) {}

    /** Process one claimed delivery. Runs inside the row's tenant scope. */
    public void processDelivery(String deliveryId) {
        Delivery d = jdbc.sql("""
                        SELECT id, "organizationId", "messageId", "conversationChannelId", "channelId", "providerId",
                               "recipientAddress", status, attempts, "maxAttempts", "providerRequestId"
                        FROM "MessageDelivery" WHERE id = ?""")
                .param(deliveryId)
                .query((rs, n) -> new Delivery(rs.getString("id"), rs.getString("organizationId"),
                        rs.getString("messageId"), rs.getString("conversationChannelId"), rs.getString("channelId"),
                        rs.getString("providerId"), rs.getString("recipientAddress"), rs.getString("status"),
                        rs.getInt("attempts"), rs.getInt("maxAttempts"), rs.getString("providerRequestId")))
                .optional().orElse(null);
        if (d == null || List.of("delivered", "read", "failed", "cancelled").contains(d.status())) {
            return;
        }

        // Provider not registered (its flag is off) → terminal failure with a reason.
        if (!providers.has(d.providerId())) {
            fail(deliveryId, d.attempts(), "provider_disabled", "Provider '" + d.providerId() + "' is not enabled.",
                    false);
            return;
        }
        MessagingProvider provider = providers.forId(d.providerId());

        // Not ready (socket down / not configured) → defer without consuming an attempt.
        if (!provider.isReady(d.channelId())) {
            defer(deliveryId, CommTime.now().plusMillis(30_000), "channel_not_ready");
            return;
        }

        String body = jdbc.sql("SELECT body FROM \"Message\" WHERE id = ?").param(d.messageId()).query(String.class)
                .optional().orElse("");
        OutboundMessage outbound = new OutboundMessage(d.organizationId(), d.channelId(), "",
                d.conversationChannelId(), d.messageId(), d.providerRequestId(), d.recipientAddress(), body, "text");

        try {
            MessagingProvider.SendOutcome outcome = provider.send(outbound);
            if (outcome instanceof Deferred deferred) {
                defer(deliveryId, deferred.deferUntil(), deferred.reason() == null ? "paced" : deferred.reason());
                return;
            }
            Sent sent = (Sent) outcome;
            deliveryStatus.markSent(deliveryId, sent.externalMessageId(), sent.sentAt(), sent.deliveredAt());
            tx.executeWithoutResult(s -> outbox.publish(DomainEvents.COMMUNICATION_MESSAGE_DELIVERED, Obj.of(
                    "organizationId", d.organizationId(), "messageId", d.messageId(), "deliveryId", deliveryId,
                    "providerId", d.providerId(), "status", sent.deliveredAt() != null ? "delivered" : "sent")));
        } catch (ProviderSendError err) {
            if (!err.retryable()) {
                fail(deliveryId, d.attempts(), err.code(), err.getMessage(), err.ambiguous());
                return;
            }
            // Retryable. An ambiguous send is clamped so we don't keep risking dupes.
            int maxAttempts = err.ambiguous() ? Math.min(d.maxAttempts(), 3) : d.maxAttempts();
            retryOrFail(deliveryId, d.attempts(), maxAttempts, err.code(), err.getMessage(), err.ambiguous(),
                    err.retryAfterMs());
        } catch (RuntimeException err) {
            // Unknown error → retryable, unambiguous.
            retryOrFail(deliveryId, d.attempts(), d.maxAttempts(), "unknown", err.toString(), false, null);
        }
    }

    private void defer(String deliveryId, Instant deferUntil, String reason) {
        jdbc.sql("""
                        UPDATE "MessageDelivery" SET status = 'queued', "nextAttemptAt" = ?, "claimToken" = NULL,
                               "claimedAt" = NULL, "lastError" = ?, "updatedAt" = ? WHERE id = ?""")
                .params(CommTime.ts(deferUntil), reason, CommTime.nowTs(), deliveryId).update();
    }

    private void retryOrFail(String deliveryId, int attempts, int maxAttempts, String errorCode, String lastError,
            boolean ambiguous, Long retryAfterMs) {
        int nextAttempts = attempts + 1;
        if (nextAttempts >= maxAttempts) {
            fail(deliveryId, attempts, errorCode, lastError, ambiguous);
            return;
        }
        long base = retryAfterMs != null ? retryAfterMs : BACKOFF_MS[Math.min(nextAttempts, BACKOFF_MS.length - 1)];
        double jitter = base * (ThreadLocalRandom.current().nextDouble() * 0.4 - 0.2);
        jdbc.sql("""
                        UPDATE "MessageDelivery" SET status = 'queued', attempts = ?, "maxAttempts" = ?,
                               "nextAttemptAt" = ?, "claimToken" = NULL, "claimedAt" = NULL, "errorCode" = ?,
                               "lastError" = ?, ambiguous = ?, "updatedAt" = ? WHERE id = ?""")
                .params(nextAttempts, maxAttempts, CommTime.ts(CommTime.now().plusMillis(Math.round(base + jitter))),
                        errorCode, truncate(lastError), ambiguous, CommTime.nowTs(), deliveryId)
                .update();
    }

    private void fail(String deliveryId, int attempts, String errorCode, String lastError, boolean ambiguous) {
        record Failed(String organizationId, String messageId, String providerId) {}
        Failed d = jdbc.sql("""
                        UPDATE "MessageDelivery" SET status = 'failed', attempts = ?, "failedAt" = ?, "claimToken" = NULL,
                               "claimedAt" = NULL, "errorCode" = ?, "lastError" = ?, ambiguous = ?, "updatedAt" = ?
                        WHERE id = ? RETURNING "organizationId", "messageId", "providerId\"""")
                .params(attempts + 1, CommTime.nowTs(), errorCode, truncate(lastError), ambiguous, CommTime.nowTs(),
                        deliveryId)
                .query((rs, n) -> new Failed(rs.getString(1), rs.getString(2), rs.getString(3)))
                .single();
        tx.executeWithoutResult(s -> outbox.publish(DomainEvents.COMMUNICATION_MESSAGE_FAILED, Obj.of(
                "organizationId", d.organizationId(), "messageId", d.messageId(), "deliveryId", deliveryId,
                "providerId", d.providerId(), "status", "failed")));
        // Tell the sender their message could not be delivered (in-app only).
        String sender = jdbc.sql("SELECT \"senderUserId\" FROM \"Message\" WHERE id = ?").param(d.messageId())
                .query(String.class).optional().orElse(null);
        if (sender != null) {
            try {
                Map<String, Object> payload = Obj.of("dedupeKey", "comm.delivery.failed:" + deliveryId,
                        "deliveryId", deliveryId, "href", "/communication");
                notifications.send(new NotificationsService.SendInput(d.organizationId(), sender, "in_app",
                        "communication", "Message could not be delivered",
                        "A message failed to send (" + errorCode + ").", payload));
            } catch (RuntimeException e) {
                log.debug("failure notification skipped: {}", e.toString());
            }
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
