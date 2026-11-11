package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.CommunicationStreamService.Audience;
import com.inventoryerp.communication.application.ConversationAccessService.AccessibleConversation;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Discuss events on the inbox stream (ADR-S064). Everything is pushed after the surrounding transaction commits and
 * reaches only connections the conversation access rule admits (personal events go to one user).
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class DiscussEvents {

    private final CommunicationStreamService stream;
    private final ConversationAccessService access;

    public DiscussEvents(CommunicationStreamService stream, ConversationAccessService access) {
        this.stream = stream;
        this.access = access;
        stream.setPresenceListener((org, user, online) -> stream.emitToOrg(org,
                Obj.of("type", "presence", "userId", user, "online", online)));
    }

    /** Push {@code event} to the conversation's audience once committed. */
    public void toConversation(String conversationId, Map<String, Object> event) {
        String orgId = TenantContext.organizationId();
        AfterCommit.run(() -> TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
            AccessibleConversation conv = access.load(conversationId);
            if (conv != null) {
                stream.emit(orgId, new Audience(conv, access.participantUserIds(conversationId)), event);
            }
        }));
    }

    /** Push {@code event} to the given users once committed. */
    public void toUsers(Collection<String> userIds, Map<String, Object> event) {
        String orgId = TenantContext.organizationId();
        List<String> ids = List.copyOf(userIds);
        AfterCommit.run(() -> ids.forEach(u -> stream.emitToUser(orgId, u, event)));
    }

    /** {@code conversation.updated} to the audience and to users who just lost access (removed / left). */
    public void conversationUpdated(String conversationId, Collection<String> alsoUsers) {
        Map<String, Object> event = Obj.of("type", "conversation.updated", "conversationId", conversationId);
        toConversation(conversationId, event);
        if (!alsoUsers.isEmpty()) {
            toUsers(alsoUsers, event);
        }
    }

    /** Immediate (no transaction) push, for typing indicators. */
    public void now(String conversationId, Map<String, Object> event) {
        toConversation(conversationId, event);
    }

    public boolean isOnline(String userId) {
        return stream.isOnline(TenantContext.organizationId(), userId);
    }

    public java.util.Set<String> online() {
        return stream.onlineUserIds(TenantContext.organizationId());
    }
}
