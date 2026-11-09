package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.projects.web.ProjectTaskController;
import com.inventoryerp.projects.web.ProjectTimesheetController;
import com.inventoryerp.projects.web.ProjectWorkspaceController;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Project management (ADR-S062): workspace (board, settings, members, stages, milestones, updates, favourites,
 * templates), tasks (numbering, stages, assignees, followers, dependencies, recurrence, subtasks, checklist, chatter,
 * bulk, delete), privacy and personal to-dos, timesheets mirrored into HR and captured into project cost once approved,
 * weekly grid and timer, sales-order projects, reports, tenant isolation and the authorization matrix.
 */
class ProjectManagementIT extends ApiTestSupport {

    String adminId;
    String partnerId;
    String employeeId;
    String employeeUserBefore;
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        partnerId = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY"
                + " \"createdAt\" LIMIT 1").param(demoOrg).query(String.class).single();
        List<Map<String, Object>> emp = jdbc.sql("SELECT id, \"userId\" FROM \"HrEmployee\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND (\"userId\" IS NULL OR \"userId\" = ?) ORDER BY \"createdAt\" LIMIT 1")
                .params(demoOrg, adminId).query().listOfRows();
        if (!emp.isEmpty()) {
            employeeId = (String) emp.getFirst().get("id");
            employeeUserBefore = (String) emp.getFirst().get("userId");
            jdbc.sql("UPDATE \"HrEmployee\" SET \"userId\" = ? WHERE id = ?").params(adminId, employeeId).update();
        }
    }

    @AfterAll
    void restore() {
        if (employeeId != null) {
            jdbc.sql("UPDATE \"HrEmployee\" SET \"userId\" = ? WHERE id = ?").params(employeeUserBefore, employeeId)
                    .update();
        }
    }

    String tokenWith(String... permissions) {
        return login("DEMO", userWith(permissions), "Pm@123456");
    }

    String userWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "pm-" + role, permissions).update();
        String email = "pm-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Pm@123456", role);
        return email;
    }

    String idOf(String email) {
        return jdbc.sql("SELECT id FROM \"User\" WHERE email = ?").param(email).query(String.class).single();
    }

    JsonNode project(String name, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name));
        body.putAll(extra);
        return ok(201, "POST", "/projects/workspace", body);
    }

    JsonNode task(String projectId, String title, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (projectId != null) {
            body.put("projectId", projectId);
        }
        body.put("title", title);
        body.putAll(extra);
        return ok(201, "POST", "/project-tasks", body);
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    String stage(JsonNode project, String name) {
        for (JsonNode s : project.get("stages")) {
            if (s.get("name").asString().equals(name)) {
                return s.get("id").asString();
            }
        }
        throw new IllegalStateException("no stage " + name);
    }

    @Test
    @DisplayName("PM-1: workspace — auto code, default stages, manager membership, board, favourite, settings, stages")
    void workspace() {
        JsonNode p = project("Website relaunch", Map.of("description", "New site", "customerId", partnerId,
                "plannedHours", 120, "privacy", "internal"));
        String id = p.get("id").asString();
        assertThat(p.get("code").asString()).startsWith("PRJ-");
        assertThat(p.get("status").asString()).isEqualTo("active");
        assertThat(p.get("stages")).hasSize(5);
        assertThat(p.get("stages").findValuesAsString("kind")).containsExactly("open", "open", "open", "done",
                "cancelled");
        assertThat(p.get("managerId").asString()).isEqualTo(adminId);
        assertThat(p.get("members").get(0).get("role").asString()).isEqualTo("manager");
        assertThat(p.get("canManage").asBoolean()).isTrue();
        assertThat(p.get("customerName").isNull()).isFalse();

        JsonNode board = ok(200, "GET", "/projects/workspace?search=" + p.get("code").asString(), null);
        assertThat(board).hasSize(1);
        assertThat(board.get(0).get("isFavorite").asBoolean()).isFalse();
        assertThat(ok(200, "POST", "/projects/" + id + "/favorite", null).get("isFavorite").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/projects/workspace?filter=favorites", null).findValuesAsString("id")).contains(id);

        JsonNode s = ok(200, "PATCH", "/projects/" + id + "/workspace", Map.of("name", "Website relaunch 2",
                "privacy", "members", "allowRecurring", false));
        assertThat(s.get("name").asString()).isEqualTo("Website relaunch 2");
        assertThat(s.get("privacy").asString()).isEqualTo("members");
        assertThat(as("PATCH", "/projects/" + id + "/workspace", Map.of("code", "X")).status()).isEqualTo(400);
        assertThat(as("PATCH", "/projects/" + id + "/workspace", Map.of("startDate", "2026-05-02", "endDate",
                "2026-05-01")).status()).isEqualTo(400);

        JsonNode stages = ok(201, "POST", "/projects/" + id + "/stages", Map.of("name", "Backlog"));
        assertThat(stages).hasSize(6);
        String backlog = stages.get(5).get("id").asString();
        List<String> order = new java.util.ArrayList<>(stages.findValuesAsString("id"));
        order.remove(backlog);
        order.addFirst(backlog);
        assertThat(ok(200, "POST", "/projects/" + id + "/stages/reorder", Map.of("stageIds", order)).get(0).get("name")
                .asString()).isEqualTo("Backlog");
        task(id, "In backlog", Map.of("stageId", backlog));
        assertThat(as("DELETE", "/projects/stages/" + backlog, null).raw().body())
                .contains("Move the tasks out of this stage first");
        String review = stage(s, "Review");
        assertThat(ok(200, "DELETE", "/projects/stages/" + review, null)).hasSize(5);

        JsonNode ms = ok(201, "POST", "/projects/" + id + "/milestones", Map.of("name", "Beta", "deadline", today));
        String milestone = ms.get(0).get("id").asString();
        JsonNode reached = ok(200, "PATCH", "/projects/milestones/" + milestone, Map.of("isReached", true));
        assertThat(reached.get(0).get("isReached").asBoolean()).isTrue();

        JsonNode updates = ok(201, "POST", "/projects/" + id + "/updates", Map.of("status", "at_risk", "summary",
                "Design late"));
        assertThat(updates.get(0).get("status").asString()).isEqualTo("at_risk");
        assertThat(updates.get(0).get("stats").get("total").asInt()).isEqualTo(1);
        assertThat(ok(200, "GET", "/projects/" + id + "/workspace", null).get("lastUpdateStatus").asString())
                .isEqualTo("at_risk");
        assertThat(as("POST", "/projects/" + id + "/updates", Map.of("status", "great")).status()).isEqualTo(400);

        JsonNode overview = ok(200, "GET", "/projects/" + id + "/overview", null);
        assertThat(overview.get("tasks").get("total").asInt()).isEqualTo(1);
        assertThat(dec(overview.get("hours").get("planned"))).isEqualByComparingTo("120");
        assertThat(overview.has("financials")).isTrue();
        assertThat(ok(200, "GET", "/projects/" + id + "/messages", null).findValuesAsString("body"))
                .anyMatch(b -> b.startsWith("Status update"));
    }

    @Test
    @DisplayName("PM-2: tasks — numbering, assignees and followers, stage moves, dependencies, recurrence, chatter")
    void tasks() {
        String other = userWith(Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TASK__WRITE);
        String otherId = idOf(other);
        JsonNode p = project("Office move", Map.of("memberIds", List.of(otherId)));
        String pid = p.get("id").asString();
        String code = p.get("code").asString();
        JsonNode tag = ok(201, "POST", "/project-tags", Map.of("name", "urgent-" + code, "color", 3));
        assertThat(as("POST", "/project-tags", Map.of("name", "URGENT-" + code)).status()).isEqualTo(409);

        JsonNode a = task(pid, "Pack boxes", Map.of("assigneeIds", List.of(otherId), "tagIds",
                List.of(tag.get("id").asString()), "checklist", List.of("Kitchen", "Desks"), "deadline", today,
                "plannedHours", 4, "priority", "high"));
        String aid = a.get("id").asString();
        assertThat(a.get("number").asString()).isEqualTo(code + "-1");
        assertThat(a.get("stageName").asString()).isEqualTo("To Do");
        assertThat(a.get("assignees").get(0).get("id").asString()).isEqualTo(otherId);
        assertThat(a.get("tags")).hasSize(1);
        assertThat(a.get("checklistTotal").asInt()).isEqualTo(2);
        assertThat(a.get("followers").findValuesAsString("id")).contains(adminId, otherId);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND title LIKE 'Task assigned%'")
                .param(otherId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'project.task.assigned' AND"
                + " payload->>'taskId' = ?").param(aid).query(Long.class).single()).isEqualTo(1);

        JsonNode b = task(pid, "Book truck", Map.of("dependsOnIds", List.of(aid)));
        String bid = b.get("id").asString();
        assertThat(b.get("blockedBy")).hasSize(1);
        assertThat(as("POST", "/project-tasks/" + aid + "/dependencies", Map.of("dependsOnId", bid)).raw().body())
                .contains("This dependency would create a cycle");
        Res blocked = as("POST", "/project-tasks/" + bid + "/done", Map.of("done", true));
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.raw().body()).contains("blocked by unfinished tasks: " + code + "-1");

        String item = a.get("checklist").get(0).get("id").asString();
        assertThat(ok(200, "PATCH", "/project-tasks/checklist/" + item, Map.of("isDone", true)).get("checklistDone")
                .asInt()).isEqualTo(1);

        JsonNode moved = ok(200, "POST", "/project-tasks/" + aid + "/move", Map.of("stageId", stage(p, "In Progress"),
                "index", 0));
        assertThat(moved.get("stageName").asString()).isEqualTo("In Progress");
        JsonNode done = ok(200, "POST", "/project-tasks/" + aid + "/done", Map.of("done", true));
        assertThat(done.get("isClosed").asBoolean()).isTrue();
        assertThat(done.get("stageKind").asString()).isEqualTo("done");
        assertThat(ok(200, "POST", "/project-tasks/" + bid + "/done", Map.of("done", true)).get("isClosed").asBoolean())
                .isTrue();
        assertThat(ok(200, "POST", "/project-tasks/" + bid + "/done", Map.of("done", false)).get("isClosed").asBoolean())
                .isFalse();

        JsonNode tracked = ok(200, "PATCH", "/project-tasks/" + bid, Map.of("priority", "urgent", "deadline", today,
                "assigneeIds", List.of(adminId)));
        assertThat(tracked.get("priority").asString()).isEqualTo("urgent");
        JsonNode history = ok(200, "GET", "/project-tasks/" + bid + "/messages", null);
        assertThat(history.findValuesAsString("body")).anyMatch(m -> m.contains("Priority: normal → urgent"))
                .anyMatch(m -> m.startsWith("Stage:"));
        assertThat(as("PATCH", "/project-tasks/" + bid, Map.of("bogus", 1)).status()).isEqualTo(400);
        assertThat(as("PATCH", "/project-tasks/" + bid, Map.of("plannedStart", "2030-01-02", "deadline", "2030-01-01"))
                .status()).isEqualTo(400);

        ok(201, "POST", "/project-tasks/" + aid + "/messages", Map.of("kind", "comment", "body", "Boxes delivered"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND title LIKE 'New message on%'")
                .param(otherId).query(Long.class).single()).isEqualTo(1);
        ok(201, "POST", "/project-tasks/" + aid + "/messages", Map.of("kind", "note", "body", "@T check",
                "mentions", List.of(otherId)));
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND title LIKE 'You were"
                + " mentioned%'").param(otherId).query(Long.class).single()).isEqualTo(1);

        JsonNode recurring = task(pid, "Weekly check", Map.of("deadline", "2030-01-07", "recurrence", "weekly"));
        ok(200, "POST", "/project-tasks/" + recurring.get("id").asString() + "/done", Map.of("done", true));
        JsonNode next = ok(200, "GET", "/project-tasks?projectId=" + pid + "&search=Weekly&state=open", null);
        assertThat(next.get("total").asInt()).isEqualTo(1);
        assertThat(next.get("data").get(0).get("deadline").asString()).isEqualTo("2030-01-14");
        assertThat(next.get("data").get(0).get("recurrence").asString()).isEqualTo("weekly");

        JsonNode sub = task(null, "Label boxes", Map.of("parentId", aid));
        assertThat(sub.get("projectId").asString()).isEqualTo(pid);
        assertThat(ok(200, "GET", "/project-tasks/" + aid, null).get("subtasks")).hasSize(1);
        assertThat(as("PATCH", "/project-tasks/" + aid, Map.of("parentId", sub.get("id").asString())).raw().body())
                .contains("A task cannot be its own subtask");

        JsonNode bulk = ok(200, "POST", "/project-tasks/bulk", Map.of("taskIds", List.of(aid, bid), "priority", "low"));
        assertThat(bulk.get("changed").asInt()).isEqualTo(2);

        String otherToken = login("DEMO", other, "Pm@123456");
        assertThat(call("DELETE", "/project-tasks/" + aid, null, otherToken).status()).isEqualTo(403);
        JsonNode deleted = ok(200, "DELETE", "/project-tasks/" + aid, null);
        assertThat(deleted.get("deleted").asInt()).isEqualTo(2);
        assertThat(as("GET", "/project-tasks/" + sub.get("id").asString(), null).status()).isEqualTo(404);

        JsonNode mine = call("GET", "/project-tasks?assigneeId=me&state=all", null, otherToken).body();
        assertThat(mine.findValuesAsString("id")).doesNotContain(aid);
    }

    @Test
    @DisplayName("PM-3: privacy — members-only projects, viewers, personal to-dos")
    void privacy() {
        String outsiderEmail = userWith(Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TASK__WRITE);
        String viewerEmail = userWith(Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TASK__WRITE);
        String outsider = login("DEMO", outsiderEmail, "Pm@123456");
        String viewer = login("DEMO", viewerEmail, "Pm@123456");
        JsonNode p = project("Secret merger", Map.of("privacy", "members"));
        String pid = p.get("id").asString();
        ok(200, "PUT", "/projects/" + pid + "/members", Map.of("members", List.of(Map.of("userId", idOf(viewerEmail),
                "role", "viewer"))));
        String tid = task(pid, "Due diligence", Map.of()).get("id").asString();

        assertThat(call("GET", "/projects/" + pid + "/workspace", null, outsider).status()).isEqualTo(404);
        assertThat(call("GET", "/project-tasks/" + tid, null, outsider).status()).isEqualTo(404);
        assertThat(call("GET", "/projects/workspace", null, outsider).body().findValuesAsString("id"))
                .doesNotContain(pid);

        assertThat(call("GET", "/project-tasks/" + tid, null, viewer).status()).isEqualTo(200);
        Res denied = call("PATCH", "/project-tasks/" + tid, Map.of("title", "x"), viewer);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(call("POST", "/project-tasks", Map.of("projectId", pid, "title", "x"), viewer).status())
                .isEqualTo(403);
        assertThat(call("POST", "/projects/" + pid + "/stages", Map.of("name", "x"), viewer).status()).isEqualTo(403);

        JsonNode todo = task(null, "Call the bank", Map.of());
        assertThat(todo.get("number").asString()).startsWith("TODO-");
        assertThat(todo.get("projectId").isNull()).isTrue();
        assertThat(call("GET", "/project-tasks/" + todo.get("id").asString(), null, outsider).status()).isEqualTo(404);
        assertThat(ok(200, "POST", "/project-tasks/" + todo.get("id").asString() + "/done", Map.of("done", true))
                .get("isClosed").asBoolean()).isTrue();
        assertThat(as("POST", "/project-tasks", Map.of("title", "x", "stageId", p.get("stages").get(0).get("id")
                .asString())).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("PM-4: timesheets — HR mirror, lock after approval, cost capture to WIP, grid, timer, reports")
    void timesheets() {
        JsonNode p = project("Survey", Map.of("plannedHours", 10));
        String pid = p.get("id").asString();
        String wbs = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "1", "name", "Field work")).get("id")
                .asString();
        String tid = task(pid, "Measure site", Map.of("plannedHours", 6)).get("id").asString();

        JsonNode line = ok(201, "POST", "/project-timesheets", Map.of("taskId", tid, "date", today, "hours", 2.5,
                "description", "Morning"));
        String lineId = line.get("id").asString();
        assertThat(line.get("projectId").asString()).isEqualTo(pid);
        assertThat(dec(ok(200, "GET", "/project-tasks/" + tid, null).get("effectiveHours")))
                .isEqualByComparingTo("2.5");
        assertThat(as("POST", "/project-timesheets", Map.of("taskId", tid, "date", today, "hours", 22)).raw().body())
                .contains("More than 24 hours logged");
        assertThat(as("POST", "/project-timesheets", Map.of("taskId", tid, "hours", 0)).status()).isEqualTo(400);

        if (employeeId != null) {
            String entry = line.get("hrEntryId").asString();
            Map<String, Object> e = jdbc.sql("SELECT e.minutes, e.\"linkedEntityType\", e.\"linkedEntityId\", e.\"timesheetId\""
                    + " FROM \"HrTimesheetEntry\" e WHERE e.id = ?").param(entry).query().singleRow();
            assertThat(((Number) e.get("minutes")).intValue()).isEqualTo(150);
            assertThat(e.get("linkedEntityType")).isEqualTo("project");
            assertThat(e.get("linkedEntityId")).isEqualTo(pid);
            ok(200, "PATCH", "/project-timesheets/" + lineId, Map.of("hours", 3));
            String entry2 = jdbc.sql("SELECT \"hrEntryId\" FROM \"ProjectTimesheetLine\" WHERE id = ?").param(lineId)
                    .query(String.class).single();
            assertThat(jdbc.sql("SELECT minutes FROM \"HrTimesheetEntry\" WHERE id = ? AND \"deletedAt\" IS NULL")
                    .param(entry2).query(Integer.class).single()).isEqualTo(180);
            jdbc.sql("UPDATE \"HrTimesheet\" SET status = 'APPROVED' WHERE id = (SELECT \"timesheetId\" FROM"
                    + " \"HrTimesheetEntry\" WHERE id = ?)").param(entry2).update();
            Res locked = as("PATCH", "/project-timesheets/" + lineId, Map.of("hours", 1));
            assertThat(locked.status()).isEqualTo(400);
            assertThat(locked.raw().body()).contains("submitted or approved HR timesheet");
            assertThat(ok(200, "GET", "/project-timesheets?taskId=" + tid, null).get(0).get("locked").asBoolean())
                    .isTrue();

            Map<String, String> h = new HashMap<>(Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                    UUID.randomUUID().toString()));
            Res captured = call("POST", "/projects/" + pid + "/costs/timesheets", Map.of("wbsId", wbs, "from", today,
                    "to", today, "ratePerHour", 40), h);
            assertThat(captured.status()).as(captured.raw().body()).isIn(200, 201);
            assertThat(jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"ProjectCostLine\" WHERE \"projectId\" = ? AND"
                    + " status = 'posted'").param(pid).query(java.math.BigDecimal.class).single())
                    .isEqualByComparingTo("120");
            jdbc.sql("UPDATE \"HrTimesheet\" SET status = 'DRAFT' WHERE id = (SELECT \"timesheetId\" FROM"
                    + " \"HrTimesheetEntry\" WHERE id = ?)").param(entry2).update();
        }

        String monday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toString();
        JsonNode week = ok(200, "PUT", "/project-timesheets/week", Map.of("taskId", tid, "date", monday, "hours", 1.5));
        assertThat(week.get("start").asString()).isEqualTo(monday);
        assertThat(week.get("rows").findValuesAsString("taskId")).contains(tid);
        JsonNode cleared = ok(200, "PUT", "/project-timesheets/week", Map.of("taskId", tid, "date", monday, "hours", 0));
        assertThat(dec(cleared.get("total"))).isLessThan(dec(week.get("total")));

        assertThat(ok(200, "POST", "/project-timesheets/timer/start", Map.of("taskId", tid)).get("running").asBoolean())
                .isTrue();
        assertThat(ok(200, "GET", "/project-timesheets/timer", null).get("taskId").asString()).isEqualTo(tid);
        JsonNode stopped = ok(200, "POST", "/project-timesheets/timer/stop", Map.of("description", "Timer"));
        assertThat(dec(stopped.get("line").get("hours"))).isEqualByComparingTo("0.02");
        assertThat(as("POST", "/project-timesheets/timer/stop", Map.of()).status()).isEqualTo(400);

        ok(200, "PATCH", "/projects/" + pid + "/workspace", Map.of("allowTimesheets", false));
        assertThat(as("POST", "/project-timesheets", Map.of("taskId", tid, "hours", 1)).raw().body())
                .contains("Timesheets are disabled on this project");

        assertThat(dec(ok(200, "GET", "/project-reports/timesheets?groupBy=user&projectId=" + pid, null).get(0)
                .get("hours"))).isPositive();
        assertThat(ok(200, "GET", "/project-reports/tasks?groupBy=stage&projectId=" + pid, null)).isNotEmpty();
        assertThat(ok(200, "GET", "/project-reports/workload", null)).isNotNull();
        assertThat(ok(200, "GET", "/project-reports/gantt?projectId=" + pid, null).get("tasks")).hasSize(1);
        JsonNode dash = ok(200, "GET", "/project-reports/dashboard", null);
        assertThat(dec(dash.get("weekHours"))).isPositive();
        assertThat(as("GET", "/project-reports/tasks?groupBy=stage", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("PM-5: sales order projects, templates and duplication, linked records")
    void integrations() {
        String so = UUID.randomUUID().toString();
        String number = "SO-PM-" + so.substring(0, 6);
        jdbc.sql("INSERT INTO \"SalesOrder\" (id, \"organizationId\", number, \"partnerId\", \"currencyCode\", status,"
                + " total) VALUES (?, ?, ?, ?, 'USD', 'confirmed', 1500)").params(so, demoOrg, number, partnerId)
                .update();
        for (String d : List.of("Installation", "Training")) {
            jdbc.sql("INSERT INTO \"SalesOrderLine\" (id, \"organizationId\", \"orderId\", description, quantity,"
                    + " \"unitPrice\") VALUES (?, ?, ?, ?, 2, 100)").params(UUID.randomUUID().toString(), demoOrg, so,
                    d).update();
        }
        JsonNode p = ok(201, "POST", "/projects/from-sales-order", Map.of("salesOrderId", so));
        String pid = p.get("id").asString();
        assertThat(p.get("salesOrderId").asString()).isEqualTo(so);
        assertThat(p.get("contractRef").asString()).isEqualTo(number);
        assertThat(dec(p.get("contractValue"))).isEqualByComparingTo("1500");
        assertThat(p.get("customerId").asString()).isEqualTo(partnerId);
        JsonNode tasks = ok(200, "GET", "/project-tasks?projectId=" + pid, null);
        assertThat(tasks.get("total").asInt()).isEqualTo(2);
        assertThat(tasks.get("data").findValuesAsString("title")).contains("Installation × 2", "Training × 2");
        JsonNode linked = ok(200, "GET", "/project-tasks/" + tasks.get("data").get(0).get("id").asString(), null);
        assertThat(linked.get("source").get("label").asString()).isEqualTo(number);
        assertThat(ok(200, "GET", "/project-tasks?sourceResType=sales_order&sourceResId=" + so, null).get("total")
                .asInt()).isEqualTo(2);
        assertThat(as("POST", "/projects/from-sales-order", Map.of("salesOrderId", so)).status()).isEqualTo(409);
        assertThat(as("POST", "/project-tasks", Map.of("projectId", pid, "title", "x", "sourceResType", "sales_order",
                "sourceResId", UUID.randomUUID().toString())).raw().body()).contains("Linked record not found");

        JsonNode tpl = project("Fit-out template", Map.of("isTemplate", true, "startDate", "2030-01-01"));
        String tplId = tpl.get("id").asString();
        ok(201, "POST", "/projects/" + tplId + "/milestones", Map.of("name", "Handover", "deadline", "2030-01-31"));
        String first = task(tplId, "Design", Map.of("deadline", "2030-01-10", "checklist", List.of("Sketch")))
                .get("id").asString();
        task(tplId, "Build", Map.of("deadline", "2030-01-20", "dependsOnIds", List.of(first)));
        assertThat(ok(200, "GET", "/projects/workspace?templates=true", null).findValuesAsString("id")).contains(tplId);
        assertThat(ok(200, "GET", "/projects/workspace", null).findValuesAsString("id")).doesNotContain(tplId);

        JsonNode fromTpl = project("Fit-out Level 3", Map.of("templateId", tplId, "startDate", "2030-03-01"));
        String newId = fromTpl.get("id").asString();
        assertThat(fromTpl.get("milestones").get(0).get("deadline").asString()).isEqualTo("2030-03-31");
        JsonNode copied = ok(200, "GET", "/project-tasks?projectId=" + newId, null);
        assertThat(copied.get("total").asInt()).isEqualTo(2);
        assertThat(copied.get("data").findValuesAsString("deadline")).contains("2030-03-10", "2030-03-20");
        JsonNode build = ok(200, "GET", "/project-tasks/" + copied.get("data").findValues("id").stream()
                .map(JsonNode::asString).filter(i -> !ok(200, "GET", "/project-tasks/" + i, null).get("blockedBy")
                        .isEmpty()).findFirst().orElseThrow(), null);
        assertThat(build.get("blockedBy").get(0).get("number").asString()).startsWith(fromTpl.get("code").asString());

        JsonNode dup = ok(201, "POST", "/projects/" + newId + "/duplicate", Map.of("name", "Fit-out Level 4",
                "includeTasks", false));
        assertThat(dup.get("stages")).hasSize(5);
        assertThat(ok(200, "GET", "/project-tasks?projectId=" + dup.get("id").asString(), null).get("total").asInt())
                .isZero();
    }

    @Test
    @DisplayName("PM-6: tenant isolation and the authorization matrix (57 routes)")
    void isolationAndAuthorization() {
        JsonNode p = project("Isolated", Map.of());
        String pid = p.get("id").asString();
        String tid = task(pid, "Hidden", Map.of()).get("id").asString();
        assertThat(call("GET", "/projects/" + pid + "/workspace", null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/project-tasks/" + tid, null, otherToken).status()).isEqualTo(404);
        JsonNode list = call("GET", "/project-tasks", null, otherToken).body();
        assertThat(list.get("data").findValuesAsString("id")).doesNotContain(tid);
        assertThat(call("POST", "/project-timesheets", Map.of("taskId", tid, "hours", 1), otherToken).status())
                .isEqualTo(404);

        String readOnly = tokenWith(Permissions.PROJECT_TASK__READ);
        assertThat(call("POST", "/project-tasks", Map.of("projectId", pid, "title", "x"), readOnly).status())
                .isEqualTo(403);
        assertThat(call("GET", "/project-tasks/" + tid, null, readOnly).status()).isEqualTo(200);

        assertThat(assertAuthorizationMatrix(ProjectWorkspaceController.class, ProjectTaskController.class,
                ProjectTimesheetController.class)).isEqualTo(57);
        assertThat(sweepGetRoutes("com.inventoryerp.projects")).isEmpty();
    }
}
