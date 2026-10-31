package com.inventoryerp.alerts.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.alerts.spi.AlertTypes.Definition;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.EntityJson;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two halves of raising alerts. {@link #claim} is the transaction: a subject is claimed on the unique key of
 * {@code AlertEvent} for its period, and only what was claimed goes on, so a re-run, a second node or a restart sends
 * nothing twice. {@link #deliver} runs after that commit, so a slow mail server never holds a database transaction
 * open. A crash between the two loses a bell or an e-mail, not the alert: the event stays in the list.
 */
@Service
public class AlertDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(AlertDeliveryService.class);
    private static final String CATEGORY = "alerts";
    private static final int MAX_RECIPIENTS = 200;

    /** What one run claimed for one type; empty when every subject had already alerted in its period. */
    public record Batch(int claimed, AlertDigest digest, List<String> recipients) {

        static final Batch EMPTY = new Batch(0, null, List.of());
    }

    private final JdbcClient jdbc;
    private final EventOutboxService outbox;
    private final NotificationsService notifications;

    public AlertDeliveryService(JdbcClient jdbc, EventOutboxService outbox, NotificationsService notifications) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.notifications = notifications;
    }

    /**
     * Claim {@code items} for the period of {@code day}. The {@code alert.raised} event goes out with the claim, in the
     * same transaction: communication sends it over WhatsApp, and rules can route it elsewhere.
     */
    @Transactional
    public Batch claim(String org, Definition def, AlertRuleService.Rule rule, LocalDate day, List<AlertItem> items) {
        String period = AlertPeriods.key(rule.repeatEvery(), day);
        String channels = String.join(",", rule.channels());
        List<String> ids = new ArrayList<>();
        List<AlertItem> claimed = new ArrayList<>();
        for (AlertItem item : items) {
            String id = UUID.randomUUID().toString();
            int inserted = jdbc.sql("INSERT INTO \"AlertEvent\" (id, \"organizationId\", \"alertType\", \"subjectKey\","
                    + " \"periodKey\", severity, title, body, link, data, channels) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?::jsonb, string_to_array(?, ',')) ON CONFLICT (\"organizationId\", \"alertType\","
                    + " \"subjectKey\", \"periodKey\") DO NOTHING")
                    .params(id, org, def.type(), item.subjectKey(), period, item.severity(), item.title(), item.body(),
                            item.link(), EntityJson.toJson(item.data() == null ? Map.of() : item.data()), channels)
                    .update();
            if (inserted > 0) {
                ids.add(id);
                claimed.add(item);
            }
        }
        if (claimed.isEmpty()) {
            return Batch.EMPTY;
        }
        List<String> recipients = recipients(org, rule);
        jdbc.sql("UPDATE \"AlertEvent\" SET recipients = :n WHERE \"organizationId\" = :org AND id IN (:ids)")
                .param("n", recipients.size()).param("org", org).param("ids", ids).update();
        AlertDigest digest = AlertDigest.of(def, claimed);
        outbox.publish(DomainEvents.ALERT_RAISED, Obj.of("organizationId", org, "alertId", ids.getFirst(), "alertType",
                def.type(), "severity", digest.severity(), "count", claimed.size(), "title", digest.title(), "body",
                digest.body(), "link", digest.link(), "channels", rule.channels(), "recipientUserIds", recipients));
        return new Batch(claimed.size(), digest, recipients);
    }

    /** Users holding the rule's permission, then the named users; inactive and deleted users never receive anything. */
    private List<String> recipients(String org, AlertRuleService.Rule rule) {
        Set<String> out = new LinkedHashSet<>(jdbc.sql("SELECT DISTINCT u.id FROM \"User\" u JOIN \"_UserRoles\" ur ON"
                + " ur.\"B\" = u.id JOIN \"Role\" r ON r.id = ur.\"A\" WHERE u.\"organizationId\" = ? AND"
                + " u.\"isActive\" AND u.\"deletedAt\" IS NULL AND ? = ANY(r.permissions) ORDER BY u.id LIMIT "
                + MAX_RECIPIENTS)
                .params(org, rule.recipientPermission()).query(String.class).list());
        if (!rule.userIds().isEmpty()) {
            out.addAll(jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = :org AND \"isActive\" AND"
                    + " \"deletedAt\" IS NULL AND id IN (:ids)").param("org", org).param("ids", rule.userIds())
                    .query(String.class).list());
        }
        return List.copyOf(out);
    }

    /** One bell and one e-mail per recipient. One failed recipient never stops the others. */
    public void deliver(String org, Definition def, AlertRuleService.Rule rule, Batch batch) {
        AlertDigest d = batch.digest();
        for (String channel : List.of(AlertTypes.CHANNEL_IN_APP, AlertTypes.CHANNEL_EMAIL)) {
            if (!rule.channels().contains(channel)) {
                continue;
            }
            for (String user : batch.recipients()) {
                try {
                    notifications.send(new SendInput(org, user, channel, CATEGORY, d.title(), d.body(), Obj.of("kind",
                            "alert", "alertType", def.type(), "severity", d.severity(), "href", d.link(), "link",
                            d.link())));
                } catch (RuntimeException e) {
                    log.warn("alert {} to user {} over {} not delivered: {}", def.type(), user, channel, e.toString());
                }
            }
        }
    }
}
