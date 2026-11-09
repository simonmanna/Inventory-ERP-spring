package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Project task behaviour (ADR-S062) beyond {@link ProjectManagementIT}: dependency removal and the per-project switches,
 * checklist counters, recurrence intervals and end dates, board ordering, bulk changes and their limits, following,
 * personal-task rules, and the timer / timesheet edge cases (switching tasks, discard, delete, viewers, weekly grid).
 */
class ProjectTaskBehaviourIT extends ApiTestSupport {

    String adminId;
    final String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    JsonNode project(Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Task behaviour " + UUID.randomUUID()));
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

    static String stage(JsonNode project, String name) {
        for (JsonNode s : project.get("stages")) {
            if (s.get("name").asString().equals(name)) {
                return s.get("id").asString();
            }
        }
        throw new IllegalStateException("no stage " + name);
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("PTB-1: dependencies — self, remove unblocks, disabled per project")
    void dependencies() {
        JsonNode p = project(Map.of());
        String pid = p.get("id").asString();
        String a = task(pid, "Foundation", Map.of()).get("id").asString();
        String b = task(pid, "Walls", Map.of()).get("id").asString();

        assertThat(as("POST", "/project-tasks/" + a + "/dependencies", Map.of("dependsOnId", a)).raw().body())
                .contains("A task cannot depend on itself");
        JsonNode blocked = ok(201, "POST", "/project-tasks/" + b + "/dependencies", Map.of("dependsOnId", a));
        assertThat(blocked.get("blockedBy").findValuesAsString("id")).containsExactly(a);
        assertThat(ok(200, "GET", "/project-tasks/" + a, null).get("blocking").findValuesAsString("id")).contains(b);
        assertThat(as("POST", "/project-tasks/" + b + "/done", Map.of("done", true)).status()).isEqualTo(400);

        JsonNode freed = ok(200, "DELETE", "/project-tasks/" + b + "/dependencies/" + a, null);
        assertThat(freed.get("blockedBy")).isEmpty();
        assertThat(ok(200, "POST", "/project-tasks/" + b + "/done", Map.of("done", true)).get("isClosed").asBoolean())
                .isTrue();
        assertThat(ok(200, "GET", "/project-tasks/" + b + "/messages", null).findValuesAsString("body"))
                .anyMatch(m -> m.startsWith("Blocked by "));

        ok(200, "PATCH", "/projects/" + pid + "/workspace", Map.of("allowDependencies", false));
        assertThat(as("POST", "/project-tasks/" + a + "/dependencies", Map.of("dependsOnId", b)).raw().body())
                .contains("Task dependencies are disabled on this project");
        assertThat(as("POST", "/project-tasks", Map.of("projectId", pid, "title", "x", "dependsOnIds", List.of(a)))
                .raw().body()).contains("Task dependencies are disabled on this project");
    }

    @Test
    @DisplayName("PTB-2: checklist — add, tick, untick, rename, delete keep the counters right")
    void checklist() {
        String pid = project(Map.of()).get("id").asString();
        String t = task(pid, "Inspect", Map.of()).get("id").asString();
        JsonNode one = ok(201, "POST", "/project-tasks/" + t + "/checklist", Map.of("title", "Roof"));
        JsonNode two = ok(201, "POST", "/project-tasks/" + t + "/checklist", Map.of("title", "Doors"));
        assertThat(two.get("checklistTotal").asInt()).isEqualTo(2);
        assertThat(two.get("checklist").findValuesAsString("title")).containsExactly("Roof", "Doors");
        assertThat(as("POST", "/project-tasks/" + t + "/checklist", Map.of("title", " ")).status()).isEqualTo(400);

        String roof = one.get("checklist").get(0).get("id").asString();
        String doors = two.get("checklist").get(1).get("id").asString();
        assertThat(ok(200, "PATCH", "/project-tasks/checklist/" + roof, Map.of("isDone", true)).get("checklistDone")
                .asInt()).isEqualTo(1);
        assertThat(ok(200, "PATCH", "/project-tasks/checklist/" + doors, Map.of("isDone", true)).get("checklistDone")
                .asInt()).isEqualTo(2);
        JsonNode renamed = ok(200, "PATCH", "/project-tasks/checklist/" + doors, Map.of("title", "Doors and windows",
                "isDone", false));
        assertThat(renamed.get("checklistDone").asInt()).isEqualTo(1);
        assertThat(renamed.get("checklist").get(1).get("title").asString()).isEqualTo("Doors and windows");
        JsonNode after = ok(200, "DELETE", "/project-tasks/checklist/" + roof, null);
        assertThat(after.get("checklistTotal").asInt()).isEqualTo(1);
        assertThat(after.get("checklistDone").asInt()).isZero();
        assertThat(as("DELETE", "/project-tasks/checklist/" + roof, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("PTB-3: recurrence — interval, end date, copies checklist unticked, disabled per project, validation")
    void recurrence() {
        JsonNode p = project(Map.of());
        String pid = p.get("id").asString();
        JsonNode monthly = task(pid, "Invoice run", Map.of("deadline", "2030-01-31", "recurrence", "monthly",
                "recurrenceInterval", 2, "recurrenceUntil", "2030-05-31", "checklist", List.of("Export"),
                "assigneeIds", List.of(adminId)));
        String first = monthly.get("id").asString();
        ok(200, "PATCH", "/project-tasks/checklist/" + monthly.get("checklist").get(0).get("id").asString(),
                Map.of("isDone", true));
        ok(200, "POST", "/project-tasks/" + first + "/done", Map.of("done", true));
        assertThat(ok(200, "GET", "/project-tasks/" + first, null).get("recurrence").isNull()).isTrue();

        JsonNode open = ok(200, "GET", "/project-tasks?projectId=" + pid + "&search=Invoice&state=open", null);
        assertThat(open.get("total").asInt()).isEqualTo(1);
        JsonNode second = open.get("data").get(0);
        assertThat(second.get("deadline").asString()).isEqualTo("2030-03-31");
        assertThat(second.get("recurrenceInterval").asInt()).isEqualTo(2);
        JsonNode secondFull = ok(200, "GET", "/project-tasks/" + second.get("id").asString(), null);
        assertThat(secondFull.get("checklistTotal").asInt()).isEqualTo(1);
        assertThat(secondFull.get("checklistDone").asInt()).isZero();
        assertThat(secondFull.get("assignees").findValuesAsString("id")).containsExactly(adminId);
        assertThat(secondFull.get("stageName").asString()).isEqualTo("To Do");

        // 2030-03-31 + 2 months = 2030-05-31 (still within the end date), then 2030-07-31 is past it.
        ok(200, "POST", "/project-tasks/" + second.get("id").asString() + "/done", Map.of("done", true));
        JsonNode third = ok(200, "GET", "/project-tasks?projectId=" + pid + "&search=Invoice&state=open", null);
        assertThat(third.get("data").get(0).get("deadline").asString()).isEqualTo("2030-05-31");
        ok(200, "POST", "/project-tasks/" + third.get("data").get(0).get("id").asString() + "/done",
                Map.of("done", true));
        assertThat(ok(200, "GET", "/project-tasks?projectId=" + pid + "&search=Invoice&state=open", null).get("total")
                .asInt()).isZero();

        assertThat(as("POST", "/project-tasks", Map.of("projectId", pid, "title", "x", "recurrence", "hourly")).status())
                .isEqualTo(400);
        assertThat(as("POST", "/project-tasks", Map.of("projectId", pid, "title", "x", "recurrence", "daily",
                "recurrenceInterval", 400)).raw().body()).contains("recurrenceInterval must be between 1 and 366");
        ok(200, "PATCH", "/projects/" + pid + "/workspace", Map.of("allowRecurring", false));
        assertThat(as("POST", "/project-tasks", Map.of("projectId", pid, "title", "x", "recurrence", "daily")).raw()
                .body()).contains("Recurring tasks are disabled on this project");
    }

    @Test
    @DisplayName("PTB-4: board ordering, kanban state, filters, bulk changes and their limits, following")
    void boardBulkAndFollowing() {
        JsonNode p = project(Map.of());
        String pid = p.get("id").asString();
        String todo = stage(p, "To Do");
        String doing = stage(p, "In Progress");
        String a = task(pid, "A", Map.of()).get("id").asString();
        String b = task(pid, "B", Map.of()).get("id").asString();
        String c = task(pid, "C", Map.of("deadline", "2020-01-01")).get("id").asString();

        ok(200, "POST", "/project-tasks/" + c + "/move", Map.of("stageId", todo, "index", 0));
        JsonNode board = ok(200, "GET", "/project-tasks?projectId=" + pid + "&stageId=" + todo, null);
        assertThat(board.get("data").findValuesAsString("id")).containsExactly(c, a, b);
        assertThat(as("POST", "/project-tasks/" + a + "/move", Map.of("stageId", UUID.randomUUID().toString())).raw()
                .body()).contains("Stage not found on this project");

        JsonNode flagged = ok(200, "PATCH", "/project-tasks/" + a, Map.of("kanbanState", "blocked", "blockedReason",
                "Waiting for permit"));
        assertThat(flagged.get("kanbanState").asString()).isEqualTo("blocked");
        assertThat(ok(200, "POST", "/project-tasks/" + a + "/move", Map.of("stageId", doing)).get("kanbanState")
                .asString()).isEqualTo("normal");

        assertThat(ok(200, "GET", "/project-tasks?projectId=" + pid + "&overdue=true", null).get("data")
                .findValuesAsString("id")).containsExactly(c);

        JsonNode bulk = ok(200, "POST", "/project-tasks/bulk", Map.of("taskIds", List.of(a, b, c), "stageId", doing,
                "priority", "high", "deadline", "2031-01-15", "addAssigneeId", adminId));
        assertThat(bulk.get("changed").asInt()).isEqualTo(3);
        JsonNode changed = ok(200, "GET", "/project-tasks?projectId=" + pid + "&stageId=" + doing, null);
        assertThat(changed.get("total").asInt()).isEqualTo(3);
        for (JsonNode t : changed.get("data")) {
            assertThat(t.get("priority").asString()).isEqualTo("high");
            assertThat(t.get("deadline").asString()).isEqualTo("2031-01-15");
            assertThat(t.get("assignees").findValuesAsString("id")).contains(adminId);
        }
        assertThat(ok(200, "POST", "/project-tasks/bulk", Map.of("taskIds", List.of(a), "deadline", "")).get("changed")
                .asInt()).isEqualTo(1);
        assertThat(ok(200, "GET", "/project-tasks/" + a, null).get("deadline").isNull()).isTrue();
        assertThat(as("POST", "/project-tasks/bulk", Map.of("taskIds", List.of(), "priority", "low")).raw().body())
                .contains("taskIds should not be empty");
        List<String> many = new ArrayList<>(IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID().toString())
                .toList());
        assertThat(as("POST", "/project-tasks/bulk", Map.of("taskIds", many, "priority", "low")).raw().body())
                .contains("At most 200 tasks can be changed at once");
        assertThat(as("POST", "/project-tasks/bulk", Map.of("taskIds", List.of(a), "priority", "extreme")).status())
                .isEqualTo(400);

        assertThat(ok(200, "POST", "/project-tasks/" + b + "/follow", Map.of("follow", false)).get("isFollowing")
                .asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/project-tasks/" + b, null).get("followers").findValuesAsString("id"))
                .doesNotContain(adminId);
        assertThat(ok(200, "POST", "/project-tasks/" + b + "/follow", Map.of("follow", true)).get("isFollowing")
                .asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/project-tasks/" + b, null).get("isFollowing").asBoolean()).isTrue();
        assertThat(as("POST", "/project-tasks/" + b + "/follow", Map.of()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("PTB-5: personal to-dos and subtasks — no stages, no move, project of the parent, closed projects")
    void personalAndSubtasks() {
        String todo = task(null, "Renew licence", Map.of()).get("id").asString();
        JsonNode p = project(Map.of());
        String pid = p.get("id").asString();
        assertThat(as("POST", "/project-tasks/" + todo + "/move", Map.of("stageId", stage(p, "To Do"))).raw().body())
                .contains("A personal task has no stages");
        assertThat(ok(200, "GET", "/project-tasks?personal=true&state=all", null).get("data").findValuesAsString("id"))
                .contains(todo);
        assertThat(as("POST", "/project-timesheets/timer/start", Map.of("taskId", todo)).raw().body())
                .contains("Time can only be logged on project tasks");

        String parent = task(pid, "Parent", Map.of()).get("id").asString();
        String other = project(Map.of()).get("id").asString();
        assertThat(as("POST", "/project-tasks", Map.of("projectId", other, "title", "x", "parentId", parent)).raw()
                .body()).contains("A subtask must belong to the project of its parent task");

        ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "closed"));
        assertThat(as("POST", "/project-tasks", Map.of("projectId", pid, "title", "Late")).raw().body())
                .contains("Tasks cannot be added to a closed project");
        assertThat(as("POST", "/projects/" + pid + "/status", Map.of("status", "active")).raw().body())
                .contains("A closed project cannot change status");
    }

    @Test
    @DisplayName("PTB-6: timer and timesheets — switching tasks logs the first, discard, delete, viewers, grid")
    void timerAndTimesheets() {
        String viewerEmail = "ptb-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "ptb-" + role, new String[] {
                    Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TIMESHEET__LOG}).update();
        String viewerId = createUser(demoOrg, viewerEmail, "Pm@123456", role);
        String viewer = login("DEMO", viewerEmail, "Pm@123456");

        JsonNode p = project(Map.of());
        String pid = p.get("id").asString();
        String t1 = task(pid, "Survey", Map.of()).get("id").asString();
        String t2 = task(pid, "Report", Map.of()).get("id").asString();
        ok(200, "POST", "/project-timesheets/timer/discard", null);

        ok(200, "POST", "/project-timesheets/timer/start", Map.of("taskId", t1));
        assertThat(ok(200, "POST", "/project-timesheets/timer/start", Map.of("taskId", t1)).get("taskId").asString())
                .isEqualTo(t1);
        JsonNode switched = ok(200, "POST", "/project-timesheets/timer/start", Map.of("taskId", t2));
        assertThat(switched.get("taskId").asString()).isEqualTo(t2);
        assertThat(switched.get("projectId").asString()).isEqualTo(pid);
        JsonNode logged = ok(200, "GET", "/project-timesheets?taskId=" + t1, null);
        assertThat(logged).hasSize(1);
        assertThat(dec(logged.get(0).get("hours"))).isEqualByComparingTo("0.02");
        assertThat(ok(200, "POST", "/project-timesheets/timer/discard", null).get("running").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/project-timesheets/timer", null).get("running").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/project-timesheets?taskId=" + t2, null)).isEmpty();

        String line = ok(201, "POST", "/project-timesheets", Map.of("taskId", t2, "hours", 2, "date", today,
                "isBillable", false)).get("id").asString();
        assertThat(dec(ok(200, "GET", "/project-tasks/" + t2, null).get("effectiveHours"))).isEqualByComparingTo("2");
        String hrEntry = jdbc.sql("SELECT \"hrEntryId\" FROM \"ProjectTimesheetLine\" WHERE id = ?").param(line)
                .query(String.class).optional().orElse(null);
        assertThat(as("PATCH", "/project-timesheets/" + line, Map.of("bogus", 1)).status()).isEqualTo(400);
        assertThat(ok(200, "DELETE", "/project-timesheets/" + line, null).get("deleted").asBoolean()).isTrue();
        assertThat(dec(ok(200, "GET", "/project-tasks/" + t2, null).get("effectiveHours"))).isEqualByComparingTo("0");
        if (hrEntry != null) {
            assertThat(jdbc.sql("SELECT count(*) FROM \"HrTimesheetEntry\" WHERE id = ? AND \"deletedAt\" IS NULL")
                    .param(hrEntry).query(Long.class).single()).isZero();
        }
        assertThat(as("DELETE", "/project-timesheets/" + line, null).status()).isEqualTo(404);
        assertThat(as("POST", "/project-timesheets", Map.of("projectId", pid, "taskId", t2, "hours", 25)).status())
                .isEqualTo(400);

        // A viewer of a members-only project may read its tasks but not log time on them.
        ok(200, "PATCH", "/projects/" + pid + "/workspace", Map.of("privacy", "members"));
        ok(200, "PUT", "/projects/" + pid + "/members", Map.of("members", List.of(Map.of("userId", viewerId, "role",
                "viewer"))));
        assertThat(call("GET", "/project-tasks/" + t1, null, viewer).status()).isEqualTo(200);
        Res denied = call("POST", "/project-timesheets", Map.of("taskId", t1, "hours", 1), viewer);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.raw().body()).contains("Viewers cannot log time on this project");

        String monday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toString();
        JsonNode grid = ok(200, "PUT", "/project-timesheets/week", Map.of("taskId", t1, "date", monday, "hours", 3));
        JsonNode week = ok(200, "GET", "/project-timesheets/week?start=" + monday, null);
        assertThat(week.get("start").asString()).isEqualTo(monday);
        assertThat(week.get("days")).hasSize(7);
        assertThat(week.get("rows").findValuesAsString("taskId")).contains(t1);
        assertThat(dec(week.get("total"))).isEqualByComparingTo(dec(grid.get("total")));
        ok(200, "PUT", "/project-timesheets/week", Map.of("taskId", t1, "date", monday, "hours", 4.5));
        assertThat(jdbc.sql("SELECT coalesce(sum(hours), 0) FROM \"ProjectTimesheetLine\" WHERE \"taskId\" = ? AND date"
                + " = ?::date").params(t1, monday).query(BigDecimal.class).single()).isEqualByComparingTo("4.5");
        assertThat(as("PUT", "/project-timesheets/week", Map.of("taskId", t1, "hours", 1)).status()).isEqualTo(400);
    }
}
