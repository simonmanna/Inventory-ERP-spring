package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Project management tenant isolation (ADR-S062): an administrator of another organisation can neither read nor change
 * DEMO's projects, stages, milestones, updates, chatter, tasks, checklist items, dependencies, tags or timesheet lines
 * (always 404, never a leak in a list or report); and inside one organisation a members-only project is invisible to a
 * non-member without {@code project_task:manage} / {@code project:manage} everywhere it could surface.
 */
class ProjectTenantIsolationIT extends ApiTestSupport {

    String pid;
    String stageId;
    String milestoneId;
    String taskId;
    String otherTaskId;
    String checklistId;
    String tagId;
    String lineId;
    String code;
    final String today = LocalDate.now(ZoneOffset.UTC).toString();

    @BeforeAll
    void demoData() {
        JsonNode p = ok(201, "POST", "/projects/workspace", Map.of("name", "Tenant A " + UUID.randomUUID()));
        pid = p.get("id").asString();
        code = p.get("code").asString();
        stageId = p.get("stages").get(0).get("id").asString();
        milestoneId = ok(201, "POST", "/projects/" + pid + "/milestones", Map.of("name", "A milestone")).get(0)
                .get("id").asString();
        tagId = ok(201, "POST", "/project-tags", Map.of("name", "tenant-" + code)).get("id").asString();
        JsonNode t = ok(201, "POST", "/project-tasks", Map.of("projectId", pid, "title", "A task", "checklist",
                List.of("A item"), "tagIds", List.of(tagId)));
        taskId = t.get("id").asString();
        checklistId = t.get("checklist").get(0).get("id").asString();
        otherTaskId = ok(201, "POST", "/project-tasks", Map.of("projectId", pid, "title", "A second task",
                "dependsOnIds", List.of(taskId))).get("id").asString();
        ok(201, "POST", "/projects/" + pid + "/updates", Map.of("status", "on_track", "summary", "A update"));
        ok(201, "POST", "/projects/" + pid + "/messages", Map.of("body", "A message"));
        ok(201, "POST", "/project-tasks/" + taskId + "/messages", Map.of("body", "A task message"));
        lineId = ok(201, "POST", "/project-timesheets", Map.of("taskId", taskId, "hours", 1, "date", today)).get("id")
                .asString();
    }

    @Test
    @DisplayName("PT-1: another organisation gets 404 on every DEMO project record and never sees it in a list")
    void crossOrganisation() {
        List<String> leaks = new ArrayList<>();
        Object[][] probes = {
            {"GET", "/projects/" + pid, null},
            {"GET", "/projects/" + pid + "/workspace", null},
            {"PATCH", "/projects/" + pid + "/workspace", Map.of("name", "Hijacked")},
            {"GET", "/projects/" + pid + "/overview", null},
            {"POST", "/projects/" + pid + "/favorite", null},
            {"PUT", "/projects/" + pid + "/members", Map.of("members", List.of())},
            {"POST", "/projects/" + pid + "/stages", Map.of("name", "Hijack")},
            {"POST", "/projects/" + pid + "/stages/reorder", Map.of("stageIds", List.of(stageId))},
            {"PATCH", "/projects/stages/" + stageId, Map.of("name", "Hijack")},
            {"DELETE", "/projects/stages/" + stageId, null},
            {"GET", "/projects/" + pid + "/milestones", null},
            {"POST", "/projects/" + pid + "/milestones", Map.of("name", "Hijack")},
            {"PATCH", "/projects/milestones/" + milestoneId, Map.of("isReached", true)},
            {"DELETE", "/projects/milestones/" + milestoneId, null},
            {"GET", "/projects/" + pid + "/updates", null},
            {"POST", "/projects/" + pid + "/updates", Map.of("status", "off_track")},
            {"GET", "/projects/" + pid + "/messages", null},
            {"POST", "/projects/" + pid + "/messages", Map.of("body", "Hijack")},
            {"POST", "/projects/" + pid + "/duplicate", Map.of()},
            {"GET", "/project-tasks/" + taskId, null},
            {"PATCH", "/project-tasks/" + taskId, Map.of("title", "Hijacked")},
            {"DELETE", "/project-tasks/" + taskId, null},
            {"POST", "/project-tasks/" + taskId + "/move", Map.of("stageId", stageId)},
            {"POST", "/project-tasks/" + taskId + "/done", Map.of("done", true)},
            {"POST", "/project-tasks/" + taskId + "/dependencies", Map.of("dependsOnId", otherTaskId)},
            {"DELETE", "/project-tasks/" + otherTaskId + "/dependencies/" + taskId, null},
            {"POST", "/project-tasks/" + taskId + "/checklist", Map.of("title", "Hijack")},
            {"PATCH", "/project-tasks/checklist/" + checklistId, Map.of("isDone", true)},
            {"DELETE", "/project-tasks/checklist/" + checklistId, null},
            {"GET", "/project-tasks/" + taskId + "/messages", null},
            {"POST", "/project-tasks/" + taskId + "/messages", Map.of("body", "Hijack")},
            {"POST", "/project-tasks/" + taskId + "/follow", Map.of("follow", true)},
            {"PATCH", "/project-tags/" + tagId, Map.of("name", "Hijack")},
            {"DELETE", "/project-tags/" + tagId, null},
            {"POST", "/project-timesheets", Map.of("taskId", taskId, "hours", 1)},
            {"PATCH", "/project-timesheets/" + lineId, Map.of("hours", 2)},
            {"DELETE", "/project-timesheets/" + lineId, null},
            {"POST", "/project-timesheets/timer/start", Map.of("taskId", taskId)},
            {"GET", "/project-timesheets?projectId=" + pid, null},
            {"GET", "/project-reports/gantt?projectId=" + pid, null},
        };
        for (Object[] probe : probes) {
            Res r = call((String) probe[0], (String) probe[1], probe[2], otherToken);
            if (r.status() != 404) {
                leaks.add(probe[0] + " " + probe[1] + " → " + r.status() + " " + r.raw().body());
            }
        }
        assertThat(leaks).isEmpty();

        assertThat(call("GET", "/projects/workspace", null, otherToken).body().findValuesAsString("id"))
                .doesNotContain(pid);
        assertThat(call("GET", "/projects", null, otherToken).raw().body()).doesNotContain(pid);
        assertThat(call("GET", "/project-tasks?search=" + code, null, otherToken).body().get("total").asInt()).isZero();
        assertThat(call("GET", "/project-tags", null, otherToken).body().findValuesAsString("id")).doesNotContain(tagId);
        assertThat(call("GET", "/project-timesheets", null, otherToken).body().findValuesAsString("id"))
                .doesNotContain(lineId);
        assertThat(call("GET", "/project-reports/tasks?groupBy=project", null, otherToken).body()
                .findValuesAsString("key")).doesNotContain(pid);
        assertThat(call("GET", "/project-reports/timesheets?groupBy=project", null, otherToken).body()
                .findValuesAsString("key")).doesNotContain(pid);
        assertThat(call("GET", "/project-reports/workload", null, otherToken).raw().body()).doesNotContain(pid);
        assertThat(call("GET", "/projects/assignable-users", null, otherToken).raw().body())
                .doesNotContain("admin@demo.test");

        // Nothing was changed by the attempts.
        JsonNode ws = ok(200, "GET", "/projects/" + pid + "/workspace", null);
        assertThat(ws.get("name").asString()).startsWith("Tenant A");
        assertThat(ws.get("stages")).hasSize(5);
        assertThat(ok(200, "GET", "/projects/" + pid + "/milestones", null).get(0).get("isReached").asBoolean())
                .isFalse();
        JsonNode task = ok(200, "GET", "/project-tasks/" + taskId, null);
        assertThat(task.get("title").asString()).isEqualTo("A task");
        assertThat(task.get("checklistDone").asInt()).isZero();
        assertThat(ok(200, "GET", "/project-tasks/" + otherTaskId, null).get("blockedBy")).hasSize(1);
        assertThat(jdbc.sql("SELECT hours FROM \"ProjectTimesheetLine\" WHERE id = ?").param(lineId)
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("1");
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProjectMessage\" WHERE \"organizationId\" <> ? AND (\"projectId\" ="
                + " ? OR \"taskId\" = ?)").params(demoOrg, pid, taskId).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("PT-2: members-only project — invisible to a non-member everywhere, visible with project_task:manage")
    void membersOnlyPrivacy() {
        String outsider = tokenWith(Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TASK__WRITE,
                Permissions.PROJECT_TIMESHEET__LOG, Permissions.PROJECT_REPORT__READ);
        String supervisor = tokenWith(Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TASK__MANAGE,
                Permissions.PROJECT_REPORT__READ);
        JsonNode p = ok(201, "POST", "/projects/workspace", Map.of("name", "Private " + UUID.randomUUID(), "privacy",
                "members"));
        String secret = p.get("id").asString();
        String secretCode = p.get("code").asString();
        String secretTask = ok(201, "POST", "/project-tasks", Map.of("projectId", secret, "title", "Hidden work",
                "plannedHours", 3, "assigneeIds", List.of(adminId()))).get("id").asString();
        ok(201, "POST", "/projects/" + secret + "/milestones", Map.of("name", "Hidden milestone"));

        for (String path : List.of("/projects/" + secret + "/workspace", "/projects/" + secret + "/overview",
                "/projects/" + secret + "/milestones", "/projects/" + secret + "/updates",
                "/projects/" + secret + "/messages", "/project-tasks/" + secretTask,
                "/project-tasks/" + secretTask + "/messages", "/project-reports/gantt?projectId=" + secret,
                "/project-timesheets?projectId=" + secret)) {
            assertThat(call("GET", path, null, outsider).status()).as(path).isEqualTo(404);
        }
        assertThat(call("POST", "/project-timesheets", Map.of("taskId", secretTask, "hours", 1), outsider).status())
                .isEqualTo(404);
        assertThat(call("POST", "/project-tasks", Map.of("projectId", secret, "title", "x"), outsider).status())
                .isEqualTo(404);
        assertThat(call("GET", "/projects/workspace", null, outsider).body().findValuesAsString("id"))
                .doesNotContain(secret);
        assertThat(call("GET", "/project-tasks?search=" + secretCode, null, outsider).body().get("total").asInt())
                .isZero();
        assertThat(call("GET", "/project-reports/tasks?groupBy=project", null, outsider).body()
                .findValuesAsString("key")).doesNotContain(secret);
        assertThat(call("GET", "/project-reports/workload?projectId=" + secret, null, outsider).body()).isEmpty();

        assertThat(call("GET", "/projects/" + secret + "/workspace", null, supervisor).status()).isEqualTo(200);
        assertThat(call("GET", "/project-tasks/" + secretTask, null, supervisor).status()).isEqualTo(200);
        assertThat(call("GET", "/project-reports/tasks?groupBy=project", null, supervisor).body()
                .findValuesAsString("key")).contains(secret);
        assertThat(call("GET", "/project-reports/workload?projectId=" + secret, null, supervisor).body()).isNotEmpty();
    }

    String adminId() {
        return jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "pmten-" + role, permissions).update();
        String email = "pmten-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Pm@123456", role);
        return login("DEMO", email, "Pm@123456");
    }
}
