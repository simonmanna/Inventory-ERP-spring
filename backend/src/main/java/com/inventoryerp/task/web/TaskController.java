package com.inventoryerp.task.web;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.task.application.TaskService;
import com.inventoryerp.task.application.TaskService.TaskQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /tasks} (port of {@code task.controller.ts}). Registered only with {@code ENABLE_TASKS=true} (Nest imports
 * {@code TaskModule} conditionally) and gated per tenant by {@code @RequiresModule('task')}; no route permissions.
 */
@RestController
@RequestMapping("/tasks")
@RequiresModule("task")
@ConditionalOnProperty(name = "app.modules.tasks", havingValue = "true")
public class TaskController {

    static final String UUID_RE = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    static final String UUID_MSG = "must be a UUID";
    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";
    static final String DATE_MSG = "must be a valid ISO 8601 date string";
    static final String STR = "must be a string";

    /** {@code CreateTaskDto}. */
    public record CreateTaskRequest(
            @NotNull(message = STR) String title,
            String description,
            String taskType,
            String priority,
            String status,
            String category,
            String area,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String branchId,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String assignedToId,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String supervisorId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueDate,
            String dueTime,
            Integer estimatedMinutes,
            Boolean isRecurring,
            Boolean requiresVerification,
            String verificationMethod) {}

    /** {@code UpdateTaskDto}. */
    public record UpdateTaskRequest(
            String title,
            String description,
            String taskType,
            String priority,
            String status,
            String category,
            String area,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String branchId,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String assignedToId,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String supervisorId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueDate,
            String dueTime,
            Integer estimatedMinutes,
            Boolean isRecurring,
            Boolean requiresVerification,
            String verificationMethod,
            String generatedByEvent,
            String sourceReferenceId,
            String sourceReferenceType) {}

    /** {@code ReorderTaskDto}. */
    public record ReorderTaskRequest(@NotNull(message = STR) String status) {}

    /** {@code CreateCommentDto}. */
    public record CreateCommentRequest(
            @NotNull(message = STR) String content,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String parentId,
            List<String> mentions,
            List<String> attachmentUrls) {}

    /** {@code CreateLabelDto}. */
    public record CreateLabelRequest(@NotNull(message = STR) String name, String color) {}

    /** {@code CreateTemplateDto}. */
    public record CreateTemplateRequest(
            @NotNull(message = STR) String title,
            String description,
            String taskType,
            String taskCategory,
            String taskArea,
            String priority,
            String recurrenceType,
            Object recurrenceConfig,
            Integer estimatedMinutes,
            Boolean requiresVerification,
            String verificationMethod,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String branchId,
            String assignToRole,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String assignToUserId,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String supervisorId,
            List<Object> checklistTemplate) {}

    /** {@code CreateTaskRuleDto}. */
    public record CreateTaskRuleRequest(
            @NotNull(message = STR) String name,
            String description,
            @NotNull(message = STR) String triggerEvent,
            Object triggerConfig,
            @NotNull(message = STR) String taskTitle,
            String taskDescription,
            @NotNull(message = STR) String taskType,
            String taskCategory,
            String taskArea,
            String taskPriority,
            String assignToRole,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String assignToUserId,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String branchId,
            Boolean requiresVerification) {}

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateTaskRequest dto) {
        return tasks.create(EntityPatch.data(dto));
    }

    @GetMapping
    public Map<String, Object> findAll(@RequestParam Map<String, String> query) {
        QueryParams.only(query, "status", "priority", "category", "area", "branchId", "assignedToId", "search",
                "dateFrom", "dateTo", "page", "limit");
        List<String> errors = new ArrayList<>();
        Integer page = positiveInt(query, "page", errors);
        Integer limit = positiveInt(query, "limit", errors);
        if (!errors.isEmpty()) {
            throw QueryParams.validation(errors);
        }
        return tasks.findAll(new TaskQuery(query.get("status"), query.get("priority"), query.get("category"),
                query.get("area"), query.get("branchId"), query.get("assignedToId"), query.get("search"),
                query.get("dateFrom"), query.get("dateTo"), page, limit));
    }

    /** {@code @Type(() => Number) @IsInt() @Min(1)}. */
    private static Integer positiveInt(Map<String, String> query, String key, List<String> errors) {
        String raw = query.get(key);
        if (raw == null) {
            return null;
        }
        double n = JsNumbers.number(raw);
        boolean integer = !Double.isNaN(n) && !Double.isInfinite(n) && n == Math.floor(n);
        if (!integer) {
            errors.add(key + " must be an integer number");
        }
        if (Double.isNaN(n) || n < 1) {
            errors.add(key + " must not be less than 1");
        }
        return integer && n >= 1 ? (int) n : null;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return tasks.dashboard();
    }

    @GetMapping("/labels")
    public List<Map<String, Object>> listLabels() {
        return tasks.listLabels();
    }

    @PostMapping("/labels")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createLabel(@Valid @RequestBody CreateLabelRequest dto) {
        return tasks.createLabel(dto.name(), dto.color());
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> listTemplates() {
        return tasks.listTemplates();
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTemplate(@Valid @RequestBody CreateTemplateRequest dto) {
        return tasks.createTemplate(EntityPatch.data(dto));
    }

    @GetMapping("/auto-rules")
    public List<Map<String, Object>> listAutoRules() {
        return tasks.listAutoRules();
    }

    @PostMapping("/auto-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createAutoRule(@Valid @RequestBody CreateTaskRuleRequest dto) {
        return tasks.createAutoRule(EntityPatch.data(dto));
    }

    @GetMapping("/{id}")
    public Map<String, Object> findOne(@PathVariable String id) {
        return tasks.findOne(id);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateTaskRequest dto) {
        return tasks.update(id, EntityPatch.data(dto));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable String id) {
        return tasks.remove(id);
    }

    @PatchMapping("/{id}/reorder")
    public Map<String, Object> reorder(@PathVariable String id, @Valid @RequestBody ReorderTaskRequest dto) {
        return tasks.reorder(id, dto.status());
    }

    /** {@code @Body('isCompleted')}: untyped, no DTO validation. */
    @PatchMapping("/{id}/checklist/{itemId}")
    public Map<String, Object> toggleChecklist(@PathVariable String id, @PathVariable String itemId,
            @RequestBody(required = false) Map<String, Object> body) {
        Object v = body == null ? null : body.get("isCompleted");
        return tasks.toggleChecklist(id, itemId, v == null ? null : Boolean.TRUE.equals(v) || "true".equals(v));
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addComment(@PathVariable String id, @Valid @RequestBody CreateCommentRequest dto) {
        return tasks.addComment(id, dto.content(), dto.parentId(), dto.mentions(), dto.attachmentUrls());
    }

    /** Untyped body {@code { verificationMethod, verificationNote? }}. */
    @PostMapping("/{id}/verify")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> verify(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Object note = body == null ? null : body.get("verificationNote");
        return tasks.verify(id, body == null ? null : body.get("verificationMethod"), note == null ? null
                : note.toString());
    }
}
