package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.PmSupport.blank;
import static com.inventoryerp.projects.application.PmSupport.day;
import static com.inventoryerp.projects.application.PmSupport.me;
import static com.inventoryerp.projects.application.PmSupport.org;
import static com.inventoryerp.projects.application.PmSupport.sqlDate;

import com.inventoryerp.hr.application.HrProjectTimeService;
import com.inventoryerp.hr.application.HrProjectTimeService.Mirror;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Time on projects (ADR-S062): manual entries, a weekly grid (one cell = the hours of a project / task on a day), and a
 * timer per user. Every line is mirrored into the user's HR timesheet when the user has an employee record
 * ({@link HrProjectTimeService}); once HR has submitted or approved that timesheet the line is locked. Approved HR
 * entries are what {@code POST /projects/:id/costs/timesheets} capture into project WIP (ADR-S038). Task effective
 * hours are the sum of their lines.
 */
@Service
public class ProjectTimesheetService {

    public record LogInput(String projectId, String taskId, String userId, String date, BigDecimal hours,
            String description, Boolean isBillable) {}

    static final BigDecimal DAY_MAX = BigDecimal.valueOf(24);

    private final PmSupport pm;
    private final ProjectTaskService tasks;
    private final HrProjectTimeService hr;

    public ProjectTimesheetService(PmSupport pm, ProjectTaskService tasks, HrProjectTimeService hr) {
        this.pm = pm;
        this.tasks = tasks;
        this.hr = hr;
    }

    static boolean manager() {
        return TenantContext.has(Permissions.PROJECT_TIMESHEET__MANAGE);
    }

    // ---- lines ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String projectId, String taskId, String userId, String from, String to) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ?");
        args.add(org());
        String user = "me".equals(userId) ? me() : blank(userId);
        boolean allOfProject = false;
        if (blank(projectId) != null) {
            Map<String, Object> p = pm.visibleProject(projectId, false);
            allOfProject = pm.canManage(p);
            where.append(" AND l.\"projectId\" = ?");
            args.add(projectId);
        }
        if (!manager() && !allOfProject) {
            if (user != null && !user.equals(me())) {
                throw ApiException.forbidden("You can only see your own time");
            }
            user = me();
        }
        if (user != null) {
            where.append(" AND l.\"userId\" = ?");
            args.add(user);
        }
        if (blank(taskId) != null) {
            where.append(" AND l.\"taskId\" = ?");
            args.add(taskId);
        }
        LocalDate f = day(from, "from");
        LocalDate t = day(to, "to");
        if (f != null) {
            where.append(" AND l.date >= ?");
            args.add(sqlDate(f));
        }
        if (t != null) {
            where.append(" AND l.date <= ?");
            args.add(sqlDate(t));
        }
        List<Map<String, Object>> rows = SqlRows.list(pm.jdbc.sql("SELECT l.*, p.code AS \"projectCode\", p.name AS"
                + " \"projectName\", k.number AS \"taskNumber\", k.title AS \"taskTitle\" FROM \"ProjectTimesheetLine\" l"
                + " JOIN \"Project\" p ON p.id = l.\"projectId\" LEFT JOIN \"ProjectTask\" k ON k.id = l.\"taskId\"" + where
                + " ORDER BY l.date DESC, l.\"createdAt\" DESC LIMIT 2000").params(args));
        decorate(rows);
        return rows;
    }

    @Transactional
    public Map<String, Object> log(LogInput in) {
        Target target = target(in.projectId(), in.taskId());
        String user = owner(in.userId(), target.project());
        LocalDate date = day(in.date(), "date");
        if (date == null) {
            date = LocalDate.now(ZoneOffset.UTC);
        }
        BigDecimal h = amount(in.hours());
        checkDay(user, date, h, null);
        String id = UUID.randomUUID().toString();
        String employee = hr.employeeOfUser(user);
        boolean billable = in.isBillable() == null || in.isBillable();
        pm.jdbc.sql("INSERT INTO \"ProjectTimesheetLine\" (id, \"organizationId\", \"projectId\", \"taskId\", \"userId\","
                        + " \"employeeId\", date, hours, description, \"isBillable\", \"createdById\") VALUES (?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?)")
                .params(id, org(), target.projectId(), target.taskId(), user, employee, sqlDate(date), h,
                        blank(in.description()), billable, me())
                .update();
        mirror(id);
        recompute(target.taskId());
        return line(id);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        for (String k : body.keySet()) {
            if (!Set.of("date", "hours", "description", "isBillable", "taskId").contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
        Map<String, Object> l = editableLine(id);
        String oldTask = (String) l.get("taskId");
        String taskId = body.containsKey("taskId") ? blank(ProjectTaskService.str(body.get("taskId"))) : oldTask;
        if (body.containsKey("taskId")) {
            target((String) l.get("projectId"), taskId);
        }
        LocalDate date = body.containsKey("date") ? day(ProjectTaskService.str(body.get("date")), "date")
                : (LocalDate) l.get("date");
        if (date == null) {
            throw ApiException.badRequest("date should not be empty");
        }
        BigDecimal h = body.containsKey("hours") ? amount(ProjectTaskService.dec(body.get("hours")))
                : SqlRows.dec(l, "hours");
        checkDay((String) l.get("userId"), date, h, id);
        pm.jdbc.sql("UPDATE \"ProjectTimesheetLine\" SET \"taskId\" = ?, date = ?, hours = ?, description = ?,"
                        + " \"isBillable\" = ?, \"updatedAt\" = now() WHERE id = ?")
                .params(taskId, sqlDate(date), h, body.containsKey("description") ? blank(ProjectTaskService.str(
                        body.get("description"))) : l.get("description"), body.containsKey("isBillable")
                        ? Boolean.TRUE.equals(body.get("isBillable")) : l.get("isBillable"), id)
                .update();
        mirror(id);
        recompute(oldTask);
        if (!Objects.equals(oldTask, taskId)) {
            recompute(taskId);
        }
        return line(id);
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> l = editableLine(id);
        hr.remove((String) l.get("hrEntryId"));
        pm.jdbc.sql("DELETE FROM \"ProjectTimesheetLine\" WHERE id = ?").param(id).update();
        recompute((String) l.get("taskId"));
        return Map.of("id", id, "deleted", true);
    }

    // ---- weekly grid ----------------------------------------------------------------------------------------------------

    /** Monday-based week of {@code start}: one row per project / task with the hours of each day. */
    @Transactional(readOnly = true)
    public Map<String, Object> week(String start, String userId) {
        LocalDate monday = (day(start, "start") == null ? LocalDate.now(ZoneOffset.UTC) : day(start, "start"))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String user = gridUser(userId);
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            days.add(monday.plusDays(i));
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        BigDecimal[] totals = zeros();
        for (Map<String, Object> l : SqlRows.list(pm.jdbc.sql("SELECT l.\"projectId\", l.\"taskId\", l.date,"
                + " sum(l.hours) AS hours, bool_or(l.\"hrEntryId\" IS NOT NULL) AS mirrored, p.code AS \"projectCode\","
                + " p.name AS \"projectName\", k.number AS \"taskNumber\", k.title AS \"taskTitle\" FROM"
                + " \"ProjectTimesheetLine\" l JOIN \"Project\" p ON p.id = l.\"projectId\" LEFT JOIN \"ProjectTask\" k ON"
                + " k.id = l.\"taskId\" WHERE l.\"organizationId\" = ? AND l.\"userId\" = ? AND l.date BETWEEN ? AND ?"
                + " GROUP BY l.\"projectId\", l.\"taskId\", l.date, p.code, p.name, k.number, k.title ORDER BY p.code,"
                + " k.number NULLS FIRST").params(org(), user, sqlDate(monday), sqlDate(monday.plusDays(6))))) {
            String key = l.get("projectId") + "/" + l.get("taskId");
            Map<String, Object> row = rows.computeIfAbsent(key, k -> {
                Map<String, Object> r = Obj.of("projectId", l.get("projectId"), "projectCode", l.get("projectCode"),
                        "projectName", l.get("projectName"), "taskId", l.get("taskId"), "taskNumber",
                        l.get("taskNumber"), "taskTitle", l.get("taskTitle"));
                r.put("hours", zeros());
                return r;
            });
            int i = (int) (((LocalDate) l.get("date")).toEpochDay() - monday.toEpochDay());
            BigDecimal[] hours = (BigDecimal[]) row.get("hours");
            hours[i] = hours[i].add(SqlRows.dec(l, "hours"));
            totals[i] = totals[i].add(SqlRows.dec(l, "hours"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows.values()) {
            BigDecimal[] hours = (BigDecimal[]) r.get("hours");
            r.put("hours", List.of(hours));
            r.put("total", sum(hours));
            out.add(r);
        }
        return Obj.of("start", monday, "days", days, "userId", user, "userName", pm.userName(user), "rows", out,
                "totals", List.of(totals), "total", sum(totals));
    }

    /** Set the hours of one grid cell: the day's lines of that project / task are replaced by one line. */
    @Transactional
    public Map<String, Object> setCell(String projectId, String taskId, String date, BigDecimal hours,
            String userId) {
        Target target = target(projectId, taskId);
        String user = owner(userId, target.project());
        LocalDate d = day(date, "date");
        if (d == null) {
            throw ApiException.badRequest("date should not be empty");
        }
        BigDecimal h = hours == null ? BigDecimal.ZERO : hours.setScale(2, RoundingMode.HALF_UP);
        if (h.signum() < 0 || h.compareTo(DAY_MAX) > 0) {
            throw ApiException.badRequest("hours must be between 0 and 24");
        }
        List<Map<String, Object>> existing = SqlRows.list(pm.jdbc.sql("SELECT * FROM \"ProjectTimesheetLine\" WHERE"
                + " \"organizationId\" = ? AND \"userId\" = ? AND \"projectId\" = ? AND \"taskId\" IS NOT DISTINCT FROM ?"
                + " AND date = ? ORDER BY \"createdAt\"").params(org(), user, target.projectId(), target.taskId(),
                sqlDate(d)));
        for (Map<String, Object> l : existing) {
            if (hr.isLocked((String) l.get("hrEntryId"))) {
                throw ApiException.badRequest("This time is on a submitted or approved HR timesheet and can no longer"
                        + " be changed");
            }
        }
        BigDecimal other = pm.jdbc.sql("SELECT coalesce(sum(hours), 0) FROM \"ProjectTimesheetLine\" WHERE"
                + " \"organizationId\" = ? AND \"userId\" = ? AND date = ? AND NOT (\"projectId\" = ? AND \"taskId\" IS NOT"
                + " DISTINCT FROM ?)").params(org(), user, sqlDate(d), target.projectId(), target.taskId())
                .query(BigDecimal.class).single();
        if (other.add(h).compareTo(DAY_MAX) > 0) {
            throw ApiException.badRequest("More than 24 hours logged on " + d);
        }
        String description = existing.isEmpty() ? null : (String) existing.getFirst().get("description");
        for (Map<String, Object> l : existing) {
            hr.remove((String) l.get("hrEntryId"));
            pm.jdbc.sql("DELETE FROM \"ProjectTimesheetLine\" WHERE id = ?").param(l.get("id")).update();
        }
        if (h.signum() > 0) {
            String id = UUID.randomUUID().toString();
            pm.jdbc.sql("INSERT INTO \"ProjectTimesheetLine\" (id, \"organizationId\", \"projectId\", \"taskId\", \"userId\","
                    + " \"employeeId\", date, hours, description, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(id, org(), target.projectId(), target.taskId(), user, hr.employeeOfUser(user), sqlDate(d), h,
                            description, me()).update();
            mirror(id);
        }
        recompute(target.taskId());
        return week(d.toString(), user);
    }

    // ---- timer ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> timer() {
        Map<String, Object> t = SqlRows.one(pm.jdbc.sql("SELECT r.*, k.number AS \"taskNumber\", k.title AS \"taskTitle\","
                + " k.\"projectId\", p.code AS \"projectCode\", p.name AS \"projectName\" FROM \"ProjectTimer\" r JOIN"
                + " \"ProjectTask\" k ON k.id = r.\"taskId\" LEFT JOIN \"Project\" p ON p.id = k.\"projectId\" WHERE"
                + " r.\"organizationId\" = ? AND r.\"userId\" = ?").params(org(), String.valueOf(me())));
        if (t == null) {
            return Obj.of("running", false);
        }
        t.put("running", true);
        t.put("elapsedSeconds", Duration.between((Instant) t.get("startedAt"), Instant.now()).toSeconds());
        return t;
    }

    /** Start the timer on a task; a timer already running on another task is stopped and logged first. */
    @Transactional
    public Map<String, Object> startTimer(String taskId) {
        Target target = target(null, PmSupport.required(taskId, "taskId"));
        if (target.projectId() == null) {
            throw ApiException.badRequest("Time can only be logged on project tasks");
        }
        Map<String, Object> running = timer();
        if (Boolean.TRUE.equals(running.get("running"))) {
            if (taskId.equals(running.get("taskId"))) {
                return running;
            }
            stopTimer(null);
        }
        pm.jdbc.sql("INSERT INTO \"ProjectTimer\" (\"organizationId\", \"userId\", \"taskId\") VALUES (?, ?, ?)")
                .params(org(), me(), taskId).update();
        return timer();
    }

    /** Stop the timer and log the elapsed time (at least one minute) on today's date. */
    @Transactional
    public Map<String, Object> stopTimer(String description) {
        Map<String, Object> t = timer();
        if (!Boolean.TRUE.equals(t.get("running"))) {
            throw ApiException.badRequest("No timer is running");
        }
        long seconds = ((Number) t.get("elapsedSeconds")).longValue();
        pm.jdbc.sql("DELETE FROM \"ProjectTimer\" WHERE \"organizationId\" = ? AND \"userId\" = ?").params(org(),
                String.valueOf(me())).update();
        if (seconds > 24 * 3600) {
            throw ApiException.badRequest("The timer ran for more than 24 hours; log the time manually");
        }
        BigDecimal h = BigDecimal.valueOf(Math.max(seconds, 60)).divide(BigDecimal.valueOf(3600), 2,
                RoundingMode.HALF_UP).max(new BigDecimal("0.02"));
        Map<String, Object> line = log(new LogInput((String) t.get("projectId"), (String) t.get("taskId"), null,
                LocalDate.now(ZoneOffset.UTC).toString(), h, description, null));
        return Obj.of("running", false, "line", line);
    }

    @Transactional
    public Map<String, Object> discardTimer() {
        pm.jdbc.sql("DELETE FROM \"ProjectTimer\" WHERE \"organizationId\" = ? AND \"userId\" = ?").params(org(),
                String.valueOf(me())).update();
        return Obj.of("running", false);
    }

    // ---- internals ------------------------------------------------------------------------------------------------------

    record Target(Map<String, Object> project, String projectId, String taskId) {}

    private Target target(String projectId, String taskId) {
        String pid = blank(projectId);
        String tid = blank(taskId);
        if (tid != null) {
            Map<String, Object> task = tasks.row(tid);
            if (task.get("projectId") == null) {
                throw ApiException.badRequest("Time can only be logged on project tasks");
            }
            if (pid != null && !pid.equals(task.get("projectId"))) {
                throw ApiException.badRequest("The task does not belong to this project");
            }
            pid = (String) task.get("projectId");
        }
        if (pid == null) {
            throw ApiException.badRequest("projectId should not be empty");
        }
        Map<String, Object> project = pm.visibleProject(pid, false);
        if (!Boolean.TRUE.equals(project.get("allowTimesheets"))) {
            throw ApiException.badRequest("Timesheets are disabled on this project");
        }
        if (Set.of("closed", "cancelled").contains(project.get("status"))) {
            throw ApiException.badRequest("Time cannot be logged on a " + project.get("status") + " project");
        }
        if ("viewer".equals(pm.roleOn(project))) {
            throw ApiException.forbidden("Viewers cannot log time on this project");
        }
        return new Target(project, pid, tid);
    }

    /** The user the time belongs to: the caller, or anyone for a timesheet manager / the project manager. */
    private String owner(String userId, Map<String, Object> project) {
        String user = blank(userId);
        if (user == null || user.equals(me())) {
            return me();
        }
        if (!manager() && !pm.canManage(project)) {
            throw ApiException.forbidden("You can only log your own time");
        }
        return pm.checkUsers(List.of(user)).getFirst();
    }

    private String gridUser(String userId) {
        String user = blank(userId);
        if (user == null || "me".equals(user) || user.equals(me())) {
            return me();
        }
        if (!manager()) {
            throw ApiException.forbidden("You can only see your own time");
        }
        return user;
    }

    private Map<String, Object> editableLine(String id) {
        Map<String, Object> l = SqlRows.one(pm.jdbc.sql("SELECT * FROM \"ProjectTimesheetLine\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (l == null) {
            throw ApiException.notFound("Timesheet line not found");
        }
        Map<String, Object> project = pm.visibleProject((String) l.get("projectId"), false);
        if (!Objects.equals(l.get("userId"), me()) && !manager() && !pm.canManage(project)) {
            throw ApiException.forbidden("You can only change your own time");
        }
        if (hr.isLocked((String) l.get("hrEntryId"))) {
            throw ApiException.badRequest("This time is on a submitted or approved HR timesheet and can no longer be"
                    + " changed");
        }
        return l;
    }

    private void mirror(String lineId) {
        Map<String, Object> l = SqlRows.one(pm.jdbc.sql("SELECT l.*, p.code, k.number FROM \"ProjectTimesheetLine\" l JOIN"
                + " \"Project\" p ON p.id = l.\"projectId\" LEFT JOIN \"ProjectTask\" k ON k.id = l.\"taskId\" WHERE"
                + " l.id = ?").param(lineId));
        String employee = l.get("employeeId") == null ? hr.employeeOfUser((String) l.get("userId"))
                : (String) l.get("employeeId");
        int minutes = SqlRows.dec(l, "hours").multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP)
                .intValue();
        String text = (l.get("number") == null ? l.get("code") : l.get("number")) + (l.get("description") == null ? ""
                : " — " + l.get("description"));
        Mirror m = hr.upsert((String) l.get("hrEntryId"), employee, (LocalDate) l.get("date"), Math.max(minutes, 1),
                text, (String) l.get("projectId"), Boolean.TRUE.equals(l.get("isBillable")));
        pm.jdbc.sql("UPDATE \"ProjectTimesheetLine\" SET \"hrEntryId\" = ?, \"employeeId\" = ? WHERE id = ?").params(
                m.entryId(), employee, lineId).update();
    }

    private void recompute(String taskId) {
        if (taskId == null) {
            return;
        }
        pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"effectiveHours\" = (SELECT coalesce(sum(hours), 0) FROM"
                + " \"ProjectTimesheetLine\" WHERE \"taskId\" = ?) WHERE id = ?").params(taskId, taskId).update();
    }

    private void checkDay(String user, LocalDate date, BigDecimal hours, String exceptId) {
        BigDecimal logged = pm.jdbc.sql("SELECT coalesce(sum(hours), 0) FROM \"ProjectTimesheetLine\" WHERE"
                + " \"organizationId\" = ? AND \"userId\" = ? AND date = ? AND id IS DISTINCT FROM ?").params(org(), user,
                sqlDate(date), exceptId).query(BigDecimal.class).single();
        if (logged.add(hours).compareTo(DAY_MAX) > 0) {
            throw ApiException.badRequest("More than 24 hours logged on " + date);
        }
    }

    private Map<String, Object> line(String id) {
        List<Map<String, Object>> rows = SqlRows.list(pm.jdbc.sql("SELECT l.*, p.code AS \"projectCode\", p.name AS"
                + " \"projectName\", k.number AS \"taskNumber\", k.title AS \"taskTitle\" FROM \"ProjectTimesheetLine\" l"
                + " JOIN \"Project\" p ON p.id = l.\"projectId\" LEFT JOIN \"ProjectTask\" k ON k.id = l.\"taskId\" WHERE"
                + " l.id = ?").param(id));
        decorate(rows);
        return rows.getFirst();
    }

    private void decorate(List<Map<String, Object>> rows) {
        Map<String, String> names = pm.userNames(rows.stream().map(r -> (String) r.get("userId")).toList());
        for (Map<String, Object> r : rows) {
            r.put("userName", names.get(r.get("userId")));
            String status = hr.status((String) r.get("hrEntryId"));
            r.put("hrStatus", status);
            r.put("locked", status != null && !"DRAFT".equals(status) && !"REJECTED".equals(status));
        }
    }

    private static BigDecimal amount(BigDecimal hours) {
        if (hours == null) {
            throw ApiException.badRequest("hours should not be empty");
        }
        BigDecimal h = hours.setScale(2, RoundingMode.HALF_UP);
        if (h.signum() <= 0 || h.compareTo(DAY_MAX) > 0) {
            throw ApiException.badRequest("hours must be greater than 0 and at most 24");
        }
        return h;
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] a = new BigDecimal[7];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal sum(BigDecimal[] a) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal b : a) {
            s = s.add(b);
        }
        return s;
    }
}
