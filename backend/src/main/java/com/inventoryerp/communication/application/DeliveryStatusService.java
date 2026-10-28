package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.ConversationAccessService.AccessibleConversation;
import com.inventoryerp.communication.application.CommunicationStreamService.Audience;
import com.inventoryerp.kernel.common.Obj;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies delivery acks to {@code MessageDelivery} (port of {@code delivery-status.service.ts}). The update is
 * conditional on the current status ranking below the incoming one, so out-of-order acks are no-ops. Unscoped
 * ({@code prisma.raw}): ack handlers run outside any request.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class DeliveryStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusService.class);

    /** Monotonic rank — a delivery status may only ever move forward. */
    private static final List<String> RANK = List.of("queued", "sending", "sent", "delivered", "read");

    private final JdbcClient jdbc;
    private final CommunicationStreamService stream;
    private final ConversationAccessService access;

    public DeliveryStatusService(JdbcClient jdbc, CommunicationStreamService stream, ConversationAccessService access) {
        this.jdbc = jdbc;
        this.stream = stream;
        this.access = access;
    }

    /** An async provider ack: {@code providerMessageId} matches our externalMessageId or providerRequestId. */
    public record ProviderStatusUpdate(String providerId, String providerMessageId, String status, Instant at) {}

    @Transactional
    public void applyProviderStatus(ProviderStatusUpdate update) {
        int target = RANK.indexOf(update.status());
        List<String> lower = RANK.subList(0, Math.max(0, target));
        if (lower.isEmpty()) {
            return;
        }
        String column = switch (update.status()) {
            case "delivered" -> "\"deliveredAt\"";
            case "read" -> "\"readAt\"";
            default -> "\"sentAt\"";
        };
        record Row(String id, String messageId, String organizationId) {}
        List<Row> rows = jdbc.sql("""
                        SELECT id, "messageId", "organizationId" FROM "MessageDelivery"
                        WHERE "providerId" = :provider AND ("externalMessageId" = :mid OR "providerRequestId" = :mid)
                          AND status IN (:lower)""")
                .param("provider", update.providerId())
                .param("mid", update.providerMessageId())
                .param("lower", lower)
                .query((rs, n) -> new Row(rs.getString("id"), rs.getString("messageId"), rs.getString("organizationId")))
                .list();
        if (rows.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE \"MessageDelivery\" SET status = :status, " + column + " = :at, \"updatedAt\" = :now"
                        + " WHERE id IN (:ids)")
                .param("status", update.status())
                .param("at", CommTime.ts(update.at()))
                .param("now", CommTime.nowTs())
                .param("ids", rows.stream().map(Row::id).toList())
                .update();
        for (Row row : rows) {
            try {
                emitStatus(row.organizationId(), row.messageId(), update.status());
            } catch (RuntimeException e) {
                log.warn("delivery status emit failed: {}", e.toString());
            }
        }
    }

    /** Mark a delivery sent (or delivered) + record the provider id, releasing the claim. */
    @Transactional
    public void markSent(String deliveryId, String externalMessageId, Instant sentAt, Instant deliveredAt) {
        Timestamp delivered = deliveredAt == null ? null : CommTime.ts(deliveredAt);
        jdbc.sql("""
                        UPDATE "MessageDelivery" SET status = ?, "externalMessageId" = ?, "sentAt" = ?,
                               "deliveredAt" = COALESCE(?, "deliveredAt"), "claimToken" = NULL, "claimedAt" = NULL,
                               "lastError" = NULL, "updatedAt" = ?
                        WHERE id = ?""")
                .params(deliveredAt != null ? "delivered" : "sent", externalMessageId, CommTime.ts(sentAt), delivered,
                        CommTime.nowTs(), deliveryId)
                .update();
    }

    private void emitStatus(String organizationId, String messageId, String status) {
        String conversationId = jdbc.sql("SELECT \"conversationId\" FROM \"Message\" WHERE id = ?").param(messageId)
                .query(String.class).optional().orElse(null);
        if (conversationId == null) {
            return;
        }
        AccessibleConversation conv = jdbc.sql("SELECT visibility, \"visibleToPermissions\" FROM \"Conversation\""
                        + " WHERE id = ?")
                .param(conversationId)
                .query((rs, n) -> new AccessibleConversation(rs.getString("visibility"),
                        ConversationAccessService.strings(rs.getArray("visibleToPermissions"))))
                .optional().orElse(null);
        if (conv == null) {
            return;
        }
        Map<String, Object> event = Obj.of("type", "delivery.status", "conversationId", conversationId,
                "messageId", messageId, "status", status);
        stream.emit(organizationId, new Audience(conv, access.participantUserIds(conversationId)), event);
    }
}
