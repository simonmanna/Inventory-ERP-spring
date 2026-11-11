package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Discuss messages (ADR-S064): post with mentions, replies and attachments; edit, delete, reactions, pins, stars;
 * pages, pinned and shared files; the personal inbox, starred messages and search. A post goes through
 * {@link MessageService#postUserMessage} so deliveries, the outbox event and Nest consumers see it like any send.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class DiscussMessageService {

    private static final Logger log = LoggerFactory.getLogger(DiscussMessageService.class);

    static final int MAX_ATTACHMENTS = 10;
    static final String DRAFT_OWNER = "MessageDraft";

    /** A post. {@code subtype}: comment (default) or note. */
    public record PostInput(String body, String replyToMessageId, List<String> mentionUserIds,
            List<String> attachmentIds, String subtype) {}

    private final JdbcClient jdbc;
    private final DiscussViews views;
    private final DiscussService discuss;
    private final DiscussEvents events;
    private final MessageService messages;
    private final ConversationAccessService access;
    private final FilesService files;
    private final NotificationsService notifications;

    public DiscussMessageService(JdbcClient jdbc, DiscussViews views, DiscussService discuss, DiscussEvents events,
            MessageService messages, ConversationAccessService access, FilesService files,
            NotificationsService notifications) {
        this.jdbc = jdbc;
        this.views = views;
        this.discuss = discuss;
        this.events = events;
        this.messages = messages;
        this.access = access;
        this.files = files;
        this.notifications = notifications;
    }

    /* ── Read ──────────────────────────────────────────────────────────────── */

    /** A page of enriched messages, ascending. {@code afterSeq} pages forward, otherwise backwards from the end. */
    @Transactional(readOnly = true)
    public Map<String, Object> page(String conversationId, String beforeSeq, String afterSeq, Integer limit) {
        String userId = DiscussViews.requireUser();
        views.assertRead(conversationId);
        int take = Math.min(100, Math.max(1, limit == null ? 40 : limit));
        String sql = DiscussViews.MESSAGE_SQL + " WHERE \"conversationId\" = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL";
        List<Object> params = new ArrayList<>(List.of(conversationId, TenantContext.organizationId()));
        boolean forward = afterSeq != null && !afterSeq.isBlank();
        if (beforeSeq != null && !beforeSeq.isBlank()) {
            sql += " AND seq < ?";
            params.add(seqParam(beforeSeq, "beforeSeq"));
        }
        if (forward) {
            sql += " AND seq > ?";
            params.add(seqParam(afterSeq, "afterSeq"));
        }
        sql += forward ? " ORDER BY seq ASC LIMIT ?" : " ORDER BY seq DESC LIMIT ?";
        params.add(take + 1);
        List<Map<String, Object>> rows = new ArrayList<>(SqlRows.list(jdbc.sql(sql).params(params)));
        boolean hasMore = rows.size() > take;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, take));
        }
        if (!forward) {
            java.util.Collections.reverse(rows);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", views.enrich(rows, userId));
        out.put("hasMore", hasMore);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pinned(String conversationId) {
        String userId = DiscussViews.requireUser();
        views.assertRead(conversationId);
        return views.enrich(SqlRows.list(jdbc.sql(DiscussViews.MESSAGE_SQL + " WHERE \"conversationId\" = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"pinnedAt\" IS NOT NULL"
                        + " ORDER BY \"pinnedAt\" DESC LIMIT 100")
                .params(conversationId, TenantContext.organizationId())), userId);
    }

    /** Files shared in the conversation, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sharedFiles(String conversationId) {
        DiscussViews.requireUser();
        views.assertRead(conversationId);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("""
                        SELECT a.id, a."messageId", a."fileId", a.kind, a.filename, a."mimeType", a."byteSize",
                               m."occurredAt", m."senderUserId"
                        FROM "MessageAttachment" a JOIN "Message" m ON m.id = a."messageId"
                        WHERE m."conversationId" = ? AND a."organizationId" = ? AND m."deletedAt" IS NULL
                        ORDER BY m.seq DESC, a.id LIMIT 200""")
                .params(conversationId, TenantContext.organizationId()));
        Map<String, String> names = views.names(rows.stream().map(r -> (String) r.get("senderUserId")).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = views.attachment(r);
            m.put("messageId", r.get("messageId"));
            m.put("occurredAt", r.get("occurredAt"));
            m.put("authorName", names.get((String) r.get("senderUserId")));
            out.add(m);
        }
        return out;
    }

    /* ── Post ──────────────────────────────────────────────────────────────── */

    @Transactional
    public Map<String, Object> post(String conversationId, PostInput input) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> head = views.assertRead(conversationId);
        return post(head, userId, input);
    }

    /** Post on an already access-checked conversation (record chatter enters here too). */
    Map<String, Object> post(Map<String, Object> head, String userId, PostInput input) {
        String orgId = TenantContext.organizationId();
        String conversationId = (String) head.get("id");
        String body = input.body() == null ? "" : input.body().trim();
        List<String> attachmentIds = input.attachmentIds() == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(input.attachmentIds()));
        if (body.isEmpty() && attachmentIds.isEmpty()) {
            throw ApiException.badRequest("Message body is required.");
        }
        if (attachmentIds.size() > MAX_ATTACHMENTS) {
            throw ApiException.badRequest("A message can have at most " + MAX_ATTACHMENTS + " attachments.");
        }
        if (head.get("archivedAt") != null) {
            throw ApiException.badRequest("This conversation is archived.");
        }
        String replyTo = input.replyToMessageId();
        if (replyTo != null && !messageInConversation(replyTo, conversationId)) {
            throw ApiException.badRequest("Message not found.");
        }
        boolean record = head.get("contextType") != null;
        String subtype = record && "note".equals(input.subtype()) ? "note" : "comment";

        // Posting in a channel joins it; posting on a record follows it.
        List<String> joined = new ArrayList<>();
        if ("channel".equals(head.get("kind")) || record) {
            if (discuss.addParticipant(conversationId, userId, "member", record ? "follow" : "manual")) {
                joined.add(userId);
            }
        }

        String messageId = messages.postUserMessage(orgId, userId,
                new MessageService.SendInput(conversationId, body, replyTo, null), subtype);
        attach(messageId, userId, attachmentIds);

        Set<String> mentioned = mentions(head, messageId, userId, input.mentionUserIds(), joined);
        Set<String> followers = record && "comment".equals(subtype)
                ? followerItems(conversationId, messageId, userId, mentioned) : Set.of();

        Map<String, Object> broadcast = views.message(messageId, null);
        events.toConversation(conversationId, Obj.of("type", "message.created", "conversationId", conversationId,
                "messageId", messageId, "syncSequence", broadcast.get("seq"), "message", messages.view(messageId),
                "discuss", broadcast));
        for (String u : mentioned) {
            events.toUsers(List.of(u), Obj.of("type", "inbox.new", "conversationId", conversationId, "messageId",
                    messageId, "reason", "mention"));
        }
        for (String u : followers) {
            events.toUsers(List.of(u), Obj.of("type", "inbox.new", "conversationId", conversationId, "messageId",
                    messageId, "reason", "follower"));
        }
        if (!joined.isEmpty()) {
            events.toUsers(joined, Obj.of("type", "conversation.updated", "conversationId", conversationId));
        }
        notifyAfterCommit(head, messageId, userId, body, mentioned);
        return views.message(messageId, userId);
    }

    private boolean messageInConversation(String messageId, String conversationId) {
        return jdbc.sql("SELECT count(*) FROM \"Message\" WHERE id = ? AND \"conversationId\" = ? AND \"organizationId\" = ?")
                .params(messageId, conversationId, TenantContext.organizationId()).query(Long.class).single() > 0;
    }

    private void attach(String messageId, String userId, List<String> fileIds) {
        for (String fileId : fileIds) {
            FilesService.FileMeta meta = files.claim(fileId, DRAFT_OWNER, userId, "Message", messageId);
            if (meta == null) {
                throw ApiException.badRequest("Attachment " + fileId + " is not available.");
            }
            String mime = meta.contentType() == null ? "application/octet-stream" : meta.contentType();
            jdbc.sql("""
                            INSERT INTO "MessageAttachment" (id, "organizationId", "messageId", "fileId", kind, filename,
                              "mimeType", "byteSize")
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(UUID.randomUUID().toString(), TenantContext.organizationId(), messageId, fileId,
                            mime.toLowerCase().startsWith("image/") ? "image" : "file", meta.filename(), mime,
                            (int) meta.byteSize())
                    .update();
        }
    }

    /**
     * Inbox items for mentioned users who can read the conversation. On a record, a mentioned user becomes a follower
     * (the mention shares the record thread with them). Returns the users that got a new item.
     */
    private Set<String> mentions(Map<String, Object> head, String messageId, String senderId, List<String> mentionIds,
            List<String> joined) {
        Set<String> out = new LinkedHashSet<>();
        if (mentionIds == null || mentionIds.isEmpty()) {
            return out;
        }
        String conversationId = (String) head.get("id");
        Set<String> wanted = new LinkedHashSet<>(mentionIds);
        wanted.remove(senderId);
        wanted.remove(null);
        if (wanted.isEmpty()) {
            return out;
        }
        List<String> users = jdbc.sql("SELECT id FROM \"User\" WHERE id IN (:ids) AND \"organizationId\" = :org"
                        + " AND \"deletedAt\" IS NULL AND \"isActive\" = true")
                .param("ids", List.copyOf(wanted)).param("org", TenantContext.organizationId())
                .query(String.class).list();
        boolean record = head.get("contextType") != null;
        boolean open = "org".equals(head.get("visibility")) && !record;
        for (String u : users) {
            boolean member = access.isParticipant(conversationId, u);
            if (!member && record) {
                discuss.addParticipant(conversationId, u, "member", "follow");
                joined.add(u);
                member = true;
            }
            if ((member || open) && inboxItem(messageId, conversationId, u, "mention")) {
                out.add(u);
            }
        }
        return out;
    }

    /** Inbox items for the other followers of a record (not muted, level not none). */
    private Set<String> followerItems(String conversationId, String messageId, String senderId, Set<String> skip) {
        Set<String> out = new LinkedHashSet<>();
        List<String> followers = jdbc.sql("""
                        SELECT "userId" FROM "ConversationParticipant"
                        WHERE "conversationId" = ? AND "organizationId" = ? AND "participantType" = 'user'
                          AND "leftAt" IS NULL AND "userId" IS NOT NULL AND "userId" <> ? AND "notifyLevel" <> 'none'
                          AND ("mutedUntil" IS NULL OR "mutedUntil" <= ?)""")
                .params(conversationId, TenantContext.organizationId(), senderId, CommTime.nowTs())
                .query(String.class).list();
        for (String u : followers) {
            if (!skip.contains(u) && inboxItem(messageId, conversationId, u, "follower")) {
                out.add(u);
            }
        }
        return out;
    }

    private boolean inboxItem(String messageId, String conversationId, String userId, String reason) {
        return jdbc.sql("""
                        INSERT INTO "MessageInboxItem" (id, "organizationId", "messageId", "conversationId", "userId", reason)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT ("messageId", "userId") DO NOTHING""")
                .params(UUID.randomUUID().toString(), TenantContext.organizationId(), messageId, conversationId, userId,
                        reason)
                .update() > 0;
    }

    /**
     * In-app notifications after commit: every mentioned user; in a chat, every other member whose level is
     * {@code all}, who is not muted and has no open stream (online users see the message live).
     */
    private void notifyAfterCommit(Map<String, Object> head, String messageId, String senderId, String body,
            Set<String> mentioned) {
        String orgId = TenantContext.organizationId();
        String conversationId = (String) head.get("id");
        String kind = (String) head.get("kind");
        boolean chat = head.get("contextType") == null && ("direct".equals(kind) || "group".equals(kind));
        List<String> chatTargets = new ArrayList<>();
        if (chat) {
            chatTargets.addAll(jdbc.sql("""
                            SELECT "userId" FROM "ConversationParticipant"
                            WHERE "conversationId" = ? AND "organizationId" = ? AND "participantType" = 'user'
                              AND "leftAt" IS NULL AND "userId" IS NOT NULL AND "userId" <> ? AND "notifyLevel" = 'all'
                              AND ("mutedUntil" IS NULL OR "mutedUntil" <= ?)""")
                    .params(conversationId, orgId, senderId, CommTime.nowTs()).query(String.class).list());
        }
        if (mentioned.isEmpty() && chatTargets.isEmpty()) {
            return;
        }
        String sender = views.name(senderId);
        String title = (String) views.item(conversationId, senderId).get("name");
        String preview = body.isEmpty() ? "Sent an attachment" : body.length() > 200 ? body.substring(0, 200) + "…" : body;
        String href = "/communication?c=" + conversationId + "&m=" + messageId;
        Map<String, Object> payload = Obj.of("href", href, "conversationId", conversationId, "messageId", messageId);
        AfterCommit.run(() -> TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
            for (String u : mentioned) {
                send(orgId, u, sender + " mentioned you in " + (chat ? "a chat" : title), preview, payload);
            }
            for (String u : chatTargets) {
                if (!mentioned.contains(u) && !events.isOnline(u)) {
                    send(orgId, u, "New message from " + sender, preview, payload);
                }
            }
        }));
    }

    private void send(String orgId, String userId, String title, String body, Map<String, Object> payload) {
        try {
            notifications.send(new NotificationsService.SendInput(orgId, userId, "in_app", "discuss", title, body,
                    payload));
        } catch (RuntimeException e) {
            log.warn("Discuss notification for {} failed: {}", userId, e.toString());
        }
    }

    /* ── Edit / delete / react / pin / star ────────────────────────────────── */

    @Transactional
    public Map<String, Object> edit(String messageId, String body, List<String> mentionUserIds) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> msg = load(messageId);
        Map<String, Object> head = views.assertRead((String) msg.get("conversationId"));
        if (!userId.equals(msg.get("senderUserId"))) {
            throw ApiException.forbidden("You can only edit your own messages.");
        }
        if (head.get("archivedAt") != null) {
            throw ApiException.badRequest("This conversation is archived.");
        }
        String text = body == null ? "" : body.trim();
        boolean hasFiles = jdbc.sql("SELECT count(*) FROM \"MessageAttachment\" WHERE \"messageId\" = ?")
                .param(messageId).query(Long.class).single() > 0;
        if (text.isEmpty() && !hasFiles) {
            throw ApiException.badRequest("Message body is required.");
        }
        jdbc.sql("UPDATE \"Message\" SET body = ?, \"editedAt\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(text, CommTime.nowTs(), messageId, TenantContext.organizationId()).update();
        Set<String> mentioned = mentions(head, messageId, userId, mentionUserIds, new ArrayList<>());
        for (String u : mentioned) {
            events.toUsers(List.of(u), Obj.of("type", "inbox.new", "conversationId", head.get("id"), "messageId",
                    messageId, "reason", "mention"));
        }
        if (!mentioned.isEmpty()) {
            notifyAfterCommit(head, messageId, userId, text, mentioned);
        }
        return updated(messageId, userId);
    }

    @Transactional
    public Map<String, Object> delete(String messageId) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> msg = load(messageId);
        String conversationId = (String) msg.get("conversationId");
        views.assertRead(conversationId);
        if (!userId.equals(msg.get("senderUserId")) && !views.isModerator(views.membership(conversationId, userId))) {
            throw ApiException.forbidden("You can only delete your own messages.");
        }
        jdbc.sql("UPDATE \"Message\" SET \"deletedAt\" = ?, \"pinnedAt\" = NULL WHERE id = ? AND \"organizationId\" = ?")
                .params(CommTime.nowTs(), messageId, TenantContext.organizationId()).update();
        events.toConversation(conversationId, Obj.of("type", "message.deleted", "conversationId", conversationId,
                "messageId", messageId));
        return Obj.of("ok", true);
    }

    /** Toggle the caller's reaction. */
    @Transactional
    public Map<String, Object> react(String messageId, String emoji) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> msg = load(messageId);
        views.assertRead((String) msg.get("conversationId"));
        String e = emoji == null ? "" : emoji.trim();
        if (e.isEmpty() || e.length() > 16) {
            throw ApiException.badRequest("emoji must be between 1 and 16 characters");
        }
        String orgId = TenantContext.organizationId();
        int removed = jdbc.sql("DELETE FROM \"MessageReaction\" WHERE \"messageId\" = ? AND \"userId\" = ? AND emoji = ?"
                        + " AND \"organizationId\" = ?")
                .params(messageId, userId, e, orgId).update();
        if (removed == 0) {
            jdbc.sql("""
                            INSERT INTO "MessageReaction" (id, "organizationId", "messageId", "userId", emoji)
                            VALUES (?, ?, ?, ?, ?) ON CONFLICT ("messageId", "userId", emoji) DO NOTHING""")
                    .params(UUID.randomUUID().toString(), orgId, messageId, userId, e).update();
        }
        return updated(messageId, userId);
    }

    /** Toggle the pin; members and moderators may pin. */
    @Transactional
    public Map<String, Object> pin(String messageId) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> msg = load(messageId);
        String conversationId = (String) msg.get("conversationId");
        views.assertRead(conversationId);
        Map<String, Object> me = views.membership(conversationId, userId);
        if (me == null && !views.isModerator(null)) {
            throw ApiException.forbidden("Only a channel moderator can do this.");
        }
        boolean pinned = msg.get("pinnedAt") != null;
        jdbc.sql("UPDATE \"Message\" SET \"pinnedAt\" = ?, \"pinnedById\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(pinned ? null : CommTime.nowTs(), pinned ? null : userId, messageId,
                        TenantContext.organizationId())
                .update();
        Map<String, Object> out = updated(messageId, userId);
        events.conversationUpdated(conversationId, List.of());
        return out;
    }

    /** Toggle the caller's star. */
    @Transactional
    public Map<String, Object> star(String messageId) {
        String userId = DiscussViews.requireUser();
        Map<String, Object> msg = load(messageId);
        views.assertRead((String) msg.get("conversationId"));
        String orgId = TenantContext.organizationId();
        int removed = jdbc.sql("DELETE FROM \"MessageStar\" WHERE \"messageId\" = ? AND \"userId\" = ?"
                        + " AND \"organizationId\" = ?")
                .params(messageId, userId, orgId).update();
        if (removed == 0) {
            jdbc.sql("""
                            INSERT INTO "MessageStar" (id, "organizationId", "messageId", "userId") VALUES (?, ?, ?, ?)
                            ON CONFLICT ("messageId", "userId") DO NOTHING""")
                    .params(UUID.randomUUID().toString(), orgId, messageId, userId).update();
        }
        return Obj.of("starred", removed == 0);
    }

    /** Store an upload as the caller's draft attachment. */
    @Transactional
    public Map<String, Object> upload(String filename, String contentType, byte[] bytes) {
        String userId = DiscussViews.requireUser();
        String id = (String) files.upload(filename, contentType, bytes, DRAFT_OWNER, userId).get("id");
        FilesService.FileMeta meta = files.meta(id);
        String mime = meta.contentType() == null ? "application/octet-stream" : meta.contentType();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("filename", meta.filename());
        out.put("mimeType", mime);
        out.put("byteSize", meta.byteSize());
        out.put("kind", mime.toLowerCase().startsWith("image/") ? "image" : "file");
        out.put("url", files.signDownload(id).get("url"));
        return out;
    }

    private Map<String, Object> load(String messageId) {
        Map<String, Object> msg = SqlRows.one(jdbc.sql("SELECT id, \"conversationId\", \"senderUserId\", \"pinnedAt\""
                        + " FROM \"Message\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(messageId, TenantContext.organizationId()));
        if (msg == null) {
            throw ApiException.notFound("Message not found.");
        }
        return msg;
    }

    /** Broadcast {@code message.updated} and return the caller's view. */
    private Map<String, Object> updated(String messageId, String userId) {
        Map<String, Object> broadcast = views.message(messageId, null);
        events.toConversation((String) broadcast.get("conversationId"), Obj.of("type", "message.updated",
                "conversationId", broadcast.get("conversationId"), "message", broadcast));
        return views.message(messageId, userId);
    }

    /* ── Inbox / starred / search ──────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Map<String, Object> inbox(Integer limit, boolean unreadOnly) {
        String userId = DiscussViews.requireUser();
        String orgId = TenantContext.organizationId();
        int take = Math.min(200, Math.max(1, limit == null ? 50 : limit));
        List<Map<String, Object>> items = SqlRows.list(jdbc.sql("""
                        SELECT i.id, i.reason, i."readAt", i."createdAt", i."messageId", i."conversationId"
                        FROM "MessageInboxItem" i JOIN "Message" m ON m.id = i."messageId" AND m."deletedAt" IS NULL
                        WHERE i."organizationId" = ? AND i."userId" = ? AND (? = false OR i."readAt" IS NULL)
                        ORDER BY i."createdAt" DESC LIMIT ?""")
                .params(orgId, userId, unreadOnly, take));
        List<Map<String, Object>> out = withMessages(items, userId);
        for (Map<String, Object> o : out) {
            Map<String, Object> src = items.stream().filter(i -> i.get("messageId").equals(
                    ((Map<?, ?>) o.get("message")).get("id"))).findFirst().orElseThrow();
            o.put("id", src.get("id"));
            o.put("reason", src.get("reason"));
            o.put("readAt", src.get("readAt"));
            o.put("createdAt", src.get("createdAt"));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", out);
        res.put("unread", unreadInbox(userId));
        return res;
    }

    /** Mark the given inbox items (or all) read. */
    @Transactional
    public Map<String, Object> inboxRead(List<String> ids) {
        String userId = DiscussViews.requireUser();
        String orgId = TenantContext.organizationId();
        if (ids == null) {
            jdbc.sql("UPDATE \"MessageInboxItem\" SET \"readAt\" = ? WHERE \"organizationId\" = ? AND \"userId\" = ?"
                            + " AND \"readAt\" IS NULL")
                    .params(CommTime.nowTs(), orgId, userId).update();
        } else if (!ids.isEmpty()) {
            jdbc.sql("UPDATE \"MessageInboxItem\" SET \"readAt\" = :now WHERE \"organizationId\" = :org"
                            + " AND \"userId\" = :me AND id IN (:ids) AND \"readAt\" IS NULL")
                    .param("now", CommTime.nowTs()).param("org", orgId).param("me", userId).param("ids", ids)
                    .update();
        }
        return Obj.of("ok", true, "unread", unreadInbox(userId));
    }

    private long unreadInbox(String userId) {
        return jdbc.sql("SELECT count(*) FROM \"MessageInboxItem\" i JOIN \"Message\" m ON m.id = i.\"messageId\""
                        + " WHERE i.\"organizationId\" = ? AND i.\"userId\" = ? AND i.\"readAt\" IS NULL"
                        + " AND m.\"deletedAt\" IS NULL")
                .params(TenantContext.organizationId(), userId).query(Long.class).single();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> starred() {
        String userId = DiscussViews.requireUser();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("""
                        SELECT s."messageId", m."conversationId" FROM "MessageStar" s
                        JOIN "Message" m ON m.id = s."messageId" AND m."deletedAt" IS NULL
                        WHERE s."organizationId" = ? AND s."userId" = ?
                        ORDER BY s."createdAt" DESC LIMIT 200""")
                .params(TenantContext.organizationId(), userId));
        return withMessages(rows, userId);
    }

    /** Messages matching {@code q} in one conversation, or in the caller's conversations and public channels. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String q, String conversationId, Integer limit) {
        String userId = DiscussViews.requireUser();
        String term = q == null ? "" : q.trim();
        if (term.length() < 2) {
            throw ApiException.badRequest("Search needs at least 2 characters.");
        }
        int take = Math.min(100, Math.max(1, limit == null ? 30 : limit));
        String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String orgId = TenantContext.organizationId();
        List<Map<String, Object>> rows;
        if (conversationId != null && !conversationId.isBlank()) {
            views.assertRead(conversationId);
            rows = SqlRows.list(jdbc.sql("""
                            SELECT id AS "messageId", "conversationId" FROM "Message"
                            WHERE "conversationId" = ? AND "organizationId" = ? AND "deletedAt" IS NULL
                              AND body ILIKE ? ESCAPE '\\' ORDER BY seq DESC LIMIT ?""")
                    .params(conversationId, orgId, like, take));
        } else {
            rows = SqlRows.list(jdbc.sql("""
                            SELECT m.id AS "messageId", m."conversationId" FROM "Message" m
                            JOIN "Conversation" c ON c.id = m."conversationId" AND c."deletedAt" IS NULL
                            WHERE m."organizationId" = :org AND m."deletedAt" IS NULL AND m.body ILIKE :q ESCAPE '\\'
                              AND (EXISTS (SELECT 1 FROM "ConversationParticipant" p WHERE p."conversationId" = c.id
                                     AND p."userId" = :me AND p."participantType" = 'user' AND p."leftAt" IS NULL)
                                   OR (c.kind = 'channel' AND c.visibility = 'org' AND c."contextType" IS NULL))
                            ORDER BY m.seq DESC LIMIT :take""")
                    .param("org", orgId).param("q", like).param("me", userId).param("take", take));
        }
        return withMessages(rows, userId);
    }

    /** {@code [{conversation, message}]} for rows with messageId / conversationId, dropping inaccessible ones. */
    private List<Map<String, Object>> withMessages(List<Map<String, Object>> rows, String userId) {
        Map<String, Boolean> readable = new HashMap<>();
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String conv = (String) r.get("conversationId");
            if (readable.computeIfAbsent(conv, this::canRead)) {
                visible.add(r);
            }
        }
        if (visible.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> ids = visible.stream().map(r -> (String) r.get("messageId")).toList();
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> m : views.enrich(SqlRows.list(jdbc.sql(DiscussViews.MESSAGE_SQL
                        + " WHERE id IN (:ids) AND \"organizationId\" = :org")
                .param("ids", ids).param("org", TenantContext.organizationId())), userId)) {
            byId.put((String) m.get("id"), m);
        }
        Map<String, Map<String, Object>> refs = views.conversationRefs(
                visible.stream().map(r -> (String) r.get("conversationId")).toList(), userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : visible) {
            Map<String, Object> m = byId.get((String) r.get("messageId"));
            if (m != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("conversation", refs.get((String) r.get("conversationId")));
                row.put("message", m);
                out.add(row);
            }
        }
        return out;
    }

    private boolean canRead(String conversationId) {
        try {
            views.assertRead(conversationId);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private static long seqParam(String raw, String field) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be a number string");
        }
    }
}
