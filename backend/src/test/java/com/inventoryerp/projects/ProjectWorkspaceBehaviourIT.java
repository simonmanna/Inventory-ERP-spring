package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Project workspace behaviour (ADR-S062) beyond {@link ProjectManagementIT}: stage kinds and the "one open stage" rule,
 * reorder validation, milestone edit / reopen / delete, members (the manager always stays, notifications, validation),
 * favourites on and off, project chatter, status updates, project status, duplication with tasks and as a template,
 * sales-order projects without task lines, tags, and the shape of the overview and report endpoints.
 */
class ProjectWorkspaceBehaviourIT extends ApiTestSupport {

    String adminId;
    String partnerId;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        partnerId = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY"
                + " \"createdAt\" LIMIT 1").param(demoOrg).query(String.class).single();
    }

    JsonNode project(Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Workspace behaviour " + UUID.randomUUID()));
        body.putAll(extra);
        return ok(201, "POST", "/projects/workspace", body);
    }

    String task(String projectId, String title, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("projectId", projectId, "title", title));
        body.putAll(extra);
        return ok(201, "POST", "/project-tasks", body).get("id").asString();
    }

    static String stage(JsonNode stages, String name) {
        for (JsonNode s : stages) {
            if (s.get("name").asString().equals(name)) {
                return s.get("id").asString();
            }
        }
        throw new IllegalStateException("no stage " + name);
    }

    String newUser(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "pwb-" + role, permissions).update();
        return createUser(demoOrg, "pwb-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test", "Pm@123456",
                role);
    }

    @Test
    @DisplayName("PWB-1: stages — kind changes close / reopen tasks, one open stage, reorder and delete rules")
    void stages() {
        JsonNode p = project(Map.of());
        String pid = p.get("id").asString();
        JsonNode stages = p.get("stages");
        String review = stage(stages, "Review");
        String t = task(pid, "Proofread", Map.of("stageId", review));

        JsonNode closed = ok(200, "PATCH", "/projects/stages/" + review, Map.of("kind", "done", "name", "Approved",
                "folded", true));
        JsonNode approved = closed.get(2);
        assertThat(approved.get("name").asString()).isEqualTo("Approved");
        assertThat(approved.get("kind").asString()).isEqualTo("done");
        assertThat(approved.get("folded").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/project-tasks/" + t, null).get("isClosed").asBoolean()).isTrue();
        ok(200, "PATCH", "/projects/stages/" + review, Map.of("kind", "open"));
        assertThat(ok(200, "GET", "/project-tasks/" + t, null).get("isClosed").asBoolean()).isFalse();
        assertThat(as("PATCH", "/projects/stages/" + review, Map.of("kind", "archived")).status()).isEqualTo(400);

        // Leave exactly one open stage, then it can neither be closed nor deleted.
        String todo = stage(stages, "To Do");
        String doing = stage(stages, "In Progress");
        ok(200, "POST", "/project-tasks/" + t + "/move", Map.of("stageId", doing));
        ok(200, "DELETE", "/projects/stages/" + todo, null);
        ok(200, "DELETE", "/projects/stages/" + review, null);
        ok(200, "POST", "/project-tasks/" + t + "/move", Map.of("stageId", stage(stages, "Done")));
        assertThat(as("PATCH", "/projects/stages/" + doing, Map.of("kind", "cancelled")).raw().body())
                .contains("A project needs at least one open stage");
        assertThat(as("DELETE", "/projects/stages/" + doing, null).raw().body())
                .contains("A project needs at least one open stage");

        JsonNode now = ok(200, "GET", "/projects/" + pid + "/workspace", null).get("stages");
        List<String> ids = new ArrayList<>(now.findValuesAsString("id"));
        assertThat(as("POST", "/projects/" + pid + "/stages/reorder", Map.of("stageIds", ids.subList(1, ids.size())))
                .raw().body()).contains("stageIds must list every stage of the project exactly once");
        List<String> dup = new ArrayList<>(ids);
        dup.add(ids.getFirst());
        assertThat(as("POST", "/projects/" + pid + "/stages/reorder", Map.of("stageIds", dup)).status()).isEqualTo(400);
        List<String> reversed = new ArrayList<>(ids).reversed();
        assertThat(ok(200, "POST", "/projects/" + pid + "/stages/reorder", Map.of("stageIds", reversed))
                .findValuesAsString("id")).containsExactlyElementsOf(reversed);
        JsonNode added = ok(201, "POST", "/projects/" + pid + "/stages", Map.of("name", "On hold", "kind", "cancelled",
                "folded", true));
        assertThat(added.get(added.size() - 1).get("kind").asString()).isEqualTo("cancelled");
        assertThat(as("POST", "/projects/" + pid + "/stages", Map.of("name", "x".repeat(81))).status()).isEqualTo(400);
        assertThat(as("PATCH", "/projects/stages/" + UUID.randomUUID(), Map.of("name", "x")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("PWB-2: milestones — edit, reopen, delete unlinks tasks, member may only mark reached, disabled")
    void milestones() {
        String member = newUser(Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TASK__WRITE);
        String memberEmail = jdbc.sql("SELECT email FROM \"User\" WHERE id = ?").param(member).query(String.class)
                .single();
        String memberToken = login("DEMO", memberEmail, "Pm@123456");
        JsonNode p = project(Map.of("memberIds", List.of(member)));
        String pid = p.get("id").asString();
        String m = ok(201, "POST", "/projects/" + pid + "/milestones", Map.of("name", "Permit", "deadline",
                "2030-06-30", "description", "Building permit")).get(0).get("id").asString();
        String t = task(pid, "Apply for permit", Map.of("milestoneId", m));
        assertThat(ok(200, "GET", "/project-tasks/" + t, null).get("milestoneId").asString()).isEqualTo(m);

        JsonNode edited = ok(200, "PATCH", "/projects/milestones/" + m, Map.of("name", "Permit granted", "deadline",
                "2030-07-15"));
        assertThat(edited.get(0).get("name").asString()).isEqualTo("Permit granted");
        assertThat(edited.get(0).get("deadline").asString()).isEqualTo("2030-07-15");
        assertThat(as("PATCH", "/projects/milestones/" + m, Map.of("colour", "red")).raw().body())
                .contains("property colour should not exist");

        assertThat(call("PATCH", "/projects/milestones/" + m, Map.of("name", "x"), memberToken).status())
                .isEqualTo(403);
        assertThat(call("PATCH", "/projects/milestones/" + m, Map.of("isReached", true), memberToken).body().get(0)
                .get("isReached").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'project.milestone.reached'"
                + " AND payload->>'milestoneId' = ?").param(m).query(Long.class).single()).isEqualTo(1);
        JsonNode reopened = ok(200, "PATCH", "/projects/milestones/" + m, Map.of("isReached", false));
        assertThat(reopened.get(0).get("isReached").asBoolean()).isFalse();
        assertThat(reopened.get(0).get("reachedAt").isNull()).isTrue();
        assertThat(ok(200, "GET", "/projects/" + pid + "/messages", null).findValuesAsString("body"))
                .contains("Milestone reached: Permit granted", "Milestone reopened: Permit granted");

        assertThat(call("DELETE", "/projects/milestones/" + m, null, memberToken).status()).isEqualTo(403);
        assertThat(ok(200, "DELETE", "/projects/milestones/" + m, null)).isEmpty();
        assertThat(ok(200, "GET", "/project-tasks/" + t, null).get("milestoneId").isNull()).isTrue();
        assertThat(as("PATCH", "/projects/milestones/" + m, Map.of("isReached", true)).status()).isEqualTo(404);

        ok(200, "PATCH", "/projects/" + pid + "/workspace", Map.of("allowMilestones", false));
        assertThat(as("POST", "/projects/" + pid + "/milestones", Map.of("name", "x")).raw().body())
                .contains("Milestones are disabled on this project");
    }

    @Test
    @DisplayName("PWB-3: members, favourites, chatter, status updates and project status")
    void peopleAndStatus() {
        String alice = newUser(Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TASK__WRITE);
        String bob = newUser(Permissions.PROJECT_TASK__READ);
        JsonNode p = project(Map.of());
        String pid = p.get("id").asString();
        String code = p.get("code").asString();

        JsonNode members = ok(200, "PUT", "/projects/" + pid + "/members", Map.of("members", List.of(
                Map.of("userId", alice), Map.of("userId", bob, "role", "viewer"))));
        assertThat(members.findValuesAsString("userId")).containsExactlyInAnyOrder(adminId, alice, bob);
        for (JsonNode m : members) {
            String expected = m.get("userId").asString().equals(adminId) ? "manager"
                    : m.get("userId").asString().equals(bob) ? "viewer" : "member";
            assertThat(m.get("role").asString()).isEqualTo(expected);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND title = ?").params(alice,
                "Added to project " + code).query(Long.class).single()).isEqualTo(1);
        // Dropping everyone keeps the manager.
        assertThat(ok(200, "PUT", "/projects/" + pid + "/members", Map.of("members", List.of()))
                .findValuesAsString("userId")).containsExactly(adminId);
        assertThat(as("PUT", "/projects/" + pid + "/members", Map.of("members", List.of(Map.of("userId",
                UUID.randomUUID().toString())))).raw().body()).contains("is not a user of this organization");
        assertThat(as("PUT", "/projects/" + pid + "/members", Map.of("members", List.of(Map.of("userId", alice,
                "role", "owner")))).status()).isEqualTo(400);

        assertThat(ok(200, "POST", "/projects/" + pid + "/favorite", null).get("isFavorite").asBoolean()).isTrue();
        assertThat(ok(200, "POST", "/projects/" + pid + "/favorite", null).get("isFavorite").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/projects/workspace?filter=favorites", null).findValuesAsString("id"))
                .doesNotContain(pid);

        ok(200, "PUT", "/projects/" + pid + "/members", Map.of("members", List.of(Map.of("userId", alice))));
        ok(201, "POST", "/projects/" + pid + "/messages", Map.of("kind", "note", "body", "Internal only"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND title LIKE 'New message on"
                + " project%'").param(alice).query(Long.class).single()).isZero();
        ok(201, "POST", "/projects/" + pid + "/messages", Map.of("kind", "comment", "body", "Kick-off on Monday"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND title LIKE 'New message on"
                + " project%'").param(alice).query(Long.class).single()).isEqualTo(1);
        assertThat(as("POST", "/projects/" + pid + "/messages", Map.of("kind", "system", "body", "x")).status())
                .isEqualTo(400);
        String tid = task(pid, "Agenda", Map.of());
        ok(201, "POST", "/project-tasks/" + tid + "/messages", Map.of("body", "On a task"));
        assertThat(ok(200, "GET", "/projects/" + pid + "/messages", null).findValuesAsString("body"))
                .doesNotContain("On a task");
        assertThat(ok(200, "GET", "/projects/" + pid + "/messages?includeTasks=true", null).findValuesAsString("body"))
                .contains("On a task");

        JsonNode upd = ok(201, "POST", "/projects/" + pid + "/updates", Map.of("status", "off_track", "progress", 35,
                "summary", "Supplier late"));
        assertThat(upd.get(0).get("progress").asInt()).isEqualTo(35);
        assertThat(upd.get(0).get("authorName")).isNotNull();
        assertThat(as("POST", "/projects/" + pid + "/updates", Map.of("status", "on_track", "progress", 101)).status())
                .isEqualTo(400);
        assertThat(ok(200, "GET", "/projects/workspace?search=" + code, null).get(0).get("lastUpdateStatus").asString())
                .isEqualTo("off_track");

        assertThat(ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "on_hold")).get("status")
                .asString()).isEqualTo("on_hold");
        assertThat(ok(200, "GET", "/projects/" + pid + "/workspace", null).get("status").asString())
                .isEqualTo("on_hold");
        assertThat(as("POST", "/projects/" + pid + "/status", Map.of("status", "paused")).raw().body())
                .contains("status must be one of the following values");
        ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "cancelled"));
        assertThat(as("POST", "/projects/" + pid + "/status", Map.of("status", "active")).raw().body())
                .contains("A cancelled project cannot change status");
    }

    @Test
    @DisplayName("PWB-4: duplicate with tasks and as a template, sales-order project without lines, cancelled orders")
    void duplicateAndSalesOrders() {
        JsonNode p = project(Map.of("startDate", "2030-01-01", "plannedHours", 40, "privacy", "members"));
        String pid = p.get("id").asString();
        ok(201, "POST", "/projects/" + pid + "/stages", Map.of("name", "Snagging"));
        String a = task(pid, "Design", Map.of("deadline", "2030-01-05", "checklist", List.of("Sketch", "Review")));
        task(pid, "Build", Map.of("deadline", "2030-01-15", "dependsOnIds", List.of(a)));

        JsonNode copy = ok(201, "POST", "/projects/" + pid + "/duplicate", Map.of("name", "Copy with tasks",
                "asTemplate", true, "startDate", "2030-02-01"));
        String copyId = copy.get("id").asString();
        assertThat(copy.get("isTemplate").asBoolean()).isTrue();
        assertThat(copy.get("privacy").asString()).isEqualTo("members");
        assertThat(copy.get("stages").findValuesAsString("name")).contains("Snagging");
        assertThat(copy.get("code").asString()).isNotEqualTo(p.get("code").asString());
        JsonNode copied = ok(200, "GET", "/project-tasks?projectId=" + copyId, null);
        assertThat(copied.get("total").asInt()).isEqualTo(2);
        assertThat(copied.get("data").findValuesAsString("deadline")).containsExactlyInAnyOrder("2030-02-05",
                "2030-02-15");
        assertThat(ok(200, "GET", "/projects/workspace?templates=true", null).findValuesAsString("id"))
                .contains(copyId);
        assertThat(ok(200, "GET", "/projects/" + copyId + "/messages", null).findValuesAsString("body"))
                .anyMatch(b -> b.startsWith("Project copied from " + p.get("code").asString()));
        assertThat(ok(200, "GET", "/projects/" + pid + "/workspace", null)
                .get("isTemplate").asBoolean()).isFalse();
        JsonNode defaults = ok(201, "POST", "/projects/" + pid + "/duplicate", Map.of());
        assertThat(defaults.get("name").asString()).endsWith("(copy)");

        String so = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SalesOrder\" (id, \"organizationId\", number, \"partnerId\", \"currencyCode\", status,"
                + " total) VALUES (?, ?, ?, ?, 'USD', 'confirmed', 900)").params(so, demoOrg, "SO-PWB-"
                + so.substring(0, 6), partnerId).update();
        jdbc.sql("INSERT INTO \"SalesOrderLine\" (id, \"organizationId\", \"orderId\", description, quantity,"
                + " \"unitPrice\") VALUES (?, ?, ?, 'Consulting', 1, 900)").params(UUID.randomUUID().toString(),
                demoOrg, so).update();
        JsonNode fromSo = ok(201, "POST", "/projects/from-sales-order", Map.of("salesOrderId", so, "name",
                "Consulting engagement", "taskPerLine", false));
        assertThat(fromSo.get("name").asString()).isEqualTo("Consulting engagement");
        assertThat(ok(200, "GET", "/project-tasks?projectId=" + fromSo.get("id").asString(), null).get("total").asInt())
                .isZero();

        String cancelled = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SalesOrder\" (id, \"organizationId\", number, \"partnerId\", \"currencyCode\", status,"
                + " total) VALUES (?, ?, ?, ?, 'USD', 'cancelled', 10)").params(cancelled, demoOrg, "SO-PWX-"
                + cancelled.substring(0, 6), partnerId).update();
        assertThat(as("POST", "/projects/from-sales-order", Map.of("salesOrderId", cancelled)).raw().body())
                .contains("A cancelled sales order cannot start a project");
        assertThat(as("POST", "/projects/from-sales-order", Map.of("salesOrderId", UUID.randomUUID().toString()))
                .status()).isEqualTo(404);
        assertThat(as("POST", "/projects/from-sales-order", Map.of()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("PWB-5: tags, overview and report shapes")
    void tagsAndShapes() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        JsonNode tag = ok(201, "POST", "/project-tags", Map.of("name", "Client-" + suffix, "color", 4));
        String tagId = tag.get("id").asString();
        ok(201, "POST", "/project-tags", Map.of("name", "Internal-" + suffix));
        assertThat(as("PATCH", "/project-tags/" + tagId, Map.of("name", "internal-" + suffix)).status())
                .isEqualTo(409);
        assertThat(as("POST", "/project-tags", Map.of("name", "x".repeat(61))).status()).isEqualTo(400);
        JsonNode renamed = ok(200, "PATCH", "/project-tags/" + tagId, Map.of("name", "Customer-" + suffix, "color", 7));
        assertThat(renamed.get("name").asString()).isEqualTo("Customer-" + suffix);
        assertThat(renamed.get("color").asInt()).isEqualTo(7);

        JsonNode p = project(Map.of("plannedHours", 8));
        String pid = p.get("id").asString();
        String t = task(pid, "Tagged", Map.of("tagIds", List.of(tagId), "plannedHours", 8, "assigneeIds",
                List.of(adminId)));
        JsonNode tags = ok(200, "GET", "/project-tags", null);
        for (JsonNode g : tags) {
            if (g.get("id").asString().equals(tagId)) {
                assertThat(g.get("taskCount").asInt()).isEqualTo(1);
            }
        }
        ok(200, "DELETE", "/project-tags/" + tagId, null);
        assertThat(ok(200, "GET", "/project-tasks/" + t, null).get("tags")).isEmpty();
        assertThat(as("DELETE", "/project-tags/" + tagId, null).status()).isEqualTo(404);

        JsonNode overview = ok(200, "GET", "/projects/" + pid + "/overview", null);
        assertThat(overview.has("tasks")).isTrue();
        assertThat(overview.has("hours")).isTrue();
        assertThat(overview.has("milestones")).isTrue();

        JsonNode byAssignee = ok(200, "GET", "/project-reports/tasks?groupBy=assignee&projectId=" + pid, null);
        assertThat(byAssignee.get(0).get("key").asString()).isEqualTo(adminId);
        for (String field : List.of("label", "total", "open", "closed", "overdue", "closedLate", "plannedHours",
                "effectiveHours", "avgDaysToClose")) {
            assertThat(byAssignee.get(0).has(field)).as(field).isTrue();
        }
        for (String g : List.of("project", "tag", "priority", "milestone", "month")) {
            assertThat(as("GET", "/project-reports/tasks?groupBy=" + g + "&projectId=" + pid, null).status()).as(g)
                    .isEqualTo(200);
        }
        assertThat(as("GET", "/project-reports/tasks?groupBy=colour", null).status()).isEqualTo(400);
        for (String g : List.of("project", "task", "user", "week", "month", "date")) {
            assertThat(as("GET", "/project-reports/timesheets?groupBy=" + g, null).status()).as(g).isEqualTo(200);
        }
        JsonNode workload = ok(200, "GET", "/project-reports/workload?projectId=" + pid, null);
        assertThat(workload.get(0).get("userId").asString()).isEqualTo(adminId);
        assertThat(workload.get(0).get("open").asInt()).isEqualTo(1);
        assertThat(new java.math.BigDecimal(workload.get(0).get("remainingHours").asString()))
                .isEqualByComparingTo("8");
        JsonNode gantt = ok(200, "GET", "/project-reports/gantt?projectId=" + pid, null);
        assertThat(gantt.has("tasks")).isTrue();
        assertThat(gantt.has("milestones")).isTrue();
        assertThat(as("GET", "/project-reports/gantt", null).status()).isEqualTo(400);
    }
}
