package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.PmSupport.blank;
import static com.inventoryerp.projects.application.PmSupport.day;
import static com.inventoryerp.projects.application.PmSupport.me;
import static com.inventoryerp.projects.application.PmSupport.oneOf;
import static com.inventoryerp.projects.application.PmSupport.org;
import static com.inventoryerp.projects.application.PmSupport.required;
import static com.inventoryerp.projects.application.PmSupport.sqlDate;

import com.inventoryerp.dms.spi.DmsAttachments;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.projects.application.ProjectService.ProjectInput;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project workspace (ADR-S062): the projects board, project settings (manager, members, privacy, features), task
 * stages, milestones, status updates, project chatter, templates and duplication, projects created from sales orders,
 * tags, and the overview that brings together tasks, time, milestones and — for holders of {@code project:read} — the
 * job-costing figures of ADR-S038 (budget, actual, committed, EAC) and linked procurement.
 */
@Service
public class ProjectWorkspaceService {

    /** Body of {@code POST /projects/workspace}. */
    public record WorkspaceInput(String code, String name, String description, String customerId, String managerId,
            List<String> memberIds, String privacy, Integer color, String startDate, String endDate,
            BigDecimal plannedHours, Boolean allowTimesheets, Boolean allowMilestones, Boolean allowDependencies,
            Boolean allowRecurring, Boolean isTemplate, String templateId, String branchId, BigDecimal contractValue,
            String status) {}

    public record MemberInput(String userId, String role) {}

    static final Set<String> SETTINGS = Set.of("name", "description", "managerId", "privacy", "color", "startDate",
            "endDate", "plannedHours", "customerId", "allowTimesheets", "allowMilestones", "allowDependencies",
            "allowRecurring", "isTemplate");

    static final List<Object[]> DEFAULT_STAGES = List.of(new Object[] {"To Do", 10, "open", false},
            new Object[] {"In Progress", 20, "open", false}, new Object[] {"Review", 30, "open", false},
            new Object[] {"Done", 40, "done", false}, new Object[] {"Cancelled", 50, "cancelled", true});

    private final PmSupport pm;
    private final ProjectService projects;
    private final ProjectCostService costs;
    private final ProjectTaskService tasks;
    private final DmsAttachments dms;
    private final AuditService audit;

    public ProjectWorkspaceService(PmSupport pm, ProjectService projects, ProjectCostService costs,
            ProjectTaskService tasks, DmsAttachments dms, AuditService audit) {
        this.pm = pm;
        this.projects = projects;
        this.costs = costs;
        this.tasks = tasks;
        this.dms = dms;
        this.audit = audit;
    }

    // ---- board ----------------------------------------------------------------------------------------------------------

    /** Projects the caller may see with task, time and milestone figures. {@code filter}: all, mine, favorites. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> board(String filter, String status, String search, boolean templates) {
        String f = filter == null ? "all" : filter;
        oneOf(f, Set.of("all", "mine", "favorites"), "filter");
        String user = String.valueOf(me());
        List<Object> args = new ArrayList<>();
        args.add(user);
        args.add(user);
        args.add(org());
        StringBuilder where = new StringBuilder(" WHERE p.\"organizationId\" = ? AND " + PmSupport.visibleSql());
        args.addAll(PmSupport.visibleArgs());
        where.append(" AND p.\"isTemplate\" = ?");
        args.add(templates);
        if (blank(status) != null) {
            if ("open".equals(status)) {
                where.append(" AND p.status NOT IN ('completed', 'closed', 'cancelled')");
            } else {
                where.append(" AND p.status = ?");
                args.add(status);
            }
        }
        if ("mine".equals(f)) {
            where.append(" AND (p.\"managerId\" = ? OR EXISTS (SELECT 1 FROM \"ProjectMember\" m WHERE m.\"projectId\" ="
                    + " p.id AND m.\"userId\" = ?) OR EXISTS (SELECT 1 FROM \"ProjectTask\" t JOIN \"ProjectTaskAssignee\""
                    + " a ON a.\"taskId\" = t.id WHERE t.\"projectId\" = p.id AND a.\"userId\" = ? AND t.\"deletedAt\" IS"
                    + " NULL))");
            args.add(user);
            args.add(user);
            args.add(user);
        } else if ("favorites".equals(f)) {
            where.append(" AND EXISTS (SELECT 1 FROM \"ProjectFavorite\" fv WHERE fv.\"projectId\" = p.id AND"
                    + " fv.\"userId\" = ?)");
            args.add(user);
        }
        if (blank(search) != null) {
            where.append(" AND (p.code ILIKE '%' || ? || '%' OR p.name ILIKE '%' || ? || '%' OR pa.name ILIKE '%' || ?"
                    + " || '%')");
            args.add(search.trim());
            args.add(search.trim());
            args.add(search.trim());
        }
        List<Map<String, Object>> rows = SqlRows.list(pm.jdbc.sql("SELECT p.id, p.code, p.name, p.description, p.status,"
                + " p.privacy, p.color, p.\"managerId\", p.\"customerId\", pa.name AS \"customerName\", p.\"startDate\","
                + " p.\"endDate\", p.\"plannedHours\", p.\"lastUpdateStatus\", p.\"isTemplate\", p.\"allowTimesheets\","
                + " p.\"salesOrderId\", p.\"createdAt\","
                + " EXISTS (SELECT 1 FROM \"ProjectFavorite\" fv WHERE fv.\"projectId\" = p.id AND fv.\"userId\" = ?) AS"
                + " \"isFavorite\","
                + " (SELECT count(*) FROM \"ProjectTask\" t WHERE t.\"projectId\" = p.id AND t.\"deletedAt\" IS NULL) AS"
                + " \"taskCount\","
                + " (SELECT count(*) FROM \"ProjectTask\" t WHERE t.\"projectId\" = p.id AND t.\"deletedAt\" IS NULL AND"
                + " NOT t.\"isClosed\") AS \"openTaskCount\","
                + " (SELECT count(*) FROM \"ProjectTask\" t WHERE t.\"projectId\" = p.id AND t.\"deletedAt\" IS NULL AND"
                + " NOT t.\"isClosed\" AND t.deadline < CURRENT_DATE) AS \"overdueTaskCount\","
                + " (SELECT count(*) FROM \"ProjectTask\" t JOIN \"ProjectTaskAssignee\" a ON a.\"taskId\" = t.id WHERE"
                + " t.\"projectId\" = p.id AND t.\"deletedAt\" IS NULL AND NOT t.\"isClosed\" AND a.\"userId\" = ?) AS"
                + " \"myOpenTaskCount\","
                + " (SELECT coalesce(sum(hours), 0) FROM \"ProjectTimesheetLine\" l WHERE l.\"projectId\" = p.id) AS"
                + " \"effectiveHours\","
                + " (SELECT count(*) FROM \"ProjectMember\" m WHERE m.\"projectId\" = p.id) AS \"memberCount\""
                + " FROM \"Project\" p LEFT JOIN \"Partner\" pa ON pa.id = p.\"customerId\"" + where
                + " ORDER BY \"isFavorite\" DESC, p.\"createdAt\" DESC")
                .params(args));
        Map<String, String> names = pm.userNames(rows.stream().map(r -> (String) r.get("managerId")).toList());
        Map<String, Map<String, Object>> nextMilestone = new HashMap<>();
        if (!rows.isEmpty()) {
            pm.jdbc.sql("SELECT DISTINCT ON (\"projectId\") \"projectId\", id, name, deadline FROM \"ProjectMilestone\""
                    + " WHERE \"projectId\" = ANY(?) AND NOT \"isReached\" ORDER BY \"projectId\", deadline NULLS LAST,"
                    + " sequence").param(rows.stream().map(r -> (String) r.get("id")).toArray(String[]::new))
                    .query((rs, n) -> nextMilestone.put(rs.getString("projectId"), Obj.of("id", rs.getString("id"),
                            "name", rs.getString("name"), "deadline", rs.getDate("deadline") == null ? null
                                    : rs.getDate("deadline").toLocalDate()))).list();
        }
        for (Map<String, Object> r : rows) {
            r.put("managerName", names.get(r.get("managerId")));
            long total = ((Number) r.get("taskCount")).longValue();
            long open = ((Number) r.get("openTaskCount")).longValue();
            r.put("progress", total == 0 ? 0 : (int) Math.round((total - open) * 100.0 / total));
            r.put("nextMilestone", nextMilestone.get(r.get("id")));
        }
        return rows;
    }

    // ---- create / settings ----------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> create(WorkspaceInput in) {
        String name = required(in.name(), "name");
        oneOf(in.privacy(), Set.of("internal", "members"), "privacy");
        String status = in.status() == null ? "active" : in.status();
        oneOf(status, ProjectService.STATUSES, "status");
        Map<String, Object> template = null;
        if (blank(in.templateId()) != null) {
            template = pm.visibleProject(in.templateId(), false);
        }
        String manager = blank(in.managerId()) == null ? me() : pm.checkUsers(List.of(in.managerId())).getFirst();
        List<String> members = pm.checkUsers(in.memberIds());
        String code = blank(in.code()) == null ? nextCode() : in.code().trim();
        String id = (String) projects.create(new ProjectInput(code, name, in.customerId(), null, in.contractValue(),
                in.branchId(), null, in.startDate(), in.endDate(), null)).get("id");
        pm.jdbc.sql("UPDATE \"Project\" SET description = ?, \"managerId\" = ?, privacy = ?, color = ?, \"plannedHours\" = ?,"
                        + " \"allowTimesheets\" = ?, \"allowMilestones\" = ?, \"allowDependencies\" = ?,"
                        + " \"allowRecurring\" = ?, \"isTemplate\" = ?, status = ?, \"updatedAt\" = now() WHERE id = ?")
                .params(in.description(), manager, in.privacy() == null ? "internal" : in.privacy(), in.color() == null
                                ? 0 : in.color(), hours(in.plannedHours()), flag(in.allowTimesheets(), template,
                                "allowTimesheets"), flag(in.allowMilestones(), template, "allowMilestones"),
                        flag(in.allowDependencies(), template, "allowDependencies"), flag(in.allowRecurring(), template,
                                "allowRecurring"), Boolean.TRUE.equals(in.isTemplate()), status, id)
                .update();
        addMember(id, manager, "manager");
        for (String m : members) {
            addMember(id, m, "member");
        }
        if (template != null) {
            LocalDate start = day(in.startDate(), "startDate");
            long shift = start != null && template.get("startDate") != null ? ChronoUnit.DAYS.between(
                    (LocalDate) template.get("startDate"), start) : 0;
            copyStructure((String) template.get("id"), id, shift, true);
        } else {
            defaultStages(id);
        }
        pm.message(id, null, "system", template == null ? "Project created" : "Project created from "
                + template.get("code"), null);
        return workspace(id);
    }

    /** The project with its PM settings, stages, members, milestones and the caller's rights. */
    @Transactional(readOnly = true)
    public Map<String, Object> workspace(String id) {
        Map<String, Object> p = pm.visibleProject(id, false);
        p.remove("taskSeq");
        Map<String, Object> customer = p.get("customerId") == null ? null : SqlRows.one(pm.jdbc.sql("SELECT id, name"
                + " FROM \"Partner\" WHERE id = ?").param(p.get("customerId")));
        p.put("customerName", customer == null ? null : customer.get("name"));
        p.put("managerName", pm.userName((String) p.get("managerId")));
        p.put("stages", tasks.stages(id));
        p.put("members", members(id));
        p.put("milestones", milestonesOf(id));
        p.put("isFavorite", pm.jdbc.sql("SELECT count(*) FROM \"ProjectFavorite\" WHERE \"projectId\" = ? AND"
                + " \"userId\" = ?").params(id, String.valueOf(me())).query(Long.class).single() > 0);
        p.put("myRole", pm.roleOn(p));
        p.put("canEditTasks", pm.canEditTasks(p));
        p.put("canManage", pm.canManage(p));
        p.put("canSeeCosts", TenantContext.has(Permissions.PROJECT__READ));
        p.put("wbs", SqlRows.list(pm.jdbc.sql("SELECT id, code, name FROM \"ProjectWbsElement\" WHERE \"projectId\" = ?"
                + " ORDER BY code").param(id)));
        p.put("sites", SqlRows.list(pm.jdbc.sql("SELECT id, code, name FROM \"ProjectSite\" WHERE \"projectId\" = ?"
                + " ORDER BY code").param(id)));
        if (p.get("salesOrderId") != null) {
            p.put("salesOrder", dms.describe("sales_order", (String) p.get("salesOrderId")));
        }
        return p;
    }

    /** Partial update of the PM settings (project manager or {@code project:manage}). */
    @Transactional
    public Map<String, Object> updateSettings(String id, Map<String, Object> body) {
        for (String k : body.keySet()) {
            if (!SETTINGS.contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
        Map<String, Object> p = pm.visibleProject(id, true);
        pm.requireManage(p);
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (body.containsKey("name")) {
            add(sets, args, "name", required(ProjectTaskService.str(body.get("name")), "name"));
        }
        if (body.containsKey("description")) {
            add(sets, args, "description", ProjectTaskService.str(body.get("description")));
        }
        if (body.containsKey("managerId")) {
            String manager = pm.checkUsers(List.of(required(ProjectTaskService.str(body.get("managerId")),
                    "managerId"))).getFirst();
            add(sets, args, "\"managerId\"", manager);
            addMember(id, manager, "manager");
            if (!manager.equals(p.get("managerId"))) {
                pm.message(id, null, "system", "Project manager: " + pm.userName(manager), null);
                pm.notifyUsers(List.of(manager), "You manage project " + p.get("code"), String.valueOf(p.get("name")),
                        "/projects/" + id);
            }
        }
        if (body.containsKey("privacy")) {
            String v = required(ProjectTaskService.str(body.get("privacy")), "privacy");
            oneOf(v, Set.of("internal", "members"), "privacy");
            add(sets, args, "privacy", v);
        }
        if (body.containsKey("color")) {
            add(sets, args, "color", body.get("color") == null ? 0 : ((Number) body.get("color")).intValue());
        }
        LocalDate start = body.containsKey("startDate") ? day(ProjectTaskService.str(body.get("startDate")),
                "startDate") : (LocalDate) p.get("startDate");
        LocalDate end = body.containsKey("endDate") ? day(ProjectTaskService.str(body.get("endDate")), "endDate")
                : (LocalDate) p.get("endDate");
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.badRequest("endDate must not be before startDate");
        }
        if (body.containsKey("startDate")) {
            add(sets, args, "\"startDate\"", sqlDate(start));
        }
        if (body.containsKey("endDate")) {
            add(sets, args, "\"endDate\"", sqlDate(end));
        }
        if (body.containsKey("plannedHours")) {
            add(sets, args, "\"plannedHours\"", hours(ProjectTaskService.dec(body.get("plannedHours"))));
        }
        if (body.containsKey("customerId")) {
            String c = blank(ProjectTaskService.str(body.get("customerId")));
            if (c != null && pm.jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(c, org()).query(Long.class).single() == 0) {
                throw ApiException.notFound("Customer not found");
            }
            add(sets, args, "\"customerId\"", c);
        }
        for (String flag : List.of("allowTimesheets", "allowMilestones", "allowDependencies", "allowRecurring",
                "isTemplate")) {
            if (body.containsKey(flag)) {
                add(sets, args, "\"" + flag + "\"", Boolean.TRUE.equals(body.get(flag)));
            }
        }
        if (!sets.isEmpty()) {
            args.add(id);
            pm.jdbc.sql("UPDATE \"Project\" SET " + String.join(", ", sets) + ", \"needsReview\" = false, \"updatedAt\""
                    + " = now() WHERE id = ?").params(args).update();
            audit.recordInTx(AuditInput.of("Project", id, "update", null, body));
        }
        return workspace(id);
    }

    @Transactional
    public Map<String, Object> toggleFavorite(String id) {
        pm.visibleProject(id, false);
        int removed = pm.jdbc.sql("DELETE FROM \"ProjectFavorite\" WHERE \"projectId\" = ? AND \"userId\" = ?")
                .params(id, String.valueOf(me())).update();
        if (removed == 0) {
            pm.jdbc.sql("INSERT INTO \"ProjectFavorite\" (\"projectId\", \"userId\", \"organizationId\") VALUES (?, ?, ?)")
                    .params(id, me(), org()).update();
        }
        return Map.of("id", id, "isFavorite", removed == 0);
    }

    @Transactional
    public List<Map<String, Object>> setMembers(String id, List<MemberInput> members) {
        Map<String, Object> p = pm.visibleProject(id, true);
        pm.requireManage(p);
        Map<String, String> wanted = new LinkedHashMap<>();
        for (MemberInput m : members == null ? List.<MemberInput>of() : members) {
            String role = m.role() == null ? "member" : m.role();
            oneOf(role, PmSupport.MEMBER_ROLES, "role");
            wanted.put(required(m.userId(), "userId"), role);
        }
        pm.checkUsers(wanted.keySet());
        if (p.get("managerId") != null) {
            wanted.put((String) p.get("managerId"), "manager");
        }
        List<String> before = pm.jdbc.sql("SELECT \"userId\" FROM \"ProjectMember\" WHERE \"projectId\" = ?").param(id)
                .query(String.class).list();
        pm.jdbc.sql("DELETE FROM \"ProjectMember\" WHERE \"projectId\" = ?").param(id).update();
        for (var e : wanted.entrySet()) {
            addMember(id, e.getKey(), e.getValue());
        }
        List<String> added = wanted.keySet().stream().filter(u -> !before.contains(u)).toList();
        pm.notifyUsers(added, "Added to project " + p.get("code"), String.valueOf(p.get("name")), "/projects/" + id);
        return members(id);
    }

    // ---- stages ---------------------------------------------------------------------------------------------------------

    @Transactional
    public List<Map<String, Object>> addStage(String projectId, String name, String kind, Boolean folded) {
        Map<String, Object> p = pm.visibleProject(projectId, true);
        pm.requireManage(p);
        String k = kind == null ? "open" : kind;
        oneOf(k, PmSupport.STAGE_KINDS, "kind");
        int seq = pm.jdbc.sql("SELECT coalesce(max(sequence), 0) + 10 FROM \"ProjectTaskStage\" WHERE \"projectId\" = ?")
                .param(projectId).query(Integer.class).single();
        pm.jdbc.sql("INSERT INTO \"ProjectTaskStage\" (id, \"organizationId\", \"projectId\", name, sequence, kind, folded)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), projectId,
                required(name, "name"), seq, k, Boolean.TRUE.equals(folded)).update();
        return tasks.stages(projectId);
    }

    @Transactional
    public List<Map<String, Object>> updateStage(String stageId, String name, String kind, Boolean folded) {
        Map<String, Object> s = stage(stageId);
        String projectId = (String) s.get("projectId");
        pm.requireManage(pm.visibleProject(projectId, true));
        oneOf(kind, PmSupport.STAGE_KINDS, "kind");
        if (kind != null && !kind.equals(s.get("kind"))) {
            if ("open".equals(s.get("kind")) && openStages(projectId) <= 1) {
                throw ApiException.badRequest("A project needs at least one open stage");
            }
            boolean closing = !"open".equals(kind);
            pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"isClosed\" = ?, \"closedAt\" = CASE WHEN ? THEN coalesce(\"closedAt\","
                    + " now()) ELSE NULL END WHERE \"stageId\" = ? AND \"deletedAt\" IS NULL").params(closing, closing,
                    stageId).update();
        }
        pm.jdbc.sql("UPDATE \"ProjectTaskStage\" SET name = coalesce(?, name), kind = coalesce(?, kind), folded ="
                + " coalesce(?, folded) WHERE id = ?").params(blank(name), kind, folded, stageId).update();
        return tasks.stages(projectId);
    }

    @Transactional
    public List<Map<String, Object>> deleteStage(String stageId) {
        Map<String, Object> s = stage(stageId);
        String projectId = (String) s.get("projectId");
        pm.requireManage(pm.visibleProject(projectId, true));
        if (pm.jdbc.sql("SELECT count(*) FROM \"ProjectTask\" WHERE \"stageId\" = ? AND \"deletedAt\" IS NULL")
                .param(stageId).query(Long.class).single() > 0) {
            throw ApiException.badRequest("Move the tasks out of this stage first");
        }
        if ("open".equals(s.get("kind")) && openStages(projectId) <= 1) {
            throw ApiException.badRequest("A project needs at least one open stage");
        }
        pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"stageId\" = NULL WHERE \"stageId\" = ?").param(stageId).update();
        pm.jdbc.sql("DELETE FROM \"ProjectTaskStage\" WHERE id = ?").param(stageId).update();
        return tasks.stages(projectId);
    }

    @Transactional
    public List<Map<String, Object>> reorderStages(String projectId, List<String> stageIds) {
        pm.requireManage(pm.visibleProject(projectId, true));
        List<String> current = pm.jdbc.sql("SELECT id FROM \"ProjectTaskStage\" WHERE \"projectId\" = ?").param(projectId)
                .query(String.class).list();
        if (stageIds == null || !new LinkedHashSet<>(stageIds).equals(new LinkedHashSet<>(current))) {
            throw ApiException.badRequest("stageIds must list every stage of the project exactly once");
        }
        int seq = 10;
        for (String s : stageIds) {
            pm.jdbc.sql("UPDATE \"ProjectTaskStage\" SET sequence = ? WHERE id = ?").params(seq, s).update();
            seq += 10;
        }
        return tasks.stages(projectId);
    }

    // ---- milestones -----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> milestones(String projectId) {
        pm.visibleProject(projectId, false);
        return milestonesOf(projectId);
    }

    @Transactional
    public List<Map<String, Object>> addMilestone(String projectId, String name, String deadline, String description) {
        Map<String, Object> p = pm.visibleProject(projectId, true);
        pm.requireManage(p);
        if (!Boolean.TRUE.equals(p.get("allowMilestones"))) {
            throw ApiException.badRequest("Milestones are disabled on this project");
        }
        int seq = pm.jdbc.sql("SELECT coalesce(max(sequence), 0) + 10 FROM \"ProjectMilestone\" WHERE \"projectId\" = ?")
                .param(projectId).query(Integer.class).single();
        pm.jdbc.sql("INSERT INTO \"ProjectMilestone\" (id, \"organizationId\", \"projectId\", name, deadline, description,"
                + " sequence) VALUES (?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), projectId,
                required(name, "name"), sqlDate(day(deadline, "deadline")), description, seq).update();
        return milestonesOf(projectId);
    }

    @Transactional
    public List<Map<String, Object>> updateMilestone(String milestoneId, Map<String, Object> body) {
        Map<String, Object> m = milestone(milestoneId);
        String projectId = (String) m.get("projectId");
        Map<String, Object> p = pm.visibleProject(projectId, true);
        for (String k : body.keySet()) {
            if (!Set.of("name", "deadline", "description", "isReached").contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
        boolean onlyReach = body.keySet().equals(Set.of("isReached"));
        if (onlyReach) {
            pm.requireEditTasks(p);
        } else {
            pm.requireManage(p);
        }
        if (body.containsKey("name")) {
            pm.jdbc.sql("UPDATE \"ProjectMilestone\" SET name = ? WHERE id = ?").params(required(ProjectTaskService.str(
                    body.get("name")), "name"), milestoneId).update();
        }
        if (body.containsKey("deadline")) {
            pm.jdbc.sql("UPDATE \"ProjectMilestone\" SET deadline = ? WHERE id = ?").params(sqlDate(day(
                    ProjectTaskService.str(body.get("deadline")), "deadline")), milestoneId).update();
        }
        if (body.containsKey("description")) {
            pm.jdbc.sql("UPDATE \"ProjectMilestone\" SET description = ? WHERE id = ?").params(ProjectTaskService.str(
                    body.get("description")), milestoneId).update();
        }
        if (body.containsKey("isReached")) {
            boolean reached = Boolean.TRUE.equals(body.get("isReached"));
            if (reached != Boolean.TRUE.equals(m.get("isReached"))) {
                pm.jdbc.sql("UPDATE \"ProjectMilestone\" SET \"isReached\" = ?, \"reachedAt\" = ?, \"reachedById\" = ?"
                        + " WHERE id = ?").params(reached, reached ? java.sql.Timestamp.from(java.time.Instant.now())
                        : null, reached ? me() : null, milestoneId).update();
                pm.message(projectId, null, "system", (reached ? "Milestone reached: " : "Milestone reopened: ")
                        + m.get("name"), null);
                if (reached) {
                    pm.event(DomainEvents.PROJECT_MILESTONE_REACHED, projectId, null, Map.of("milestoneId", milestoneId,
                            "name", m.get("name")));
                    pm.notifyUsers(memberIds(projectId), "Milestone reached: " + m.get("name"), String.valueOf(
                            p.get("name")), "/projects/" + projectId);
                }
            }
        }
        pm.jdbc.sql("UPDATE \"ProjectMilestone\" SET \"updatedAt\" = now() WHERE id = ?").param(milestoneId).update();
        return milestonesOf(projectId);
    }

    @Transactional
    public List<Map<String, Object>> deleteMilestone(String milestoneId) {
        Map<String, Object> m = milestone(milestoneId);
        String projectId = (String) m.get("projectId");
        pm.requireManage(pm.visibleProject(projectId, true));
        pm.jdbc.sql("DELETE FROM \"ProjectMilestone\" WHERE id = ?").param(milestoneId).update();
        return milestonesOf(projectId);
    }

    // ---- status updates, chatter ----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> updates(String projectId) {
        pm.visibleProject(projectId, false);
        List<Map<String, Object>> rows = SqlRows.list(pm.jdbc.sql("SELECT * FROM \"ProjectUpdate\" WHERE \"projectId\" = ?"
                + " ORDER BY \"createdAt\" DESC LIMIT 100").param(projectId));
        Map<String, String> names = pm.userNames(rows.stream().map(r -> (String) r.get("authorId")).toList());
        rows.forEach(r -> r.put("authorName", names.get(r.get("authorId"))));
        return rows;
    }

    @Transactional
    public List<Map<String, Object>> postUpdate(String projectId, String status, Integer progress, String summary) {
        Map<String, Object> p = pm.visibleProject(projectId, true);
        pm.requireManage(p);
        String s = required(status, "status");
        oneOf(s, PmSupport.UPDATE_STATUSES, "status");
        Map<String, Object> stats = taskStats(projectId);
        int pct = progress != null ? progress : ((Number) stats.get("progress")).intValue();
        if (pct < 0 || pct > 100) {
            throw ApiException.badRequest("progress must be between 0 and 100");
        }
        pm.jdbc.sql("INSERT INTO \"ProjectUpdate\" (id, \"organizationId\", \"projectId\", status, progress, summary,"
                + " stats, \"authorId\") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)").params(UUID.randomUUID().toString(),
                org(), projectId, s, pct, summary, EntityJson.toJson(stats), me()).update();
        pm.jdbc.sql("UPDATE \"Project\" SET \"lastUpdateStatus\" = ?, \"updatedAt\" = now() WHERE id = ?").params(s,
                projectId).update();
        pm.message(projectId, null, "system", "Status update: " + s.replace('_', ' ') + " (" + pct + "%)", null);
        pm.event(DomainEvents.PROJECT_UPDATE_POSTED, projectId, null, Map.of("status", s, "progress", pct));
        pm.notifyUsers(memberIds(projectId), "Project update: " + p.get("code") + " is " + s.replace('_', ' '),
                summary == null ? String.valueOf(p.get("name")) : summary, "/projects/" + projectId + "?tab=updates");
        return updates(projectId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> messages(String projectId, boolean includeTasks) {
        pm.visibleProject(projectId, false);
        return SqlRows.list(pm.jdbc.sql("SELECT m.*, t.number AS \"taskNumber\", t.title AS \"taskTitle\" FROM"
                + " \"ProjectMessage\" m LEFT JOIN \"ProjectTask\" t ON t.id = m.\"taskId\" WHERE m.\"projectId\" = ?"
                + (includeTasks ? " AND (t.id IS NULL OR t.\"deletedAt\" IS NULL)" : " AND m.\"taskId\" IS NULL")
                + " ORDER BY m.\"createdAt\" DESC, m.id DESC LIMIT 200").param(projectId));
    }

    @Transactional
    public List<Map<String, Object>> postMessage(String projectId, String kind, String body, List<String> mentions) {
        Map<String, Object> p = pm.visibleProject(projectId, false);
        pm.requireEditTasks(p);
        String k = kind == null ? "comment" : kind;
        oneOf(k, Set.of("comment", "note"), "kind");
        String text = required(body, "body");
        List<String> mentioned = pm.checkUsers(mentions);
        pm.message(projectId, null, k, text, mentioned);
        String preview = text.length() > 140 ? text.substring(0, 140) + "…" : text;
        pm.notifyUsers(mentioned, "You were mentioned on project " + p.get("code"), preview, "/projects/" + projectId
                + "?tab=discussion");
        if ("comment".equals(k)) {
            List<String> others = new ArrayList<>(memberIds(projectId));
            others.removeAll(mentioned);
            pm.notifyUsers(others, "New message on project " + p.get("code"), preview, "/projects/" + projectId
                    + "?tab=discussion");
        }
        return messages(projectId, false);
    }

    // ---- overview -------------------------------------------------------------------------------------------------------

    /** Tasks by stage, time, milestones, latest update, linked records and — with {@code project:read} — costs. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview(String projectId) {
        Map<String, Object> p = pm.visibleProject(projectId, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("tasks", taskStats(projectId));
        out.put("byStage", SqlRows.list(pm.jdbc.sql("SELECT s.id, s.name, s.kind, count(t.id) AS count FROM"
                + " \"ProjectTaskStage\" s LEFT JOIN \"ProjectTask\" t ON t.\"stageId\" = s.id AND t.\"deletedAt\" IS NULL"
                + " WHERE s.\"projectId\" = ? GROUP BY s.id, s.name, s.kind, s.sequence ORDER BY s.sequence")
                .param(projectId)));
        List<Map<String, Object>> byAssignee = SqlRows.list(pm.jdbc.sql("SELECT a.\"userId\", count(*) FILTER (WHERE NOT"
                + " t.\"isClosed\") AS open, count(*) FILTER (WHERE t.\"isClosed\") AS closed, count(*) FILTER (WHERE NOT"
                + " t.\"isClosed\" AND t.deadline < CURRENT_DATE) AS overdue, coalesce(sum(t.\"plannedHours\") FILTER"
                + " (WHERE NOT t.\"isClosed\"), 0) AS \"plannedHours\" FROM \"ProjectTask\" t JOIN \"ProjectTaskAssignee\" a"
                + " ON a.\"taskId\" = t.id WHERE t.\"projectId\" = ? AND t.\"deletedAt\" IS NULL GROUP BY a.\"userId\""
                + " ORDER BY 2 DESC").param(projectId));
        Map<String, String> names = pm.userNames(byAssignee.stream().map(r -> (String) r.get("userId")).toList());
        byAssignee.forEach(r -> r.put("name", names.get(r.get("userId"))));
        out.put("byAssignee", byAssignee);
        BigDecimal effective = pm.jdbc.sql("SELECT coalesce(sum(hours), 0) FROM \"ProjectTimesheetLine\" WHERE"
                + " \"projectId\" = ?").param(projectId).query(BigDecimal.class).single();
        BigDecimal billable = pm.jdbc.sql("SELECT coalesce(sum(hours), 0) FROM \"ProjectTimesheetLine\" WHERE"
                + " \"projectId\" = ? AND \"isBillable\"").param(projectId).query(BigDecimal.class).single();
        BigDecimal taskPlanned = pm.jdbc.sql("SELECT coalesce(sum(\"plannedHours\"), 0) FROM \"ProjectTask\" WHERE"
                + " \"projectId\" = ? AND \"deletedAt\" IS NULL AND \"parentId\" IS NULL").param(projectId)
                .query(BigDecimal.class).single();
        BigDecimal planned = SqlRows.dec(p, "plannedHours").signum() > 0 ? SqlRows.dec(p, "plannedHours") : taskPlanned;
        out.put("hours", Obj.of("planned", planned, "taskPlanned", taskPlanned, "effective", effective, "billable",
                billable, "remaining", planned.subtract(effective), "usedPercent", planned.signum() == 0 ? null
                        : effective.multiply(BigDecimal.valueOf(100)).divide(planned, 0, RoundingMode.HALF_UP)));
        out.put("milestones", milestonesOf(projectId));
        List<Map<String, Object>> latest = SqlRows.list(pm.jdbc.sql("SELECT * FROM \"ProjectUpdate\" WHERE"
                + " \"projectId\" = ? ORDER BY \"createdAt\" DESC LIMIT 1").param(projectId));
        out.put("lastUpdate", latest.isEmpty() ? null : latest.getFirst());
        out.put("upcoming", tasks.decorate(SqlRows.list(pm.jdbc.sql("SELECT " + ProjectTaskService.TASK_COLUMNS
                + ProjectTaskService.TASK_FROM + " WHERE t.\"projectId\" = ? AND t.\"deletedAt\" IS NULL AND NOT"
                + " t.\"isClosed\" AND t.deadline IS NOT NULL ORDER BY t.deadline, t.sequence LIMIT 8")
                .param(projectId))));
        Map<String, Object> links = new LinkedHashMap<>();
        if (p.get("salesOrderId") != null) {
            links.put("salesOrder", dms.describe("sales_order", (String) p.get("salesOrderId")));
        }
        if (p.get("dealId") != null) {
            links.put("deal", dms.describe("crm_deal", (String) p.get("dealId")));
        }
        links.put("purchaseRequests", pm.jdbc.sql("SELECT count(DISTINCT \"purchaseRequestId\") FROM \"PurchaseRequestLine\""
                + " WHERE \"projectId\" = ?").param(projectId).query(Long.class).single());
        links.put("linkedTasks", SqlRows.list(pm.jdbc.sql("SELECT \"sourceResType\" AS \"resType\", count(*) AS count FROM"
                + " \"ProjectTask\" WHERE \"projectId\" = ? AND \"sourceResType\" IS NOT NULL AND \"deletedAt\" IS NULL"
                + " GROUP BY 1 ORDER BY 1").param(projectId)));
        out.put("links", links);
        if (TenantContext.has(Permissions.PROJECT__READ)) {
            Map<String, Object> report = costs.report(projectId);
            Map<String, Object> fin = new LinkedHashMap<>();
            fin.put("contractValue", p.get("contractValue"));
            fin.putAll(castMap(report.get("totals")));
            fin.put("budgetId", report.get("budgetId"));
            out.put("financials", fin);
        }
        return out;
    }

    // ---- duplicate, from sales order ------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> duplicate(String projectId, String name, String code, Boolean asTemplate,
            Boolean includeTasks, String startDate) {
        Map<String, Object> src = pm.visibleProject(projectId, false);
        if (!TenantContext.has(Permissions.PROJECT__MANAGE)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.PROJECT__MANAGE);
        }
        String newCode = blank(code) == null ? nextCode() : code.trim();
        String newName = blank(name) == null ? src.get("name") + " (copy)" : name.trim();
        LocalDate start = day(startDate, "startDate");
        String id = (String) projects.create(new ProjectInput(newCode, newName, (String) src.get("customerId"), null,
                null, (String) src.get("branchId"), (String) src.get("costCenterId"), start == null ? null
                        : start.toString(), null, null)).get("id");
        pm.jdbc.sql("UPDATE \"Project\" SET description = ?, \"managerId\" = ?, privacy = ?, color = ?, \"plannedHours\" = ?,"
                        + " \"allowTimesheets\" = ?, \"allowMilestones\" = ?, \"allowDependencies\" = ?,"
                        + " \"allowRecurring\" = ?, \"isTemplate\" = ?, status = 'active' WHERE id = ?")
                .params(src.get("description"), me(), src.get("privacy"), src.get("color"), src.get("plannedHours"),
                        src.get("allowTimesheets"), src.get("allowMilestones"), src.get("allowDependencies"),
                        src.get("allowRecurring"), Boolean.TRUE.equals(asTemplate), id)
                .update();
        pm.jdbc.sql("INSERT INTO \"ProjectMember\" (\"projectId\", \"userId\", \"organizationId\", role) SELECT ?,"
                + " \"userId\", \"organizationId\", role FROM \"ProjectMember\" WHERE \"projectId\" = ?").params(id,
                projectId).update();
        pm.jdbc.sql("DELETE FROM \"ProjectMember\" WHERE \"projectId\" = ? AND \"userId\" = ?").params(id,
                String.valueOf(me())).update();
        addMember(id, me(), "manager");
        long shift = start != null && src.get("startDate") != null ? ChronoUnit.DAYS.between(
                (LocalDate) src.get("startDate"), start) : 0;
        copyStructure(projectId, id, shift, includeTasks == null || includeTasks);
        pm.message(id, null, "system", "Project copied from " + src.get("code"), null);
        return workspace(id);
    }

    /** A project for a sales order: customer, contract value and reference from the order; optionally a task per line. */
    @Transactional
    public Map<String, Object> fromSalesOrder(String salesOrderId, String name, Boolean taskPerLine, String managerId,
            String templateId) {
        if (!TenantContext.has(Permissions.PROJECT__MANAGE)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.PROJECT__MANAGE);
        }
        Map<String, Object> so = SqlRows.one(pm.jdbc.sql("SELECT o.*, pa.name AS \"partnerName\" FROM \"SalesOrder\" o"
                + " LEFT JOIN \"Partner\" pa ON pa.id = o.\"partnerId\" WHERE o.id = ? AND o.\"organizationId\" = ? AND"
                + " o.\"deletedAt\" IS NULL").params(required(salesOrderId, "salesOrderId"), org()));
        if (so == null) {
            throw ApiException.notFound("Sales order not found");
        }
        if ("cancelled".equals(so.get("status"))) {
            throw ApiException.badRequest("A cancelled sales order cannot start a project");
        }
        String existing = pm.jdbc.sql("SELECT code FROM \"Project\" WHERE \"organizationId\" = ? AND \"salesOrderId\" = ?"
                + " LIMIT 1").params(org(), salesOrderId).query(String.class).optional().orElse(null);
        if (existing != null) {
            throw ApiException.conflict("Project " + existing + " already exists for sales order " + so.get("number"));
        }
        String projectName = blank(name) != null ? name.trim() : so.get("number") + (so.get("partnerName") == null ? ""
                : " — " + so.get("partnerName"));
        Map<String, Object> created = create(new WorkspaceInput(null, projectName, (String) so.get("notes"),
                (String) so.get("partnerId"), managerId, null, null, null, null, null, null, null, null, null, null,
                false, templateId, (String) so.get("branchId"), SqlRows.dec(so, "total").setScale(2,
                        RoundingMode.HALF_UP), "active"));
        String id = (String) created.get("id");
        pm.jdbc.sql("UPDATE \"Project\" SET \"salesOrderId\" = ?, \"dealId\" = ?, \"contractRef\" = ? WHERE id = ?")
                .params(salesOrderId, so.get("dealId"), so.get("number"), id).update();
        if (taskPerLine == null || taskPerLine) {
            for (Map<String, Object> l : SqlRows.list(pm.jdbc.sql("SELECT description, quantity FROM \"SalesOrderLine\""
                    + " WHERE \"orderId\" = ? ORDER BY \"sortOrder\", id").param(salesOrderId))) {
                BigDecimal qty = SqlRows.dec(l, "quantity").stripTrailingZeros();
                tasks.create(new ProjectTaskService.TaskInput(id, l.get("description") + (qty.compareTo(BigDecimal.ONE)
                        == 0 ? "" : " × " + qty.toPlainString()), null, null, null, null, null, null, null, null,
                        null, null, null, null, (String) so.get("partnerId"), null, null, null, null, null,
                        "sales_order", salesOrderId, null, null, null));
            }
        }
        pm.message(id, null, "system", "Created from sales order " + so.get("number"), null);
        return workspace(id);
    }

    // ---- people, tags ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> assignableUsers(String search) {
        return SqlRows.list(pm.jdbc.sql("SELECT id, trim(coalesce(\"firstName\", '') || ' ' || coalesce(\"lastName\", ''))"
                + " AS name, email FROM \"User\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\""
                + " AND (?::text IS NULL OR \"firstName\" ILIKE '%' || ? || '%' OR \"lastName\" ILIKE '%' || ? || '%' OR"
                + " email ILIKE '%' || ? || '%') ORDER BY \"firstName\", \"lastName\" LIMIT 200").params(org(),
                blank(search), blank(search), blank(search), blank(search)));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> tags() {
        return SqlRows.list(pm.jdbc.sql("SELECT g.*, (SELECT count(*) FROM \"ProjectTaskTag\" x JOIN \"ProjectTask\" t ON"
                + " t.id = x.\"taskId\" WHERE x.\"tagId\" = g.id AND t.\"deletedAt\" IS NULL) AS \"taskCount\" FROM"
                + " \"ProjectTag\" g WHERE g.\"organizationId\" = ? ORDER BY lower(g.name)").param(org()));
    }

    @Transactional
    public Map<String, Object> createTag(String name, Integer color) {
        String n = required(name, "name");
        if (n.length() > 60) {
            throw ApiException.badRequest("name must be at most 60 characters");
        }
        if (pm.jdbc.sql("SELECT count(*) FROM \"ProjectTag\" WHERE \"organizationId\" = ? AND lower(name) = lower(?)")
                .params(org(), n).query(Long.class).single() > 0) {
            throw ApiException.conflict("A tag named " + n + " already exists");
        }
        String id = UUID.randomUUID().toString();
        pm.jdbc.sql("INSERT INTO \"ProjectTag\" (id, \"organizationId\", name, color) VALUES (?, ?, ?, ?)").params(id,
                org(), n, color == null ? 0 : color).update();
        return SqlRows.one(pm.jdbc.sql("SELECT * FROM \"ProjectTag\" WHERE id = ?").param(id));
    }

    @Transactional
    public Map<String, Object> updateTag(String id, String name, Integer color) {
        tag(id);
        if (blank(name) != null && pm.jdbc.sql("SELECT count(*) FROM \"ProjectTag\" WHERE \"organizationId\" = ? AND"
                + " lower(name) = lower(?) AND id <> ?").params(org(), name.trim(), id).query(Long.class).single() > 0) {
            throw ApiException.conflict("A tag named " + name.trim() + " already exists");
        }
        pm.jdbc.sql("UPDATE \"ProjectTag\" SET name = coalesce(?, name), color = coalesce(?, color) WHERE id = ?")
                .params(blank(name), color, id).update();
        return SqlRows.one(pm.jdbc.sql("SELECT * FROM \"ProjectTag\" WHERE id = ?").param(id));
    }

    @Transactional
    public Map<String, Object> deleteTag(String id) {
        tag(id);
        pm.jdbc.sql("DELETE FROM \"ProjectTag\" WHERE id = ?").param(id).update();
        return Map.of("id", id, "deleted", true);
    }

    // ---- internals ------------------------------------------------------------------------------------------------------

    Map<String, Object> taskStats(String projectId) {
        Map<String, Object> s = SqlRows.one(pm.jdbc.sql("SELECT count(*) AS total, count(*) FILTER (WHERE NOT"
                + " \"isClosed\") AS open, count(*) FILTER (WHERE \"isClosed\") AS closed, count(*) FILTER (WHERE NOT"
                + " \"isClosed\" AND deadline < CURRENT_DATE) AS overdue, count(*) FILTER (WHERE \"kanbanState\" ="
                + " 'blocked' AND NOT \"isClosed\") AS blocked, count(*) FILTER (WHERE NOT \"isClosed\" AND NOT EXISTS"
                + " (SELECT 1 FROM \"ProjectTaskAssignee\" a WHERE a.\"taskId\" = t.id)) AS unassigned FROM \"ProjectTask\" t"
                + " WHERE \"projectId\" = ? AND \"deletedAt\" IS NULL").param(projectId));
        long total = ((Number) s.get("total")).longValue();
        long closed = ((Number) s.get("closed")).longValue();
        s.put("progress", total == 0 ? 0 : (int) Math.round(closed * 100.0 / total));
        return s;
    }

    private List<Map<String, Object>> members(String projectId) {
        List<Map<String, Object>> rows = SqlRows.list(pm.jdbc.sql("SELECT m.\"userId\", m.role, u.email,"
                + " trim(coalesce(u.\"firstName\", '') || ' ' || coalesce(u.\"lastName\", '')) AS name FROM"
                + " \"ProjectMember\" m LEFT JOIN \"User\" u ON u.id = m.\"userId\" WHERE m.\"projectId\" = ? ORDER BY"
                + " CASE m.role WHEN 'manager' THEN 0 WHEN 'member' THEN 1 ELSE 2 END, name").param(projectId));
        return rows;
    }

    private List<String> memberIds(String projectId) {
        return pm.jdbc.sql("SELECT \"userId\" FROM \"ProjectMember\" WHERE \"projectId\" = ? UNION SELECT \"managerId\""
                + " FROM \"Project\" WHERE id = ? AND \"managerId\" IS NOT NULL").params(projectId, projectId)
                .query(String.class).list();
    }

    private List<Map<String, Object>> milestonesOf(String projectId) {
        return SqlRows.list(pm.jdbc.sql("SELECT m.*, (SELECT count(*) FROM \"ProjectTask\" t WHERE t.\"milestoneId\" = m.id"
                + " AND t.\"deletedAt\" IS NULL) AS \"taskCount\", (SELECT count(*) FROM \"ProjectTask\" t WHERE"
                + " t.\"milestoneId\" = m.id AND t.\"deletedAt\" IS NULL AND t.\"isClosed\") AS \"doneCount\", NOT"
                + " m.\"isReached\" AND m.deadline < CURRENT_DATE AS late FROM \"ProjectMilestone\" m WHERE"
                + " m.\"projectId\" = ? ORDER BY m.deadline NULLS LAST, m.sequence").param(projectId));
    }

    private void addMember(String projectId, String userId, String role) {
        pm.jdbc.sql("INSERT INTO \"ProjectMember\" (\"projectId\", \"userId\", \"organizationId\", role) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (\"projectId\", \"userId\") DO UPDATE SET role = CASE WHEN EXCLUDED.role = 'manager' THEN"
                + " 'manager' ELSE \"ProjectMember\".role END").params(projectId, userId, org(), role).update();
    }

    private void defaultStages(String projectId) {
        for (Object[] s : DEFAULT_STAGES) {
            pm.jdbc.sql("INSERT INTO \"ProjectTaskStage\" (id, \"organizationId\", \"projectId\", name, sequence, kind,"
                    + " folded) VALUES (?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), projectId,
                    s[0], s[1], s[2], s[3]).update();
        }
    }

    /** Stages, milestones and (optionally) open tasks of {@code from} into {@code to}, dates moved by {@code shift}. */
    private void copyStructure(String from, String to, long shift, boolean includeTasks) {
        Map<String, String> stageMap = new HashMap<>();
        for (Map<String, Object> s : tasks.stages(from)) {
            String id = UUID.randomUUID().toString();
            stageMap.put((String) s.get("id"), id);
            pm.jdbc.sql("INSERT INTO \"ProjectTaskStage\" (id, \"organizationId\", \"projectId\", name, sequence, kind,"
                    + " folded) VALUES (?, ?, ?, ?, ?, ?, ?)").params(id, org(), to, s.get("name"), s.get("sequence"),
                    s.get("kind"), s.get("folded")).update();
        }
        if (stageMap.isEmpty()) {
            defaultStages(to);
        }
        Map<String, String> milestoneMap = new HashMap<>();
        for (Map<String, Object> m : milestonesOf(from)) {
            String id = UUID.randomUUID().toString();
            milestoneMap.put((String) m.get("id"), id);
            pm.jdbc.sql("INSERT INTO \"ProjectMilestone\" (id, \"organizationId\", \"projectId\", name, description,"
                    + " deadline, sequence) VALUES (?, ?, ?, ?, ?, ?, ?)").params(id, org(), to, m.get("name"),
                    m.get("description"), sqlDate(shift((LocalDate) m.get("deadline"), shift)), m.get("sequence"))
                    .update();
        }
        if (!includeTasks) {
            return;
        }
        Map<String, Object> target = SqlRows.one(pm.jdbc.sql("SELECT * FROM \"Project\" WHERE id = ?").param(to));
        String firstOpen = tasks.firstStage(to, "open");
        List<Map<String, Object>> rows = SqlRows.list(pm.jdbc.sql("WITH RECURSIVE tree AS (SELECT t.*, 0 AS depth FROM"
                + " \"ProjectTask\" t WHERE t.\"projectId\" = ? AND t.\"parentId\" IS NULL AND t.\"deletedAt\" IS NULL"
                + " UNION ALL SELECT c.*, tree.depth + 1 FROM \"ProjectTask\" c JOIN tree ON c.\"parentId\" = tree.id"
                + " WHERE c.\"deletedAt\" IS NULL) SELECT * FROM tree ORDER BY depth, sequence, \"createdAt\"")
                .param(from));
        Map<String, String> taskMap = new HashMap<>();
        for (Map<String, Object> t : rows) {
            String id = UUID.randomUUID().toString();
            taskMap.put((String) t.get("id"), id);
            String stage = Boolean.TRUE.equals(t.get("isClosed")) ? firstOpen : stageMap.getOrDefault(
                    (String) t.get("stageId"), firstOpen);
            pm.jdbc.sql("INSERT INTO \"ProjectTask\" (id, \"organizationId\", \"projectId\", number, title, description,"
                            + " \"stageId\", \"parentId\", \"milestoneId\", priority, \"plannedStart\", deadline,"
                            + " \"plannedHours\", sequence, color, recurrence, \"recurrenceInterval\", \"partnerId\","
                            + " \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(id, org(), to, tasks.number(target), t.get("title"), t.get("description"), stage,
                            taskMap.get((String) t.get("parentId")), milestoneMap.get((String) t.get("milestoneId")),
                            t.get("priority"), sqlDate(shift((LocalDate) t.get("plannedStart"), shift)),
                            sqlDate(shift((LocalDate) t.get("deadline"), shift)), t.get("plannedHours"),
                            t.get("sequence"), t.get("color"), t.get("recurrence"), t.get("recurrenceInterval"),
                            t.get("partnerId"), me())
                    .update();
            String old = (String) t.get("id");
            pm.jdbc.sql("INSERT INTO \"ProjectTaskTag\" (\"taskId\", \"tagId\", \"organizationId\") SELECT ?, \"tagId\","
                    + " \"organizationId\" FROM \"ProjectTaskTag\" WHERE \"taskId\" = ?").params(id, old).update();
            pm.jdbc.sql("INSERT INTO \"ProjectTaskAssignee\" (\"taskId\", \"userId\", \"organizationId\") SELECT ?,"
                    + " \"userId\", \"organizationId\" FROM \"ProjectTaskAssignee\" WHERE \"taskId\" = ?").params(id, old)
                    .update();
            pm.jdbc.sql("INSERT INTO \"ProjectTaskFollower\" (\"taskId\", \"userId\", \"organizationId\") SELECT ?,"
                    + " \"userId\", \"organizationId\" FROM \"ProjectTaskAssignee\" WHERE \"taskId\" = ?").params(id, old)
                    .update();
            pm.jdbc.sql("INSERT INTO \"ProjectTaskChecklistItem\" (id, \"organizationId\", \"taskId\", title, sequence)"
                    + " SELECT gen_random_uuid()::text, \"organizationId\", ?, title, sequence FROM"
                    + " \"ProjectTaskChecklistItem\" WHERE \"taskId\" = ?").params(id, old).update();
            pm.jdbc.sql("UPDATE \"ProjectTask\" SET \"checklistTotal\" = (SELECT count(*) FROM"
                    + " \"ProjectTaskChecklistItem\" WHERE \"taskId\" = ?) WHERE id = ?").params(id, id).update();
        }
        for (Map<String, Object> d : SqlRows.list(pm.jdbc.sql("SELECT d.* FROM \"ProjectTaskDependency\" d JOIN"
                + " \"ProjectTask\" t ON t.id = d.\"taskId\" WHERE t.\"projectId\" = ?").param(from))) {
            String a = taskMap.get((String) d.get("taskId"));
            String b = taskMap.get((String) d.get("dependsOnId"));
            if (a != null && b != null) {
                pm.jdbc.sql("INSERT INTO \"ProjectTaskDependency\" (\"taskId\", \"dependsOnId\", \"organizationId\")"
                        + " VALUES (?, ?, ?) ON CONFLICT DO NOTHING").params(a, b, org()).update();
            }
        }
    }

    private static LocalDate shift(LocalDate d, long days) {
        return d == null ? null : d.plusDays(days);
    }

    private String nextCode() {
        while (true) {
            String code = String.format("PRJ-%04d", pm.nextCounter("project_code"));
            if (pm.jdbc.sql("SELECT count(*) FROM \"Project\" WHERE \"organizationId\" = ? AND code = ?").params(org(),
                    code).query(Long.class).single() == 0) {
                return code;
            }
        }
    }

    private int openStages(String projectId) {
        return pm.jdbc.sql("SELECT count(*) FROM \"ProjectTaskStage\" WHERE \"projectId\" = ? AND kind = 'open'")
                .param(projectId).query(Integer.class).single();
    }

    private Map<String, Object> stage(String stageId) {
        Map<String, Object> s = SqlRows.one(pm.jdbc.sql("SELECT * FROM \"ProjectTaskStage\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(stageId, org()));
        if (s == null) {
            throw ApiException.notFound("Stage not found");
        }
        return s;
    }

    private Map<String, Object> milestone(String id) {
        Map<String, Object> m = SqlRows.one(pm.jdbc.sql("SELECT * FROM \"ProjectMilestone\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (m == null) {
            throw ApiException.notFound("Milestone not found");
        }
        return m;
    }

    private void tag(String id) {
        if (pm.jdbc.sql("SELECT count(*) FROM \"ProjectTag\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .query(Long.class).single() == 0) {
            throw ApiException.notFound("Tag not found");
        }
    }

    private static boolean flag(Boolean value, Map<String, Object> template, String key) {
        if (value != null) {
            return value;
        }
        return template == null || Boolean.TRUE.equals(template.get(key));
    }

    private static BigDecimal hours(BigDecimal v) {
        BigDecimal h = PmSupport.hours(v, "plannedHours");
        return h == null ? BigDecimal.ZERO : h;
    }

    private static void add(List<String> sets, List<Object> args, String column, Object value) {
        sets.add(column + " = ?");
        args.add(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
