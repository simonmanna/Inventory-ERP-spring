package com.inventoryerp.kernel.staff;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.hibernate.validator.constraints.UniqueElements;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /roles} (port of {@code roles.controller.ts}). */
@RestController
@RequestMapping("/roles")
public class RolesController {

    static final String NAME_MESSAGE = "name may contain letters, digits, spaces, dashes, dots and underscores only";

    /** {@code CreateRoleDto}. */
    public record CreateRoleRequest(
            @NotNull(message = "must be a string")
            @Size(min = 2, max = 64, message = "must be longer than or equal to 2 and shorter than or equal to 64 characters")
            @Pattern(regexp = "^[A-Za-z0-9 _\\-.]+$", message = NAME_MESSAGE) String name,
            @Size(max = 250, message = "must be shorter than or equal to 250 characters") String description,
            @NotNull(message = "must be an array")
            @UniqueElements(message = "All permissions's elements must be unique") List<@NotNull(message = "each value in permissions must be a string") String> permissions,
            Boolean isSystem) {}

    /** {@code UpdateRoleDto}. */
    public record UpdateRoleRequest(
            @Size(min = 2, max = 64, message = "must be longer than or equal to 2 and shorter than or equal to 64 characters")
            @Pattern(regexp = "^[A-Za-z0-9 _\\-.]+$", message = NAME_MESSAGE) String name,
            @Size(max = 250, message = "must be shorter than or equal to 250 characters") String description,
            @UniqueElements(message = "All permissions's elements must be unique") List<String> permissions) {}

    private final RolesService roles;

    public RolesController(RolesService roles) {
        this.roles = roles;
    }

    @GetMapping
    @RequirePermissions(Permissions.ROLE__READ)
    public List<Map<String, Object>> list() {
        return roles.list();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.ROLE__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return roles.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ROLE__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateRoleRequest dto) {
        return roles.create(new RolesService.CreateRole(dto.name(), dto.description(), dto.permissions(), dto.isSystem()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.ROLE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateRoleRequest dto) {
        return roles.update(id, new RolesService.UpdateRole(dto.name(), dto.description(), dto.permissions()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.ROLE__DELETE)
    public void remove(@PathVariable String id) {
        roles.remove(id);
    }
}
