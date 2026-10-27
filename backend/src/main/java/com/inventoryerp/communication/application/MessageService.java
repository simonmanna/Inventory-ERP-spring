package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.CommunicationStreamService.Audience;
import com.inventoryerp.communication.application.ConversationAccessService.AccessibleConversation;
import com.inventoryerp.communication.application.MessageDispatchService.DeliveryTarget;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Messages (port of {@code message.service.ts}): chronological pages, user sends and system messages. A send
 * writes the message, its {@code MessageDelivery} rows and the {@code communication.message.created} outbox event in
 * one transaction, then pushes {@code message.created} on the inbox stream.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class MessageService {

    /** Send input. {@code targetConversationChannelIds} picks transports; default: reply's channel, else default. */
    public record SendInput(String conversationId, String body, String replyToMessageId,
            List<String> targetConversationChannelIds) {}

    /** A conversation binding (active only). */
    record Binding(String id, String channelId, String externalConversationId) {}

    static final String MESSAGE_COLUMNS = "id, \"conversationId\", \"senderType\", \"senderUserId\", \"contentType\", body,"
            + " \"replyToMessageId\", status, \"occurredAt\", \"editedAt\", seq";

    private final JdbcClient jdbc;
    private final ConversationAccessService access;
    private final EventOutboxService outbox;
    private final MessageDispatchService dispatch;
    private final CommunicationStreamService stream;

    public MessageService(JdbcClient jdbc, ConversationAccessService access, EventOutboxService outbox,
            MessageDispatchService dispatch, CommunicationStreamService stream) {
        this.jdbc = jdbc;
        this.access = access;
        this.outbox = outbox;
        this.dispatch = dispatch;
        this.stream = stream;
    }

    /** Serialized message — {@code seq} becomes {@code syncSequence} (a string, never a number). */
    static Map<String, Object> serialize(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("conversationId", rs.getString("conversationId"));
        m.put("senderType", rs.getString("senderType"));
        m.put("senderUserId", rs.getString("senderUserId"));
        m.put("contentType", rs.getString("contentType"));
        m.put("body", rs.getString("body"));
        m.put("replyToMessageId", rs.getString("replyToMessageId"));
        m.put("status", rs.getString("status"));
        m.put("occurredAt", iso(rs.getTimestamp("occurredAt")));
        Timestamp edited = rs.getTimestamp("editedAt");
        m.put("editedAt", edited == null ? null : iso(edited));
        m.put("syncSequence", String.valueOf(rs.getLong("seq")));
        return m;
    }

    private static String iso(Timestamp ts) {
        return com.inventoryerp.kernel.web.JsonConfig.toJsIso(ts.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC));
    }

    /** Load a serialized message by id (unscoped: callers already hold the org). */
    Map<String, Object> view(String messageId) {
        return jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM \"Message\" WHERE id = ?").param(messageId)
                .query((rs, n) -> serialize(rs)).optional().orElse(null);
    }

    /** Chronological page (ascending) of a conversation's messages; {@code limit} clamped to 1..200 (default 50). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String conversationId, String beforeSeq, Integer limit) {
        access.assertCanRead(conversationId);
        int take = Math.min(200, Math.max(1, limit == null ? 50 : limit));
        String sql = "SELECT " + MESSAGE_COLUMNS + " FROM \"Message\" WHERE \"conversationId\" = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL";
        List<Object> params = new ArrayList<>(List.of(conversationId, TenantContext.organizationId()));
        if (beforeSeq != null && !beforeSeq.isEmpty()) {
            long before;
            try {
                before = Long.parseLong(beforeSeq.trim());
            } catch (NumberFormatException e) {
                // Nest: BigInt('x') throws a SyntaxError → 500.
                throw new IllegalArgumentException("Cannot convert " + beforeSeq + " to a BigInt");
            }
            sql += " AND seq < ?";
            params.add(before);
        }
        sql += " ORDER BY seq DESC LIMIT ?";
        params.add(take);
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.sql(sql).params(params).query((rs, n) -> serialize(rs))
                .list());
        java.util.Collections.reverse(rows);
        return rows;
    }

    /** A user send over the resolved target channels. */
    @Transactional
    public Map<String, Object> send(SendInput input) {
        String orgId = TenantContext.organizationId();
        String userId = TenantContext.userId();
        if (userId == null) {
            throw ApiException.forbidden("Authentication required to send a message.");
        }
        if (input.body() == null || input.body().trim().isEmpty()) {
            throw ApiException.badRequest("Message body is required.");
        }
        access.assertCanRead(input.conversationId());

        Map<String, Object> conv = conversation(input.conversationId());
        if (conv == null) {
            throw ApiException.badRequest("Conversation not found.");
        }
        @SuppressWarnings("unchecked")
        List<Binding> channels = (List<Binding>) conv.get("channels");
        List<Binding> targetChannels = resolveTargets((String) conv.get("defaultConversationChannelId"), channels,
                input);
        if (targetChannels.isEmpty()) {
            throw ApiException.badRequest("No active channel to send on.");
        }
        List<DeliveryTarget> targets = targets(targetChannels);

        String messageId = insertMessage(orgId, input.conversationId(), "user", userId, input.body().trim(),
                input.replyToMessageId());
        touch(input.conversationId());
        dispatch.enqueue(orgId, messageId, targets);
        // The sender has read their own message.
        jdbc.sql("UPDATE \"ConversationParticipant\" SET \"lastReadMessageId\" = ? WHERE \"conversationId\" = ?"
                        + " AND \"userId\" = ? AND \"participantType\" = 'user' AND \"organizationId\" = ?")
                .params(messageId, input.conversationId(), userId, orgId).update();
        outbox.publish(DomainEvents.COMMUNICATION_MESSAGE_CREATED, Obj.of("organizationId", orgId,
                "messageId", messageId, "conversationId", input.conversationId(), "providerId", "internal",
                "direction", "outbound"));

        Map<String, Object> view = view(messageId);
        emitCreatedAfterCommit(orgId, input.conversationId(), view);
        return view;
    }

    /**
     * Persist a system-authored message (no acting user) and enqueue delivery — used by the rule engine. Bypasses the
     * per-user access check; runs in the caller's tenant scope. {@code null} when nothing can be sent.
     */
    @Transactional
    public Map<String, Object> postSystemMessage(String conversationId, String body, String providerFilter) {
        String orgId = TenantContext.organizationId();
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        Map<String, Object> conv = conversation(conversationId);
        if (conv == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Binding> channels = new ArrayList<>((List<Binding>) conv.get("channels"));
        if (providerFilter != null) {
            Set<String> allow = Set.copyOf(jdbc.sql("SELECT id FROM \"CommunicationChannel\" WHERE \"providerId\" = ?"
                            + " AND \"organizationId\" = ?")
                    .params(providerFilter, orgId).query(String.class).list());
            channels.removeIf(c -> !allow.contains(c.channelId()));
        }
        if (channels.isEmpty()) {
            return null;
        }
        List<DeliveryTarget> targets = targets(channels);
        String messageId = insertMessage(orgId, conversationId, "system", null, body.trim(), null);
        touch(conversationId);
        dispatch.enqueue(orgId, messageId, targets);
        outbox.publish(DomainEvents.COMMUNICATION_MESSAGE_CREATED, Obj.of("organizationId", orgId,
                "messageId", messageId, "conversationId", conversationId, "providerId", "internal",
                "direction", "outbound"));
        Map<String, Object> view = view(messageId);
        emitCreatedAfterCommit(orgId, conversationId, view);
        return view;
    }

    private String insertMessage(String orgId, String conversationId, String senderType, String senderUserId,
            String body, String replyTo) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO "Message" (id, "organizationId", "conversationId", "senderType", "senderUserId",
                          "contentType", body, "replyToMessageId", "occurredAt")
                        VALUES (?, ?, ?, ?, ?, 'text', ?, ?, ?)""")
                .params(id, orgId, conversationId, senderType, senderUserId, body, replyTo, CommTime.nowTs())
                .update();
        return id;
    }

    private void touch(String conversationId) {
        jdbc.sql("UPDATE \"Conversation\" SET \"updatedAt\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(CommTime.nowTs(), conversationId, TenantContext.organizationId()).update();
    }

    /** The conversation (not deleted) with its active channel bindings, or {@code null}. */
    private Map<String, Object> conversation(String conversationId) {
        String orgId = TenantContext.organizationId();
        String defaultCc = jdbc.sql("SELECT coalesce(\"defaultConversationChannelId\", '') FROM \"Conversation\""
                        + " WHERE id = ? AND \"deletedAt\" IS NULL AND \"organizationId\" = ?")
                .params(conversationId, orgId).query(String.class).optional().orElse(null);
        if (defaultCc == null) {
            return null;
        }
        List<Binding> channels = jdbc.sql("SELECT id, \"channelId\", \"externalConversationId\" FROM \"ConversationChannel\""
                        + " WHERE \"conversationId\" = ? AND active = true AND \"organizationId\" = ?")
                .params(conversationId, orgId)
                .query((rs, n) -> new Binding(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list();
        Map<String, Object> out = new HashMap<>();
        out.put("defaultConversationChannelId", defaultCc.isEmpty() ? null : defaultCc);
        out.put("channels", channels);
        return out;
    }

    /** Internal is a single broadcast row; external channels address the external thread. */
    private List<DeliveryTarget> targets(List<Binding> channels) {
        Map<String, String> providerById = new HashMap<>();
        if (!channels.isEmpty()) {
            jdbc.sql("SELECT id, \"providerId\" FROM \"CommunicationChannel\" WHERE id IN (:ids)"
                            + " AND \"organizationId\" = :org")
                    .param("ids", channels.stream().map(Binding::channelId).toList())
                    .param("org", TenantContext.organizationId())
                    .query((rs, n) -> providerById.put(rs.getString(1), rs.getString(2)))
                    .list();
        }
        List<DeliveryTarget> out = new ArrayList<>();
        for (Binding cc : channels) {
            String providerId = providerById.getOrDefault(cc.channelId(), "internal");
            String address = "internal".equals(providerId) ? cc.channelId()
                    : cc.externalConversationId() != null ? cc.externalConversationId() : cc.channelId();
            out.add(new DeliveryTarget(cc.id(), cc.channelId(), providerId, address));
        }
        return out;
    }

    private List<Binding> resolveTargets(String defaultCc, List<Binding> channels, SendInput input) {
        if (input.targetConversationChannelIds() != null && !input.targetConversationChannelIds().isEmpty()) {
            return channels.stream().filter(c -> input.targetConversationChannelIds().contains(c.id())).toList();
        }
        if (input.replyToMessageId() != null) {
            // A reply follows the channel of the message it replies to (via its delivery).
            String ccId = jdbc.sql("SELECT \"conversationChannelId\" FROM \"MessageDelivery\" WHERE \"messageId\" = ?"
                            + " AND \"organizationId\" = ? LIMIT 1")
                    .params(input.replyToMessageId(), TenantContext.organizationId())
                    .query(String.class).optional().orElse(null);
            if (ccId != null) {
                for (Binding c : channels) {
                    if (c.id().equals(ccId)) {
                        return List.of(c);
                    }
                }
            }
        }
        if (defaultCc != null) {
            for (Binding c : channels) {
                if (c.id().equals(defaultCc)) {
                    return List.of(c);
                }
            }
        }
        // Fallback: every active binding.
        return channels;
    }

    /** Push {@code message.created} to the conversation's audience once the send has committed. */
    private void emitCreatedAfterCommit(String orgId, String conversationId, Map<String, Object> view) {
        AfterCommit.run(() -> TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
            AccessibleConversation conv = access.load(conversationId);
            if (conv == null) {
                return;
            }
            Map<String, Object> event = Obj.of("type", "message.created", "conversationId", conversationId,
                    "messageId", view.get("id"), "syncSequence", view.get("syncSequence"), "message", view);
            stream.emit(orgId, new Audience(conv, access.participantUserIds(conversationId)), event);
        }));
    }
}
