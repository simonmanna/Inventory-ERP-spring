package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.ConversationAccessService.AccessibleConversation;
import com.inventoryerp.communication.domain.Conversation;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversation lifecycle (port of {@code conversation.service.ts}). A conversation is transport-independent; on
 * creation it always gets an internal {@code ConversationChannel}, external channels are attached by the provider
 * layer.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class ConversationService {

    /** Create input; {@code syncToDevices} is internal-only (not on the HTTP DTO). */
    public record CreateInput(String kind, String name, List<String> participantUserIds, String contextType,
            String contextId, String visibility, List<String> visibleToPermissions, Boolean syncToDevices) {}

    private final JdbcClient jdbc;
    private final ConversationAccessService access;

    public ConversationService(JdbcClient jdbc, ConversationAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** Get (or lazily create) the org's singleton internal CommunicationChannel. */
    @Transactional
    public String ensureInternalChannel() {
        String orgId = TenantContext.organizationId();
        String existing = jdbc.sql("SELECT id FROM \"CommunicationChannel\" WHERE \"providerId\" = 'internal'"
                        + " AND \"organizationId\" = ? LIMIT 1")
                .param(orgId).query(String.class).optional().orElse(null);
        if (existing != null) {
            return existing;
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CommunicationChannel\" (id, \"organizationId\", \"providerId\", transport, name, status,"
                        + " \"desiredState\", \"updatedAt\") VALUES (?, ?, 'internal', 'internal', 'Internal', 'connected',"
                        + " 'connected', ?)")
                .params(id, orgId, CommTime.nowTs()).update();
        return id;
    }

    /** Create a conversation with its internal channel binding and participants (creator = owner). */
    @Transactional
    public Map<String, Object> create(CreateInput input) {
        String orgId = TenantContext.organizationId();
        String userId = TenantContext.userId();
        String kind = input.kind() == null ? "direct" : input.kind();
        if (Boolean.TRUE.equals(input.syncToDevices()) && !"channel".equals(kind)) {
            throw ApiException.badRequest("Only channel conversations may be synced to POS devices.");
        }
        String visibility = input.visibility() != null ? input.visibility() : "channel".equals(kind) ? "role" : "private";

        String internalChannelId = ensureInternalChannel();

        // Deduplicate participants; always include the creator.
        Set<String> participantIds = new LinkedHashSet<>(input.participantUserIds() == null ? List.of()
                : input.participantUserIds());
        if (userId != null) {
            participantIds.add(userId);
        }

        Timestamp now = CommTime.nowTs();
        String convId = UUID.randomUUID().toString();
        List<String> visibleTo = input.visibleToPermissions() == null ? List.of() : input.visibleToPermissions();
        jdbc.sql("INSERT INTO \"Conversation\" (id, \"organizationId\", kind, name, \"contextType\", \"contextId\","
                        + " visibility, \"visibleToPermissions\", \"syncToDevices\", \"createdById\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(convId, orgId, kind, input.name(), input.contextType(), input.contextId(), visibility,
                        visibleTo.toArray(String[]::new), Boolean.TRUE.equals(input.syncToDevices()), userId, now)
                .update();
        String ccId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ConversationChannel\" (id, \"organizationId\", \"conversationId\", \"channelId\", active,"
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, true, ?)")
                .params(ccId, orgId, convId, internalChannelId, now).update();
        jdbc.sql("UPDATE \"Conversation\" SET \"defaultConversationChannelId\" = ?, \"updatedAt\" = ? WHERE id = ?"
                        + " AND \"organizationId\" = ?")
                .params(ccId, CommTime.nowTs(), convId, orgId).update();
        for (String uid : participantIds) {
            jdbc.sql("INSERT INTO \"ConversationParticipant\" (id, \"organizationId\", \"conversationId\","
                            + " \"participantType\", \"userId\", role) VALUES (?, ?, ?, 'user', ?, ?)")
                    .params(UUID.randomUUID().toString(), orgId, convId, uid,
                            uid.equals(userId) ? "owner" : "member")
                    .update();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", convId);
        return out;
    }

    /** Conversations the current user can access, newest first (max 100), with last message and unread count. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        String userId = TenantContext.userId();
        if (userId == null) {
            return List.of();
        }
        String orgId = TenantContext.organizationId();
        List<String> perms = TenantContext.permissions();
        boolean readAll = perms.contains(Permissions.COMMUNICATION__CONVERSATION_READ_ALL);

        // Base set: participations plus org/role-visible ones (everything with read_all); the rule gates each below.
        List<Map<String, Object>> convs = SqlRows.list(jdbc.sql("""
                        SELECT c.id, c.kind, c.name, c."contextType", c."contextId", c.visibility,
                               c."visibleToPermissions", c."updatedAt"
                        FROM "Conversation" c
                        WHERE c."organizationId" = :org AND c."deletedAt" IS NULL AND (
                          EXISTS (SELECT 1 FROM "ConversationParticipant" p WHERE p."conversationId" = c.id
                                  AND p."organizationId" = :org AND p."userId" = :user
                                  AND p."participantType" = 'user' AND p."leftAt" IS NULL)
                          OR c.visibility = 'org' OR c.visibility = 'role' OR :readAll)
                        ORDER BY c."updatedAt" DESC
                        LIMIT 100""")
                .param("org", orgId).param("user", userId).param("readAll", readAll));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> c : convs) {
            String convId = (String) c.get("id");
            List<Map<String, Object>> participants = SqlRows.list(jdbc.sql("""
                            SELECT "userId", "lastReadMessageId" FROM "ConversationParticipant"
                            WHERE "conversationId" = ? AND "organizationId" = ? AND "participantType" = 'user'
                              AND "leftAt" IS NULL""")
                    .params(convId, orgId));
            Map<String, Object> me = participants.stream().filter(p -> userId.equals(p.get("userId"))).findFirst()
                    .orElse(null);
            @SuppressWarnings("unchecked")
            List<String> visibleTo = (List<String>) c.get("visibleToPermissions");
            AccessibleConversation ac = new AccessibleConversation((String) c.get("visibility"),
                    visibleTo == null ? List.of() : visibleTo);
            if (!ConversationAccessService.canAccess(ac, me != null, perms)) {
                continue;
            }
            Map<String, Object> last = SqlRows.one(jdbc.sql("""
                            SELECT id, body, "occurredAt" FROM "Message"
                            WHERE "conversationId" = ? AND "organizationId" = ? AND "deletedAt" IS NULL
                            ORDER BY "occurredAt" DESC LIMIT 1""")
                    .params(convId, orgId));
            String lastRead = me == null ? null : (String) me.get("lastReadMessageId");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", convId);
            row.put("kind", c.get("kind"));
            row.put("name", c.get("name"));
            row.put("contextType", c.get("contextType"));
            row.put("contextId", c.get("contextId"));
            row.put("visibility", c.get("visibility"));
            row.put("updatedAt", c.get("updatedAt"));
            row.put("lastMessage", last);
            row.put("unread", unreadCount(convId, lastRead, userId));
            result.add(row);
        }
        return result;
    }

    /** Chatter: get-or-create the conversation attached to a business record (org-visible). */
    @Transactional
    public Map<String, Object> getOrCreateForContext(String contextType, String contextId) {
        String existing = jdbc.sql("SELECT id FROM \"Conversation\" WHERE \"contextType\" = ? AND \"contextId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND \"organizationId\" = ? LIMIT 1")
                .params(contextType, contextId, TenantContext.organizationId())
                .query(String.class).optional().orElse(null);
        if (existing != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", existing);
            return out;
        }
        return create(new CreateInput("direct", contextType + " " + contextId, null, contextType, contextId, "org",
                null, null));
    }

    /** The conversation row with its active participants and channel bindings. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String conversationId) {
        access.assertCanRead(conversationId);
        String orgId = TenantContext.organizationId();
        Map<String, Object> row = SqlRows.one(Conversation.class, jdbc.sql(
                        "SELECT * FROM \"Conversation\" WHERE id = ? AND \"deletedAt\" IS NULL AND \"organizationId\" = ?")
                .params(conversationId, orgId));
        if (row == null) {
            throw ApiException.notFound("Conversation not found.");
        }
        row.put("participants", SqlRows.list(jdbc.sql("""
                        SELECT "participantType", "userId", "partnerId", role FROM "ConversationParticipant"
                        WHERE "conversationId" = ? AND "organizationId" = ? AND "leftAt" IS NULL""")
                .params(conversationId, orgId)));
        row.put("channels", SqlRows.list(jdbc.sql("""
                        SELECT id, "channelId", "externalConversationId", active FROM "ConversationChannel"
                        WHERE "conversationId" = ? AND "organizationId" = ?""")
                .params(conversationId, orgId)));
        return row;
    }

    /** Advance the caller's read cursor to the given (or latest) message. */
    @Transactional
    public Map<String, Object> markRead(String conversationId, String messageId) {
        access.assertCanRead(conversationId);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        String userId = TenantContext.userId();
        if (userId == null) {
            return ok;
        }
        String orgId = TenantContext.organizationId();
        String lastRead = messageId;
        if (lastRead == null) {
            lastRead = jdbc.sql("SELECT id FROM \"Message\" WHERE \"conversationId\" = ? AND \"organizationId\" = ?"
                            + " AND \"deletedAt\" IS NULL ORDER BY \"occurredAt\" DESC LIMIT 1")
                    .params(conversationId, orgId).query(String.class).optional().orElse(null);
        }
        if (lastRead == null) {
            return ok;
        }
        jdbc.sql("UPDATE \"ConversationParticipant\" SET \"lastReadMessageId\" = ? WHERE \"conversationId\" = ?"
                        + " AND \"userId\" = ? AND \"participantType\" = 'user' AND \"organizationId\" = ?")
                .params(lastRead, conversationId, userId, orgId).update();
        return ok;
    }

    private long unreadCount(String conversationId, String lastReadMessageId, String userId) {
        String orgId = TenantContext.organizationId();
        Timestamp after = null;
        if (lastReadMessageId != null) {
            after = jdbc.sql("SELECT \"occurredAt\" FROM \"Message\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(lastReadMessageId, orgId).query(Timestamp.class).optional().orElse(null);
        }
        // Prisma `senderUserId: { not: userId }` is SQL `<>`: system/external messages (NULL sender) never count.
        String sql = "SELECT count(*) FROM \"Message\" WHERE \"conversationId\" = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND \"senderUserId\" <> ?";
        List<Object> params = new ArrayList<>(List.of(conversationId, orgId, userId));
        if (after != null) {
            sql += " AND \"occurredAt\" > ?";
            params.add(after);
        }
        return Objects.requireNonNull(jdbc.sql(sql).params(params).query(Long.class).single());
    }
}
