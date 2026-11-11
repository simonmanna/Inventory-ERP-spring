package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Discuss conversations (ADR-S064): sidebar, directory, channels (create, browse, join, leave, members, settings,
 * archive), direct and group chats, read cursors and typing. Messages are in {@link DiscussMessageService}.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class DiscussService {

    /** "Until I unmute": a far-future mute. */
    static final Instant MUTE_FOREVER = Instant.parse("2999-12-31T00:00:00Z");
    static final int MAX_CHAT_USERS = 20;

    /** Channel creation input. */
    public record ChannelInput(String name, String description, String visibility, List<String> memberIds) {}

    /** Conversation edit input (nulls are left unchanged). */
    public record UpdateInput(String name, String description, String visibility) {}

    /** Per-member settings input (nulls are left unchanged). */
    public record SettingsInput(String notifyLevel, Integer muteMinutes, Boolean pinned) {}

    private final JdbcClient jdbc;
    private final ConversationService conversations;
    private final DiscussViews views;
    private final DiscussEvents events;

    public DiscussService(JdbcClient jdbc, ConversationService conversations, DiscussViews views,
            DiscussEvents events) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.views = views;
        this.events = events;
    }

    /* ── Read models ───────────────────────────────────────────────────────── */

    /** Sidebar: channels and chats the caller has joined, plus counters. Joins {@code #general} on first use. */
    @Transactional
    public Map<String, Object> sidebar() {
        String userId = DiscussViews.requireUser();
        ensureGeneral(userId);
        List<Map<String, Object>> items = views.sidebarItems(userId);
        List<Map<String, Object>> channels = new ArrayList<>();
        List<Map<String, Object>> chats = new ArrayList<>();
        for (Map<String, Object> i : items) {
            ("channel".equals(i.get("kind")) ? channels : chats).add(i);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("me", DiscussViews.person(userId, views.name(userId)));
        out.put("counters", counters(userId, items));
        out.put("channels", channels);
        out.put("chats", chats);
        return out;
    }

    /** {@code {unread, inbox, starred}} for the header badge. */
    @Transactional(readOnly = true)
    public Map<String, Object> counters() {
        String userId = DiscussViews.requireUser();
        return counters(userId, views.sidebarItems(userId));
    }

    private Map<String, Object> counters(String userId, List<Map<String, Object>> items) {
        String orgId = TenantContext.organizationId();
        long unread = items.stream().mapToLong(i -> ((Number) i.get("badge")).longValue()).sum();
        long inbox = jdbc.sql("SELECT count(*) FROM \"MessageInboxItem\" i JOIN \"Message\" m ON m.id = i.\"messageId\""
                        + " WHERE i.\"organizationId\" = ? AND i.\"userId\" = ? AND i.\"readAt\" IS NULL"
                        + " AND m.\"deletedAt\" IS NULL")
                .params(orgId, userId).query(Long.class).single();
        long starred = jdbc.sql("SELECT count(*) FROM \"MessageStar\" s JOIN \"Message\" m ON m.id = s.\"messageId\""
                        + " WHERE s.\"organizationId\" = ? AND s.\"userId\" = ? AND m.\"deletedAt\" IS NULL")
                .params(orgId, userId).query(Long.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unread", unread);
        out.put("inbox", inbox);
        out.put("starred", starred);
        return out;
    }

    /** Staff directory (active users with department and presence) and the departments with their channels. */
    @Transactional(readOnly = true)
    public Map<String, Object> directory() {
        String userId = DiscussViews.requireUser();
        String orgId = TenantContext.organizationId();
        Set<String> online = events.online();
        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                        SELECT u.id, u.email, u."firstName", u."lastName", d.id AS "departmentId",
                               d.name AS "departmentName"
                        FROM "User" u
                        LEFT JOIN LATERAL (SELECT e."departmentId" FROM "HrEmployee" e
                             WHERE e."userId" = u.id AND e."organizationId" = u."organizationId"
                               AND e."deletedAt" IS NULL AND e."isActive" = true
                             ORDER BY e."createdAt" DESC LIMIT 1) emp ON true
                        LEFT JOIN "HrDepartment" d ON d.id = emp."departmentId" AND d."deletedAt" IS NULL
                        WHERE u."organizationId" = ? AND u."deletedAt" IS NULL AND u."isActive" = true
                        ORDER BY u."firstName", u."lastName\"""")
                .param(orgId))) {
            String name = DiscussViews.fullName((String) r.get("firstName"), (String) r.get("lastName"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("name", name);
            m.put("email", r.get("email"));
            m.put("initials", DiscussViews.initials(name));
            m.put("departmentId", r.get("departmentId"));
            m.put("departmentName", r.get("departmentName"));
            m.put("online", online.contains((String) r.get("id")));
            users.add(m);
        }
        List<Map<String, Object>> departments = SqlRows.list(jdbc.sql("""
                        SELECT d.id, d.name, d.code,
                               (SELECT count(DISTINCT e."userId") FROM "HrEmployee" e WHERE e."departmentId" = d.id
                                  AND e."userId" IS NOT NULL AND e."deletedAt" IS NULL AND e."isActive" = true)
                                 AS "memberCount",
                               c.id AS "channelId",
                               (p.id IS NOT NULL) AS joined
                        FROM "HrDepartment" d
                        LEFT JOIN "Conversation" c ON c."departmentId" = d.id AND c."organizationId" = d."organizationId"
                             AND c."deletedAt" IS NULL
                        LEFT JOIN "ConversationParticipant" p ON p."conversationId" = c.id AND p."userId" = ?
                             AND p."participantType" = 'user' AND p."leftAt" IS NULL
                        WHERE d."organizationId" = ? AND d."deletedAt" IS NULL AND d."isActive" = true
                        ORDER BY d.name""")
                .params(userId, orgId));
        departments.forEach(d -> d.put("memberCount", ((Number) d.get("memberCount")).longValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("users", users);
        out.put("departments", departments);
        return out;
    }

    /** Users with an open stream (this instance). */
    public Map<String, Object> presence() {
        DiscussViews.requireUser();
        return Obj.of("online", List.copyOf(events.online()));
    }

    /** Public channels plus the caller's own; {@code archived} lists archived ones instead. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> browseChannels(boolean archived) {
        String userId = DiscussViews.requireUser();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("""
                        SELECT c.id, c.name, c.description, c.visibility, c."departmentId", c."archivedAt",
                               c."updatedAt" AS "lastActivityAt",
                               (SELECT count(*) FROM "ConversationParticipant" q WHERE q."conversationId" = c.id
                                  AND q."participantType" = 'user' AND q."leftAt" IS NULL) AS "memberCount",
                               (p.id IS NOT NULL) AS joined
                        FROM "Conversation" c
                        LEFT JOIN "ConversationParticipant" p ON p."conversationId" = c.id AND p."userId" = :me
                             AND p."participantType" = 'user' AND p."leftAt" IS NULL
                        WHERE c."organizationId" = :org AND c."deletedAt" IS NULL AND c.kind = 'channel'
                          AND c."contextType" IS NULL AND (c.visibility = 'org' OR p.id IS NOT NULL)
                          AND (c."archivedAt" IS NOT NULL) = :archived
                        ORDER BY lower(c.name)
                        LIMIT 500""")
                .param("me", userId).param("org", TenantContext.organizationId()).param("archived", archived));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("name", r.get("name"));
            m.put("description", r.get("description"));
            m.put("visibility", DiscussViews.visibilityOut(r.get("visibility")));
            m.put("departmentId", r.get("departmentId"));
            m.put("memberCount", ((Number) r.get("memberCount")).longValue());
            m.put("joined", Boolean.TRUE.equals(r.get("joined")));
            m.put("archived", r.get("archivedAt") != null);
            m.put("lastActivityAt", r.get("lastActivityAt"));
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String conversationId) {
        String userId = DiscussViews.requireUser();
        views.assertRead(conversationId);
        return views.detail(conversationId, userId);
    }

    /* ── Create ────────────────────────────────────────────────────────────── */

    /** A new channel; the caller is its owner. */
    @Transactional
    public Map<String, Object> createChannel(ChannelInput input) {
        String userId = DiscussViews.requireUser();
        String name = channelName(input.name());
        lock("channel:" + name.toLowerCase());
        assertChannelNameFree(name, null);
        List<String> members = validUsers(input.memberIds());
        String visibility = "private".equals(input.visibility()) ? "private" : "org";
        String id = (String) conversations.create(new ConversationService.CreateInput("channel", name, members, null,
                null, visibility, null, null)).get("id");
        jdbc.sql("UPDATE \"Conversation\" SET description = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(blankToNull(input.description()), id, TenantContext.organizationId()).update();
        events.conversationUpdated(id, members);
        return views.item(id, userId);
    }

    /** One user → the (unique) direct message with them; several → a new private group chat. */
    @Transactional
    public Map<String, Object> chat(List<String> userIds) {
        String userId = DiscussViews.requireUser();
        List<String> others = new ArrayList<>(validUsers(userIds));
        if (others.isEmpty()) {
            throw ApiException.badRequest("userIds should not be empty");
        }
        if (others.size() > MAX_CHAT_USERS) {
            throw ApiException.badRequest("A chat can have at most " + MAX_CHAT_USERS + " other members.");
        }
        String orgId = TenantContext.organizationId();
        if (others.size() == 1) {
            String other = others.getFirst();
            String key = other.compareTo(userId) < 0 ? other + ":" + userId : userId + ":" + other;
            lock("direct:" + key);
            String existing = jdbc.sql("SELECT id FROM \"Conversation\" WHERE \"organizationId\" = ? AND \"directKey\" = ?"
                            + " AND \"deletedAt\" IS NULL")
                    .params(orgId, key).query(String.class).optional().orElse(null);
            if (existing != null) {
                // Someone who had left a DM comes back on the next message anyway; rejoin both now.
                addParticipant(existing, userId, "member", "manual");
                addParticipant(existing, other, "member", "manual");
                return views.item(existing, userId);
            }
            String id = (String) conversations.create(new ConversationService.CreateInput("direct", null,
                    List.of(other), null, null, "private", null, null)).get("id");
            jdbc.sql("UPDATE \"Conversation\" SET \"directKey\" = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(key, id, orgId).update();
            events.conversationUpdated(id, List.of());
            return views.item(id, userId);
        }
        String id = (String) conversations.create(new ConversationService.CreateInput("group", null, others, null,
                null, "private", null, null)).get("id");
        events.conversationUpdated(id, List.of());
        return views.item(id, userId);
    }

    /** The org's {@code #general} channel exists and the user is in it, unless they left it before. */
    void ensureGeneral(String userId) {
        String orgId = TenantContext.organizationId();
        String id = generalId(orgId);
        if (id != null && participantRow(id, userId) != null) {
            return;
        }
        lock("general");
        id = generalId(orgId);
        if (id == null) {
            id = jdbc.sql("SELECT id FROM \"Conversation\" WHERE \"organizationId\" = ? AND kind = 'channel'"
                            + " AND \"contextType\" IS NULL AND lower(name) = 'general' AND \"deletedAt\" IS NULL LIMIT 1")
                    .param(orgId).query(String.class).optional().orElse(null);
            if (id == null) {
                id = (String) conversations.create(new ConversationService.CreateInput("channel", "general", null, null,
                        null, "org", null, null)).get("id");
                jdbc.sql("UPDATE \"Conversation\" SET description = 'Company-wide announcements and conversation'"
                        + " WHERE id = ? AND \"organizationId\" = ?").params(id, orgId).update();
            }
            jdbc.sql("UPDATE \"Conversation\" SET \"autoJoin\" = true WHERE id = ? AND \"organizationId\" = ?")
                    .params(id, orgId).update();
        }
        if (participantRow(id, userId) == null) {
            insertParticipant(id, userId, "member", "auto");
        }
    }

    private String generalId(String orgId) {
        return jdbc.sql("SELECT id FROM \"Conversation\" WHERE \"organizationId\" = ? AND \"autoJoin\" = true"
                        + " AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" LIMIT 1")
                .param(orgId).query(String.class).optional().orElse(null);
    }

    /* ── Edit / membership ─────────────────────────────────────────────────── */

    @Transactional
    public Map<String, Object> update(String conversationId, UpdateInput input) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> head = views.assertRead(conversationId);
        Map<String, Object> me = views.membership(conversationId, userId);
        String kind = (String) head.get("kind");
        String orgId = TenantContext.organizationId();
        if ("channel".equals(kind)) {
            views.assertModerator(me);
        } else if (!"group".equals(kind) || me == null || head.get("contextType") != null) {
            throw ApiException.forbidden("Only a channel moderator can do this.");
        }
        if (input.name() != null) {
            String name = "channel".equals(kind) ? channelName(input.name()) : blankToNull(input.name());
            if ("channel".equals(kind)) {
                lock("channel:" + name.toLowerCase());
                assertChannelNameFree(name, conversationId);
            }
            jdbc.sql("UPDATE \"Conversation\" SET name = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(name, conversationId, orgId).update();
        }
        if (input.description() != null) {
            jdbc.sql("UPDATE \"Conversation\" SET description = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(blankToNull(input.description()), conversationId, orgId).update();
        }
        if (input.visibility() != null && "channel".equals(kind)) {
            jdbc.sql("UPDATE \"Conversation\" SET visibility = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params("private".equals(input.visibility()) ? "private" : "org", conversationId, orgId).update();
        }
        touch(conversationId);
        events.conversationUpdated(conversationId, List.of());
        return views.detail(conversationId, userId);
    }

    /** Join a channel the caller can see. */
    @Transactional
    public Map<String, Object> join(String conversationId) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> head = views.assertRead(conversationId);
        if (!"channel".equals(head.get("kind")) || head.get("contextType") != null) {
            throw ApiException.badRequest("Only channels can be joined.");
        }
        if (head.get("archivedAt") != null) {
            throw ApiException.badRequest("This conversation is archived.");
        }
        if (addParticipant(conversationId, userId, "member", "manual")) {
            events.conversationUpdated(conversationId, List.of(userId));
        }
        return views.detail(conversationId, userId);
    }

    @Transactional
    public Map<String, Object> leave(String conversationId) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> head = views.head(conversationId);
        if (head == null) {
            throw ApiException.notFound("Conversation not found.");
        }
        if ("direct".equals(head.get("kind")) && head.get("contextType") == null) {
            throw ApiException.badRequest("You cannot leave a direct conversation.");
        }
        removeParticipant(conversationId, userId);
        // A deliberate leave is not undone by the department sync.
        jdbc.sql("UPDATE \"ConversationParticipant\" SET \"addedVia\" = 'manual' WHERE \"conversationId\" = ?"
                        + " AND \"userId\" = ? AND \"participantType\" = 'user' AND \"organizationId\" = ?")
                .params(conversationId, userId, TenantContext.organizationId()).update();
        events.conversationUpdated(conversationId, List.of(userId));
        return Obj.of("ok", true);
    }

    @Transactional
    public Map<String, Object> addMembers(String conversationId, List<String> userIds) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> head = views.assertRead(conversationId);
        if ("direct".equals(head.get("kind")) && head.get("contextType") == null) {
            throw ApiException.badRequest("Members cannot be added to a direct conversation.");
        }
        if (head.get("archivedAt") != null) {
            throw ApiException.badRequest("This conversation is archived.");
        }
        Map<String, Object> me = views.membership(conversationId, userId);
        if (me == null && !views.isModerator(null)) {
            throw ApiException.forbidden("Only a channel moderator can do this.");
        }
        List<String> added = new ArrayList<>();
        for (String u : validUsers(userIds)) {
            if (addParticipant(conversationId, u, "member", head.get("contextType") != null ? "follow" : "manual")) {
                added.add(u);
            }
        }
        if (!added.isEmpty()) {
            touch(conversationId);
            events.conversationUpdated(conversationId, added);
        }
        return views.detail(conversationId, userId);
    }

    @Transactional
    public Map<String, Object> removeMember(String conversationId, String memberId) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> head = views.assertRead(conversationId);
        if ("direct".equals(head.get("kind")) && head.get("contextType") == null) {
            throw ApiException.badRequest("Members cannot be removed from a direct conversation.");
        }
        if (!memberId.equals(userId)) {
            Map<String, Object> me = views.membership(conversationId, userId);
            if ("group".equals(head.get("kind")) ? me == null : !views.isModerator(me)) {
                throw ApiException.forbidden("Only a channel moderator can do this.");
            }
        }
        removeParticipant(conversationId, memberId);
        events.conversationUpdated(conversationId, List.of(memberId));
        return views.detail(conversationId, userId);
    }

    /** The caller's notify level, mute and pin. */
    @Transactional
    public Map<String, Object> settings(String conversationId, SettingsInput input) {
        String userId = DiscussViews.requireUser();
        views.assertRead(conversationId);
        Map<String, Object> me = views.membership(conversationId, userId);
        if (me == null) {
            throw ApiException.badRequest("Join the conversation first.");
        }
        String orgId = TenantContext.organizationId();
        if (input.notifyLevel() != null) {
            jdbc.sql("UPDATE \"ConversationParticipant\" SET \"notifyLevel\" = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(input.notifyLevel(), me.get("id"), orgId).update();
        }
        if (input.muteMinutes() != null) {
            int minutes = input.muteMinutes();
            Timestamp until = minutes == 0 ? null : SqlRows.ts(minutes < 0 ? MUTE_FOREVER
                    : Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(minutes, ChronoUnit.MINUTES));
            jdbc.sql("UPDATE \"ConversationParticipant\" SET \"mutedUntil\" = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(until, me.get("id"), orgId).update();
        }
        if (input.pinned() != null) {
            jdbc.sql("UPDATE \"ConversationParticipant\" SET pinned = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(input.pinned(), me.get("id"), orgId).update();
        }
        events.toUsers(List.of(userId), Obj.of("type", "conversation.updated", "conversationId", conversationId));
        return views.item(conversationId, userId);
    }

    @Transactional
    public Map<String, Object> setArchived(String conversationId, boolean archived) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> head = views.assertRead(conversationId);
        if (!"channel".equals(head.get("kind")) && !"group".equals(head.get("kind"))
                || head.get("contextType") != null || Boolean.TRUE.equals(head.get("autoJoin")) && archived) {
            throw ApiException.forbidden("Only a channel moderator can do this.");
        }
        views.assertModerator(views.membership(conversationId, userId));
        jdbc.sql("UPDATE \"Conversation\" SET \"archivedAt\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(archived ? CommTime.nowTs() : null, conversationId, TenantContext.organizationId()).update();
        events.conversationUpdated(conversationId, List.of());
        return views.detail(conversationId, userId);
    }

    /* ── Read cursor / typing ──────────────────────────────────────────────── */

    /** Advance the caller's read cursor (and clear inbox items up to it); tells the audience who has seen what. */
    @Transactional
    public Map<String, Object> markRead(String conversationId, String messageId) {
        String userId = DiscussViews.requireUser();
        views.assertRead(conversationId);
        String orgId = TenantContext.organizationId();
        Map<String, Object> target = SqlRows.one(messageId != null
                ? jdbc.sql("SELECT id, seq FROM \"Message\" WHERE id = ? AND \"conversationId\" = ?"
                        + " AND \"organizationId\" = ?").params(messageId, conversationId, orgId)
                : jdbc.sql("SELECT id, seq FROM \"Message\" WHERE \"conversationId\" = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL ORDER BY seq DESC LIMIT 1").params(conversationId, orgId));
        if (target == null) {
            return Obj.of("ok", true);
        }
        long seq = ((Number) target.get("seq")).longValue();
        int moved = jdbc.sql("""
                        UPDATE "ConversationParticipant" p SET "lastReadMessageId" = :msg
                        WHERE p."conversationId" = :conv AND p."userId" = :me AND p."participantType" = 'user'
                          AND p."organizationId" = :org AND p."leftAt" IS NULL
                          AND coalesce((SELECT r.seq FROM "Message" r WHERE r.id = p."lastReadMessageId"), 0) < :seq""")
                .param("msg", target.get("id")).param("conv", conversationId).param("me", userId).param("org", orgId)
                .param("seq", seq).update();
        jdbc.sql("""
                        UPDATE "MessageInboxItem" i SET "readAt" = :now
                        FROM "Message" m
                        WHERE m.id = i."messageId" AND i."conversationId" = :conv AND i."userId" = :me
                          AND i."organizationId" = :org AND i."readAt" IS NULL AND m.seq <= :seq""")
                .param("now", CommTime.nowTs()).param("conv", conversationId).param("me", userId).param("org", orgId)
                .param("seq", seq).update();
        if (moved > 0) {
            events.toConversation(conversationId, Obj.of("type", "read", "conversationId", conversationId,
                    "userId", userId, "seq", String.valueOf(seq)));
        }
        return Obj.of("ok", true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> typing(String conversationId, boolean typing) {
        String userId = DiscussViews.requireUser();
        views.assertRead(conversationId);
        events.now(conversationId, Obj.of("type", "typing", "conversationId", conversationId, "userId", userId,
                "name", views.name(userId), "typing", typing));
        return Obj.of("ok", true);
    }

    /* ── Participants ──────────────────────────────────────────────────────── */

    /** Add (or bring back) a member. {@code true} when they were not an active member before. */
    boolean addParticipant(String conversationId, String userId, String role, String addedVia) {
        Map<String, Object> row = participantRow(conversationId, userId);
        if (row == null) {
            insertParticipant(conversationId, userId, role, addedVia);
            return true;
        }
        if (row.get("leftAt") == null) {
            return false;
        }
        jdbc.sql("UPDATE \"ConversationParticipant\" SET \"leftAt\" = NULL, \"joinedAt\" = ?, role = ?, \"addedVia\" = ?"
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(CommTime.nowTs(), role, addedVia, row.get("id"), TenantContext.organizationId()).update();
        return true;
    }

    void insertParticipant(String conversationId, String userId, String role, String addedVia) {
        jdbc.sql("""
                        INSERT INTO "ConversationParticipant" (id, "organizationId", "conversationId", "participantType",
                          "userId", role, "addedVia")
                        VALUES (?, ?, ?, 'user', ?, ?, ?)
                        ON CONFLICT ("conversationId", "userId") WHERE "participantType" = 'user' AND "userId" IS NOT NULL
                        DO NOTHING""")
                .params(UUID.randomUUID().toString(), TenantContext.organizationId(), conversationId, userId, role,
                        addedVia)
                .update();
    }

    /** Any participant row of the user (active or left), or {@code null}. */
    Map<String, Object> participantRow(String conversationId, String userId) {
        return SqlRows.one(jdbc.sql("SELECT id, \"leftAt\", role, \"addedVia\" FROM \"ConversationParticipant\""
                        + " WHERE \"conversationId\" = ? AND \"userId\" = ? AND \"participantType\" = 'user'"
                        + " AND \"organizationId\" = ?")
                .params(conversationId, userId, TenantContext.organizationId()));
    }

    /** Mark the member as left; when the last owner leaves, the longest-standing member becomes owner. */
    void removeParticipant(String conversationId, String userId) {
        String orgId = TenantContext.organizationId();
        jdbc.sql("UPDATE \"ConversationParticipant\" SET \"leftAt\" = ?, pinned = false WHERE \"conversationId\" = ?"
                        + " AND \"userId\" = ? AND \"participantType\" = 'user' AND \"organizationId\" = ?"
                        + " AND \"leftAt\" IS NULL")
                .params(CommTime.nowTs(), conversationId, userId, orgId).update();
        long owners = jdbc.sql("SELECT count(*) FROM \"ConversationParticipant\" WHERE \"conversationId\" = ?"
                        + " AND \"organizationId\" = ? AND \"participantType\" = 'user' AND \"leftAt\" IS NULL"
                        + " AND role = 'owner'")
                .params(conversationId, orgId).query(Long.class).single();
        if (owners == 0) {
            jdbc.sql("""
                            UPDATE "ConversationParticipant" SET role = 'owner' WHERE id = (
                              SELECT id FROM "ConversationParticipant" WHERE "conversationId" = ? AND "organizationId" = ?
                                AND "participantType" = 'user' AND "leftAt" IS NULL AND "userId" IS NOT NULL
                                AND "addedVia" <> 'follow'
                              ORDER BY "joinedAt" LIMIT 1)""")
                    .params(conversationId, orgId).update();
        }
    }

    /* ── Helpers ───────────────────────────────────────────────────────────── */

    /** Active users of the org, de-duplicated, without the caller; 400 naming unknown ids. */
    List<String> validUsers(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>(userIds);
        wanted.remove(null);
        wanted.remove(TenantContext.userId());
        if (wanted.isEmpty()) {
            return List.of();
        }
        Set<String> found = Set.copyOf(jdbc.sql("SELECT id FROM \"User\" WHERE id IN (:ids) AND \"organizationId\" = :org"
                        + " AND \"deletedAt\" IS NULL AND \"isActive\" = true")
                .param("ids", List.copyOf(wanted)).param("org", TenantContext.organizationId())
                .query(String.class).list());
        List<String> unknown = wanted.stream().filter(id -> !found.contains(id)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Unknown user(s): " + String.join(", ", unknown));
        }
        return List.copyOf(wanted);
    }

    static String channelName(String raw) {
        String name = raw == null ? "" : raw.trim();
        while (name.startsWith("#")) {
            name = name.substring(1).trim();
        }
        if (name.isEmpty()) {
            throw ApiException.badRequest("name should not be empty");
        }
        return name;
    }

    void assertChannelNameFree(String name, String exceptId) {
        long n = jdbc.sql("SELECT count(*) FROM \"Conversation\" WHERE \"organizationId\" = ? AND kind = 'channel'"
                        + " AND \"contextType\" IS NULL AND \"deletedAt\" IS NULL AND lower(name) = lower(?)"
                        + " AND id <> ?")
                .params(TenantContext.organizationId(), name, exceptId == null ? "" : exceptId)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("A channel named '" + name + "' already exists.");
        }
    }

    /** Transaction-scoped advisory lock, per org and key (serializes get-or-create). */
    void lock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))")
                .param("discuss:" + TenantContext.organizationId() + ":" + key).query((rs, n) -> 1).list();
    }

    void touch(String conversationId) {
        jdbc.sql("UPDATE \"Conversation\" SET \"updatedAt\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(CommTime.nowTs(), conversationId, TenantContext.organizationId()).update();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
