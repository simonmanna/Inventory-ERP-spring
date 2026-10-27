package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The single authorization choke point for conversations (port of {@code conversation-access.service.ts}).
 * Permissions are capabilities; this decides access to a specific conversation, for the HTTP layer, the SSE
 * fan-out and the sync filter alike.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class ConversationAccessService {

    /** The subset of a conversation the access rule needs. */
    public record AccessibleConversation(String visibility, List<String> visibleToPermissions) {}

    private final JdbcClient jdbc;

    public ConversationAccessService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The pure rule, deny by default: an active participant → allow; {@code private} → participants only (read_all
     * does NOT override it); read_all → allow; {@code org} + conversation:read → allow; {@code role} + permissions ∩
     * visibleToPermissions → allow.
     */
    public static boolean canAccess(AccessibleConversation conv, boolean isParticipant, List<String> perms) {
        if (isParticipant) {
            return true;
        }
        if ("private".equals(conv.visibility())) {
            return false;
        }
        if (perms.contains(Permissions.COMMUNICATION__CONVERSATION_READ_ALL)) {
            return true;
        }
        if ("org".equals(conv.visibility()) && perms.contains(Permissions.COMMUNICATION__CONVERSATION_READ)) {
            return true;
        }
        return "role".equals(conv.visibility())
                && conv.visibleToPermissions().stream().anyMatch(perms::contains);
    }

    /** The conversation's visibility fields in the current org, or {@code null}. */
    public AccessibleConversation load(String conversationId) {
        return jdbc.sql("SELECT visibility, \"visibleToPermissions\" FROM \"Conversation\" WHERE id = ?"
                        + " AND \"organizationId\" = ?")
                .params(conversationId, TenantContext.organizationId())
                .query((rs, n) -> new AccessibleConversation(rs.getString("visibility"),
                        strings(rs.getArray("visibleToPermissions"))))
                .optional()
                .orElse(null);
    }

    /** DB-backed check for the current tenant/user. */
    public boolean canRead(String conversationId) {
        String userId = TenantContext.userId();
        if (userId == null) {
            return false;
        }
        AccessibleConversation conv = load(conversationId);
        if (conv == null) {
            return false;
        }
        return canAccess(conv, isParticipant(conversationId, userId), TenantContext.permissions());
    }

    public void assertCanRead(String conversationId) {
        if (!canRead(conversationId)) {
            throw ApiException.forbidden("You do not have access to this conversation.");
        }
    }

    public boolean isParticipant(String conversationId, String userId) {
        return jdbc.sql("SELECT count(*) FROM \"ConversationParticipant\" WHERE \"conversationId\" = ? AND \"userId\" = ?"
                        + " AND \"participantType\" = 'user' AND \"leftAt\" IS NULL AND \"organizationId\" = ?")
                .params(conversationId, userId, TenantContext.organizationId())
                .query(Long.class).single() > 0;
    }

    /** The user ids that are active participants of a conversation (SSE audience). Unscoped by org on purpose. */
    public List<String> participantUserIds(String conversationId) {
        return jdbc.sql("SELECT \"userId\" FROM \"ConversationParticipant\" WHERE \"conversationId\" = ?"
                        + " AND \"participantType\" = 'user' AND \"leftAt\" IS NULL AND \"userId\" IS NOT NULL")
                .param(conversationId)
                .query(String.class).list();
    }

    static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return new ArrayList<>();
        }
        Object[] values = (Object[]) array.getArray();
        List<String> out = new ArrayList<>();
        for (Object v : Arrays.asList(values)) {
            out.add((String) v);
        }
        return out;
    }
}
