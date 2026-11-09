package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.web.ContractController;
import com.inventoryerp.projects.web.ProjectController;
import com.inventoryerp.projects.web.ProjectTaskController;
import com.inventoryerp.projects.web.ProjectTimesheetController;
import com.inventoryerp.projects.web.ProjectWorkspaceController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.JsonNode;

/**
 * Projects authorization beyond the "no permission at all" matrix of the scenario ITs: every route of the five project
 * controllers is denied to a user holding every other project-family permission (so the families do not leak into each
 * other — e.g. {@code project_task:*} never opens job costing, {@code contract:manage} never approves), and is let
 * through the permission guard for a user holding exactly the required permission. Plus the in-service rules that
 * permissions alone do not express (project manager vs member, {@code project_task:manage}, timesheet manager).
 */
class ProjectAuthorizationIT extends ApiTestSupport {

    static final List<Class<?>> CONTROLLERS = List.of(ProjectController.class, ContractController.class,
            ProjectWorkspaceController.class, ProjectTaskController.class, ProjectTimesheetController.class);

    static final Set<String> FAMILY = new LinkedHashSet<>(List.of(Permissions.PROJECT__READ, Permissions.PROJECT__MANAGE,
            Permissions.PROJECT__POST, Permissions.CONTRACT__READ, Permissions.CONTRACT__MANAGE,
            Permissions.CONTRACT__POST, Permissions.CONTRACT__APPROVE, Permissions.PROJECT_TASK__READ,
            Permissions.PROJECT_TASK__WRITE, Permissions.PROJECT_TASK__MANAGE, Permissions.PROJECT_TIMESHEET__LOG,
            Permissions.PROJECT_TIMESHEET__MANAGE, Permissions.PROJECT_REPORT__READ));

    final Map<Set<String>, String> tokens = new HashMap<>();

    String tokenWith(Set<String> permissions) {
        return tokens.computeIfAbsent(permissions, p -> {
            String role = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                    + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "pmauth-" + role,
                    p.toArray(String[]::new)).update();
            String email = "pmauth-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
            createUser(demoOrg, email, "Pm@123456", role);
            return login("DEMO", email, "Pm@123456");
        });
    }

    String tokenWith(String... permissions) {
        return tokenWith(new TreeSet<>(Arrays.asList(permissions)));
    }

    record Route(String method, String pattern, String path, Set<String> required) {}

    List<Route> routes() {
        List<Route> out = new ArrayList<>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod hm = entry.getValue();
            if (!CONTROLLERS.contains(hm.getBeanType())) {
                continue;
            }
            RequirePermissions perms = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(),
                    RequirePermissions.class);
            assertThat(perms).as(hm.toString()).isNotNull();
            for (String pattern : entry.getKey().getPatternValues()) {
                String path = pattern.replace("/api/v1", "").replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                for (var m : entry.getKey().getMethodsCondition().getMethods()) {
                    out.add(new Route(m.name(), pattern, path, new TreeSet<>(Arrays.asList(perms.value()))));
                }
            }
        }
        return out;
    }

    Res hit(Route r, String token) {
        Object body = Set.of("POST", "PUT", "PATCH").contains(r.method()) ? Map.of() : null;
        return call(r.method(), r.path(), body, token);
    }

    static boolean deniedByGuard(Res res, Set<String> required) {
        if (res.status() != 403 || res.body() == null || !res.body().has("message")) {
            return false;
        }
        return res.body().get("message").asString().equals("Missing required permission(s): "
                + String.join(", ", required));
    }

    @Test
    @DisplayName("PA-1: every project route — denied with all other project-family permissions, allowed with its own")
    void crossFamilyMatrix() {
        List<Route> routes = routes();
        assertThat(routes).hasSize(116);
        List<String> failures = new ArrayList<>();
        for (Route r : routes) {
            Set<String> others = new TreeSet<>(FAMILY);
            others.removeAll(r.required());
            Res denied = hit(r, tokenWith(others));
            if (denied.status() != 403) {
                failures.add(r.method() + " " + r.pattern() + " with " + others + " → " + denied.status() + " "
                        + denied.raw().body());
            }
            Res allowed = hit(r, tokenWith(r.required()));
            if (deniedByGuard(allowed, r.required()) || allowed.status() == 401 || allowed.status() >= 500) {
                failures.add(r.method() + " " + r.pattern() + " with only " + r.required() + " → " + allowed.status()
                        + " " + allowed.raw().body());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("PA-2: in-service rules — manager vs member, project_task:manage, timesheet manager, reports")
    void inServiceRules() {
        String writer = tokenWith(Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TASK__WRITE,
                Permissions.PROJECT_TIMESHEET__LOG);
        String taskManager = tokenWith(Permissions.PROJECT_TASK__READ, Permissions.PROJECT_TASK__WRITE,
                Permissions.PROJECT_TASK__MANAGE);
        JsonNode p = ok(201, "POST", "/projects/workspace", Map.of("name", "Authz " + UUID.randomUUID()));
        String pid = p.get("id").asString();
        String tid = ok(201, "POST", "/project-tasks", Map.of("projectId", pid, "title", "Admin task")).get("id")
                .asString();

        // A member of an internal project may edit tasks but not its settings, stages, milestones or updates.
        assertThat(call("PATCH", "/project-tasks/" + tid, Map.of("title", "Renamed"), writer).status()).isEqualTo(200);
        Res settings = call("PATCH", "/projects/" + pid + "/workspace", Map.of("name", "x"), writer);
        assertThat(settings.status()).isEqualTo(403);
        assertThat(settings.raw().body()).contains("Only the project manager can change the project settings");
        assertThat(call("POST", "/projects/" + pid + "/stages", Map.of("name", "x"), writer).status()).isEqualTo(403);
        assertThat(call("POST", "/projects/" + pid + "/milestones", Map.of("name", "x"), writer).status())
                .isEqualTo(403);
        assertThat(call("POST", "/projects/" + pid + "/updates", Map.of("status", "on_track"), writer).status())
                .isEqualTo(403);
        assertThat(call("PUT", "/projects/" + pid + "/members", Map.of("members", List.of()), writer).status())
                .isEqualTo(403);

        // Deleting someone else's task needs the creator, a project manager or project_task:manage.
        Res del = call("DELETE", "/project-tasks/" + tid, null, writer);
        assertThat(del.status()).isEqualTo(403);
        assertThat(del.raw().body()).contains("Only the task's creator or a project manager can delete it");
        String own = call("POST", "/project-tasks", Map.of("projectId", pid, "title", "Mine"), writer).body().get("id")
                .asString();
        assertThat(call("DELETE", "/project-tasks/" + own, null, writer).status()).isEqualTo(200);
        assertThat(call("DELETE", "/project-tasks/" + tid, null, taskManager).status()).isEqualTo(200);

        // project_task:manage sees members-only projects it is not a member of.
        String secret = ok(201, "POST", "/projects/workspace", Map.of("name", "Secret " + UUID.randomUUID(), "privacy",
                "members")).get("id").asString();
        assertThat(call("GET", "/projects/" + secret + "/workspace", null, writer).status()).isEqualTo(404);
        assertThat(call("GET", "/projects/" + secret + "/workspace", null, taskManager).status()).isEqualTo(200);

        // Time: only your own, unless project_timesheet:manage.
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class)
                .single();
        Res others = call("GET", "/project-timesheets?userId=" + adminId, null, writer);
        assertThat(others.status()).isEqualTo(403);
        assertThat(others.raw().body()).contains("You can only see your own time");
        assertThat(call("GET", "/project-timesheets/week?userId=" + adminId, null, writer).status()).isEqualTo(403);
        Res logForOther = call("POST", "/project-timesheets", Map.of("taskId", createTask(pid),
                "hours", 1, "userId", adminId), writer);
        assertThat(logForOther.status()).isEqualTo(403);
        assertThat(logForOther.raw().body()).contains("You can only log your own time");
        String tsManager = tokenWith(Permissions.PROJECT_TIMESHEET__LOG, Permissions.PROJECT_TIMESHEET__MANAGE);
        assertThat(call("GET", "/project-timesheets?userId=" + adminId, null, tsManager).status()).isEqualTo(200);

        // Reports need project_report:read; the dashboard and gantt only project_task:read.
        assertThat(call("GET", "/project-reports/dashboard", null, writer).status()).isEqualTo(200);
        assertThat(call("GET", "/project-reports/gantt?projectId=" + pid, null, writer).status()).isEqualTo(200);
        assertThat(call("GET", "/project-reports/workload", null, writer).status()).isEqualTo(403);

        // Duplicate and sales-order projects need project:manage even though a member may read the source.
        assertThat(call("POST", "/projects/" + pid + "/duplicate", Map.of(), writer).status()).isEqualTo(403);
    }

    String createTask(String pid) {
        return ok(201, "POST", "/project-tasks", Map.of("projectId", pid, "title", "Log target")).get("id").asString();
    }
}
