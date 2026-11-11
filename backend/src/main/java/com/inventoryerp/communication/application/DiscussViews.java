package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Discuss read models (ADR-S064): conversation items as the sidebar shows them, conversation detail, and enriched
 * messages (author, reply preview, attachments with signed URLs, reactions, mentions, star). Also the Discuss access
 * rules on top of {@link ConversationAccessService}: record-chatter permissions and moderation. All queries are
 * scoped to the current organization.
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class DiscussViews {

    /** Record types that have a chatter, with the permission needed to read the record. */
    public static final Map<String, String> RECORD_TYPES = Map.of(
            "SalesOrder", "sales:order:read",
            "SalesQuotation", "sales:quotation:read",
            "PurchaseOrder", "purchase_order:read",
            "Invoice", "invoice:read",
            "Expense", "expense:read");

    static final int CHAT_MEMBER_PREVIEW = 5;
    static final int REPLY_PREVIEW = 160;

    private final JdbcClient jdbc;
    private final ConversationAccessService access;
    private final CommunicationStreamService stream;
    private final FilesService files;

    public DiscussViews(JdbcClient jdbc, ConversationAccessService access, CommunicationStreamService stream,
            FilesService files) {
        this.jdbc = jdbc;
        this.access = access;
        this.stream = stream;
        this.files = files;
    }

    /* ── Identity ──────────────────────────────────────────────────────────── */

    /** The acting user, or 403 for a token without one. */
    static String requireUser() {
        String userId = TenantContext.userId();
        if (userId == null) {
            throw ApiException.forbidden("Authentication required to send a message.");
        }
        return userId;
    }

    /** Display names ("First Last") of users of the current org. */
    Map<String, String> names(Collection<String> userIds) {
        Map<String, String> out = new HashMap<>();
        Set<String> ids = new HashSet<>(userIds);
        ids.remove(null);
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, \"firstName\", \"lastName\" FROM \"User\" WHERE id IN (:ids) AND \"organizationId\" = :org")
                .param("ids", List.copyOf(ids)).param("org", TenantContext.organizationId())
                .query((rs, n) -> out.put(rs.getString(1), fullName(rs.getString(2), rs.getString(3))))
                .list();
        return out;
    }

    String name(String userId) {
        return userId == null ? null : names(List.of(userId)).get(userId);
    }

    static String fullName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        return (f + " " + l).trim();
    }

    static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        String a = parts[0].substring(0, 1);
        String b = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (a + b).toUpperCase();
    }

    static Map<String, Object> person(String userId, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", userId);
        m.put("name", name == null ? "Unknown user" : name);
        m.put("initials", initials(name));
        return m;
    }

    /* ── Access ────────────────────────────────────────────────────────────── */

    /** Conversation basics in the current org (not deleted), or {@code null}. */
    Map<String, Object> head(String conversationId) {
        return SqlRows.one(jdbc.sql("""
                        SELECT id, kind, name, visibility, "contextType", "contextId", "departmentId", "autoJoin",
                               "archivedAt", "directKey"
                        FROM "Conversation" WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                .params(conversationId, TenantContext.organizationId()));
    }

    /** The caller's active participant row (role, settings), or {@code null}. */
    Map<String, Object> membership(String conversationId, String userId) {
        if (userId == null) {
            return null;
        }
        return SqlRows.one(jdbc.sql("""
                        SELECT id, role, "notifyLevel", "mutedUntil", pinned, "addedVia", "lastReadMessageId"
                        FROM "ConversationParticipant"
                        WHERE "conversationId" = ? AND "organizationId" = ? AND "participantType" = 'user'
                          AND "userId" = ? AND "leftAt" IS NULL""")
                .params(conversationId, TenantContext.organizationId(), userId));
    }

    /**
     * Discuss read access: the conversation rule, plus — for a registered record chatter — the record's read
     * permission unless the caller follows it. Returns the conversation head.
     */
    Map<String, Object> assertRead(String conversationId) {
        Map<String, Object> head = head(conversationId);
        if (head == null) {
            throw ApiException.notFound("Conversation not found.");
        }
        access.assertCanRead(conversationId);
        String contextType = (String) head.get("contextType");
        String perm = contextType == null ? null : RECORD_TYPES.get(contextType);
        if (perm != null && !TenantContext.permissions().contains(perm)
                && membership(conversationId, TenantContext.userId()) == null) {
            throw ApiException.forbidden("You do not have access to this conversation.");
        }
        return head;
    }

    /** Owner / admin of the conversation, or an org-wide communication moderator. */
    boolean isModerator(Map<String, Object> membership) {
        if (TenantContext.permissions().contains(Permissions.COMMUNICATION__CHANNEL_MANAGE)) {
            return true;
        }
        if (membership == null) {
            return false;
        }
        Object role = membership.get("role");
        return "owner".equals(role) || "admin".equals(role);
    }

    void assertModerator(Map<String, Object> membership) {
        if (!isModerator(membership)) {
            throw ApiException.forbidden("Only a channel moderator can do this.");
        }
    }

    static String visibilityOut(Object stored) {
        return "org".equals(stored) ? "public" : "private";
    }

    /* ── Conversation items ────────────────────────────────────────────────── */

    private static final String ITEM_SQL = """
            SELECT c.id, c.kind, c.name, c.description, c.visibility, c."departmentId", c."autoJoin", c."archivedAt",
                   c."contextType", c."contextId", c."updatedAt",
                   p.role, p."notifyLevel", p."mutedUntil", p.pinned,
                   (SELECT count(*) FROM "ConversationParticipant" q WHERE q."conversationId" = c.id
                      AND q."participantType" = 'user' AND q."leftAt" IS NULL) AS "memberCount",
                   CASE WHEN p.id IS NULL THEN 0 ELSE (SELECT count(*) FROM "Message" m
                      WHERE m."conversationId" = c.id AND m."deletedAt" IS NULL AND m."senderUserId" <> :me
                        AND m.seq > coalesce((SELECT r.seq FROM "Message" r WHERE r.id = p."lastReadMessageId"), 0))
                   END AS unread,
                   (SELECT count(*) FROM "MessageInboxItem" i WHERE i."conversationId" = c.id AND i."userId" = :me
                      AND i."readAt" IS NULL AND i.reason = 'mention') AS "mentionUnread",
                   lm.id AS "lmId", lm.body AS "lmBody", lm."occurredAt" AS "lmAt", lm."senderUserId" AS "lmSender"
            FROM "Conversation" c
            LEFT JOIN "ConversationParticipant" p ON p."conversationId" = c.id AND p."participantType" = 'user'
                 AND p."userId" = :me AND p."leftAt" IS NULL
            LEFT JOIN LATERAL (SELECT id, body, "occurredAt", "senderUserId" FROM "Message" m
                 WHERE m."conversationId" = c.id AND m."deletedAt" IS NULL ORDER BY seq DESC LIMIT 1) lm ON true
            WHERE c."organizationId" = :org AND c."deletedAt" IS NULL AND\s""";

    /** The caller's sidebar: joined, non-archived, non-record conversations. */
    List<Map<String, Object>> sidebarItems(String userId) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(ITEM_SQL + """
                        p.id IS NOT NULL AND c."contextType" IS NULL AND c."archivedAt" IS NULL
                        ORDER BY p.pinned DESC, coalesce(lm."occurredAt", c."updatedAt") DESC
                        LIMIT 500""")
                .param("me", userId).param("org", TenantContext.organizationId()));
        return items(rows, userId);
    }

    /** One conversation as the caller sees it in the sidebar (access already checked). */
    Map<String, Object> item(String conversationId, String userId) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(ITEM_SQL + "c.id = :id")
                .param("me", userId).param("org", TenantContext.organizationId()).param("id", conversationId));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Conversation not found.");
        }
        return items(rows, userId).getFirst();
    }

    private List<Map<String, Object>> items(List<Map<String, Object>> rows, String userId) {
        List<String> chatIds = rows.stream().filter(r -> !"channel".equals(r.get("kind")) && r.get("contextType") == null)
                .map(r -> (String) r.get("id")).toList();
        Map<String, List<String>> others = new HashMap<>();
        if (!chatIds.isEmpty()) {
            jdbc.sql("""
                            SELECT "conversationId", "userId" FROM "ConversationParticipant"
                            WHERE "conversationId" IN (:ids) AND "organizationId" = :org AND "participantType" = 'user'
                              AND "leftAt" IS NULL AND "userId" IS NOT NULL AND "userId" <> :me
                            ORDER BY "joinedAt\"""")
                    .param("ids", chatIds).param("org", TenantContext.organizationId()).param("me", userId)
                    .query((rs, n) -> others.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)))
                    .list();
        }
        Set<String> people = new HashSet<>();
        others.values().forEach(people::addAll);
        rows.forEach(r -> people.add((String) r.get("lmSender")));
        Map<String, String> names = names(people);
        String myName = name(userId);
        Instant now = Instant.now();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String id = (String) r.get("id");
            String kind = (String) r.get("kind");
            List<String> otherIds = others.getOrDefault(id, List.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("name", title(r, otherIds, names, myName));
            m.put("description", r.get("description"));
            m.put("visibility", visibilityOut(r.get("visibility")));
            m.put("departmentId", r.get("departmentId"));
            m.put("autoJoin", Boolean.TRUE.equals(r.get("autoJoin")));
            m.put("archived", r.get("archivedAt") != null);
            m.put("contextType", r.get("contextType"));
            m.put("contextId", r.get("contextId"));
            m.put("role", r.get("role"));
            String level = r.get("notifyLevel") == null ? "all" : (String) r.get("notifyLevel");
            Instant mutedUntil = (Instant) r.get("mutedUntil");
            boolean muted = mutedUntil != null && mutedUntil.isAfter(now);
            long unread = ((Number) r.get("unread")).longValue();
            long mentionUnread = ((Number) r.get("mentionUnread")).longValue();
            m.put("notifyLevel", level);
            m.put("mutedUntil", muted ? mutedUntil : null);
            m.put("muted", muted);
            m.put("pinned", Boolean.TRUE.equals(r.get("pinned")));
            m.put("unread", unread);
            m.put("mentionUnread", mentionUnread);
            m.put("badge", badge(level, muted, unread, mentionUnread));
            m.put("memberCount", ((Number) r.get("memberCount")).longValue());
            List<Map<String, Object>> members = new ArrayList<>();
            for (String o : otherIds.subList(0, Math.min(CHAT_MEMBER_PREVIEW, otherIds.size()))) {
                members.add(person(o, names.get(o)));
            }
            m.put("members", members);
            m.put("directUserId", "direct".equals(kind) && r.get("contextType") == null && !otherIds.isEmpty()
                    ? otherIds.getFirst() : null);
            if (r.get("lmId") != null) {
                Map<String, Object> last = new LinkedHashMap<>();
                last.put("id", r.get("lmId"));
                last.put("body", r.get("lmBody"));
                last.put("occurredAt", r.get("lmAt"));
                last.put("authorName", r.get("lmSender") == null ? null : names.get((String) r.get("lmSender")));
                m.put("lastMessage", last);
            } else {
                m.put("lastMessage", null);
            }
            m.put("updatedAt", r.get("updatedAt"));
            out.add(m);
        }
        return out;
    }

    static long badge(String level, boolean muted, long unread, long mentionUnread) {
        if (muted || "none".equals(level)) {
            return 0;
        }
        return "mentions".equals(level) ? mentionUnread : unread;
    }

    private static String title(Map<String, Object> r, List<String> otherIds, Map<String, String> names,
            String myName) {
        String name = (String) r.get("name");
        String kind = (String) r.get("kind");
        if ("channel".equals(kind) || r.get("contextType") != null) {
            return name;
        }
        if ("direct".equals(kind)) {
            return otherIds.isEmpty() ? (myName == null ? name : myName) : names.getOrDefault(otherIds.getFirst(), name);
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        List<String> parts = new ArrayList<>();
        for (String o : otherIds) {
            String n = names.get(o);
            parts.add(n == null ? "?" : n.split(" ")[0]);
        }
        return parts.isEmpty() ? "Group chat" : String.join(", ", parts);
    }

    /** Item + members (with read cursor and presence) and what the caller may do. */
    Map<String, Object> detail(String conversationId, String userId) {
        Map<String, Object> out = new LinkedHashMap<>(item(conversationId, userId));
        String orgId = TenantContext.organizationId();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("""
                        SELECT p."userId", p.role, p."addedVia", r.seq AS "lastReadSeq", u.email, u."firstName",
                               u."lastName"
                        FROM "ConversationParticipant" p
                        LEFT JOIN "Message" r ON r.id = p."lastReadMessageId"
                        LEFT JOIN "User" u ON u.id = p."userId"
                        WHERE p."conversationId" = ? AND p."organizationId" = ? AND p."participantType" = 'user'
                          AND p."leftAt" IS NULL AND p."userId" IS NOT NULL
                        ORDER BY CASE p.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, u."firstName\"""")
                .params(conversationId, orgId));
        Set<String> online = stream.onlineUserIds(orgId);
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String name = fullName((String) r.get("firstName"), (String) r.get("lastName"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", r.get("userId"));
            m.put("name", name);
            m.put("email", r.get("email"));
            m.put("initials", initials(name));
            m.put("role", r.get("role"));
            m.put("online", online.contains((String) r.get("userId")));
            m.put("lastReadSeq", r.get("lastReadSeq") == null ? null : String.valueOf(r.get("lastReadSeq")));
            m.put("addedVia", r.get("addedVia"));
            members.add(m);
        }
        Map<String, Object> me = membership(conversationId, userId);
        boolean archived = Boolean.TRUE.equals(out.get("archived"));
        out.put("members", members);
        out.put("canManage", "channel".equals(out.get("kind")) && isModerator(me)
                || "group".equals(out.get("kind")) && me != null);
        out.put("canPost", !archived && TenantContext.permissions().contains(Permissions.COMMUNICATION__MESSAGE_SEND));
        out.put("createdAt", jdbc.sql("SELECT \"createdAt\" FROM \"Conversation\" WHERE id = ?").param(conversationId)
                .query(java.sql.Timestamp.class).optional().map(t -> SqlRows.value(t)).orElse(null));
        out.put("pinnedCount", jdbc.sql("SELECT count(*) FROM \"Message\" WHERE \"conversationId\" = ?"
                        + " AND \"organizationId\" = ? AND \"pinnedAt\" IS NOT NULL AND \"deletedAt\" IS NULL")
                .params(conversationId, orgId).query(Long.class).single());
        return out;
    }

    /* ── Messages ──────────────────────────────────────────────────────────── */

    static final String MESSAGE_SQL = """
            SELECT id, "conversationId", seq, subtype, "senderType", "senderUserId", body, "occurredAt", "editedAt",
                   "pinnedAt", "replyToMessageId"
            FROM "Message\"""";

    /** Enriched messages for the given rows (order kept). {@code userId} decides {@code starred}; null omits it. */
    List<Map<String, Object>> enrich(List<Map<String, Object>> rows, String userId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String orgId = TenantContext.organizationId();
        List<String> ids = rows.stream().map(r -> (String) r.get("id")).toList();
        Set<String> people = new LinkedHashSet<>();
        rows.forEach(r -> people.add((String) r.get("senderUserId")));

        Map<String, Map<String, Object>> replies = new HashMap<>();
        List<String> replyIds = rows.stream().map(r -> (String) r.get("replyToMessageId"))
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!replyIds.isEmpty()) {
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                            SELECT id, body, "senderUserId", "deletedAt" FROM "Message"
                            WHERE id IN (:ids) AND "organizationId" = :org""")
                    .param("ids", replyIds).param("org", orgId))) {
                replies.put((String) r.get("id"), r);
                people.add((String) r.get("senderUserId"));
            }
        }

        Map<String, List<Map<String, Object>>> attachments = attachments(ids);

        Map<String, List<Map<String, Object>>> reactions = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                        SELECT "messageId", emoji, count(*) AS n, array_agg("userId" ORDER BY "createdAt") AS users,
                               min("createdAt") AS first
                        FROM "MessageReaction" WHERE "messageId" IN (:ids) AND "organizationId" = :org
                        GROUP BY "messageId", emoji ORDER BY first""")
                .param("ids", ids).param("org", orgId))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("emoji", r.get("emoji"));
            m.put("count", ((Number) r.get("n")).longValue());
            m.put("userIds", r.get("users"));
            reactions.computeIfAbsent((String) r.get("messageId"), k -> new ArrayList<>()).add(m);
        }

        Map<String, List<String>> mentions = new HashMap<>();
        jdbc.sql("""
                        SELECT "messageId", "userId" FROM "MessageInboxItem"
                        WHERE "messageId" IN (:ids) AND "organizationId" = :org AND reason = 'mention'
                        ORDER BY "createdAt\"""")
                .param("ids", ids).param("org", orgId)
                .query((rs, n) -> mentions.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)))
                .list();
        mentions.values().forEach(people::addAll);

        Set<String> starred = new HashSet<>();
        if (userId != null) {
            starred.addAll(jdbc.sql("SELECT \"messageId\" FROM \"MessageStar\" WHERE \"messageId\" IN (:ids)"
                            + " AND \"userId\" = :me AND \"organizationId\" = :org")
                    .param("ids", ids).param("me", userId).param("org", orgId).query(String.class).list());
        }

        Map<String, String> names = names(people);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String id = (String) r.get("id");
            String sender = (String) r.get("senderUserId");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("conversationId", r.get("conversationId"));
            m.put("seq", String.valueOf(r.get("seq")));
            m.put("subtype", r.get("subtype"));
            m.put("senderType", r.get("senderType"));
            m.put("author", sender == null ? null : person(sender, names.get(sender)));
            m.put("body", r.get("body"));
            m.put("occurredAt", r.get("occurredAt"));
            m.put("editedAt", r.get("editedAt"));
            m.put("pinnedAt", r.get("pinnedAt"));
            Map<String, Object> reply = replies.get((String) r.get("replyToMessageId"));
            if (reply != null) {
                Map<String, Object> rp = new LinkedHashMap<>();
                rp.put("id", reply.get("id"));
                String body = reply.get("deletedAt") != null ? "" : (String) reply.get("body");
                rp.put("body", body.length() > REPLY_PREVIEW ? body.substring(0, REPLY_PREVIEW) + "…" : body);
                rp.put("deleted", reply.get("deletedAt") != null);
                rp.put("authorName", names.get((String) reply.get("senderUserId")));
                m.put("replyTo", rp);
            } else {
                m.put("replyTo", null);
            }
            m.put("attachments", attachments.getOrDefault(id, List.of()));
            m.put("reactions", reactions.getOrDefault(id, List.of()));
            List<Map<String, Object>> ms = new ArrayList<>();
            for (String u : mentions.getOrDefault(id, List.of())) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("userId", u);
                mm.put("name", names.get(u));
                ms.add(mm);
            }
            m.put("mentions", ms);
            if (userId != null) {
                m.put("starred", starred.contains(id));
            }
            out.add(m);
        }
        return out;
    }

    /** One enriched message by id (current org, not deleted), or 404. */
    Map<String, Object> message(String messageId, String userId) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(MESSAGE_SQL
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(messageId, TenantContext.organizationId()));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Message not found.");
        }
        return enrich(rows, userId).getFirst();
    }

    /** Attachments per message id, each with a one-hour signed URL. */
    Map<String, List<Map<String, Object>>> attachments(List<String> messageIds) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        if (messageIds.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                        SELECT id, "messageId", "fileId", kind, filename, "mimeType", "byteSize" FROM "MessageAttachment"
                        WHERE "messageId" IN (:ids) AND "organizationId" = :org ORDER BY "createdAt", id""")
                .param("ids", messageIds).param("org", TenantContext.organizationId()))) {
            out.computeIfAbsent((String) r.get("messageId"), k -> new ArrayList<>()).add(attachment(r));
        }
        return out;
    }

    Map<String, Object> attachment(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("fileId", r.get("fileId"));
        m.put("filename", r.get("filename"));
        m.put("mimeType", r.get("mimeType"));
        m.put("byteSize", r.get("byteSize"));
        m.put("kind", r.get("kind"));
        m.put("url", files.signDownload((String) r.get("fileId")).get("url"));
        return m;
    }

    /** Small conversation reference for inbox / starred / search rows. */
    Map<String, Map<String, Object>> conversationRefs(Collection<String> conversationIds, String userId) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (String id : new LinkedHashSet<>(conversationIds)) {
            Map<String, Object> item = item(id, userId);
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("id", id);
            ref.put("name", item.get("name"));
            ref.put("kind", item.get("kind"));
            ref.put("contextType", item.get("contextType"));
            ref.put("contextId", item.get("contextId"));
            out.put(id, ref);
        }
        return out;
    }
}
