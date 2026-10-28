package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.CommunicationStreamService.Audience;
import com.inventoryerp.communication.application.ConversationAccessService.AccessibleConversation;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.shared.events.DomainEvents;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single write path for inbound messages across every external provider (port of
 * {@code inbound-message.service.ts}). Idempotency is structural: the conversation is found by
 * {@code (channel, externalConversationId)} and the message is unique on {@code ExternalMessage
 * (org, provider, externalMessageId)} — a duplicate is a replay that returns the stored message. The caller
 * establishes the tenant scope.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class InboundMessageService {

    /** One inbound provider message. */
    public record InboundMessage(String organizationId, String channelId, String providerId,
            String externalConversationId, String externalMessageId, String externalSenderId, String senderAddress,
            String senderDisplayName, String body, String contentType, Instant occurredAt, Object raw) {}

    /** Ingest result: the stored message id and whether it was a replay. */
    public record Ingested(String messageId, boolean deduped) {}

    private record Identity(String id, String partnerId) {}

    private final JdbcClient jdbc;
    private final EventOutboxService outbox;
    private final CommunicationStreamService stream;
    private final ConversationAccessService access;
    private final MessageService messages;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;

    public InboundMessageService(JdbcClient jdbc, EventOutboxService outbox, CommunicationStreamService stream,
            ConversationAccessService access, MessageService messages, JsonMapper mapper,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.stream = stream;
        this.access = access;
        this.messages = messages;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
    }

    /** E.164-ish normalization: keep a leading +, strip everything else non-digit. */
    public static String normalizePhone(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String digits = raw.replaceAll("[^\\d+]", "");
        if (digits.isEmpty()) {
            return null;
        }
        return digits.startsWith("+") ? digits : "+" + digits;
    }

    public Ingested ingest(InboundMessage input) {
        String orgId = input.organizationId();
        // 1. Resolve (or create) the external identity. Never auto-create a Partner.
        Identity identity = tx.execute(s -> resolveIdentity(orgId, input));
        // 2. Resolve (or create) the conversation + its external channel binding.
        String conversationId = resolveConversation(orgId, input, identity);
        // 3. Insert the message + sidecar. Replay-safe on ExternalMessage's unique key.
        String messageId;
        try {
            messageId = tx.execute(s -> {
                String id = UUID.randomUUID().toString();
                jdbc.sql("""
                                INSERT INTO "Message" (id, "organizationId", "conversationId", "senderType",
                                  "senderExternalIdentityId", "contentType", body, "occurredAt")
                                VALUES (?, ?, ?, 'external', ?, ?, ?, ?)""")
                        .params(id, orgId, conversationId, identity == null ? null : identity.id(),
                                input.contentType() == null ? "text" : input.contentType(), input.body(),
                                CommTime.ts(input.occurredAt()))
                        .update();
                jdbc.sql("""
                                INSERT INTO "ExternalMessage" (id, "organizationId", "messageId", "providerId",
                                  "externalMessageId", "externalConversationId", "externalSenderId", "rawMetadata")
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)""")
                        .params(UUID.randomUUID().toString(), orgId, id, input.providerId(), input.externalMessageId(),
                                input.externalConversationId(), input.externalSenderId(),
                                mapper.writeValueAsString(input.raw() == null ? Map.of() : input.raw()))
                        .update();
                jdbc.sql("UPDATE \"Conversation\" SET \"updatedAt\" = ? WHERE id = ? AND \"organizationId\" = ?")
                        .params(CommTime.nowTs(), conversationId, orgId).update();
                jdbc.sql("""
                                UPDATE "ConversationChannel" SET "lastInboundAt" = ?, "updatedAt" = ?
                                WHERE "conversationId" = ? AND "channelId" = ? AND "organizationId" = ?""")
                        .params(CommTime.ts(input.occurredAt()), CommTime.nowTs(), conversationId, input.channelId(),
                                orgId)
                        .update();
                outbox.publish(DomainEvents.COMMUNICATION_MESSAGE_RECEIVED, Obj.of("organizationId", orgId,
                        "messageId", id, "conversationId", conversationId, "providerId", input.providerId(),
                        "direction", "inbound"));
                return id;
            });
        } catch (DuplicateKeyException replay) {
            String existing = jdbc.sql("""
                            SELECT "messageId" FROM "ExternalMessage" WHERE "organizationId" = ? AND "providerId" = ?
                              AND "externalMessageId" = ?""")
                    .params(orgId, input.providerId(), input.externalMessageId())
                    .query(String.class).optional().orElse(null);
            return existing == null ? null : new Ingested(existing, true);
        }
        emit(orgId, conversationId, messageId);
        return new Ingested(messageId, false);
    }

    private Identity resolveIdentity(String orgId, InboundMessage input) {
        String phone = normalizePhone(input.senderAddress());
        // Link to a Partner ONLY when exactly one matches — ambiguity stays unresolved.
        String partnerId = null;
        if (phone != null) {
            List<String> matches = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"phoneE164\" = ? AND \"organizationId\" = ?"
                            + " AND \"deletedAt\" IS NULL LIMIT 2")
                    .params(phone, orgId).query(String.class).list();
            if (matches.size() == 1) {
                partnerId = matches.getFirst();
            }
        }
        String address = phone != null ? phone : input.senderAddress();
        return jdbc.sql("""
                        INSERT INTO "ExternalIdentity" (id, "organizationId", "providerId", "externalUserId", address,
                          "displayName", "partnerId", "updatedAt")
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT ("organizationId", "providerId", "externalUserId") DO UPDATE SET
                          address = COALESCE(EXCLUDED.address, "ExternalIdentity".address),
                          "displayName" = COALESCE(EXCLUDED."displayName", "ExternalIdentity"."displayName"),
                          "partnerId" = COALESCE(EXCLUDED."partnerId", "ExternalIdentity"."partnerId"),
                          "updatedAt" = EXCLUDED."updatedAt"
                        RETURNING id, "partnerId\"""")
                .params(UUID.randomUUID().toString(), orgId, input.providerId(), input.externalSenderId(), address,
                        input.senderDisplayName(), partnerId, CommTime.nowTs())
                .query((rs, n) -> new Identity(rs.getString(1), rs.getString(2)))
                .single();
    }

    private String resolveConversation(String orgId, InboundMessage input, Identity identity) {
        String existing = jdbc.sql("""
                        SELECT "conversationId" FROM "ConversationChannel" WHERE "channelId" = ?
                          AND "externalConversationId" = ? AND "organizationId" = ? LIMIT 1""")
                .params(input.channelId(), input.externalConversationId(), orgId)
                .query(String.class).optional().orElse(null);
        if (existing != null) {
            return existing;
        }
        return tx.execute(s -> {
            String convId = UUID.randomUUID().toString();
            String name = input.senderDisplayName() != null ? input.senderDisplayName()
                    : input.senderAddress() != null ? input.senderAddress() : "External contact";
            boolean partner = identity != null && identity.partnerId() != null;
            // External customer threads are org-visible (support/managers), not private.
            jdbc.sql("""
                            INSERT INTO "Conversation" (id, "organizationId", kind, name, "contextType", "contextId",
                              visibility, "updatedAt")
                            VALUES (?, ?, 'direct', ?, ?, ?, 'org', ?)""")
                    .params(convId, orgId, name, partner ? "Partner" : null, partner ? identity.partnerId() : null,
                            CommTime.nowTs())
                    .update();
            String ccId = UUID.randomUUID().toString();
            jdbc.sql("""
                            INSERT INTO "ConversationChannel" (id, "organizationId", "conversationId", "channelId",
                              "externalConversationId", "lastInboundAt", "updatedAt")
                            VALUES (?, ?, ?, ?, ?, ?, ?)""")
                    .params(ccId, orgId, convId, input.channelId(), input.externalConversationId(),
                            CommTime.ts(input.occurredAt()), CommTime.nowTs())
                    .update();
            jdbc.sql("UPDATE \"Conversation\" SET \"defaultConversationChannelId\" = ?, \"updatedAt\" = ? WHERE id = ?")
                    .params(ccId, CommTime.nowTs(), convId).update();
            if (identity != null) {
                jdbc.sql("""
                                INSERT INTO "ConversationParticipant" (id, "organizationId", "conversationId",
                                  "participantType", "externalIdentityId")
                                VALUES (?, ?, ?, 'external', ?)""")
                        .params(UUID.randomUUID().toString(), orgId, convId, identity.id())
                        .update();
            }
            return convId;
        });
    }

    private void emit(String orgId, String conversationId, String messageId) {
        Map<String, Object> view = messages.view(messageId);
        if (view == null) {
            return;
        }
        AccessibleConversation conv = access.load(conversationId);
        if (conv == null) {
            return;
        }
        Map<String, Object> event = Obj.of("type", "message.created", "conversationId", conversationId,
                "messageId", messageId, "syncSequence", view.get("syncSequence"), "message", view);
        stream.emit(orgId, new Audience(conv, access.participantUserIds(conversationId)), event);
    }
}
