package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.PmSupport.blank;
import static com.inventoryerp.projects.application.PmSupport.day;
import static com.inventoryerp.projects.application.PmSupport.me;
import static com.inventoryerp.projects.application.PmSupport.oneOf;
import static com.inventoryerp.projects.application.PmSupport.org;
import static com.inventoryerp.projects.application.PmSupport.required;
import static com.inventoryerp.projects.application.PmSupport.sqlDate;

import com.inventoryerp.dms.spi.DmsAttachments;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project tasks (ADR-S062): Kanban stages per project, several assignees, tags, subtasks, dependencies (a task cannot
 * be completed while a task it depends on is open), milestones, checklists, chatter with tracked changes, followers
 * with in-app notifications, recurrence (completing a recurring task schedules the next one), links to WBS elements,
 * sites, customers and any record known to the DMS registry (helpdesk tickets, sales orders, opportunities, ...).
 * Personal to-dos have no project and are visible to their creator and assignees only.
 */
@Service
public class ProjectTaskService {

    /** Body of {@code POST /project-tasks}. */
    public record TaskInput(String projectId, String title, String description, String stageId, String parentId,
            List<String> assigneeIds, List<String> tagIds, String milestoneId, String priority, String kanbanState,
            String blockedReason, String plannedStart, String deadline, BigDecimal plannedHours, String partnerId,
            String wbsId, String siteId, String recurrence, Integer recurrenceInterval, String recurrenceUntil,
            String sourceResType, String sourceResId, List<String> checklist, List<String> dependsOnIds,
            Integer color) {}

    /** Filters of {@code GET /project-tasks}. {@code assigneeId = me} is the caller. */
    public record TaskQuery(String projectId, String stageId, String assigneeId, String tagId, String milestoneId,
            String search, String state, String deadlineFrom, String deadlineTo, String parentId, Boolean topLevel,
            String sourceResType, String sourceResId, String priority, Boolean overdue, Boolean personal,
            Integer limit, Integer offset) {}

    static final Set<String> UPDATABLE = Set.of("title", "description", "stageId", "parentId", "assigneeIds",
            "tagIds", "milestoneId", "priority", "kanbanState", "blockedReason", "plannedStart", "deadline",
            "plannedHours", "partnerId", "wbsId", "siteId", "recurrence", "recurrenceInterval", "recurrenceUntil",
            "color", "sequence");

    static final String TASK_COLUMNS = "t.*, p.code AS \"projectCode\", p.name AS \"projectName\", s.name AS"
            + " \"stageName\", s.kind AS \"stageKind\", m.name AS \"milestoneName\", pa.name AS \"partnerName\","
            + " (SELECT count(*) FROM \"ProjectTask\" c WHERE c.\"parentId\" = t.id AND c.\"deletedAt\" IS NULL) AS"
            + " \"subtaskCount\", (SELECT count(*) FROM \"ProjectTask\" c WHERE c.\"parentId\" = t.id AND"
            + " c.\"deletedAt\" IS NULL AND c.\"isClosed\") AS \"subtaskDone\", (SELECT count(*) FROM"
            + " \"ProjectTaskDependency\" d JOIN \"ProjectTask\" o ON o.id = d.\"dependsOnId\" WHERE d.\"taskId\" = t.id"
            + " AND NOT o.\"isClosed\" AND o.\"deletedAt\" IS NULL) AS \"blockedByOpen\"";

    static final String TASK_FROM = " FROM \"ProjectTask\" t LEFT JOIN \"Project\" p ON p.id = t.\"projectId\""
            + " LEFT JOIN \"ProjectTaskStage\" s ON s.id = t.\"stageId\" LEFT JOIN \"ProjectMilestone\" m ON"
            + " m.id = t.\"milestoneId\" LEFT JOIN \"Partner\" pa ON pa.id = t.\"partnerId\"";

    private final PmSupport pm;
    private final DmsAttachments dms;

    public ProjectTaskService(PmSupport pm, DmsAttachments dms) {
        this.pm = pm;
        this.dms = dms;
    }

    // ---- queries -------------------------------------------------------------------------------------------------------

    /** Tasks the caller may see, filtered; {@code {data, total}}. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(TaskQuery q) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND t.\"deletedAt\" IS NULL AND ");
        args.add(org());
        where.append(visibility(args));
        if (blank(q.projectId()) != null) {
            where.append(" AND t.\"projectId\" = ?");
            args.add(q.projectId());
        } else {
            where.append(" AND (p.id IS NULL OR NOT p.\"isTemplate\")");
        }
        if (Boolean.TRUE.equals(q.personal())) {
            where.append(" AND t.\"projectId\" IS NULL");
        }
        eq(where, args, "t.\"stageId\"", q.stageId());
        eq(where, args, "t.\"milestoneId\"", q.milestoneId());
        eq(where, args, "t.priority", q.priority());
        eq(where, args, "t.\"sourceResType\"", q.sourceResType());
        eq(where, args, "t.\"sourceResId\"", q.sourceResId());
        if (blank(q.parentId()) != null) {
            where.append(" AND t.\"parentId\" = ?");
            args.add(q.parentId());
        } else if (Boolean.TRUE.equals(q.topLevel())) {
            where.append(" AND t.\"parentId\" IS NULL");
        }
        String assignee = "me".equals(q.assigneeId()) ? me() : blank(q.assigneeId());
        if ("none".equals(q.assigneeId())) {
            where.append(" AND NOT EXISTS (SELECT 1 FROM \"ProjectTaskAssignee\" a WHERE a.\"taskId\" = t.id)");
        } else if (assignee != null) {
            where.append(" AND EXISTS (SELECT 1 FROM \"ProjectTaskAssignee\" a WHERE a.\"taskId\" = t.id AND"
                    + " a.\"userId\" = ?)");
            args.add(assignee);
        }
        if (blank(q.tagId()) != null) {
            where.append(" AND EXISTS (SELECT 1 FROM \"ProjectTaskTag\" g WHERE g.\"taskId\" = t.id AND g.\"tagId\" = ?)");
            args.add(q.tagId());
        }
        if (blank(q.search()) != null) {
            where.append(" AND (t.title ILIKE '%' || ? || '%' OR t.number ILIKE '%' || ? || '%' OR t.description"
                    + " ILIKE '%' || ? || '%')");
            args.add(q.search().trim());
            args.add(q.search().trim());
            args.add(q.search().trim());
        }
        String state = q.state() == null ? "all" : q.state();
        oneOf(state, Set.of("open", "closed", "all"), "state");
        if ("open".equals(state)) {
            where.append(" AND NOT t.\"isClosed\"");
        } else if ("closed".equals(state)) {
            where.append(" AND t.\"isClosed\"");
        }
        LocalDate from = day(q.deadlineFrom(), "deadlineFrom");
        LocalDate to = day(q.deadlineTo(), "deadlineTo");
        if (from != null) {
            where.append(" AND t.deadline >= ?");
            args.add(sqlDate(from));
        }
        if (to != null) {
            where.append(" AND t.deadline <= ?");
            args.add(sqlDate(to));
        }
        if (Boolean.TRUE.equals(q.overdue())) {
            where.append(" AND NOT t.\"isClosed\" AND t.deadline < CURRENT_DATE");
        }
        long total = pm.jdbc.sql("SELECT count(*)" + TASK_FROM + where).params(args).query(Long.class).single();
        int limit = q.limit() == null ? 500 : Math.max(1, Math.min(q.limit(), 2000));
        int offset = q.offset() == null ? 0 : Math.max(0, q.offset());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Map<String, Object>> rows = SqlRows.list(pm.jdbc.sql("SELECT " + TASK_COLUMNS + TASK_FROM + where
                + " ORDER BY s.sequence NULLS FIRST, t.sequence, t.\"createdAt\" LIMIT ? OFFSET ?").params(pageArgs));
        decorate(rows);
        return Map.of("data", rows, "total", total);
    }

    /** One task with subtasks, dependencies, checklist, followers, linked record and time summary. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> t = row(id);
        Map<String, Object> project = t.get("projectId") == null ? null : pm.visibleProject((String) t.get("projectId"),
                false);
        decorate(List.of(t));
        t.put("subtasks", decorate(SqlRows.list(pm.jdbc.sql("SELECT " + TASK_COLUMNS + TASK_FROM + " WHERE"
                + " t.\"parentId\" = ? AND t.\"deletedAt\" IS NULL ORDER BY t.sequence, t.\"createdAt\"").param(id))));
        t.put("blockedBy", SqlRows.list(pm.jdbc.sql("SELECT o.id, o.number, o.title, o.\"isClosed\", o.deadline FROM"
                + " \"ProjectTaskDependency\" d JOIN \"ProjectTask\" o ON o.id = d.\"dependsOnId\" WHERE d.\"taskId\" = ?"
                + " AND o.\"deletedAt\" IS NULL ORDER BY o.number").param(id)));
        t.put("blocking", SqlRows.list(pm.jdbc.sql("SELECT o.id, o.number, o.title, o.\"isClosed\", o.deadline FROM"
                + " \"ProjectTaskDependency\" d JOIN \"ProjectTask\" o ON o.id = d.\"taskId\" WHERE d.\"dependsOnId\" = ?"
                + " AND o.\"deletedAt\" IS NULL ORDER BY o.number").param(id)));
        t.put("checklist", SqlRows.list(pm.jdbc.sql("SELECT * FROM \"ProjectTaskChecklistItem\" WHERE \"taskId\" = ?"
                + " ORDER BY sequence, \"createdAt\"").param(id)));
        List<String> followers = pm.jdbc.sql("SELECT \"userId\" FROM \"ProjectTaskFollower\" WHERE \"taskId\" = ?")
                .param(id).query(String.class).list();
        Map<String, String> names = pm.userNames(followers);
        t.put("followers", followers.stream().map(u -> Map.of("id", u, "name", String.valueOf(names.get(u))))
                .toList());
        t.put("isFollowing", followers.contains(me()));
        if (t.get("sourceResType") != null) {
            t.put("source", dms.describe((String) t.get("sourceResType"), (String) t.get("sourceResId")));
        }
        if (t.get("parentId") != null) {
            t.put("parent", SqlRows.one(pm.jdbc.sql("SELECT id, number, title FROM \"ProjectTask\" WHERE id = ?")
                    .param(t.get("parentId"))));
        }
        t.put("myHours", pm.jdbc.sql("SELECT coalesce(sum(hours), 0) FROM \"ProjectTimesheetLine\" WHERE \"taskId\" = ?"
                + " AND \"userId\" = ?").params(id, String.valueOf(me())).query(BigDecimal.class).single());
        t.put("stages", project == null ? List.of() : stages((String) project.get("id")));
        t.put("canEdit", canEdit(t, project));
        t.put("canDelete", canDelete(t, project));
        return t;
    }

    /** The chatter of a task, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> messages(String id) {
        row(id);
        return SqlRows.list(pm.jdbc.sql("SELECT * FROM \"ProjectMessage\" WHERE \"taskId\" = ? ORDER BY \"createdAt\""
                + " DESC, id DESC LIMIT 300").param(id));
    }

    List<Map<String, Object>> stages(String projectId) {
        return SqlRows.list(pm.jdbc.sql("SELECT * FROM \"ProjectTaskStage\" WHERE \"projectId\" = ? ORDER BY sequence,"
                + " \"createdAt\"").param(projectId));
    }

    // ---- create ---------------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> create(TaskInput in) {
        String title = required(in.title(), "title");
        String projectId = blank(in.projectId());
        Map<String, Object> project = null;
        String parentId = blank(in.parentId());
        if (projectId == null && parentId != null) {
            projectId = (String) row(parentId).get("projectId");
        }
        if (projectId != null) {
            project = pm.visibleProject(projectId, true);
            pm.requireEditTasks(project);
            if (Set.of("closed", "cancelled").contains(project.get("status"))) {
                throw ApiException.badRequest("Tasks cannot be added to a " + project.get("status") + " project");
            }
        } else if (!TenantContext.has(Permissions.PROJECT_TASK__WRITE)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.PROJECT_TASK__WRITE);
        }
        String stageId = blank(in.stageId());
        if (project != null) {
            stageId = stageId == null ? firstStage(projectId, "open") : stageOf(projectId, stageId);
        } else if (stageId != null) {
            throw ApiException.badRequest("A personal task has no stages");
        }
        if (parentId != null) {
            Map<String, Object> parent = row(parentId);
            if (!Objects.equals(parent.get("projectId"), projectId)) {
                throw ApiException.badRequest("A subtask must belong to the project of its parent task");
            }
        }
        String priority = in.priority() == null ? "normal" : in.priority();
        oneOf(priority, PmSupport.PRIORITIES, "priority");
        String kanban = in.kanbanState() == null ? "normal" : in.kanbanState();
        oneOf(kanban, PmSupport.KANBAN_STATES, "kanbanState");
        LocalDate start = day(in.plannedStart(), "plannedStart");
        LocalDate deadline = day(in.deadline(), "deadline");
        checkDates(start, deadline);
        String milestoneId = milestone(project, blank(in.milestoneId()));
        String wbsId = projectChild(project, "ProjectWbsElement", blank(in.wbsId()), "WBS element not found on this"
                + " project");
        String siteId = projectChild(project, "ProjectSite", blank(in.siteId()), "Site not found on this project");
        String partnerId = partner(blank(in.partnerId()));
        recurrence(project, in.recurrence(), in.recurrenceInterval());
        LocalDate until = day(in.recurrenceUntil(), "recurrenceUntil");
        String resType = blank(in.sourceResType());
        String resId = blank(in.sourceResId());
        source(resType, resId);
        List<String> assignees = pm.checkUsers(in.assigneeIds());
        List<String> tags = tags(in.tagIds());
        String id = UUID.randomUUID().toString();
        String number = number(project);
        int sequence = project == null ? 1000 : pm.jdbc.sql("SELECT coalesce(max(sequence), 0) + 10 FROM \"ProjectTask\""
                + " WHERE \"projectId\" = ? AND \"stageId\" = ?").params(projectId, stageId).query(Integer.class)
                .single();
        pm.jdbc.sql("INSERT INTO \"ProjectTask\" (id, \"organizationId\", \"projectId\", number, title, description,"
                        + " \"stageId\", \"parentId\", \"milestoneId\", \"wbsId\", \"siteId\", \"partnerId\", priority,"
                        + " \"kanbanState\", \"blockedReason\", \"plannedStart\", deadline, \"plannedHours\", sequence,"
                        + " color, recurrence, \"recurrenceInterval\", \"recurrenceUntil\", \"sourceResType\","
                        + " \"sourceResId\", \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), projectId, number, title, in.description(), stageId, parentId, milestoneId, wbsId,
                        siteId, partnerId, priority, kanban, blank(in.blockedReason()), sqlDate(start), sqlDate(deadline),
                        nz(PmSupport.hours(in.plannedHours(), "plannedHours")), sequence, in.color() == null ? 0
                                : in.color(), blank(in.recurrence()), in.recurrenceInterval() == null ? 1
                                : in.recurrenceInterval(), sqlDate(until), resType, resId, me())
                .update();
        for (String tag : tags) {
            pm.jdbc.sql("INSERT INTO \"ProjectTaskTag\" (\"taskId\", \"tagId\", \"organizationId\") VALUES (?, ?, ?)")
                    .params(id, tag, org()).update();
        }
        if (in.checklist() != null) {
            int seq = 10;
            for (String item : in.checklist()) {
                if (blank(item) != null) {
                    addChecklistRow(id, item.trim(), seq);
                    seq += 10;
                }
            }
            recountChecklist(id);
        }
        if (in.dependsOnIds() != null) {
            for (String dep : new LinkedHashSet<>(in.dependsOnIds())) {
                addDependencyRow(project, id, dep);
            }
        }
        follow(id, me());
        assign(id, project, title, number, List.of(), assignees);
        if (stageKind(stageId).equals("done") || stageKind(stageId).equals("cancelled")) {
            pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"isClosed\" = true, \"closedAt\" = now() WHERE id = ?").param(id)
                    .update();
        }
        pm.message(projectId, id, "system", "Task created", null);
        pm.event(DomainEvents.PROJECT_TASK_CREATED, projectId, id, Map.of("number", number, "title", title));
        return get(id);
    }

    // ---- update ---------------------------------------------------------------------------------------------------------

    /** Partial update: only the keys present change; {@code null} clears an optional field. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        for (String k : body.keySet()) {
            if (!UPDATABLE.contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
        Map<String, Object> t = lockedRow(id);
        Map<String, Object> project = editable(t);
        String projectId = (String) t.get("projectId");
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        List<String> tracked = new ArrayList<>();
        if (body.containsKey("title")) {
            set(sets, args, "title", required(str(body.get("title")), "title"));
        }
        if (body.containsKey("description")) {
            set(sets, args, "description", str(body.get("description")));
        }
        if (body.containsKey("priority")) {
            String v = required(str(body.get("priority")), "priority");
            oneOf(v, PmSupport.PRIORITIES, "priority");
            if (!v.equals(t.get("priority"))) {
                tracked.add("Priority: " + t.get("priority") + " → " + v);
            }
            set(sets, args, "priority", v);
        }
        if (body.containsKey("kanbanState")) {
            String v = required(str(body.get("kanbanState")), "kanbanState");
            oneOf(v, PmSupport.KANBAN_STATES, "kanbanState");
            if (!v.equals(t.get("kanbanState"))) {
                tracked.add("Status: " + label(v) + (body.get("blockedReason") != null && "blocked".equals(v) ? " — "
                        + body.get("blockedReason") : ""));
            }
            set(sets, args, "\"kanbanState\"", v);
        }
        if (body.containsKey("blockedReason")) {
            set(sets, args, "\"blockedReason\"", blank(str(body.get("blockedReason"))));
        }
        LocalDate start = body.containsKey("plannedStart") ? day(str(body.get("plannedStart")), "plannedStart")
                : (LocalDate) t.get("plannedStart");
        LocalDate deadline = body.containsKey("deadline") ? day(str(body.get("deadline")), "deadline")
                : (LocalDate) t.get("deadline");
        checkDates(start, deadline);
        if (body.containsKey("plannedStart")) {
            set(sets, args, "\"plannedStart\"", sqlDate(start));
        }
        if (body.containsKey("deadline")) {
            if (!Objects.equals(deadline, t.get("deadline"))) {
                tracked.add("Deadline: " + (t.get("deadline") == null ? "none" : t.get("deadline")) + " → "
                        + (deadline == null ? "none" : deadline));
            }
            set(sets, args, "deadline", sqlDate(deadline));
            set(sets, args, "\"lastReminderOn\"", null);
        }
        if (body.containsKey("plannedHours")) {
            set(sets, args, "\"plannedHours\"", nz(PmSupport.hours(dec(body.get("plannedHours")), "plannedHours")));
        }
        if (body.containsKey("color")) {
            set(sets, args, "color", body.get("color") == null ? 0 : ((Number) body.get("color")).intValue());
        }
        if (body.containsKey("sequence") && body.get("sequence") != null) {
            set(sets, args, "sequence", ((Number) body.get("sequence")).intValue());
        }
        if (body.containsKey("milestoneId")) {
            String v = milestone(project, blank(str(body.get("milestoneId"))));
            if (!Objects.equals(v, t.get("milestoneId"))) {
                tracked.add("Milestone: " + (v == null ? "none" : milestoneName(v)));
            }
            set(sets, args, "\"milestoneId\"", v);
        }
        if (body.containsKey("wbsId")) {
            set(sets, args, "\"wbsId\"", projectChild(project, "ProjectWbsElement", blank(str(body.get("wbsId"))),
                    "WBS element not found on this project"));
        }
        if (body.containsKey("siteId")) {
            set(sets, args, "\"siteId\"", projectChild(project, "ProjectSite", blank(str(body.get("siteId"))),
                    "Site not found on this project"));
        }
        if (body.containsKey("partnerId")) {
            set(sets, args, "\"partnerId\"", partner(blank(str(body.get("partnerId")))));
        }
        if (body.containsKey("parentId")) {
            String parentId = blank(str(body.get("parentId")));
            if (parentId != null) {
                Map<String, Object> parent = row(parentId);
                if (!Objects.equals(parent.get("projectId"), projectId)) {
                    throw ApiException.badRequest("A subtask must belong to the project of its parent task");
                }
                if (ancestors(parentId).contains(id)) {
                    throw ApiException.badRequest("A task cannot be its own subtask");
                }
            }
            set(sets, args, "\"parentId\"", parentId);
        }
        if (body.containsKey("recurrence") || body.containsKey("recurrenceInterval")) {
            String rec = body.containsKey("recurrence") ? blank(str(body.get("recurrence")))
                    : (String) t.get("recurrence");
            Integer interval = body.containsKey("recurrenceInterval") && body.get("recurrenceInterval") != null
                    ? ((Number) body.get("recurrenceInterval")).intValue() : (Integer) t.get("recurrenceInterval");
            recurrence(project, rec, interval);
            set(sets, args, "recurrence", rec);
            set(sets, args, "\"recurrenceInterval\"", interval == null ? 1 : interval);
        }
        if (body.containsKey("recurrenceUntil")) {
            set(sets, args, "\"recurrenceUntil\"", sqlDate(day(str(body.get("recurrenceUntil")), "recurrenceUntil")));
        }
        if (!sets.isEmpty()) {
            args.add(id);
            pm.jdbc.sql("UPDATE \"ProjectTask\" SET " + String.join(", ", sets) + ", version = version + 1,"
                    + " \"updatedAt\" = now() WHERE id = ?").params(args).update();
        }
        if (body.containsKey("tagIds")) {
            List<String> tags = tags(strings(body.get("tagIds")));
            pm.jdbc.sql("DELETE FROM \"ProjectTaskTag\" WHERE \"taskId\" = ?").param(id).update();
            for (String tag : tags) {
                pm.jdbc.sql("INSERT INTO \"ProjectTaskTag\" (\"taskId\", \"tagId\", \"organizationId\") VALUES (?, ?, ?)")
                        .params(id, tag, org()).update();
            }
        }
        if (body.containsKey("assigneeIds")) {
            List<String> next = pm.checkUsers(strings(body.get("assigneeIds")));
            List<String> before = assignees(id);
            if (!new LinkedHashSet<>(next).equals(new LinkedHashSet<>(before))) {
                pm.jdbc.sql("DELETE FROM \"ProjectTaskAssignee\" WHERE \"taskId\" = ? AND NOT (\"userId\" = ANY(?))")
                        .params(id, next.toArray(String[]::new)).update();
                assign(id, project, (String) t.get("title"), (String) t.get("number"), before, next);
            }
        }
        if (!tracked.isEmpty()) {
            pm.message(projectId, id, "system", String.join("\n", tracked), null);
        }
        if (body.containsKey("stageId")) {
            String stageId = blank(str(body.get("stageId")));
            if (project == null) {
                throw ApiException.badRequest("A personal task has no stages");
            }
            moveToStage(lockedRow(id), project, stageOf(projectId, required(stageId, "stageId")));
        }
        return get(id);
    }

    /** Kanban drag and drop: put the task into {@code stageId} at position {@code index} (0 = top). */
    @Transactional
    public Map<String, Object> move(String id, String stageId, Integer index) {
        Map<String, Object> t = lockedRow(id);
        Map<String, Object> project = editable(t);
        if (project == null) {
            throw ApiException.badRequest("A personal task has no stages");
        }
        String target = stageOf((String) project.get("id"), required(stageId, "stageId"));
        moveToStage(t, project, target);
        List<String> order = new ArrayList<>(pm.jdbc.sql("SELECT id FROM \"ProjectTask\" WHERE \"projectId\" = ? AND"
                + " \"stageId\" = ? AND id <> ? AND \"deletedAt\" IS NULL ORDER BY sequence, \"createdAt\"")
                .params(project.get("id"), target, id).query(String.class).list());
        int at = index == null ? order.size() : Math.max(0, Math.min(index, order.size()));
        order.add(at, id);
        int seq = 10;
        for (String taskId : order) {
            pm.jdbc.sql("UPDATE \"ProjectTask\" SET sequence = ? WHERE id = ?").params(seq, taskId).update();
            seq += 10;
        }
        return get(id);
    }

    /** Mark done (first "done" stage, or closed for a personal task) or reopen (first open stage). */
    @Transactional
    public Map<String, Object> setDone(String id, boolean done) {
        Map<String, Object> t = lockedRow(id);
        Map<String, Object> project = editable(t);
        if (project == null) {
            if (done != Boolean.TRUE.equals(t.get("isClosed"))) {
                if (done) {
                    requireNoOpenDependencies(id);
                }
                pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"isClosed\" = ?, \"closedAt\" = ?, \"updatedAt\" = now(),"
                        + " version = version + 1 WHERE id = ?").params(done, done ? java.sql.Timestamp.from(
                        java.time.Instant.now()) : null, id).update();
                pm.message(null, id, "system", done ? "Marked as done" : "Reopened", null);
                if (done) {
                    completed(lockedRow(id), null);
                }
            }
            return get(id);
        }
        String stage = firstStage((String) project.get("id"), done ? "done" : "open");
        if (stage == null) {
            throw ApiException.badRequest(done ? "The project has no done stage" : "The project has no open stage");
        }
        moveToStage(t, project, stage);
        return get(id);
    }

    /** Several tasks at once: stage, priority, deadline, an extra assignee. */
    @Transactional
    public Map<String, Object> bulk(List<String> taskIds, Map<String, Object> changes, String addAssigneeId) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw ApiException.badRequest("taskIds should not be empty");
        }
        if (taskIds.size() > 200) {
            throw ApiException.badRequest("At most 200 tasks can be changed at once");
        }
        int changed = 0;
        for (String id : new LinkedHashSet<>(taskIds)) {
            Map<String, Object> body = new LinkedHashMap<>(changes);
            if (blank(addAssigneeId) != null) {
                List<String> a = new ArrayList<>(assignees(id));
                if (!a.contains(addAssigneeId)) {
                    a.add(addAssigneeId);
                }
                body.put("assigneeIds", a);
            }
            if (!body.isEmpty()) {
                update(id, body);
                changed++;
            }
        }
        return Map.of("changed", changed);
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> t = lockedRow(id);
        Map<String, Object> project = t.get("projectId") == null ? null : pm.visibleProject((String) t.get("projectId"),
                false);
        if (!canDelete(t, project)) {
            throw ApiException.forbidden("Only the task's creator or a project manager can delete it");
        }
        List<String> ids = pm.jdbc.sql("WITH RECURSIVE tree AS (SELECT id FROM \"ProjectTask\" WHERE id = ? UNION"
                + " SELECT c.id FROM \"ProjectTask\" c JOIN tree ON c.\"parentId\" = tree.id WHERE c.\"deletedAt\" IS NULL)"
                + " SELECT id FROM tree").param(id).query(String.class).list();
        pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"deletedAt\" = now(), \"updatedAt\" = now() WHERE id = ANY(?)")
                .param(ids.toArray(String[]::new)).update();
        pm.jdbc.sql("DELETE FROM \"ProjectTimer\" WHERE \"taskId\" = ANY(?)").param(ids.toArray(String[]::new)).update();
        if (project != null) {
            pm.message((String) project.get("id"), null, "system", "Task " + t.get("number") + " deleted: "
                    + t.get("title"), null);
        }
        return Map.of("id", id, "deleted", ids.size());
    }

    // ---- dependencies, checklist, chatter, followers ---------------------------------------------------------------------

    @Transactional
    public Map<String, Object> addDependency(String id, String dependsOnId) {
        Map<String, Object> t = lockedRow(id);
        Map<String, Object> project = editable(t);
        addDependencyRow(project, id, required(dependsOnId, "dependsOnId"));
        pm.message((String) t.get("projectId"), id, "system", "Blocked by " + row(dependsOnId).get("number"), null);
        return get(id);
    }

    @Transactional
    public Map<String, Object> removeDependency(String id, String dependsOnId) {
        Map<String, Object> t = lockedRow(id);
        editable(t);
        pm.jdbc.sql("DELETE FROM \"ProjectTaskDependency\" WHERE \"taskId\" = ? AND \"dependsOnId\" = ?")
                .params(id, dependsOnId).update();
        return get(id);
    }

    @Transactional
    public Map<String, Object> addChecklistItem(String id, String title) {
        Map<String, Object> t = lockedRow(id);
        editable(t);
        int seq = pm.jdbc.sql("SELECT coalesce(max(sequence), 0) + 10 FROM \"ProjectTaskChecklistItem\" WHERE"
                + " \"taskId\" = ?").param(id).query(Integer.class).single();
        addChecklistRow(id, required(title, "title"), seq);
        recountChecklist(id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> updateChecklistItem(String itemId, String title, Boolean isDone) {
        Map<String, Object> item = checklistItem(itemId);
        String taskId = (String) item.get("taskId");
        editable(lockedRow(taskId));
        if (title != null) {
            pm.jdbc.sql("UPDATE \"ProjectTaskChecklistItem\" SET title = ? WHERE id = ?").params(required(title,
                    "title"), itemId).update();
        }
        if (isDone != null) {
            pm.jdbc.sql("UPDATE \"ProjectTaskChecklistItem\" SET \"isDone\" = ?, \"doneById\" = ?, \"doneAt\" = ? WHERE"
                    + " id = ?").params(isDone, isDone ? me() : null, isDone ? java.sql.Timestamp.from(
                    java.time.Instant.now()) : null, itemId).update();
        }
        recountChecklist(taskId);
        return get(taskId);
    }

    @Transactional
    public Map<String, Object> deleteChecklistItem(String itemId) {
        Map<String, Object> item = checklistItem(itemId);
        String taskId = (String) item.get("taskId");
        editable(lockedRow(taskId));
        pm.jdbc.sql("DELETE FROM \"ProjectTaskChecklistItem\" WHERE id = ?").param(itemId).update();
        recountChecklist(taskId);
        return get(taskId);
    }

    /**
     * Post to the chatter. {@code comment} notifies the followers (and makes the author one), {@code note} is internal
     * and notifies only the mentioned users.
     */
    @Transactional
    public List<Map<String, Object>> postMessage(String id, String kind, String body, List<String> mentions) {
        Map<String, Object> t = row(id);
        Map<String, Object> project = t.get("projectId") == null ? null : pm.visibleProject((String) t.get("projectId"),
                false);
        if (!TenantContext.has(Permissions.PROJECT_TASK__WRITE)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.PROJECT_TASK__WRITE);
        }
        String k = kind == null ? "comment" : kind;
        oneOf(k, Set.of("comment", "note"), "kind");
        String text = required(body, "body");
        if (text.length() > 20000) {
            throw ApiException.badRequest("body must be at most 20000 characters");
        }
        List<String> mentioned = pm.checkUsers(mentions);
        pm.message((String) t.get("projectId"), id, k, text, mentioned);
        String title = t.get("number") + " " + t.get("title");
        String path = path(t);
        String preview = text.length() > 140 ? text.substring(0, 140) + "…" : text;
        Set<String> notify = new LinkedHashSet<>(mentioned);
        if ("comment".equals(k)) {
            follow(id, me());
            notify.addAll(pm.jdbc.sql("SELECT \"userId\" FROM \"ProjectTaskFollower\" WHERE \"taskId\" = ?").param(id)
                    .query(String.class).list());
        }
        pm.notifyUsers(mentioned, "You were mentioned on " + title, preview, path);
        notify.removeAll(mentioned);
        pm.notifyUsers(notify, "New message on " + title, preview, path);
        if (project != null) {
            pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"updatedAt\" = now() WHERE id = ?").param(id).update();
        }
        return messages(id);
    }

    @Transactional
    public Map<String, Object> setFollowing(String id, boolean following) {
        row(id);
        if (following) {
            follow(id, me());
        } else {
            pm.jdbc.sql("DELETE FROM \"ProjectTaskFollower\" WHERE \"taskId\" = ? AND \"userId\" = ?").params(id,
                    String.valueOf(me())).update();
        }
        return Map.of("id", id, "isFollowing", following);
    }

    // ---- internals ------------------------------------------------------------------------------------------------------

    /** Visibility condition for {@code t} / {@code p} (adds its parameters to {@code args}). */
    static String visibility(List<Object> args) {
        String user = String.valueOf(me());
        args.addAll(PmSupport.visibleArgs());
        args.add(user);
        args.add(user);
        return "((t.\"projectId\" IS NOT NULL AND " + PmSupport.visibleSql() + ") OR (t.\"projectId\" IS NULL AND"
                + " (t.\"createdById\" = ? OR EXISTS (SELECT 1 FROM \"ProjectTaskAssignee\" a WHERE a.\"taskId\" = t.id"
                + " AND a.\"userId\" = ?))))";
    }

    /** The visible, live task row (404 otherwise). */
    Map<String, Object> row(String id) {
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.add(org());
        String vis = visibility(args);
        Map<String, Object> t = id == null ? null : SqlRows.one(pm.jdbc.sql("SELECT " + TASK_COLUMNS + TASK_FROM
                + " WHERE t.id = ? AND t.\"organizationId\" = ? AND t.\"deletedAt\" IS NULL AND " + vis).params(args));
        if (t == null) {
            throw ApiException.notFound("Task not found");
        }
        return t;
    }

    Map<String, Object> lockedRow(String id) {
        row(id);
        pm.jdbc.sql("SELECT id FROM \"ProjectTask\" WHERE id = ? FOR UPDATE").param(id).query(String.class).single();
        return row(id);
    }

    /** The project of the task after the edit check (null for a personal task, which only its people may edit). */
    private Map<String, Object> editable(Map<String, Object> t) {
        if (t.get("projectId") == null) {
            if (!TenantContext.has(Permissions.PROJECT_TASK__WRITE)) {
                throw ApiException.forbidden("Missing required permission(s): " + Permissions.PROJECT_TASK__WRITE);
            }
            return null;
        }
        Map<String, Object> project = pm.visibleProject((String) t.get("projectId"), false);
        pm.requireEditTasks(project);
        return project;
    }

    private boolean canEdit(Map<String, Object> t, Map<String, Object> project) {
        return project == null ? TenantContext.has(Permissions.PROJECT_TASK__WRITE) : pm.canEditTasks(project);
    }

    private boolean canDelete(Map<String, Object> t, Map<String, Object> project) {
        if (TenantContext.has(Permissions.PROJECT_TASK__MANAGE)) {
            return true;
        }
        if (project != null && pm.canManage(project)) {
            return true;
        }
        return Objects.equals(t.get("createdById"), me()) && canEdit(t, project);
    }

    private void moveToStage(Map<String, Object> t, Map<String, Object> project, String stageId) {
        String id = (String) t.get("id");
        if (stageId.equals(t.get("stageId"))) {
            return;
        }
        String kind = stageKind(stageId);
        boolean closing = !"open".equals(kind);
        if ("done".equals(kind)) {
            requireNoOpenDependencies(id);
        }
        boolean wasClosed = Boolean.TRUE.equals(t.get("isClosed"));
        pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"stageId\" = ?, \"isClosed\" = ?, \"closedAt\" = CASE WHEN ? THEN"
                + " coalesce(\"closedAt\", now()) ELSE NULL END, \"kanbanState\" = 'normal', \"updatedAt\" = now(),"
                + " version = version + 1 WHERE id = ?").params(stageId, closing, closing, id).update();
        String from = (String) t.get("stageName");
        String to = pm.jdbc.sql("SELECT name FROM \"ProjectTaskStage\" WHERE id = ?").param(stageId).query(String.class)
                .single();
        pm.message((String) project.get("id"), id, "system", "Stage: " + from + " → " + to, null);
        pm.event(DomainEvents.PROJECT_TASK_STAGE_CHANGED, (String) project.get("id"), id, Map.of("stageId", stageId,
                "stage", to, "closed", closing));
        if ("done".equals(kind) && !wasClosed) {
            completed(t, project);
        }
    }

    private void completed(Map<String, Object> t, Map<String, Object> project) {
        String id = (String) t.get("id");
        String projectId = project == null ? null : (String) project.get("id");
        pm.event(DomainEvents.PROJECT_TASK_COMPLETED, projectId, id, Map.of("number", t.get("number")));
        List<String> followers = pm.jdbc.sql("SELECT \"userId\" FROM \"ProjectTaskFollower\" WHERE \"taskId\" = ?")
                .param(id).query(String.class).list();
        pm.notifyUsers(followers, "Task done: " + t.get("number"), String.valueOf(t.get("title")), path(t));
        if (t.get("recurrence") != null) {
            spawnNext(id, project);
        }
    }

    /** Next occurrence of a recurring task; the completed one stops recurring. */
    private void spawnNext(String id, Map<String, Object> project) {
        Map<String, Object> t = SqlRows.one(pm.jdbc.sql("SELECT * FROM \"ProjectTask\" WHERE id = ?").param(id));
        String rec = (String) t.get("recurrence");
        int interval = t.get("recurrenceInterval") == null ? 1 : ((Number) t.get("recurrenceInterval")).intValue();
        LocalDate base = t.get("deadline") != null ? (LocalDate) t.get("deadline") : LocalDate.now(ZoneOffset.UTC);
        LocalDate next = advance(base, rec, interval);
        LocalDate until = (LocalDate) t.get("recurrenceUntil");
        pm.jdbc.sql("UPDATE \"ProjectTask\" SET recurrence = NULL WHERE id = ?").param(id).update();
        if (until != null && next.isAfter(until)) {
            return;
        }
        LocalDate start = t.get("plannedStart") == null ? null : advance((LocalDate) t.get("plannedStart"), rec,
                interval);
        String projectId = (String) t.get("projectId");
        String stage = project == null ? null : firstStage(projectId, "open");
        String newId = UUID.randomUUID().toString();
        String number = number(project);
        pm.jdbc.sql("INSERT INTO \"ProjectTask\" (id, \"organizationId\", \"projectId\", number, title, description,"
                        + " \"stageId\", \"parentId\", \"milestoneId\", \"wbsId\", \"siteId\", \"partnerId\", priority,"
                        + " \"plannedStart\", deadline, \"plannedHours\", color, recurrence, \"recurrenceInterval\","
                        + " \"recurrenceUntil\", \"recurrenceSourceId\", \"sourceResType\", \"sourceResId\","
                        + " \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(newId, org(), projectId, number, t.get("title"), t.get("description"), stage, t.get("parentId"),
                        null, t.get("wbsId"), t.get("siteId"), t.get("partnerId"), t.get("priority"), sqlDate(start),
                        sqlDate(next), t.get("plannedHours"), t.get("color"), rec, interval, sqlDate(until),
                        t.get("recurrenceSourceId") == null ? id : t.get("recurrenceSourceId"), t.get("sourceResType"),
                        t.get("sourceResId"), me())
                .update();
        pm.jdbc.sql("INSERT INTO \"ProjectTaskAssignee\" (\"taskId\", \"userId\", \"organizationId\") SELECT ?,"
                + " \"userId\", \"organizationId\" FROM \"ProjectTaskAssignee\" WHERE \"taskId\" = ?").params(newId, id)
                .update();
        pm.jdbc.sql("INSERT INTO \"ProjectTaskFollower\" (\"taskId\", \"userId\", \"organizationId\") SELECT ?,"
                + " \"userId\", \"organizationId\" FROM \"ProjectTaskFollower\" WHERE \"taskId\" = ?").params(newId, id)
                .update();
        pm.jdbc.sql("INSERT INTO \"ProjectTaskTag\" (\"taskId\", \"tagId\", \"organizationId\") SELECT ?, \"tagId\","
                + " \"organizationId\" FROM \"ProjectTaskTag\" WHERE \"taskId\" = ?").params(newId, id).update();
        pm.jdbc.sql("INSERT INTO \"ProjectTaskChecklistItem\" (id, \"organizationId\", \"taskId\", title, sequence)"
                + " SELECT gen_random_uuid()::text, \"organizationId\", ?, title, sequence FROM"
                + " \"ProjectTaskChecklistItem\" WHERE \"taskId\" = ?").params(newId, id).update();
        recountChecklist(newId);
        pm.message(projectId, newId, "system", "Recurring task created from " + t.get("number"), null);
        pm.message(projectId, id, "system", "Next occurrence: " + number + " due " + next, null);
        pm.event(DomainEvents.PROJECT_TASK_CREATED, projectId, newId, Map.of("number", number, "title",
                t.get("title"), "recurrenceOf", id));
    }

    static LocalDate advance(LocalDate d, String rec, int interval) {
        return switch (rec) {
            case "daily" -> d.plusDays(interval);
            case "weekly" -> d.plusWeeks(interval);
            case "monthly" -> d.plusMonths(interval);
            default -> d.plusYears(interval);
        };
    }

    private void requireNoOpenDependencies(String id) {
        List<String> open = pm.jdbc.sql("SELECT o.number FROM \"ProjectTaskDependency\" d JOIN \"ProjectTask\" o ON"
                + " o.id = d.\"dependsOnId\" WHERE d.\"taskId\" = ? AND NOT o.\"isClosed\" AND o.\"deletedAt\" IS NULL"
                + " ORDER BY o.number").param(id).query(String.class).list();
        if (!open.isEmpty()) {
            throw ApiException.badRequest("This task is blocked by unfinished tasks: " + String.join(", ", open));
        }
    }

    private void assign(String id, Map<String, Object> project, String title, String number, List<String> before,
            List<String> next) {
        List<String> added = next.stream().filter(u -> !before.contains(u)).toList();
        List<String> removed = before.stream().filter(u -> !next.contains(u)).toList();
        for (String u : added) {
            pm.jdbc.sql("INSERT INTO \"ProjectTaskAssignee\" (\"taskId\", \"userId\", \"organizationId\") VALUES (?, ?, ?)"
                    + " ON CONFLICT DO NOTHING").params(id, u, org()).update();
            follow(id, u);
        }
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        String projectId = project == null ? null : (String) project.get("id");
        Map<String, String> names = pm.userNames(new LinkedHashSet<>(next) {
            {
                addAll(removed);
            }
        });
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) {
            parts.add("Assigned to " + added.stream().map(names::get).collect(Collectors.joining(", ")));
        }
        if (!removed.isEmpty()) {
            parts.add("Unassigned " + removed.stream().map(names::get).collect(Collectors.joining(", ")));
        }
        if (!before.isEmpty() || !removed.isEmpty()) {
            pm.message(projectId, id, "system", String.join("\n", parts), null);
        } else {
            pm.message(projectId, id, "system", parts.getFirst(), null);
        }
        String path = projectId == null ? "/projects/my-tasks?task=" + id : "/projects/" + projectId + "?task=" + id;
        pm.notifyUsers(added, "Task assigned: " + number, title, path);
        if (!added.isEmpty()) {
            pm.event(DomainEvents.PROJECT_TASK_ASSIGNED, projectId, id, Map.of("number", number, "assigneeIds", added));
        }
    }

    private void follow(String taskId, String userId) {
        if (userId == null) {
            return;
        }
        pm.jdbc.sql("INSERT INTO \"ProjectTaskFollower\" (\"taskId\", \"userId\", \"organizationId\") VALUES (?, ?, ?) ON"
                + " CONFLICT DO NOTHING").params(taskId, userId, org()).update();
    }

    private List<String> assignees(String taskId) {
        return pm.jdbc.sql("SELECT \"userId\" FROM \"ProjectTaskAssignee\" WHERE \"taskId\" = ? ORDER BY \"assignedAt\"")
                .param(taskId).query(String.class).list();
    }

    private void addDependencyRow(Map<String, Object> project, String id, String dep) {
        if (project != null && !Boolean.TRUE.equals(project.get("allowDependencies"))) {
            throw ApiException.badRequest("Task dependencies are disabled on this project");
        }
        if (id.equals(dep)) {
            throw ApiException.badRequest("A task cannot depend on itself");
        }
        row(dep);
        boolean cycle = pm.jdbc.sql("WITH RECURSIVE r AS (SELECT \"dependsOnId\" AS id FROM \"ProjectTaskDependency\""
                + " WHERE \"taskId\" = ? UNION SELECT d.\"dependsOnId\" FROM \"ProjectTaskDependency\" d JOIN r ON"
                + " d.\"taskId\" = r.id) SELECT count(*) FROM r WHERE id = ?").params(dep, id).query(Long.class)
                .single() > 0;
        if (cycle) {
            throw ApiException.badRequest("This dependency would create a cycle");
        }
        pm.jdbc.sql("INSERT INTO \"ProjectTaskDependency\" (\"taskId\", \"dependsOnId\", \"organizationId\") VALUES (?,"
                + " ?, ?) ON CONFLICT DO NOTHING").params(id, dep, org()).update();
    }

    private void addChecklistRow(String taskId, String title, int seq) {
        pm.jdbc.sql("INSERT INTO \"ProjectTaskChecklistItem\" (id, \"organizationId\", \"taskId\", title, sequence)"
                + " VALUES (?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), taskId, title, seq).update();
    }

    private void recountChecklist(String taskId) {
        pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"checklistTotal\" = (SELECT count(*) FROM \"ProjectTaskChecklistItem\""
                + " WHERE \"taskId\" = :id), \"checklistDone\" = (SELECT count(*) FROM \"ProjectTaskChecklistItem\" WHERE"
                + " \"taskId\" = :id AND \"isDone\") WHERE id = :id").param("id", taskId).update();
    }

    private Map<String, Object> checklistItem(String itemId) {
        Map<String, Object> item = SqlRows.one(pm.jdbc.sql("SELECT * FROM \"ProjectTaskChecklistItem\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(itemId, org()));
        if (item == null) {
            throw ApiException.notFound("Checklist item not found");
        }
        row((String) item.get("taskId"));
        return item;
    }

    private Set<String> ancestors(String taskId) {
        return new LinkedHashSet<>(pm.jdbc.sql("WITH RECURSIVE up AS (SELECT id, \"parentId\" FROM \"ProjectTask\" WHERE"
                + " id = ? UNION SELECT t.id, t.\"parentId\" FROM \"ProjectTask\" t JOIN up ON t.id = up.\"parentId\")"
                + " SELECT id FROM up").param(taskId).query(String.class).list());
    }

    String number(Map<String, Object> project) {
        if (project == null) {
            return "TODO-" + pm.nextCounter("todo");
        }
        int n = pm.jdbc.sql("UPDATE \"Project\" SET \"taskSeq\" = \"taskSeq\" + 1 WHERE id = ? RETURNING \"taskSeq\"")
                .param(project.get("id")).query(Integer.class).single();
        return project.get("code") + "-" + n;
    }

    String firstStage(String projectId, String kind) {
        return pm.jdbc.sql("SELECT id FROM \"ProjectTaskStage\" WHERE \"projectId\" = ? AND kind = ? ORDER BY sequence,"
                + " \"createdAt\" LIMIT 1").params(projectId, kind).query(String.class).optional().orElse(null);
    }

    private String stageOf(String projectId, String stageId) {
        if (pm.jdbc.sql("SELECT count(*) FROM \"ProjectTaskStage\" WHERE id = ? AND \"projectId\" = ?").params(stageId,
                projectId).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Stage not found on this project");
        }
        return stageId;
    }

    private String stageKind(String stageId) {
        return stageId == null ? "open" : pm.jdbc.sql("SELECT kind FROM \"ProjectTaskStage\" WHERE id = ?")
                .param(stageId).query(String.class).single();
    }

    private String milestone(Map<String, Object> project, String milestoneId) {
        if (milestoneId == null) {
            return null;
        }
        if (project == null || pm.jdbc.sql("SELECT count(*) FROM \"ProjectMilestone\" WHERE id = ? AND \"projectId\" = ?")
                .params(milestoneId, project.get("id")).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Milestone not found on this project");
        }
        return milestoneId;
    }

    private String milestoneName(String id) {
        return pm.jdbc.sql("SELECT name FROM \"ProjectMilestone\" WHERE id = ?").param(id).query(String.class).single();
    }

    private String projectChild(Map<String, Object> project, String table, String id, String message) {
        if (id == null) {
            return null;
        }
        if (project == null || pm.jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"projectId\" = ?")
                .params(id, project.get("id")).query(Long.class).single() == 0) {
            throw ApiException.badRequest(message);
        }
        return id;
    }

    private String partner(String partnerId) {
        if (partnerId != null && pm.jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(partnerId, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Customer not found");
        }
        return partnerId;
    }

    private void source(String resType, String resId) {
        if ((resType == null) != (resId == null)) {
            throw ApiException.badRequest("sourceResType and sourceResId go together");
        }
        if (resType != null && dms.describe(resType, resId) == null) {
            throw ApiException.badRequest("Linked record not found");
        }
    }

    private void recurrence(Map<String, Object> project, String rec, Integer interval) {
        if (rec == null || rec.isBlank()) {
            return;
        }
        oneOf(rec, PmSupport.RECURRENCES, "recurrence");
        if (project != null && !Boolean.TRUE.equals(project.get("allowRecurring"))) {
            throw ApiException.badRequest("Recurring tasks are disabled on this project");
        }
        if (interval != null && (interval < 1 || interval > 366)) {
            throw ApiException.badRequest("recurrenceInterval must be between 1 and 366");
        }
    }

    private List<String> tags(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        List<String> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (!clean.isEmpty() && pm.jdbc.sql("SELECT count(*) FROM \"ProjectTag\" WHERE \"organizationId\" = ? AND id ="
                + " ANY(?)").params(org(), clean.toArray(String[]::new)).query(Long.class).single() != clean.size()) {
            throw ApiException.badRequest("Tag not found");
        }
        return clean;
    }

    private static void checkDates(LocalDate start, LocalDate deadline) {
        if (start != null && deadline != null && deadline.isBefore(start)) {
            throw ApiException.badRequest("deadline must not be before plannedStart");
        }
    }

    /** Assignees, tags, progress and overdue flag for each row. */
    List<Map<String, Object>> decorate(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        String[] ids = rows.stream().map(r -> (String) r.get("id")).toArray(String[]::new);
        Map<String, List<String>> assignees = new HashMap<>();
        pm.jdbc.sql("SELECT \"taskId\", \"userId\" FROM \"ProjectTaskAssignee\" WHERE \"taskId\" = ANY(?) ORDER BY"
                + " \"assignedAt\"").param(ids).query((rs, n) -> assignees.computeIfAbsent(rs.getString(1),
                        k -> new ArrayList<>()).add(rs.getString(2))).list();
        Map<String, String> names = pm.userNames(assignees.values().stream().flatMap(Collection::stream).toList());
        Map<String, List<Map<String, Object>>> tags = new HashMap<>();
        pm.jdbc.sql("SELECT g.\"taskId\", t.id, t.name, t.color FROM \"ProjectTaskTag\" g JOIN \"ProjectTag\" t ON"
                + " t.id = g.\"tagId\" WHERE g.\"taskId\" = ANY(?) ORDER BY t.name").param(ids).query((rs, n) ->
                tags.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(Map.of("id", rs.getString(2), "name",
                        rs.getString(3), "color", rs.getInt(4)))).list();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (Map<String, Object> r : rows) {
            String id = (String) r.get("id");
            r.put("assignees", assignees.getOrDefault(id, List.of()).stream().map(u -> Map.of("id", u, "name",
                    String.valueOf(names.get(u)))).toList());
            r.put("tags", tags.getOrDefault(id, List.of()));
            BigDecimal planned = SqlRows.dec(r, "plannedHours");
            BigDecimal effective = SqlRows.dec(r, "effectiveHours");
            r.put("progress", planned.signum() == 0 ? null : effective.multiply(BigDecimal.valueOf(100))
                    .divide(planned, 0, RoundingMode.HALF_UP).intValue());
            r.put("overdue", !Boolean.TRUE.equals(r.get("isClosed")) && r.get("deadline") instanceof LocalDate d
                    && d.isBefore(today));
        }
        return rows;
    }

    static String path(Map<String, Object> t) {
        return t.get("projectId") == null ? "/projects/my-tasks?task=" + t.get("id")
                : "/projects/" + t.get("projectId") + "?task=" + t.get("id");
    }

    private static String label(String kanbanState) {
        return switch (kanbanState) {
            case "blocked" -> "Blocked";
            case "ready" -> "Ready for next stage";
            default -> "In progress";
        };
    }

    private static void eq(StringBuilder where, List<Object> args, String column, String value) {
        if (blank(value) != null) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private static void set(List<String> sets, List<Object> args, String column, Object value) {
        sets.add(column + " = ?");
        args.add(value);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static String str(Object v) {
        return v == null ? null : v.toString();
    }

    static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("plannedHours must be a number");
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object v) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw ApiException.badRequest("Expected an array of ids");
        }
        return ((List<Object>) list).stream().map(o -> o == null ? null : o.toString()).toList();
    }
}
