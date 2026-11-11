package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Record chatter (ADR-S064): the thread attached to a business record (the Nest context conversation), its followers,
 * and messages / log notes posted on it. Only {@link DiscussViews#RECORD_TYPES} are accepted; the record's read
 * permission is required unless the caller already follows the record.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class DiscussRecordService {

    private final JdbcClient jdbc;
    private final ConversationService conversations;
    private final DiscussService discuss;
    private final DiscussMessageService messages;
    private final DiscussViews views;
    private final DiscussEvents events;

    public DiscussRecordService(JdbcClient jdbc, ConversationService conversations, DiscussService discuss,
            DiscussMessageService messages, DiscussViews views, DiscussEvents events) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.discuss = discuss;
        this.messages = messages;
        this.views = views;
        this.events = events;
    }

    /** {@code {conversationId, following, followers}}; nothing is created by reading. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String type, String recordId) {
        String userId = DiscussViews.requireUser();
        String conversationId = existing(type, recordId);
        assertRecordAccess(type, conversationId, userId);
        return state(conversationId, userId);
    }

    @Transactional
    public Map<String, Object> post(String type, String recordId, DiscussMessageService.PostInput input) {
        String userId = DiscussViews.requireUser();
        assertRecordAccess(type, existing(type, recordId), userId);
        String conversationId = resolve(type, recordId);
        return messages.post(views.head(conversationId), userId, input);
    }

    @Transactional
    public Map<String, Object> follow(String type, String recordId, boolean follow) {
        String userId = DiscussViews.requireUser();
        String existing = existing(type, recordId);
        assertRecordAccess(type, existing, userId);
        if (!follow && existing == null) {
            return state(null, userId);
        }
        String conversationId = existing != null ? existing : resolve(type, recordId);
        if (follow) {
            discuss.addParticipant(conversationId, userId, "member", "follow");
        } else {
            discuss.removeParticipant(conversationId, userId);
        }
        events.toUsers(List.of(userId), Map.of("type", "conversation.updated", "conversationId", conversationId));
        return state(conversationId, userId);
    }

    private void assertRecordAccess(String type, String conversationId, String userId) {
        String perm = DiscussViews.RECORD_TYPES.get(type);
        if (perm == null) {
            throw ApiException.badRequest("Unsupported record type '" + type + "'.");
        }
        if (!TenantContext.permissions().contains(perm)
                && (conversationId == null || views.membership(conversationId, userId) == null)) {
            throw ApiException.forbidden("You do not have access to this conversation.");
        }
    }

    private String existing(String type, String recordId) {
        return jdbc.sql("SELECT id FROM \"Conversation\" WHERE \"contextType\" = ? AND \"contextId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND \"organizationId\" = ? ORDER BY \"createdAt\" LIMIT 1")
                .params(type, recordId, TenantContext.organizationId()).query(String.class).optional().orElse(null);
    }

    /** Get-or-create the record thread, serialized per record. */
    private String resolve(String type, String recordId) {
        discuss.lock("record:" + type + ":" + recordId);
        return (String) conversations.getOrCreateForContext(type, recordId).get("id");
    }

    private Map<String, Object> state(String conversationId, String userId) {
        List<Map<String, Object>> followers = new ArrayList<>();
        boolean following = false;
        if (conversationId != null) {
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                            SELECT p."userId", u."firstName", u."lastName" FROM "ConversationParticipant" p
                            JOIN "User" u ON u.id = p."userId"
                            WHERE p."conversationId" = ? AND p."organizationId" = ? AND p."participantType" = 'user'
                              AND p."leftAt" IS NULL
                            ORDER BY u."firstName\"""")
                    .params(conversationId, TenantContext.organizationId()))) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("userId", r.get("userId"));
                f.put("name", DiscussViews.fullName((String) r.get("firstName"), (String) r.get("lastName")));
                followers.add(f);
                following |= userId.equals(r.get("userId"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conversationId);
        out.put("following", following);
        out.put("followers", followers);
        return out;
    }
}
