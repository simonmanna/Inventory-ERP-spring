package com.inventoryerp.notificationrouting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.events.OutboxDelivery;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.notificationrouting.application.NotificationRouter;
import com.inventoryerp.notificationrouting.application.RoutedEventCatalog;
import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.web.NotificationRoutingController;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Notification routing (ADR-S065): the catalog against the real schema, routing, settings, tenancy, authorization. */
class NotificationRoutingIT extends ApiTestSupport {

    private static final Pattern PARAM = Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)");

    @Autowired
    RoutedEventCatalog catalog;

    @Autowired
    NotificationRouter router;

    @Autowired
    EventOutboxService outbox;

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String user(String label) {
        return createUser(demoOrg, label + "-" + suffix() + "@demo.test", "User@12345", null);
    }

    /** A task module row assigned to {@code assignee}. */
    String task(String assignee, String supervisor) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Task\" (id, \"organizationId\", title, \"assignedToId\", \"supervisorId\", \"createdBy\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, 'system', now())")
                .params(id, demoOrg, "Count the yard " + suffix(), assignee, supervisor).update();
        return id;
    }

    OutboxDelivery assigned(String taskId, String assignee, String actor) {
        JsonNode payload = mapper.valueToTree(Map.of("organizationId", demoOrg, "taskId", taskId, "title", "x",
                "assignedToId", assignee));
        return new OutboxDelivery(UUID.randomUUID().toString(), "task.assigned", actor, payload);
    }

    List<Map<String, Object>> inbox(String userId, String event) {
        return jdbc.sql("SELECT channel::text AS channel, category, title, payload::text AS payload FROM \"Notification\""
                + " WHERE \"userId\" = ? AND payload->>'event' = ? ORDER BY \"createdAt\"")
                .params(userId, event).query().listOfRows();
    }

    Map<String, Object> route(boolean enabled, List<String> parties, List<String> roleIds, List<String> userIds,
            List<String> channels) {
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", enabled);
        body.put("parties", parties);
        body.put("roleIds", roleIds);
        body.put("userIds", userIds);
        body.put("channels", channels);
        return body;
    }

    @Test
    @DisplayName("every record and party query of the catalog runs against the schema")
    void catalogQueriesRun() {
        assertThat(catalog.all()).hasSizeGreaterThan(40);
        Set<String> areas = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (RoutedEvent e : catalog.all()) {
            areas.add(e.area());
            List<String> sqls = new ArrayList<>();
            if (e.recordSql() != null) {
                sqls.add(e.recordSql());
            }
            e.parties().stream().map(Party::sql).filter(s -> s != null).forEach(sqls::add);
            for (String sql : sqls) {
                Map<String, Object> params = new HashMap<>();
                Matcher m = PARAM.matcher(sql.replaceAll("'[^']*'", "''"));
                while (m.find()) {
                    params.put(m.group(1), m.group(1).equals("org") ? demoOrg : UUID.randomUUID().toString());
                }
                try {
                    new TransactionTemplate(txm).executeWithoutResult(s -> jdbc.sql(sql).params(params).query()
                            .listOfRows());
                } catch (RuntimeException ex) {
                    failures.add(e.event() + ": " + ex.getMessage());
                }
            }
        }
        assertThat(failures).isEmpty();
        assertThat(areas).contains("sales", "purchasing", "inventory", "projects", "helpdesk", "hr", "fleet",
                "maintenance", "production", "quality");
    }

    @Test
    @DisplayName("the responsible person is told once, the actor never, and a stale event nobody")
    void routesToTheResponsible() {
        String assignee = user("assignee");
        String boss = user("boss");
        String taskId = task(assignee, null);

        OutboxDelivery d = assigned(taskId, assignee, boss);
        assertThat(router.route(d)).isEqualTo(1);
        // A retried outbox row is claimed once.
        assertThat(router.route(d)).isZero();
        List<Map<String, Object>> rows = inbox(assignee, "task.assigned");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("channel")).isEqualTo("in_app");
        assertThat(rows.getFirst().get("category")).isEqualTo("projects");
        assertThat((String) rows.getFirst().get("title")).startsWith("Task assigned to you: Count the yard");
        assertThat((String) rows.getFirst().get("payload")).contains("/tasks/" + taskId + "/edit", "Assignee");

        // Assigning yourself tells nobody.
        assertThat(router.route(assigned(taskId, assignee, assignee))).isZero();
        // The task was reassigned before the event was routed (or the write rolled back): nothing to announce.
        assertThat(router.route(assigned(taskId, boss, null))).isZero();
        // Inactive users are never told.
        jdbc.sql("UPDATE \"User\" SET \"isActive\" = false WHERE id = ?").param(assignee).update();
        assertThat(router.route(assigned(taskId, assignee, null))).isZero();
    }

    @Test
    @DisplayName("an organization picks parties, adds roles and users, picks channels, switches events off")
    void settingsChangeTheRoute() {
        String assignee = user("assignee");
        String supervisor = user("supervisor");
        String extra = user("extra");
        String roleMember = user("rolemember");
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Yard-" + suffix(), new String[0])
                .update();
        jdbc.sql("INSERT INTO \"_UserRoles\" (\"A\", \"B\") VALUES (?, ?)").params(role, roleMember).update();

        JsonNode listed = ok(200, "GET", "/notification-routing/events", null);
        JsonNode taskEvent = null;
        for (JsonNode e : listed) {
            if ("task.assigned".equals(e.get("event").asString())) {
                taskEvent = e;
            }
        }
        assertThat(taskEvent).isNotNull();
        assertThat(taskEvent.get("customized").asBoolean()).isFalse();
        assertThat(taskEvent.get("selectedParties").toString()).contains("assignee");

        JsonNode saved = ok(200, "PUT", "/notification-routing/events/task.assigned",
                route(true, List.of("assignee", "supervisor"), List.of(role), List.of(extra), List.of("in_app",
                        "email")));
        assertThat(saved.get("customized").asBoolean()).isTrue();
        assertThat(saved.get("channels").size()).isEqualTo(2);
        // The other organization keeps the defaults.
        Res other = call("GET", "/notification-routing/events", null, otherToken);
        assertThat(other.status()).isEqualTo(200);
        for (JsonNode e : other.body()) {
            assertThat(e.get("customized").asBoolean()).isFalse();
        }

        String taskId = task(assignee, supervisor);
        assertThat(router.route(assigned(taskId, assignee, null))).isEqualTo(4);
        assertThat(inbox(assignee, "task.assigned")).hasSize(2);
        assertThat(inbox(supervisor, "task.assigned")).hasSize(2);
        assertThat(inbox(extra, "task.assigned")).hasSize(2);
        assertThat((String) inbox(roleMember, "task.assigned").getFirst().get("payload")).contains("Role Yard-");

        ok(200, "PUT", "/notification-routing/events/task.assigned", route(false, List.of("assignee"), null, null,
                List.of("in_app")));
        assertThat(router.route(assigned(taskId, assignee, null))).isZero();

        JsonNode reset = ok(200, "DELETE", "/notification-routing/events/task.assigned", null);
        assertThat(reset.get("customized").asBoolean()).isFalse();
        assertThat(router.route(assigned(taskId, assignee, null))).isEqualTo(1);

        JsonNode activity = ok(200, "GET", "/notification-routing/activity?limit=5", null);
        assertThat(activity.get(0).get("eventName").asString()).isEqualTo("task.assigned");
    }

    @Test
    @DisplayName("bad settings are refused")
    void validation() {
        assertThat(call("PUT", "/notification-routing/events/task.assigned", route(true, List.of("nobody"), null, null,
                List.of("in_app")), adminToken).status()).isEqualTo(400);
        assertThat(call("PUT", "/notification-routing/events/task.assigned", route(true, List.of("assignee"), null,
                null, List.of("pigeon")), adminToken).status()).isEqualTo(400);
        // A user of another organization cannot be subscribed.
        String foreign = createUser(otherOrg, "f-" + suffix() + "@other.test", "User@12345", null);
        assertThat(call("PUT", "/notification-routing/events/task.assigned", route(true, List.of("assignee"), null,
                List.of(foreign), List.of("in_app")), adminToken).status()).isEqualTo(400);
        assertThat(call("PUT", "/notification-routing/events/no.such.event", route(true, List.of(), null, null,
                List.of("in_app")), adminToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the outbox records who published an event")
    void outboxStampsTheActor() {
        String actor = user("actor");
        String marker = UUID.randomUUID().toString();
        TenantContext.run(TenantStore.user(demoOrg, actor), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> outbox.publish("routing.test", Map.of("marker", marker))));
        String stamped = jdbc.sql("SELECT \"actorId\" FROM \"EventOutbox\" WHERE payload->>'marker' = ?").param(marker)
                .query(String.class).single();
        assertThat(stamped).isEqualTo(actor);
    }

    @Test
    @DisplayName("every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(NotificationRoutingController.class)).isEqualTo(4);
    }
}
