package com.inventoryerp.projects.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.application.ProjectTaskService;
import com.inventoryerp.projects.application.ProjectTaskService.TaskInput;
import com.inventoryerp.projects.application.ProjectTaskService.TaskQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * {@code /project-tasks} (ADR-S062; extension): project tasks and personal to-dos — board / list queries, CRUD, stage
 * moves, done / reopen, bulk changes, dependencies, checklist, chatter and following. Separate from the cafe task board
 * at {@code /tasks} (Nest parity).
 */
@RestController
@RequestMapping("/project-tasks")
public class ProjectTaskController {

    public record CreateRequest(
            String projectId,
            @NotBlank(message = "title should not be empty") @Size(max = 500, message = "title must be at most 500"
                    + " characters") String title,
            @Size(max = 50000, message = "description must be at most 50000 characters") String description,
            String stageId,
            String parentId,
            List<String> assigneeIds,
            List<String> tagIds,
            String milestoneId,
            String priority,
            String kanbanState,
            String blockedReason,
            String plannedStart,
            String deadline,
            BigDecimal plannedHours,
            String partnerId,
            String wbsId,
            String siteId,
            String recurrence,
            Integer recurrenceInterval,
            String recurrenceUntil,
            String sourceResType,
            String sourceResId,
            List<String> checklist,
            List<String> dependsOnIds,
            Integer color) {}

    public record MoveRequest(@NotBlank(message = "stageId should not be empty") String stageId, Integer index) {}

    public record DoneRequest(@NotNull(message = "done should not be empty") Boolean done) {}

    public record BulkRequest(List<String> taskIds, String stageId, String priority, String deadline,
            String addAssigneeId) {}

    public record DependencyRequest(@NotBlank(message = "dependsOnId should not be empty") String dependsOnId) {}

    public record ChecklistRequest(@Size(max = 500, message = "title must be at most 500 characters") String title,
            Boolean isDone) {}

    public record MessageRequest(String kind, @NotBlank(message = "body should not be empty") String body,
            List<String> mentions) {}

    public record FollowRequest(@NotNull(message = "follow should not be empty") Boolean follow) {}

    private final ProjectTaskService tasks;

    public ProjectTaskController(ProjectTaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public Map<String, Object> list(@RequestParam(required = false) String projectId,
            @RequestParam(required = false) String stageId, @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String tagId, @RequestParam(required = false) String milestoneId,
            @RequestParam(required = false) String search, @RequestParam(required = false) String state,
            @RequestParam(required = false) String deadlineFrom, @RequestParam(required = false) String deadlineTo,
            @RequestParam(required = false) String parentId, @RequestParam(required = false) Boolean topLevel,
            @RequestParam(required = false) String sourceResType, @RequestParam(required = false) String sourceResId,
            @RequestParam(required = false) String priority, @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) Boolean personal, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return tasks.list(new TaskQuery(projectId, stageId, assigneeId, tagId, milestoneId, search, state, deadlineFrom,
                deadlineTo, parentId, topLevel, sourceResType, sourceResId, priority, overdue, personal, limit,
                offset));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest r) {
        return tasks.create(new TaskInput(r.projectId(), r.title(), r.description(), r.stageId(), r.parentId(),
                r.assigneeIds(), r.tagIds(), r.milestoneId(), r.priority(), r.kanbanState(), r.blockedReason(),
                r.plannedStart(), r.deadline(), r.plannedHours(), r.partnerId(), r.wbsId(), r.siteId(), r.recurrence(),
                r.recurrenceInterval(), r.recurrenceUntil(), r.sourceResType(), r.sourceResId(), r.checklist(),
                r.dependsOnIds(), r.color()));
    }

    @PostMapping("/bulk")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> bulk(@RequestBody BulkRequest r) {
        Map<String, Object> changes = new LinkedHashMap<>();
        if (r.stageId() != null) {
            changes.put("stageId", r.stageId());
        }
        if (r.priority() != null) {
            changes.put("priority", r.priority());
        }
        if (r.deadline() != null) {
            changes.put("deadline", r.deadline().isBlank() ? null : r.deadline());
        }
        return tasks.bulk(r.taskIds(), changes, r.addAssigneeId());
    }

    @PatchMapping("/checklist/{itemId}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> updateChecklist(@PathVariable String itemId, @Valid @RequestBody ChecklistRequest r) {
        return tasks.updateChecklistItem(itemId, r.title(), r.isDone());
    }

    @DeleteMapping("/checklist/{itemId}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> deleteChecklist(@PathVariable String itemId) {
        return tasks.deleteChecklistItem(itemId);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return tasks.get(id);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return tasks.update(id, body);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> delete(@PathVariable String id) {
        return tasks.delete(id);
    }

    @PostMapping("/{id}/move")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> move(@PathVariable String id, @Valid @RequestBody MoveRequest r) {
        return tasks.move(id, r.stageId(), r.index());
    }

    @PostMapping("/{id}/done")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> done(@PathVariable String id, @Valid @RequestBody DoneRequest r) {
        return tasks.setDone(id, r.done());
    }

    @PostMapping("/{id}/dependencies")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> addDependency(@PathVariable String id, @Valid @RequestBody DependencyRequest r) {
        return tasks.addDependency(id, r.dependsOnId());
    }

    @DeleteMapping("/{id}/dependencies/{dependsOnId}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> removeDependency(@PathVariable String id, @PathVariable String dependsOnId) {
        return tasks.removeDependency(id, dependsOnId);
    }

    @PostMapping("/{id}/checklist")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> addChecklist(@PathVariable String id, @Valid @RequestBody ChecklistRequest r) {
        return tasks.addChecklistItem(id, r.title());
    }

    @GetMapping("/{id}/messages")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public List<Map<String, Object>> messages(@PathVariable String id) {
        return tasks.messages(id);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> postMessage(@PathVariable String id, @Valid @RequestBody MessageRequest r) {
        return tasks.postMessage(id, r.kind(), r.body(), r.mentions());
    }

    @PostMapping("/{id}/follow")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public Map<String, Object> follow(@PathVariable String id, @Valid @RequestBody FollowRequest r) {
        return tasks.setFollowing(id, r.follow());
    }
}
