package com.inventoryerp.task.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.task.domain.Task;
import com.inventoryerp.task.domain.TaskActivityLog;
import com.inventoryerp.task.domain.TaskAutoRule;
import com.inventoryerp.task.domain.TaskChecklistItem;
import com.inventoryerp.task.domain.TaskComment;
import com.inventoryerp.task.domain.TaskLabel;
import com.inventoryerp.task.domain.TaskRecurringTemplate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kanban tasks (port of {@code task.service.ts}): CRUD with an activity timeline, status reorder, checklist
 * progress, threaded comments, verification, the dashboard counters, labels, recurring templates and auto-rules.
 */
@Service
public class TaskService {

    /** {@code TaskQueryDto} after transformation. */
    public record TaskQuery(String status, String priority, String category, String area, String branchId,
            String assignedToId, String search, String dateFrom, String dateTo, Integer page, Integer limit) {}

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Column → SQL cast for enum / json / timestamp columns written from DTO maps. */
    private static final Map<String, String> CASTS = Map.ofEntries(
            Map.entry("taskType", "\"TaskType\""), Map.entry("priority", "\"TaskPriority\""),
            Map.entry("taskPriority", "\"TaskPriority\""), Map.entry("status", "\"TaskMgmtStatus\""),
            Map.entry("category", "\"TaskCategory\""), Map.entry("taskCategory", "\"TaskCategory\""),
            Map.entry("area", "\"TaskArea\""), Map.entry("taskArea", "\"TaskArea\""),
            Map.entry("recurrenceType", "\"RecurrenceType\""), Map.entry("verificationMethod", "\"VerificationMethod\""),
            Map.entry("recurrenceConfig", "jsonb"), Map.entry("checklistTemplate", "jsonb"),
            Map.entry("triggerConfig", "jsonb"));

    private static final DateTimeFormatter JS_DATE = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss", Locale.US);

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final EventBus events;

    public TaskService(JdbcClient jdbc, JsonMapper mapper, EventBus events) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.events = events;
    }

    /** Notification routing (ADR-S065): the assignee learns about the task. */
    private void assigned(String taskId, Object assignedToId, Object title) {
        if (assignedToId instanceof String a && !a.isBlank()) {
            events.publish("task.assigned", Obj.of("organizationId", org(), "taskId", taskId, "title", title,
                    "assignedToId", a));
        }
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String userId() {
        String id = TenantContext.userId();
        return id != null ? id : "system";
    }

    // ------------------------------------------------------------------ CRUD

    /** Create a task (DTO fields spread into the row) and log {@code created}. */
    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>(dto);
        data.put("id", id);
        data.put("organizationId", org());
        data.put("createdBy", userId());
        data.put("checklistProgress", 0);
        data.put("checklistTotal", 0);
        insert("Task", data);
        Map<String, Object> task = withIncludes(row(id));
        logActivity(id, "created", null, null, "Task created");
        assigned(id, task.get("assignedToId"), task.get("title"));
        return task;
    }

    /** {@code {items, total, page, limit, totalPages}}, newest first. */
    @Transactional(readOnly = true)
    public Map<String, Object> findAll(TaskQuery q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"deletedAt\" IS NULL");
        Map<String, String> eq = new LinkedHashMap<>();
        eq.put("status", q.status());
        eq.put("priority", q.priority());
        eq.put("category", q.category());
        eq.put("area", q.area());
        eq.put("branchId", q.branchId());
        eq.put("assignedToId", q.assignedToId());
        eq.forEach((col, v) -> {
            if (v != null && !v.isEmpty()) {
                where.append(" AND \"").append(col).append("\"::text = ?");
                params.add(v);
            }
        });
        if (q.search() != null && !q.search().isEmpty()) {
            String like = "%" + BaseCrudService.escapeLike(q.search()) + "%";
            where.append(" AND (title ILIKE ? OR description ILIKE ?)");
            params.add(like);
            params.add(like);
        }
        if (q.dateFrom() != null && !q.dateFrom().isEmpty()) {
            where.append(" AND \"dueDate\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(q.dateFrom())));
        }
        if (q.dateTo() != null && !q.dateTo().isEmpty()) {
            where.append(" AND \"dueDate\" <= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(q.dateTo())));
        }
        int page = q.page() == null ? 1 : q.page();
        int limit = q.limit() == null ? 50 : q.limit();
        List<Map<String, Object>> items = SqlRows.list(Task.class, jdbc.sql("SELECT * FROM \"Task\" WHERE" + where
                + " ORDER BY \"createdAt\" DESC OFFSET " + (long) (page - 1) * limit + " LIMIT " + limit).params(params));
        items.replaceAll(this::withIncludes);
        long total = jdbc.sql("SELECT count(*) FROM \"Task\" WHERE" + where).params(params).query(Long.class).single();
        return Obj.of("items", items, "total", total, "page", page, "limit", limit, "totalPages",
                (long) Math.ceil((double) total / limit));
    }

    /** Task with includes, comments (+ replies), activity log and live recurrence children. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> task = SqlRows.one(Task.class, jdbc.sql("SELECT * FROM \"Task\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (task == null) {
            throw ApiException.notFound("Task not found");
        }
        withIncludes(task);
        List<Map<String, Object>> comments = SqlRows.list(TaskComment.class, jdbc.sql("SELECT * FROM \"TaskComment\""
                + " WHERE \"taskId\" = ? ORDER BY \"createdAt\" ASC").param(id));
        for (Map<String, Object> c : comments) {
            c.put("replies", replies(c.get("id")));
        }
        task.put("comments", comments);
        task.put("activityLog", SqlRows.list(TaskActivityLog.class, jdbc.sql("SELECT * FROM \"TaskActivityLog\" WHERE"
                + " \"taskId\" = ? ORDER BY \"createdAt\" DESC").param(id)));
        List<Map<String, Object>> children = SqlRows.list(Task.class, jdbc.sql("SELECT * FROM \"Task\" WHERE"
                + " \"parentTaskId\" = ? AND \"deletedAt\" IS NULL").param(id));
        children.replaceAll(this::withIncludes);
        task.put("children", children);
        return task;
    }

    /** Partial update; one {@code updated} activity per changed field. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> dto) {
        Map<String, Object> existing = findOne(id);
        record Change(String field, String oldValue, String description) {}
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Object> e : dto.entrySet()) {
            Object value = e.getValue();
            if (value != null && !jsString(existing.get(e.getKey())).equals(jsString(value))) {
                Object old = existing.get(e.getKey());
                changes.add(new Change(e.getKey(), old == null ? "" : jsString(old), "Updated " + e.getKey()));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>(dto);
        data.put("updatedBy", userId());
        updateRow(id, data);
        Map<String, Object> task = withIncludes(row(id));
        for (Change c : changes) {
            logActivity(id, "updated", c.field(), c.oldValue(), c.description());
        }
        if (changes.stream().anyMatch(c -> "assignedToId".equals(c.field()))) {
            assigned(id, task.get("assignedToId"), task.get("title"));
        }
        return task;
    }

    /** Soft delete; returns the bare row. */
    @Transactional
    public Map<String, Object> remove(String id) {
        findOne(id);
        jdbc.sql("UPDATE \"Task\" SET \"deletedAt\" = " + NOW + ", \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ?").params(userId(), id).update();
        return row(id);
    }

    /** Kanban drop: set the status and log {@code status_changed}. */
    @Transactional
    public Map<String, Object> reorder(String id, String status) {
        findOne(id);
        updateRow(id, Obj.of("status", status, "updatedBy", userId()));
        Map<String, Object> task = withIncludes(row(id));
        logActivity(id, "status_changed", "status", null, "Status changed to " + status);
        return task;
    }

    // ------------------------------------------------------------------ checklist

    /** Toggle a checklist item and recompute the task's progress. */
    @Transactional
    public Map<String, Object> toggleChecklist(String taskId, String itemId, Boolean isCompleted) {
        findOne(taskId);
        boolean done = Boolean.TRUE.equals(isCompleted);
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (isCompleted != null) {
            sets.add("\"isCompleted\" = ?");
            params.add(isCompleted);
        }
        sets.add(done ? "\"completedAt\" = " + NOW : "\"completedAt\" = NULL");
        sets.add("\"completedBy\" = ?");
        params.add(done ? userId() : null);
        params.addAll(List.of(itemId, org()));
        int n = jdbc.sql("UPDATE \"TaskChecklistItem\" SET " + String.join(", ", sets) + ", \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?").params(params).update();
        if (n == 0) {
            // Prisma update() on a missing row: P2025, an unhandled 500 in Nest.
            throw new IllegalStateException("No TaskChecklistItem found for id " + itemId);
        }
        List<Map<String, Object>> items = jdbc.sql("SELECT \"isCompleted\" FROM \"TaskChecklistItem\" WHERE"
                + " \"taskId\" = ? AND \"organizationId\" = ?").params(taskId, org()).query().listOfRows();
        int total = items.size();
        long completed = items.stream().filter(i -> Boolean.TRUE.equals(i.get("isCompleted"))).count();
        long progress = total > 0 ? Math.round((double) completed / total * 100) : 0;
        updateRow(taskId, Obj.of("checklistProgress", (int) progress, "checklistTotal", total, "updatedBy", userId()));
        Map<String, Object> task = withIncludes(row(taskId));
        logActivity(taskId, "checklist_updated", "checklistProgress", null,
                "Checklist " + completed + "/" + total + " (" + progress + "%)");
        return task;
    }

    // ------------------------------------------------------------------ comments

    /** Add a (threaded) comment. */
    @Transactional
    public Map<String, Object> addComment(String taskId, String content, String parentId, List<String> mentions,
            List<String> attachmentUrls) {
        findOne(taskId);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"TaskComment\" (id, \"organizationId\", \"taskId\", content, \"parentId\", mentions,"
                        + " \"attachmentUrls\", \"createdById\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ")")
                .params(id, org(), taskId, content, parentId,
                        (mentions == null ? List.<String>of() : mentions).toArray(String[]::new),
                        (attachmentUrls == null ? List.<String>of() : attachmentUrls).toArray(String[]::new), userId())
                .update();
        Map<String, Object> comment = SqlRows.one(TaskComment.class, jdbc.sql("SELECT * FROM \"TaskComment\" WHERE id = ?")
                .param(id));
        comment.put("replies", replies(id));
        logActivity(taskId, "commented", null, null, "Comment added");
        return comment;
    }

    // ------------------------------------------------------------------ verification

    /** Verify a task that requires verification. */
    @Transactional
    public Map<String, Object> verify(String taskId, Object verificationMethod, String verificationNote) {
        Map<String, Object> task = findOne(taskId);
        if (!Boolean.TRUE.equals(task.get("requiresVerification"))) {
            throw ApiException.badRequest("This task does not require verification");
        }
        jdbc.sql("UPDATE \"Task\" SET \"verifiedById\" = ?, \"verifiedAt\" = " + NOW + ", \"verificationNote\" = ?,"
                        + " status = 'VERIFIED', \"updatedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(userId(), verificationNote, userId(), taskId).update();
        Map<String, Object> updated = withIncludes(row(taskId));
        logActivity(taskId, "verified", "status", (String) task.get("status"),
                "Verified via " + (verificationMethod == null ? "undefined" : jsString(verificationMethod)));
        return updated;
    }

    // ------------------------------------------------------------------ dashboard

    /** Today / backlog counters, completion rate, average completion time and top assignees. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        ZoneId zone = BusinessZone.get();
        Instant now = Instant.now();
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant todayEnd = todayStart.plusMillis(86_400_000L);
        Object s = SqlRows.ts(todayStart);
        Object e = SqlRows.ts(todayEnd);
        String base = "SELECT count(*) FROM \"Task\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL";
        long todayTotal = count(base + " AND \"createdAt\" >= ? AND \"createdAt\" < ?", s, e);
        long todayCompleted = count(base + " AND status = 'COMPLETED' AND \"completedAt\" >= ? AND \"completedAt\" < ?",
                s, e);
        long pending = count(base + " AND status = 'PENDING'");
        long inProgress = count(base + " AND status = 'IN_PROGRESS'");
        long overdue = count(base + " AND status NOT IN ('COMPLETED', 'CANCELLED', 'SKIPPED') AND \"dueDate\" < ?",
                SqlRows.ts(now));
        long dueToday = count(base + " AND \"dueDate\" >= ? AND \"dueDate\" < ?", s, e);
        long verificationPending = count(base + " AND \"requiresVerification\" = true AND status = 'COMPLETED' AND"
                + " \"verifiedAt\" IS NULL");
        long recurringToday = count(base + " AND \"isRecurring\" = true AND \"createdAt\" >= ? AND \"createdAt\" < ?", s,
                e);
        long totalTasks = count(base);
        long completedTasks = count(base + " AND status = 'COMPLETED'");
        long completionRate = totalTasks > 0 ? Math.round((double) completedTasks / totalTasks * 100) : 0;
        List<Map<String, Object>> done = jdbc.sql("SELECT \"createdAt\", \"completedAt\" FROM \"Task\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"completedAt\" IS NOT NULL").param(org()).query()
                .listOfRows();
        long avg = 0;
        if (!done.isEmpty()) {
            double totalMinutes = 0;
            for (Map<String, Object> t : done) {
                totalMinutes += (millis(t.get("completedAt")) - millis(t.get("createdAt"))) / 60000d;
            }
            avg = Math.round(totalMinutes / done.size());
        }
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT \"assignedToId\", count(id) AS n FROM \"Task\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status = 'COMPLETED' AND \"assignedToId\" IS NOT"
                + " NULL GROUP BY \"assignedToId\" ORDER BY count(id) DESC LIMIT 5").param(org()).query().listOfRows()) {
            top.add(Obj.of("assignedToId", r.get("assignedToId"), "completedCount", ((Number) r.get("n")).longValue()));
        }
        return Obj.of("todayTotal", todayTotal, "todayCompleted", todayCompleted, "pending", pending, "inProgress",
                inProgress, "overdue", overdue, "dueToday", dueToday, "recurringToday", recurringToday,
                "verificationPending", verificationPending, "completionRate", completionRate, "avgCompletionMinutes", avg,
                "topEmployees", top);
    }

    private long count(String sql, Object... extra) {
        List<Object> params = new ArrayList<>(List.of(org()));
        params.addAll(List.of(extra));
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static long millis(Object ts) {
        return ((java.sql.Timestamp) ts).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
    }

    // ------------------------------------------------------------------ labels / templates / auto-rules

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLabels() {
        return SqlRows.list(TaskLabel.class, jdbc.sql("SELECT * FROM \"TaskLabel\" WHERE \"organizationId\" = ?"
                + " ORDER BY name ASC").param(org()));
    }

    @Transactional
    public Map<String, Object> createLabel(String name, String color) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"TaskLabel\" (id, \"organizationId\", name, color) VALUES (?, ?, ?, ?)")
                .params(id, org(), name, color != null ? color : "#6366f1").update();
        return SqlRows.one(TaskLabel.class, jdbc.sql("SELECT * FROM \"TaskLabel\" WHERE id = ?").param(id));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTemplates() {
        return SqlRows.list(TaskRecurringTemplate.class, jdbc.sql("SELECT * FROM \"TaskRecurringTemplate\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY title ASC").param(org()));
    }

    @Transactional
    public Map<String, Object> createTemplate(Map<String, Object> dto) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>(dto);
        data.put("id", id);
        data.put("organizationId", org());
        data.put("createdBy", userId());
        insert("TaskRecurringTemplate", data);
        return SqlRows.one(TaskRecurringTemplate.class, jdbc.sql("SELECT * FROM \"TaskRecurringTemplate\" WHERE id = ?")
                .param(id));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAutoRules() {
        return SqlRows.list(TaskAutoRule.class, jdbc.sql("SELECT * FROM \"TaskAutoRule\" WHERE \"organizationId\" = ?"
                + " ORDER BY name ASC").param(org()));
    }

    @Transactional
    public Map<String, Object> createAutoRule(Map<String, Object> dto) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>(dto);
        data.put("id", id);
        data.put("organizationId", org());
        data.put("createdBy", userId());
        insert("TaskAutoRule", data);
        return SqlRows.one(TaskAutoRule.class, jdbc.sql("SELECT * FROM \"TaskAutoRule\" WHERE id = ?").param(id));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> row(String id) {
        return SqlRows.one(Task.class, jdbc.sql("SELECT * FROM \"Task\" WHERE id = ?").param(id));
    }

    private List<Map<String, Object>> replies(Object commentId) {
        return SqlRows.list(TaskComment.class, jdbc.sql("SELECT * FROM \"TaskComment\" WHERE \"parentId\" = ?")
                .param(commentId));
    }

    /** {@code defaultInclude()}: assignee / supervisor / verifier, branch, labels, ordered checklist. */
    private Map<String, Object> withIncludes(Map<String, Object> t) {
        t.put("assignedTo", user(t.get("assignedToId")));
        t.put("supervisor", user(t.get("supervisorId")));
        t.put("verifiedBy", user(t.get("verifiedById")));
        t.put("branch", t.get("branchId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, name FROM \"Branch\""
                + " WHERE id = ?").param(t.get("branchId"))));
        t.put("labels", jdbc.sql("SELECT l.id, l.name, l.color FROM \"TaskLabel\" l JOIN \"_TaskLabelLink\" k ON"
                + " k.\"B\" = l.id WHERE k.\"A\" = ?").param(t.get("id")).query().listOfRows());
        t.put("checklistItems", SqlRows.list(TaskChecklistItem.class, jdbc.sql("SELECT * FROM \"TaskChecklistItem\""
                + " WHERE \"taskId\" = ? ORDER BY \"sortOrder\" ASC").param(t.get("id"))));
        return t;
    }

    private Map<String, Object> user(Object id) {
        return id == null ? null : SqlRows.one(jdbc.sql("SELECT id, \"firstName\", \"lastName\", email FROM \"User\""
                + " WHERE id = ?").param(id));
    }

    private void logActivity(String taskId, String action, String field, String oldValue, String description) {
        String newValue = null;
        if (field != null) {
            newValue = jsString(findOne(taskId).get(field));
        }
        jdbc.sql("INSERT INTO \"TaskActivityLog\" (id, \"organizationId\", \"taskId\", action, field, \"oldValue\","
                        + " \"newValue\", description, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org(), taskId, action, field,
                        oldValue == null || oldValue.isEmpty() ? null : oldValue, newValue, description, userId())
                .update();
    }

    private Object sqlValue(String column, Object value) {
        if (value == null) {
            return null;
        }
        if ("jsonb".equals(CASTS.get(column))) {
            return mapper.writeValueAsString(value);
        }
        if (column.equals("dueDate") || column.equals("startTime")) {
            return value.toString().isEmpty() ? null : SqlRows.ts(EntityPatch.toInstant(value.toString()));
        }
        return value;
    }

    private String placeholder(String column) {
        String cast = CASTS.get(column);
        return cast == null ? "?" : "?::" + cast;
    }

    private void insert(String table, Map<String, Object> data) {
        List<String> cols = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        data.forEach((k, v) -> {
            cols.add("\"" + k + "\"");
            marks.add(placeholder(k));
            params.add(sqlValue(k, v));
        });
        cols.add("\"updatedAt\"");
        marks.add(NOW);
        jdbc.sql("INSERT INTO \"" + table + "\" (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", marks)
                + ")").params(params).update();
    }

    private void updateRow(String id, Map<String, Object> data) {
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        data.forEach((k, v) -> {
            sets.add("\"" + k + "\" = " + placeholder(k));
            params.add(sqlValue(k, v));
        });
        sets.add("\"updatedAt\" = " + NOW);
        params.addAll(List.of(id, org()));
        jdbc.sql("UPDATE \"Task\" SET " + String.join(", ", sets) + " WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(params).update();
    }

    /** JavaScript {@code String(value)} for the activity-log diff (dates use {@code Date#toString}). */
    static String jsString(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Instant i) {
            ZoneId zone = BusinessZone.get();
            ZonedDateTime z = i.atZone(zone);
            String offset = z.getOffset().getId().equals("Z") ? "+0000" : z.getOffset().getId().replace(":", "");
            boolean dst = TimeZone.getTimeZone(zone).inDaylightTime(java.util.Date.from(i));
            return JS_DATE.format(z) + " GMT" + offset + " (" + TimeZone.getTimeZone(zone).getDisplayName(dst,
                    TimeZone.LONG, Locale.US) + ")";
        }
        if (v instanceof Double || v instanceof Float) {
            return JsNumbers.toJsString(((Number) v).doubleValue());
        }
        if (v instanceof java.math.BigDecimal d) {
            return JsNumbers.toJsString(d.doubleValue());
        }
        if (v instanceof List<?> list) {
            return String.join(",", list.stream().map(x -> x == null ? "" : jsString(x)).toList());
        }
        if (v instanceof Map<?, ?>) {
            return "[object Object]";
        }
        return v.toString();
    }
}
