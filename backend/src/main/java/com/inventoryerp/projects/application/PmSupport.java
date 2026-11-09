package com.inventoryerp.projects.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Shared rules of project management (ADR-S062): who may see and change a project, counters, chatter messages,
 * notifications and domain events. Package-internal helper of the project services.
 *
 * <p>Access: an {@code internal} project is visible to everyone holding {@code project_task:read}; a {@code members}
 * project only to its manager and members, and to holders of {@code project_task:manage} or {@code project:manage}.
 * Task changes need {@code project_task:write} and must not come from a {@code viewer} member. Project settings
 * (stages, members, milestones) need {@code project:manage} or the project's manager role.
 */
@Component
public class PmSupport {

    private static final Logger log = LoggerFactory.getLogger(PmSupport.class);

    static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    static final Set<String> KANBAN_STATES = Set.of("normal", "blocked", "ready");
    static final Set<String> RECURRENCES = Set.of("daily", "weekly", "monthly", "yearly");
    static final Set<String> STAGE_KINDS = Set.of("open", "done", "cancelled");
    static final Set<String> MEMBER_ROLES = Set.of("manager", "member", "viewer");
    static final Set<String> UPDATE_STATUSES = Set.of("on_track", "at_risk", "off_track", "on_hold", "done");

    final JdbcClient jdbc;
    private final NotificationsService notifications;
    private final EventOutboxService outbox;

    public PmSupport(JdbcClient jdbc, NotificationsService notifications, EventOutboxService outbox) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.outbox = outbox;
    }

    static String org() {
        return TenantContext.organizationId();
    }

    static String me() {
        return TenantContext.userId();
    }

    // ---- access -------------------------------------------------------------------------------------------------------

    static boolean seesAll() {
        return TenantContext.has(Permissions.PROJECT_TASK__MANAGE) || TenantContext.has(Permissions.PROJECT__MANAGE);
    }

    /**
     * SQL condition (alias {@code p}) limiting projects to those the caller may see; binds two parameters: the caller
     * twice. Use with {@link #visibleArgs()}.
     */
    static String visibleSql() {
        if (seesAll()) {
            return "true";
        }
        return "(p.privacy = 'internal' OR p.\"managerId\" = ? OR EXISTS (SELECT 1 FROM \"ProjectMember\" pm WHERE"
                + " pm.\"projectId\" = p.id AND pm.\"userId\" = ?))";
    }

    static List<Object> visibleArgs() {
        return seesAll() ? List.of() : List.of(String.valueOf(me()), String.valueOf(me()));
    }

    /** The caller's role on the project: {@code manager}, {@code member}, {@code viewer} or null. */
    String roleOn(Map<String, Object> project) {
        String user = me();
        if (user == null) {
            return null;
        }
        if (user.equals(project.get("managerId"))) {
            return "manager";
        }
        return jdbc.sql("SELECT role FROM \"ProjectMember\" WHERE \"projectId\" = ? AND \"userId\" = ?")
                .params(project.get("id"), user).query(String.class).optional().orElse(null);
    }

    /** The project row when the caller may see it (404 otherwise). */
    Map<String, Object> visibleProject(String id, boolean lock) {
        Map<String, Object> p = id == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"Project\" WHERE id = ? AND"
                + " \"organizationId\" = ?" + (lock ? " FOR UPDATE" : "")).params(id, org()));
        if (p == null || !canSee(p)) {
            throw ApiException.notFound("Project not found");
        }
        return p;
    }

    boolean canSee(Map<String, Object> project) {
        return seesAll() || "internal".equals(project.get("privacy")) || roleOn(project) != null;
    }

    boolean canEditTasks(Map<String, Object> project) {
        if (!TenantContext.has(Permissions.PROJECT_TASK__WRITE)) {
            return false;
        }
        if (seesAll()) {
            return true;
        }
        String role = roleOn(project);
        return "viewer".equals(role) ? false : role != null || "internal".equals(project.get("privacy"));
    }

    boolean canManage(Map<String, Object> project) {
        return TenantContext.has(Permissions.PROJECT__MANAGE) || "manager".equals(roleOn(project));
    }

    void requireEditTasks(Map<String, Object> project) {
        if (!canEditTasks(project)) {
            throw ApiException.forbidden("You can only view the tasks of this project");
        }
    }

    void requireManage(Map<String, Object> project) {
        if (!canManage(project)) {
            throw ApiException.forbidden("Only the project manager can change the project settings");
        }
    }

    // ---- counters ------------------------------------------------------------------------------------------------------

    long nextCounter(String key) {
        return jdbc.sql("INSERT INTO \"ProjectCounter\" (\"organizationId\", key, value) VALUES (?, ?, 1) ON CONFLICT"
                + " (\"organizationId\", key) DO UPDATE SET value = \"ProjectCounter\".value + 1 RETURNING value")
                .params(org(), key).query(Long.class).single();
    }

    // ---- users ---------------------------------------------------------------------------------------------------------

    /** Ids that are not active users of this organisation, after removing blanks and duplicates. */
    List<String> checkUsers(Collection<String> ids) {
        if (ids == null) {
            return List.of();
        }
        List<String> clean = new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(s -> s != null && !s.isBlank())
                .toList()));
        if (clean.isEmpty()) {
            return clean;
        }
        Set<String> found = new java.util.HashSet<>(jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND id = ANY(?)").params(org(), clean.toArray(String[]::new))
                .query(String.class).list());
        for (String id : clean) {
            if (!found.contains(id)) {
                throw ApiException.badRequest("User " + id + " is not a user of this organization");
            }
        }
        return clean;
    }

    /** {@code id → "First Last"} for the given users. */
    Map<String, String> userNames(Collection<String> ids) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> clean = ids == null ? List.of() : ids.stream().filter(s -> s != null).distinct().toList();
        if (clean.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, trim(coalesce(\"firstName\", '') || ' ' || coalesce(\"lastName\", '')) AS name, email FROM"
                + " \"User\" WHERE id = ANY(?)").param(clean.toArray(String[]::new)).query((rs, n) -> {
                    String name = rs.getString("name");
                    out.put(rs.getString("id"), name == null || name.isBlank() ? rs.getString("email") : name);
                    return null;
                }).list();
        return out;
    }

    String userName(String id) {
        return id == null ? null : userNames(List.of(id)).get(id);
    }

    // ---- chatter, notifications, events --------------------------------------------------------------------------------

    void message(String projectId, String taskId, String kind, String body, List<String> mentions) {
        jdbc.sql("INSERT INTO \"ProjectMessage\" (id, \"organizationId\", \"projectId\", \"taskId\", kind, body,"
                + " \"authorId\", \"authorName\", mentions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org(), projectId, taskId, kind, body, me(), userName(me()),
                        (mentions == null ? List.<String>of() : mentions).toArray(String[]::new))
                .update();
    }

    /** In-app notification to each user except the caller; failures are logged, never thrown. */
    void notifyUsers(Collection<String> users, String title, String body, String path) {
        if (users == null) {
            return;
        }
        for (String u : new LinkedHashSet<>(users)) {
            if (u == null || u.equals(me())) {
                continue;
            }
            try {
                notifications.send(new SendInput(org(), u, "in_app", "project", title, body, Map.of("path", path)));
            } catch (RuntimeException e) {
                log.warn("project notification to {} failed: {}", u, e.toString());
            }
        }
    }

    void event(String name, String projectId, String taskId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", org());
        payload.put("projectId", projectId);
        if (taskId != null) {
            payload.put("taskId", taskId);
        }
        if (extra != null) {
            payload.putAll(extra);
        }
        outbox.publish(name, payload);
    }

    // ---- parsing -------------------------------------------------------------------------------------------------------

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String required(String v, String field) {
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest(field + " should not be empty");
        }
        return v.trim();
    }

    static LocalDate day(String v, String field) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a valid ISO 8601 date string");
        }
    }

    static java.sql.Date sqlDate(LocalDate d) {
        return d == null ? null : java.sql.Date.valueOf(d);
    }

    static BigDecimal hours(BigDecimal v, String field) {
        if (v == null) {
            return null;
        }
        if (v.signum() < 0) {
            throw ApiException.badRequest(field + " must not be negative");
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    static void oneOf(String v, Set<String> allowed, String field) {
        if (v != null && !allowed.contains(v)) {
            throw ApiException.badRequest(field + " must be one of the following values: " + String.join(", ",
                    allowed.stream().sorted().toList()));
        }
    }
}
