package com.inventoryerp.dms.web;

import com.inventoryerp.dms.application.DmsWorkspaceService;
import com.inventoryerp.dms.application.DmsWorkspaceService.AccessRuleInput;
import com.inventoryerp.dms.application.DmsWorkspaceService.WorkspaceInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /dms/workspaces|folders|tags|tag-categories} (DMS, ADR-S044). */
@RestController
@RequestMapping("/dms")
public class DmsWorkspaceController {

    public record WorkspaceRequest(
            @Size(max = 40, message = "code must be at most 40 characters") String code,
            @NotBlank(message = "name should not be empty")
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            @Size(max = 2000, message = "description must be at most 2000 characters") String description,
            String parentId,
            String icon,
            String color,
            String requiredPermission,
            Boolean requiresApproval,
            Integer sortOrder) {}

    public record WorkspacePatch(
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            String description,
            String parentId,
            String icon,
            String color,
            String requiredPermission,
            Boolean requiresApproval,
            Integer sortOrder) {}

    public record AccessRule(String userId, String roleId, @NotBlank(message = "level should not be empty") String level) {}

    public record AccessRequest(@NotNull(message = "rules must be an array") List<@Valid AccessRule> rules) {}

    public record FolderRequest(
            @NotBlank(message = "workspaceId should not be empty") String workspaceId,
            String parentId,
            @NotBlank(message = "name should not be empty")
            @Size(max = 120, message = "name must be at most 120 characters") String name) {}

    public record FolderPatch(
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            String parentId,
            Boolean moveToRoot) {}

    public record TagRequest(
            @NotBlank(message = "name should not be empty")
            @Size(max = 60, message = "name must be at most 60 characters") String name,
            String categoryId,
            String color) {}

    public record TagPatch(@Size(max = 60, message = "name must be at most 60 characters") String name,
            String categoryId, String color) {}

    public record CategoryRequest(
            @NotBlank(message = "name should not be empty")
            @Size(max = 60, message = "name must be at most 60 characters") String name,
            Integer sortOrder) {}

    private final DmsWorkspaceService workspaces;

    public DmsWorkspaceController(DmsWorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping("/workspaces")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public List<Map<String, Object>> list() {
        return workspaces.list();
    }

    @PostMapping("/workspaces")
    @RequirePermissions(Permissions.DMS__WORKSPACE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody WorkspaceRequest r) {
        return workspaces.create(new WorkspaceInput(r.code(), r.name(), r.description(), r.parentId(), r.icon(),
                r.color(), r.requiredPermission(), r.requiresApproval(), r.sortOrder()));
    }

    @PatchMapping("/workspaces/{id}")
    @RequirePermissions(Permissions.DMS__WORKSPACE_MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody WorkspacePatch r) {
        return workspaces.update(id, new WorkspaceInput(null, r.name(), r.description(), r.parentId(), r.icon(),
                r.color(), r.requiredPermission(), r.requiresApproval(), r.sortOrder()));
    }

    @DeleteMapping("/workspaces/{id}")
    @RequirePermissions(Permissions.DMS__WORKSPACE_MANAGE)
    public Map<String, Object> archive(@PathVariable String id) {
        return workspaces.archive(id);
    }

    @GetMapping("/workspaces/{id}/access")
    @RequirePermissions(Permissions.DMS__WORKSPACE_MANAGE)
    public List<Map<String, Object>> access(@PathVariable String id) {
        return workspaces.accessRules(id);
    }

    @PutMapping("/workspaces/{id}/access")
    @RequirePermissions(Permissions.DMS__WORKSPACE_MANAGE)
    public List<Map<String, Object>> replaceAccess(@PathVariable String id, @Valid @RequestBody AccessRequest r) {
        return workspaces.replaceAccessRules(id, r.rules().stream()
                .map(a -> new AccessRuleInput(DmsParts.blank(a.userId()), DmsParts.blank(a.roleId()), a.level()))
                .toList());
    }

    @GetMapping("/workspaces/{id}/folders")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public List<Map<String, Object>> folders(@PathVariable String id) {
        return workspaces.folders(id);
    }

    @PostMapping("/folders")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createFolder(@Valid @RequestBody FolderRequest r) {
        return workspaces.createFolder(r.workspaceId(), DmsParts.blank(r.parentId()), r.name());
    }

    @PatchMapping("/folders/{id}")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> updateFolder(@PathVariable String id, @Valid @RequestBody FolderPatch r) {
        boolean move = r.parentId() != null || Boolean.TRUE.equals(r.moveToRoot());
        return workspaces.updateFolder(id, r.name(), Boolean.TRUE.equals(r.moveToRoot()) ? null : r.parentId(), move);
    }

    @DeleteMapping("/folders/{id}")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> deleteFolder(@PathVariable String id) {
        workspaces.deleteFolder(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/tags")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public List<Map<String, Object>> tags() {
        return workspaces.tags();
    }

    @PostMapping("/tags")
    @RequirePermissions(Permissions.DMS__TAG_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTag(@Valid @RequestBody TagRequest r) {
        return workspaces.createTag(r.name(), DmsParts.blank(r.categoryId()), r.color());
    }

    @PatchMapping("/tags/{id}")
    @RequirePermissions(Permissions.DMS__TAG_MANAGE)
    public Map<String, Object> updateTag(@PathVariable String id, @Valid @RequestBody TagPatch r) {
        return workspaces.updateTag(id, r.name(), DmsParts.blank(r.categoryId()), r.color());
    }

    @DeleteMapping("/tags/{id}")
    @RequirePermissions(Permissions.DMS__TAG_MANAGE)
    public Map<String, Object> deleteTag(@PathVariable String id) {
        workspaces.deleteTag(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/tag-categories")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public List<Map<String, Object>> categories() {
        return workspaces.tagCategories();
    }

    @PostMapping("/tag-categories")
    @RequirePermissions(Permissions.DMS__TAG_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCategory(@Valid @RequestBody CategoryRequest r) {
        return workspaces.createTagCategory(r.name(), r.sortOrder());
    }

    @DeleteMapping("/tag-categories/{id}")
    @RequirePermissions(Permissions.DMS__TAG_MANAGE)
    public Map<String, Object> deleteCategory(@PathVariable String id) {
        workspaces.deleteTagCategory(id);
        return Map.of("deleted", true);
    }
}
