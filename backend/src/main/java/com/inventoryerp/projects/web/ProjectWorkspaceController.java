package com.inventoryerp.projects.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.application.ProjectWorkspaceService;
import com.inventoryerp.projects.application.ProjectWorkspaceService.MemberInput;
import com.inventoryerp.projects.application.ProjectWorkspaceService.WorkspaceInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project management workspace (ADR-S062; extension): projects board, settings, members, stages, milestones, status
 * updates, project chatter, overview, duplication, projects from sales orders, assignable users and tags. Literal
 * segments ({@code /projects/workspace}, {@code /projects/stages/...}) win over {@code /projects/{id}}.
 */
@RestController
public class ProjectWorkspaceController {

    public record CreateRequest(
            @Size(max = 40, message = "code must be at most 40 characters") String code,
            @NotBlank(message = "name should not be empty") @Size(max = 200, message = "name must be at most 200"
                    + " characters") String name,
            @Size(max = 20000, message = "description must be at most 20000 characters") String description,
            String customerId,
            String managerId,
            List<String> memberIds,
            String privacy,
            Integer color,
            String startDate,
            String endDate,
            BigDecimal plannedHours,
            Boolean allowTimesheets,
            Boolean allowMilestones,
            Boolean allowDependencies,
            Boolean allowRecurring,
            Boolean isTemplate,
            String templateId,
            String branchId,
            BigDecimal contractValue,
            String status) {}

    public record MembersRequest(List<@Valid MemberInput> members) {}

    public record StageRequest(@Size(max = 80, message = "name must be at most 80 characters") String name,
            String kind, Boolean folded) {}

    public record ReorderRequest(List<String> stageIds) {}

    public record MilestoneRequest(@NotBlank(message = "name should not be empty") String name, String deadline,
            String description) {}

    public record UpdateRequest(@NotBlank(message = "status should not be empty") String status,
            @Min(value = 0, message = "progress must not be less than 0")
            @Max(value = 100, message = "progress must not be greater than 100") Integer progress,
            @Size(max = 20000, message = "summary must be at most 20000 characters") String summary) {}

    public record MessageRequest(String kind, @NotBlank(message = "body should not be empty") String body,
            List<String> mentions) {}

    public record DuplicateRequest(String name, String code, Boolean asTemplate, Boolean includeTasks,
            String startDate) {}

    public record FromSalesOrderRequest(@NotBlank(message = "salesOrderId should not be empty") String salesOrderId,
            String name, Boolean taskPerLine, String managerId, String templateId) {}

    public record TagRequest(@Size(max = 60, message = "name must be at most 60 characters") String name,
            Integer color) {}

    private final ProjectWorkspaceService workspace;

    public ProjectWorkspaceController(ProjectWorkspaceService workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/projects/workspace")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public List<Map<String, Object>> board(@RequestParam(required = false) String filter,
            @RequestParam(required = false) String status, @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean templates) {
        return workspace.board(filter, status, search, Boolean.TRUE.equals(templates));
    }

    @PostMapping("/projects/workspace")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest r) {
        return workspace.create(new WorkspaceInput(r.code(), r.name(), r.description(), r.customerId(), r.managerId(),
                r.memberIds(), r.privacy(), r.color(), r.startDate(), r.endDate(), r.plannedHours(),
                r.allowTimesheets(), r.allowMilestones(), r.allowDependencies(), r.allowRecurring(), r.isTemplate(),
                r.templateId(), r.branchId(), r.contractValue(), r.status()));
    }

    @PostMapping("/projects/from-sales-order")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> fromSalesOrder(@Valid @RequestBody FromSalesOrderRequest r) {
        return workspace.fromSalesOrder(r.salesOrderId(), r.name(), r.taskPerLine(), r.managerId(), r.templateId());
    }

    @GetMapping("/projects/assignable-users")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public List<Map<String, Object>> assignableUsers(@RequestParam(required = false) String search) {
        return workspace.assignableUsers(search);
    }

    @GetMapping("/projects/{id}/workspace")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return workspace.workspace(id);
    }

    @PatchMapping("/projects/{id}/workspace")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> settings(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return workspace.updateSettings(id, body);
    }

    @PostMapping("/projects/{id}/favorite")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public Map<String, Object> favorite(@PathVariable String id) {
        return workspace.toggleFavorite(id);
    }

    @GetMapping("/projects/{id}/overview")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public Map<String, Object> overview(@PathVariable String id) {
        return workspace.overview(id);
    }

    @PutMapping("/projects/{id}/members")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> members(@PathVariable String id, @Valid @RequestBody MembersRequest r) {
        return workspace.setMembers(id, r.members());
    }

    @PostMapping("/projects/{id}/stages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> addStage(@PathVariable String id, @Valid @RequestBody StageRequest r) {
        return workspace.addStage(id, r.name(), r.kind(), r.folded());
    }

    @PostMapping("/projects/{id}/stages/reorder")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> reorderStages(@PathVariable String id, @RequestBody ReorderRequest r) {
        return workspace.reorderStages(id, r.stageIds());
    }

    @PatchMapping("/projects/stages/{stageId}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> updateStage(@PathVariable String stageId, @Valid @RequestBody StageRequest r) {
        return workspace.updateStage(stageId, r.name(), r.kind(), r.folded());
    }

    @DeleteMapping("/projects/stages/{stageId}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> deleteStage(@PathVariable String stageId) {
        return workspace.deleteStage(stageId);
    }

    @GetMapping("/projects/{id}/milestones")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public List<Map<String, Object>> milestones(@PathVariable String id) {
        return workspace.milestones(id);
    }

    @PostMapping("/projects/{id}/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> addMilestone(@PathVariable String id, @Valid @RequestBody MilestoneRequest r) {
        return workspace.addMilestone(id, r.name(), r.deadline(), r.description());
    }

    @PatchMapping("/projects/milestones/{milestoneId}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> updateMilestone(@PathVariable String milestoneId,
            @RequestBody Map<String, Object> body) {
        return workspace.updateMilestone(milestoneId, body);
    }

    @DeleteMapping("/projects/milestones/{milestoneId}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> deleteMilestone(@PathVariable String milestoneId) {
        return workspace.deleteMilestone(milestoneId);
    }

    @GetMapping("/projects/{id}/updates")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public List<Map<String, Object>> updates(@PathVariable String id) {
        return workspace.updates(id);
    }

    @PostMapping("/projects/{id}/updates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> postUpdate(@PathVariable String id, @Valid @RequestBody UpdateRequest r) {
        return workspace.postUpdate(id, r.status(), r.progress(), r.summary());
    }

    @GetMapping("/projects/{id}/messages")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public List<Map<String, Object>> messages(@PathVariable String id,
            @RequestParam(required = false) Boolean includeTasks) {
        return workspace.messages(id, Boolean.TRUE.equals(includeTasks));
    }

    @PostMapping("/projects/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public List<Map<String, Object>> postMessage(@PathVariable String id, @Valid @RequestBody MessageRequest r) {
        return workspace.postMessage(id, r.kind(), r.body(), r.mentions());
    }

    @PostMapping("/projects/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> duplicate(@PathVariable String id, @RequestBody DuplicateRequest r) {
        return workspace.duplicate(id, r.name(), r.code(), r.asTemplate(), r.includeTasks(), r.startDate());
    }

    @GetMapping("/project-tags")
    @RequirePermissions(Permissions.PROJECT_TASK__READ)
    public List<Map<String, Object>> tags() {
        return workspace.tags();
    }

    @PostMapping("/project-tags")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> createTag(@Valid @RequestBody TagRequest r) {
        return workspace.createTag(r.name(), r.color());
    }

    @PatchMapping("/project-tags/{id}")
    @RequirePermissions(Permissions.PROJECT_TASK__WRITE)
    public Map<String, Object> updateTag(@PathVariable String id, @Valid @RequestBody TagRequest r) {
        return workspace.updateTag(id, r.name(), r.color());
    }

    @DeleteMapping("/project-tags/{id}")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> deleteTag(@PathVariable String id) {
        return workspace.deleteTag(id);
    }
}
