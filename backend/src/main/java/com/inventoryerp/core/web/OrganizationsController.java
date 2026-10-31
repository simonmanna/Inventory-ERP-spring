package com.inventoryerp.core.web;

import com.inventoryerp.core.application.OrganizationsService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /organizations} (port of {@code organizations.controller.ts}). Deviation (security, owner-approved
 * 2026-10-31): Nest let any signed-in user invite users, deactivate users and change organization settings; those
 * routes now need {@code user:create}, {@code user:update} and {@code organization:update}.
 */
@RestController
@RequestMapping("/organizations")
public class OrganizationsController {

    /** {@code BootstrapDto}. */
    public record BootstrapRequest(
            @NotNull(message = "must be a string") String organizationCode,
            @NotNull(message = "must be a string") String organizationName,
            String timezone,
            String currencyCode,
            @NotNull(message = "must be an email") @Email(message = "must be an email") String adminEmail,
            @NotNull(message = "must be a string") String adminFirstName,
            String adminLastName,
            @Size(min = 8, message = "must be longer than or equal to 8 characters") String adminPassword) {}

    /** {@code AcceptInviteDto}. */
    public record AcceptInviteRequest(
            @NotNull(message = "must be a string") String token,
            @NotNull(message = "must be a string")
            @Size(min = 8, message = "must be longer than or equal to 8 characters") String newPassword) {}

    /** {@code UpdateSettingsDto}. */
    public record UpdateSettingsRequest(String name, String timezone, String currencyCode, Map<String, Object> settings) {}

    /** {@code InviteUserDto}. */
    public record InviteUserRequest(
            @NotNull(message = "must be an email") @Email(message = "must be an email") String email,
            @NotNull(message = "must be a string") String firstName,
            String lastName,
            String roleId) {}

    private final OrganizationsService svc;

    public OrganizationsController(OrganizationsService svc) {
        this.svc = svc;
    }

    /** Public: create a new tenant. */
    @Public
    @PostMapping("/bootstrap")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> bootstrap(@Valid @RequestBody BootstrapRequest dto) {
        return svc.bootstrap(new OrganizationsService.BootstrapInput(dto.organizationCode(), dto.organizationName(),
                dto.timezone(), dto.currencyCode(), dto.adminEmail(), dto.adminFirstName(), dto.adminLastName(),
                dto.adminPassword()));
    }

    @Public
    @PostMapping("/accept-invite")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> acceptInvite(@Valid @RequestBody AcceptInviteRequest dto) {
        return svc.acceptInvite(dto.token(), dto.newPassword());
    }

    @GetMapping("/me")
    public Map<String, Object> me(AuthUser user) {
        return svc.me(user.organizationId());
    }

    @PatchMapping("/me/settings")
    @RequirePermissions(Permissions.ORGANIZATION__UPDATE)
    public Map<String, Object> updateSettings(@Valid @RequestBody UpdateSettingsRequest dto) {
        return svc.updateSettings(dto.name(), dto.timezone(), dto.currencyCode(), dto.settings(),
                EntityPatch.present("name", dto.name()), EntityPatch.present("timezone", dto.timezone()),
                EntityPatch.present("currencyCode", dto.currencyCode()), EntityPatch.present("settings", dto.settings()));
    }

    /** Set the caller's active branch; {@code defaultBranchId: null} grants org-wide access. */
    @PatchMapping("/me/branch")
    public Map<String, Object> setMyBranch(AuthUser user, @RequestBody(required = false) Map<String, Object> dto) {
        Object branch = dto == null ? null : dto.get("defaultBranchId");
        return svc.setMyBranch(user.sub(), branch == null ? null : String.valueOf(branch));
    }

    @GetMapping("/users")
    public List<Map<String, Object>> listUsers() {
        return svc.listUsers();
    }

    @PostMapping("/users/invite")
    @RequirePermissions(Permissions.USER__CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> invite(@Valid @RequestBody InviteUserRequest dto) {
        return svc.inviteUser(dto.email(), dto.firstName(), dto.lastName(), dto.roleId());
    }

    @PatchMapping("/users/{id}/deactivate")
    @RequirePermissions(Permissions.USER__UPDATE)
    public Map<String, Object> deactivate(@PathVariable String id) {
        return svc.deactivate(id);
    }
}
