package com.inventoryerp.communication.application;

import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * WhatsApp delivery of scheduled alerts (ADR-S021). When an alert rule lists the channel, each recipient who has a
 * WhatsApp identity gets the digest. A staff user is matched to the partner with the same e-mail, the convention SMS
 * notifications already use. Each (alert, user) is claimed once on {@code CommunicationDispatch.dedupeKey}, because
 * the outbox delivers at least once. A recipient with no identity, or whose conversation has no WhatsApp channel, is
 * skipped.
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class AlertWhatsAppSubscriber {

    private static final Logger log = LoggerFactory.getLogger(AlertWhatsAppSubscriber.class);
    private static final String PROVIDER = "whatsapp";

    private final EventBus events;
    private final JdbcClient jdbc;
    private final ConversationService conversations;
    private final MessageService messages;

    public AlertWhatsAppSubscriber(EventBus events, JdbcClient jdbc, ConversationService conversations,
            MessageService messages) {
        this.events = events;
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.messages = messages;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.ALERT_RAISED, this::handle);
    }

    void handle(JsonNode node) {
        String organizationId = node.path("organizationId").asString("");
        if (organizationId.isEmpty() || !wanted(node)) {
            return;
        }
        String text = node.path("title").asString("") + "\n" + node.path("body").asString("");
        String alertId = node.path("alertId").asString("");
        TenantContext.run(TenantContext.TenantStore.organization(organizationId), () -> {
            for (JsonNode user : node.path("recipientUserIds")) {
                try {
                    send(organizationId, alertId, user.asString(""), text);
                } catch (RuntimeException e) {
                    log.warn("alert {} over WhatsApp failed: {}", alertId, e.toString());
                }
            }
        });
    }

    private static boolean wanted(JsonNode node) {
        for (JsonNode channel : node.path("channels")) {
            if (AlertTypes.CHANNEL_WHATSAPP.equals(channel.asString(""))) {
                return true;
            }
        }
        return false;
    }

    private void send(String organizationId, String alertId, String userId, String text) {
        String partnerId = jdbc.sql("""
                        SELECT i."partnerId" FROM "ExternalIdentity" i
                        JOIN "Partner" p ON p.id = i."partnerId" AND p."deletedAt" IS NULL
                        JOIN "User" u ON u."organizationId" = p."organizationId" AND u.email = p.email
                        WHERE i."organizationId" = ? AND i."providerId" = ? AND u.id = ? LIMIT 1""")
                .params(organizationId, PROVIDER, userId).query(String.class).optional().orElse(null);
        if (partnerId == null || !claim(organizationId, "alert:" + alertId + ":" + userId)) {
            return;
        }
        String conversationId = (String) conversations.getOrCreateForContext("Partner", partnerId).get("id");
        messages.postSystemMessage(conversationId, text, PROVIDER);
    }

    private boolean claim(String organizationId, String dedupeKey) {
        return jdbc.sql("""
                        INSERT INTO "CommunicationDispatch" (id, "organizationId", "ruleId", "dedupeKey")
                        VALUES (?, ?, NULL, ?) ON CONFLICT ("organizationId", "dedupeKey") DO NOTHING""")
                .params(UUID.randomUUID().toString(), organizationId, dedupeKey).update() == 1;
    }
}
