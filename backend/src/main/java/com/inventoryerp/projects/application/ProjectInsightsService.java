package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.PmSupport.blank;
import static com.inventoryerp.projects.application.PmSupport.day;
import static com.inventoryerp.projects.application.PmSupport.me;
import static com.inventoryerp.projects.application.PmSupport.oneOf;
import static com.inventoryerp.projects.application.PmSupport.org;
import static com.inventoryerp.projects.application.PmSupport.sqlDate;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project management reporting (ADR-S062): the personal dashboard, task analysis and timesheet analysis grouped by a
 * dimension, team workload, and Gantt data (tasks with planned dates, dependencies and milestones). Every figure is
 * limited to the projects the caller may see.
 */
@Service
public class ProjectInsightsService {

    static final Set<String> TASK_GROUPS = Set.of("project", "stage", "assignee", "tag", "priority", "milestone",
            "month");
    static final Set<String> TIME_GROUPS = Set.of("project", "task", "user", "week", "month", "date");

    private final PmSupport pm;
    private final ProjectTaskService tasks;

    public ProjectInsightsService(PmSupport pm, ProjectTaskService tasks) {
        this.pm = pm;
        this.tasks = tasks;
    }

    /** The caller's work: open, overdue, due today / this week, hours this week, followed projects at risk. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        String user = String.valueOf(me());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Object> args = new ArrayList<>();
        args.add(org());
        String vis = ProjectTaskService.visibility(args);
        args.add(user);
        Map<String, Object> mine = SqlRows.one(pm.jdbc.sql("SELECT count(*) FILTER (WHERE NOT t.\"isClosed\") AS open,"
                + " count(*) FILTER (WHERE NOT t.\"isClosed\" AND t.deadline < CURRENT_DATE) AS overdue, count(*) FILTER"
                + " (WHERE NOT t.\"isClosed\" AND t.deadline = CURRENT_DATE) AS \"dueToday\", count(*) FILTER (WHERE NOT"
                + " t.\"isClosed\" AND t.deadline > CURRENT_DATE AND t.deadline <= CURRENT_DATE + 7) AS \"dueThisWeek\","
                + " count(*) FILTER (WHERE t.\"isClosed\" AND t.\"closedAt\" >= date_trunc('week', now())) AS"
                + " \"doneThisWeek\", count(*) FILTER (WHERE NOT t.\"isClosed\" AND t.\"kanbanState\" = 'blocked') AS"
                + " blocked FROM \"ProjectTask\" t LEFT JOIN \"Project\" p ON p.id = t.\"projectId\" WHERE"
                + " t.\"organizationId\" = ? AND t.\"deletedAt\" IS NULL AND " + vis + " AND (p.id IS NULL OR NOT"
                + " p.\"isTemplate\") AND EXISTS (SELECT 1 FROM \"ProjectTaskAssignee\" a WHERE a.\"taskId\" = t.id AND"
                + " a.\"userId\" = ?)").params(args));
        BigDecimal weekHours = pm.jdbc.sql("SELECT coalesce(sum(hours), 0) FROM \"ProjectTimesheetLine\" WHERE"
                + " \"organizationId\" = ? AND \"userId\" = ? AND date BETWEEN ? AND ?").params(org(), user,
                sqlDate(monday), sqlDate(monday.plusDays(6))).query(BigDecimal.class).single();
        List<Map<String, Object>> hoursByDay = SqlRows.list(pm.jdbc.sql("SELECT d::date AS date, coalesce((SELECT"
                + " sum(hours) FROM \"ProjectTimesheetLine\" l WHERE l.\"organizationId\" = ? AND l.\"userId\" = ? AND"
                + " l.date = d::date), 0) AS hours FROM generate_series(?::date, ?::date, interval '1 day') d ORDER BY d")
                .params(org(), user, sqlDate(monday), sqlDate(monday.plusDays(6))));
        List<Object> pargs = new ArrayList<>();
        pargs.add(org());
        pargs.addAll(PmSupport.visibleArgs());
        pargs.add(user);
        pargs.add(user);
        List<Map<String, Object>> atRisk = SqlRows.list(pm.jdbc.sql("SELECT p.id, p.code, p.name, p.\"lastUpdateStatus\","
                + " p.\"endDate\", (SELECT count(*) FROM \"ProjectTask\" t WHERE t.\"projectId\" = p.id AND t.\"deletedAt\""
                + " IS NULL AND NOT t.\"isClosed\" AND t.deadline < CURRENT_DATE) AS overdue FROM \"Project\" p WHERE"
                + " p.\"organizationId\" = ? AND " + PmSupport.visibleSql() + " AND NOT p.\"isTemplate\" AND p.status NOT IN"
                + " ('completed', 'closed', 'cancelled') AND (p.\"managerId\" = ? OR EXISTS (SELECT 1 FROM \"ProjectMember\""
                + " m WHERE m.\"projectId\" = p.id AND m.\"userId\" = ?)) AND (p.\"lastUpdateStatus\" IN ('at_risk',"
                + " 'off_track') OR p.\"endDate\" < CURRENT_DATE OR EXISTS (SELECT 1 FROM \"ProjectTask\" t WHERE"
                + " t.\"projectId\" = p.id AND t.\"deletedAt\" IS NULL AND NOT t.\"isClosed\" AND t.deadline <"
                + " CURRENT_DATE)) ORDER BY p.code LIMIT 20").params(pargs));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> focus = (List<Map<String, Object>>) tasks.list(new ProjectTaskService.TaskQuery(null,
                null, "me", null, null, null, "open", null, today.plusDays(7).toString(), null, null, null, null, null,
                null, null, 20, 0)).get("data");
        return Obj.of("myTasks", mine, "weekHours", weekHours, "hoursByDay", hoursByDay, "projectsAtRisk", atRisk,
                "focus", focus, "today", today);
    }

    /** Task counts and hours grouped by a dimension (project, stage, assignee, tag, priority, milestone, month). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> taskAnalysis(String groupBy, String projectId, String from, String to) {
        String g = groupBy == null ? "project" : groupBy;
        oneOf(g, TASK_GROUPS, "groupBy");
        if ("stage".equals(g) && blank(projectId) == null) {
            throw ApiException.badRequest("Grouping by stage needs a projectId");
        }
        String key;
        String label;
        String join = "";
        switch (g) {
            case "stage" -> {
                key = "t.\"stageId\"";
                label = "max(s.name)";
            }
            case "assignee" -> {
                join = " LEFT JOIN \"ProjectTaskAssignee\" a ON a.\"taskId\" = t.id LEFT JOIN \"User\" u ON u.id ="
                        + " a.\"userId\"";
                key = "a.\"userId\"";
                label = "coalesce(max(trim(coalesce(u.\"firstName\", '') || ' ' || coalesce(u.\"lastName\", ''))),"
                        + " 'Unassigned')";
            }
            case "tag" -> {
                join = " LEFT JOIN \"ProjectTaskTag\" x ON x.\"taskId\" = t.id LEFT JOIN \"ProjectTag\" g ON g.id ="
                        + " x.\"tagId\"";
                key = "g.id";
                label = "coalesce(max(g.name), 'No tag')";
            }
            case "priority" -> {
                key = "t.priority";
                label = "t.priority";
            }
            case "milestone" -> {
                key = "t.\"milestoneId\"";
                label = "coalesce(max(m.name), 'No milestone')";
            }
            case "month" -> {
                key = "to_char(t.\"createdAt\", 'YYYY-MM')";
                label = key;
            }
            default -> {
                key = "t.\"projectId\"";
                label = "coalesce(max(p.code || ' ' || p.name), 'Personal')";
            }
        }
        List<Object> args = new ArrayList<>();
        args.add(org());
        String vis = ProjectTaskService.visibility(args);
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND t.\"deletedAt\" IS NULL AND " + vis
                + " AND (p.id IS NULL OR NOT p.\"isTemplate\")");
        if (blank(projectId) != null) {
            where.append(" AND t.\"projectId\" = ?");
            args.add(projectId);
        }
        LocalDate f = day(from, "from");
        LocalDate t = day(to, "to");
        if (f != null) {
            where.append(" AND t.\"createdAt\" >= ?");
            args.add(sqlDate(f));
        }
        if (t != null) {
            where.append(" AND t.\"createdAt\" < ?");
            args.add(sqlDate(t.plusDays(1)));
        }
        return SqlRows.list(pm.jdbc.sql("SELECT " + key + " AS key, " + label + " AS label, count(*) AS total, count(*)"
                + " FILTER (WHERE NOT t.\"isClosed\") AS open, count(*) FILTER (WHERE t.\"isClosed\") AS closed, count(*)"
                + " FILTER (WHERE NOT t.\"isClosed\" AND t.deadline < CURRENT_DATE) AS overdue, count(*) FILTER (WHERE"
                + " t.\"isClosed\" AND t.deadline IS NOT NULL AND t.\"closedAt\"::date > t.deadline) AS \"closedLate\","
                + " coalesce(sum(t.\"plannedHours\"), 0) AS \"plannedHours\", coalesce(sum(t.\"effectiveHours\"), 0) AS"
                + " \"effectiveHours\", round(avg(EXTRACT(EPOCH FROM (t.\"closedAt\" - t.\"createdAt\")) / 86400.0)"
                + " FILTER (WHERE t.\"isClosed\")::numeric, 1) AS \"avgDaysToClose\"" + ProjectTaskService.TASK_FROM
                + join + where + " GROUP BY " + key + " ORDER BY total DESC, label").params(args));
    }

    /** Hours grouped by project, task, user, week, month or date; billable split. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> timesheetAnalysis(String groupBy, String projectId, String userId, String from,
            String to) {
        String g = groupBy == null ? "project" : groupBy;
        oneOf(g, TIME_GROUPS, "groupBy");
        String key;
        String label;
        switch (g) {
            case "task" -> {
                key = "l.\"taskId\"";
                label = "coalesce(max(k.number || ' ' || k.title), 'No task')";
            }
            case "user" -> {
                key = "l.\"userId\"";
                label = "max(trim(coalesce(u.\"firstName\", '') || ' ' || coalesce(u.\"lastName\", '')))";
            }
            case "week" -> {
                key = "to_char(date_trunc('week', l.date), 'YYYY-MM-DD')";
                label = key;
            }
            case "month" -> {
                key = "to_char(l.date, 'YYYY-MM')";
                label = key;
            }
            case "date" -> {
                key = "to_char(l.date, 'YYYY-MM-DD')";
                label = key;
            }
            default -> {
                key = "l.\"projectId\"";
                label = "max(p.code || ' ' || p.name)";
            }
        }
        List<Object> args = new ArrayList<>();
        args.add(org());
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ? AND " + PmSupport.visibleSql());
        args.addAll(PmSupport.visibleArgs());
        if (!ProjectTimesheetService.manager()) {
            where.append(" AND (l.\"userId\" = ? OR p.\"managerId\" = ? OR EXISTS (SELECT 1 FROM \"ProjectMember\" pm WHERE"
                    + " pm.\"projectId\" = p.id AND pm.\"userId\" = ? AND pm.role = 'manager'))");
            args.add(String.valueOf(me()));
            args.add(String.valueOf(me()));
            args.add(String.valueOf(me()));
        }
        if (blank(projectId) != null) {
            where.append(" AND l.\"projectId\" = ?");
            args.add(projectId);
        }
        if (blank(userId) != null) {
            where.append(" AND l.\"userId\" = ?");
            args.add("me".equals(userId) ? me() : userId);
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
        return SqlRows.list(pm.jdbc.sql("SELECT " + key + " AS key, " + label + " AS label, sum(l.hours) AS hours,"
                + " coalesce(sum(l.hours) FILTER (WHERE l.\"isBillable\"), 0) AS \"billableHours\", count(*) AS lines,"
                + " count(DISTINCT l.\"userId\") AS people FROM \"ProjectTimesheetLine\" l JOIN \"Project\" p ON p.id ="
                + " l.\"projectId\" LEFT JOIN \"ProjectTask\" k ON k.id = l.\"taskId\" LEFT JOIN \"User\" u ON u.id ="
                + " l.\"userId\"" + where + " GROUP BY " + key + " ORDER BY " + (Set.of("week", "month", "date")
                .contains(g) ? "key" : "hours DESC")).params(args));
    }

    /** Open tasks and planned hours per person over the visible projects (optionally one project). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> workload(String projectId) {
        List<Object> args = new ArrayList<>();
        args.add(org());
        String vis = ProjectTaskService.visibility(args);
        String filter = "";
        if (blank(projectId) != null) {
            filter = " AND t.\"projectId\" = ?";
            args.add(projectId);
        }
        return SqlRows.list(pm.jdbc.sql("SELECT a.\"userId\", max(trim(coalesce(u.\"firstName\", '') || ' ' ||"
                + " coalesce(u.\"lastName\", ''))) AS name, count(*) AS open, count(*) FILTER (WHERE t.deadline <"
                + " CURRENT_DATE) AS overdue, count(*) FILTER (WHERE t.deadline BETWEEN CURRENT_DATE AND CURRENT_DATE + 7)"
                + " AS \"dueThisWeek\", coalesce(sum(t.\"plannedHours\" - least(t.\"effectiveHours\", t.\"plannedHours\")),"
                + " 0) AS \"remainingHours\", count(DISTINCT t.\"projectId\") AS projects FROM \"ProjectTask\" t LEFT JOIN"
                + " \"Project\" p ON p.id = t.\"projectId\" JOIN \"ProjectTaskAssignee\" a ON a.\"taskId\" = t.id LEFT JOIN"
                + " \"User\" u ON u.id = a.\"userId\" WHERE t.\"organizationId\" = ? AND t.\"deletedAt\" IS NULL AND NOT"
                + " t.\"isClosed\" AND " + vis + " AND (p.id IS NULL OR NOT p.\"isTemplate\")" + filter + " GROUP BY"
                + " a.\"userId\" ORDER BY open DESC").params(args));
    }

    /** Gantt data of a project: tasks with planned start / deadline, dependencies, milestones. */
    @Transactional(readOnly = true)
    public Map<String, Object> gantt(String projectId) {
        Map<String, Object> p = pm.visibleProject(PmSupport.required(projectId, "projectId"), false);
        List<Map<String, Object>> rows = tasks.decorate(SqlRows.list(pm.jdbc.sql("SELECT " + ProjectTaskService.TASK_COLUMNS
                + ProjectTaskService.TASK_FROM + " WHERE t.\"projectId\" = ? AND t.\"deletedAt\" IS NULL ORDER BY"
                + " coalesce(t.\"plannedStart\", t.deadline, t.\"createdAt\"::date), t.sequence").param(projectId)));
        List<Map<String, Object>> deps = SqlRows.list(pm.jdbc.sql("SELECT d.\"taskId\", d.\"dependsOnId\" FROM"
                + " \"ProjectTaskDependency\" d JOIN \"ProjectTask\" t ON t.id = d.\"taskId\" WHERE t.\"projectId\" = ? AND"
                + " t.\"deletedAt\" IS NULL").param(projectId));
        List<Map<String, Object>> milestones = SqlRows.list(pm.jdbc.sql("SELECT id, name, deadline, \"isReached\" FROM"
                + " \"ProjectMilestone\" WHERE \"projectId\" = ? ORDER BY deadline NULLS LAST").param(projectId));
        return Obj.of("project", Obj.of("id", p.get("id"), "code", p.get("code"), "name", p.get("name"), "startDate",
                p.get("startDate"), "endDate", p.get("endDate")), "tasks", rows, "dependencies", deps, "milestones",
                milestones);
    }
}
