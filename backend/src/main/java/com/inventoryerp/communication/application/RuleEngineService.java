package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.RecipientResolverService.Recipient;
import com.inventoryerp.kernel.common.ContractCatalogs;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The event-driven messaging engine (port of {@code rules/rule-engine.service.ts}): an event fires, matching
 * enabled {@code CommunicationRule}s pick a template + recipients + channel, and the result is either an in-app
 * notification ({@code internal}) or a durable system message over an external provider. Each
 * (rule, event-instance) is claimed once on {@code CommunicationDispatch.dedupeKey}. The caller establishes the
 * tenant scope.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class RuleEngineService {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);

    private record Rule(String id, String condition, String templateKey, String recipientResolver,
            String channelSelector) {}

    private record Template(String key, String subject, String body) {}

    private final JdbcClient jdbc;
    private final NotificationsService notifications;
    private final ConversationService conversations;
    private final MessageService messages;
    private final RecipientResolverService recipients;
    private final JsonMapper mapper;

    public RuleEngineService(JdbcClient jdbc, NotificationsService notifications, ConversationService conversations,
            MessageService messages, RecipientResolverService recipients, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.conversations = conversations;
        this.messages = messages;
        this.recipients = recipients;
        this.mapper = mapper;
    }

    public void dispatch(String eventName, Map<String, Object> payload) {
        String organizationId = payload.get("organizationId") instanceof String s ? s : "";
        if (organizationId.isEmpty()) {
            return;
        }
        List<Rule> rules = jdbc.sql("""
                        SELECT id, condition::text AS condition, "templateKey", "recipientResolver", "channelSelector"
                        FROM "CommunicationRule" WHERE "organizationId" = ? AND "eventName" = ? AND enabled = true""")
                .params(organizationId, eventName)
                .query((rs, n) -> new Rule(rs.getString("id"), rs.getString("condition"), rs.getString("templateKey"),
                        rs.getString("recipientResolver"), rs.getString("channelSelector")))
                .list();
        if (rules.isEmpty()) {
            return;
        }
        String entityId = entityId(eventName, payload);

        for (Rule rule : rules) {
            try {
                Object condition = rule.condition() == null ? Map.of() : mapper.readValue(rule.condition(), Object.class);
                if (!Condition.matches(condition, payload)) {
                    continue;
                }
                // Claim the dedupe key; already there ⇒ another delivery of the same event handled this rule.
                String dedupeKey = rule.id() + ":" + eventName + ":" + entityId;
                if (!claim(organizationId, rule.id(), dedupeKey)) {
                    continue;
                }
                Template template = jdbc.sql("""
                                SELECT key, subject, body FROM "MessageTemplate"
                                WHERE "organizationId" = ? AND key = ? AND active = true
                                ORDER BY version DESC LIMIT 1""")
                        .params(organizationId, rule.templateKey())
                        .query((rs, n) -> new Template(rs.getString(1), rs.getString(2), rs.getString(3)))
                        .optional().orElse(null);
                if (template == null) {
                    log.warn("rule {}: template '{}' not found/active", rule.id(), rule.templateKey());
                    continue;
                }
                String body = TemplateRender.render(template.body(), payload);
                String subject = template.subject() != null && !template.subject().isEmpty()
                        ? TemplateRender.render(template.subject(), payload) : null;

                List<Recipient> to = recipients.resolve(organizationId, rule.recipientResolver(), payload);
                if (to.isEmpty()) {
                    continue;
                }
                if ("internal".equals(rule.channelSelector())) {
                    fanNotifications(organizationId, to, subject != null ? subject : template.key(), body, dedupeKey);
                } else {
                    fanExternalMessage(organizationId, rule.channelSelector(), to, body);
                }
            } catch (RuntimeException err) {
                log.warn("rule {} failed: {}", rule.id(), err.toString());
            }
        }
    }

    /** In-app system alerts — the bell. */
    private void fanNotifications(String organizationId, List<Recipient> to, String title, String body,
            String dedupeKey) {
        for (Recipient r : to) {
            if (!"user".equals(r.kind()) || r.userId() == null) {
                continue;
            }
            try {
                notifications.send(new NotificationsService.SendInput(organizationId, r.userId(), "in_app",
                        "communication", title, body,
                        Obj.of("dedupeKey", dedupeKey + ":" + r.userId(), "href", "/communication")));
            } catch (RuntimeException e) {
                log.warn("notify {} failed: {}", r.userId(), e.toString());
            }
        }
    }

    /**
     * A message to a customer over an external transport: only when the partner has an ExternalIdentity for the
     * provider (and that provider's channel is attached to the partner's conversation); otherwise skipped.
     */
    private void fanExternalMessage(String organizationId, String providerId, List<Recipient> to, String body) {
        for (Recipient r : to) {
            if (!"partner".equals(r.kind()) || r.partnerId() == null) {
                continue;
            }
            boolean hasIdentity = jdbc.sql("SELECT count(*) FROM \"ExternalIdentity\" WHERE \"organizationId\" = ?"
                            + " AND \"providerId\" = ? AND \"partnerId\" = ?")
                    .params(organizationId, providerId, r.partnerId()).query(Long.class).single() > 0;
            if (!hasIdentity) {
                log.debug("no {} identity for partner {}; skipping external message", providerId, r.partnerId());
                continue;
            }
            String convId = (String) conversations.getOrCreateForContext("Partner", r.partnerId()).get("id");
            try {
                messages.postSystemMessage(convId, body, providerId);
            } catch (RuntimeException e) {
                log.warn("external message to partner {} failed: {}", r.partnerId(), e.toString());
            }
        }
    }

    /** Insert the dedupe row; false if it already existed. */
    private boolean claim(String organizationId, String ruleId, String dedupeKey) {
        return jdbc.sql("""
                        INSERT INTO "CommunicationDispatch" (id, "organizationId", "ruleId", "dedupeKey")
                        VALUES (?, ?, ?, ?) ON CONFLICT ("organizationId", "dedupeKey") DO NOTHING""")
                .params(UUID.randomUUID().toString(), organizationId, ruleId, dedupeKey)
                .update() == 1;
    }

    /** A stable id for the event-instance: the declared subject id, else a payload hash. */
    String entityId(String eventName, Map<String, Object> payload) {
        var subject = ContractCatalogs.eventSubjects().get(eventName);
        if (subject != null && payload.get(subject.idField()) instanceof String v && !v.isEmpty()) {
            return v;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
