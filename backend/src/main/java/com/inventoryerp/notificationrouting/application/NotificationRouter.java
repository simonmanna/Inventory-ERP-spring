package com.inventoryerp.notificationrouting.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.OutboxDelivery;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.notificationrouting.application.NotificationRouteService.Route;
import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Routes one outbox delivery of a catalogued event (ADR-S065): claim the row once, read the record (no row = nothing to
 * announce), collect the route's parties, roles and users, keep the organization's active users minus the actor, and
 * send one notification per user and channel. Never throws: a failure here must not make the outbox retry the row for
 * the other subscribers.
 */
@Service
public class NotificationRouter {

    private static final Logger log = LoggerFactory.getLogger(NotificationRouter.class);

    /** Above this many recipients the event is cut off and logged: a route should name people, not the company. */
    static final int MAX_RECIPIENTS = 200;

    private final JdbcClient jdbc;
    private final RoutedEventCatalog catalog;
    private final NotificationRouteService routes;
    private final NotificationsService notifications;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public NotificationRouter(JdbcClient jdbc, RoutedEventCatalog catalog, NotificationRouteService routes,
            NotificationsService notifications, PlatformTransactionManager txm, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.routes = routes;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(txm);
        this.meters = meters;
    }

    /** Outbox entry point. Returns how many users were told (0 when skipped or failed). */
    public int route(OutboxDelivery d) {
        RoutedEvent def = catalog.get(d.eventName());
        JsonNode payload = d.payload();
        if (def == null || payload == null || !payload.isObject()) {
            return 0;
        }
        String org = text(payload.get("organizationId"));
        String recordId = text(payload.get(def.idField()));
        if (org == null || recordId == null) {
            return 0;
        }
        for (Map.Entry<String, String> skip : def.skipWhen().entrySet()) {
            String actual = text(payload.get(skip.getKey()));
            if (actual != null && List.of(skip.getValue().split("\\|")).contains(actual)) {
                return 0;
            }
        }
        try {
            Integer told = TenantContext.run(TenantStore.organization(org),
                    () -> tx.execute(s -> deliver(def, d, org, recordId, payload)));
            int n = told == null ? 0 : told;
            if (n > 0) {
                meters.counter("notification_routing.sent", "event", def.event()).increment(n);
            }
            return n;
        } catch (RuntimeException e) {
            meters.counter("notification_routing.failed", "event", def.event()).increment();
            log.warn("notification routing of {} {} failed: {}", def.event(), recordId, e.toString());
            return 0;
        }
    }

    private int deliver(RoutedEvent def, OutboxDelivery d, String org, String recordId, JsonNode payload) {
        String claimId = d.id() == null ? UUID.randomUUID().toString() : d.id();
        int claimed = jdbc.sql("INSERT INTO \"NotificationRouteClaim\" (\"outboxId\", \"organizationId\", \"eventName\","
                + " \"recordId\") VALUES (?, ?, ?, ?) ON CONFLICT (\"outboxId\") DO NOTHING")
                .params(claimId, org, def.event(), recordId).update();
        if (claimed == 0) {
            return 0;
        }
        Route route = routes.route(def);
        if (!route.enabled() || route.channels().isEmpty()) {
            return 0;
        }
        Map<String, Object> params = params(payload, recordId, org);
        Map<String, Object> record = null;
        if (def.recordSql() != null) {
            List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(def.recordSql()).params(params));
            if (rows.isEmpty()) {
                return 0;
            }
            record = rows.getFirst();
        }
        // user id -> why they are told (the first reason wins).
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String key : route.parties()) {
            Party party = def.party(key);
            for (String user : partyUsers(def, party, payload, params)) {
                reasons.putIfAbsent(user, party.label());
            }
        }
        if (!route.roleIds().isEmpty()) {
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT ur.\"B\" AS \"userId\", r.name FROM"
                    + " \"_UserRoles\" ur JOIN \"Role\" r ON r.id = ur.\"A\" WHERE ur.\"A\" IN (:roles) AND"
                    + " r.\"organizationId\" = :org AND r.\"deletedAt\" IS NULL")
                    .param("roles", route.roleIds()).param("org", org))) {
                reasons.putIfAbsent((String) r.get("userId"), "Role " + r.get("name"));
            }
        }
        for (String user : route.userIds()) {
            reasons.putIfAbsent(user, "Subscribed");
        }
        if (d.actorId() != null) {
            reasons.remove(d.actorId());
        }
        if (reasons.isEmpty()) {
            return 0;
        }
        Set<String> active = new LinkedHashSet<>(jdbc.sql("SELECT id FROM \"User\" WHERE id IN (:ids) AND"
                + " \"organizationId\" = :org AND \"isActive\" AND \"deletedAt\" IS NULL")
                .param("ids", List.copyOf(reasons.keySet())).param("org", org).query(String.class).list());
        List<String> recipients = reasons.keySet().stream().filter(active::contains).toList();
        if (recipients.size() > MAX_RECIPIENTS) {
            log.warn("notification routing of {} {}: {} recipients, only the first {} are told", def.event(),
                    recordId, recipients.size(), MAX_RECIPIENTS);
            recipients = recipients.subList(0, MAX_RECIPIENTS);
        }
        String title = Templates.render(def.title(), recordId, record, payload);
        String body = Templates.render(def.body(), recordId, record, payload);
        String href = def.href() == null ? null : Templates.render(def.href(), recordId, record, payload);
        for (String user : recipients) {
            for (String channel : route.channels()) {
                notifications.send(new SendInput(org, user, channel, def.area(), title, body,
                        Obj.of("href", href, "event", def.event(), "recordId", recordId, "reason", reasons.get(user),
                                "tag", def.event() + ":" + recordId)));
            }
        }
        jdbc.sql("UPDATE \"NotificationRouteClaim\" SET recipients = ? WHERE \"outboxId\" = ?")
                .params(recipients.size(), claimId).update();
        return recipients.size();
    }

    private List<String> partyUsers(RoutedEvent def, Party party, JsonNode payload, Map<String, Object> params) {
        List<String> out = new ArrayList<>();
        if (party.payloadField() != null) {
            JsonNode v = payload.get(party.payloadField());
            if (v != null && v.isArray()) {
                v.forEach(item -> addText(out, item));
            } else {
                addText(out, v);
            }
            return out;
        }
        // A failing statement aborts the Postgres transaction, so a broken party fails the event (logged by route()).
        // NotificationRoutingCatalogIT runs every party query, so a typo does not reach production.
        for (String user : jdbc.sql(party.sql()).params(params).query(String.class).list()) {
            if (user != null && !user.isBlank()) {
                out.add(user);
            }
        }
        return out;
    }

    /** Named parameters: every scalar payload field by its name, then {@code :id} and {@code :org}. */
    private static Map<String, Object> params(JsonNode payload, String recordId, String org) {
        Map<String, Object> params = new HashMap<>();
        payload.properties().forEach(e -> {
            JsonNode v = e.getValue();
            if (v != null && v.isValueNode() && !v.isNull()) {
                params.put(e.getKey(), v.isNumber() ? v.numberValue() : v.isBoolean() ? v.asBoolean() : v.asString());
            }
        });
        params.put("id", recordId);
        params.put("org", org);
        return params;
    }

    private static void addText(List<String> out, JsonNode v) {
        String s = text(v);
        if (s != null) {
            out.add(s);
        }
    }

    private static String text(JsonNode v) {
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asString();
        return s == null || s.isBlank() ? null : s;
    }
}
