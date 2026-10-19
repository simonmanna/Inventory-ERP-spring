package com.inventoryerp.kernel.staff;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /users} (port of {@code users.controller.ts}). */
@RestController
@RequestMapping("/users")
public class UsersController {

    /** {@code CreateUserDto}. */
    public record CreateUserRequest(
            @NotNull(message = "must be an email") @Email(message = "must be an email") String email,
            @NotNull(message = "must be a string")
            @Size(min = 10, message = "password must be at least 10 characters")
            @Size(max = 128, message = "must be shorter than or equal to 128 characters") String password,
            @NotNull(message = "must be a string")
            @Size(min = 1, max = 64, message = "must be longer than or equal to 1 and shorter than or equal to 64 characters") String firstName,
            @Size(max = 64, message = "must be shorter than or equal to 64 characters") String lastName,
            Boolean isActive,
            @NotNull(message = "must be an array")
            @UniqueElements(message = "All roleIds's elements must be unique") List<String> roleIds) {}

    /** {@code UpdateUserDto}. */
    public record UpdateUserRequest(
            @Size(min = 1, max = 64, message = "must be longer than or equal to 1 and shorter than or equal to 64 characters") String firstName,
            @Size(max = 64, message = "must be shorter than or equal to 64 characters") String lastName,
            @Email(message = "must be an email") String email,
            Boolean isActive,
            @UniqueElements(message = "All roleIds's elements must be unique") List<String> roleIds) {}

    /** Staff {@code ResetPasswordDto}. */
    public record ResetPasswordRequest(
            @NotNull(message = "must be a string")
            @Size(min = 10, message = "new password must be at least 10 characters")
            @Size(max = 128, message = "must be shorter than or equal to 128 characters") String newPassword) {}

    /** {@code SetPinDto}. */
    public record SetPinRequest(
            @NotNull(message = "must be a string") @Pattern(regexp = "^\\d{4,8}$", message = "PIN must be 4–8 digits") String pin) {}

    private final UsersService users;

    public UsersController(UsersService users) {
        this.users = users;
    }

    @GetMapping
    @RequirePermissions(Permissions.USER__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        String page = query.get("page");
        String pageSize = query.get("pageSize");
        String linked = query.get("linked");
        return users.list(query.get("search"), page == null || page.isEmpty() ? null : (int) jsNumber(page),
                pageSize == null || pageSize.isEmpty() ? null : (int) jsNumber(pageSize),
                "true".equals(linked) ? Boolean.TRUE : "false".equals(linked) ? Boolean.FALSE : null);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.USER__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return users.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.USER__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateUserRequest dto) {
        return users.create(new UsersService.CreateUser(dto.email(), dto.password(), dto.firstName(), dto.lastName(),
                dto.isActive(), dto.roleIds()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.USER__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest dto) {
        return users.update(id, new UsersService.UpdateUser(dto.firstName(), dto.lastName(), dto.email(), dto.isActive(),
                dto.roleIds()));
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.USER__UPDATE)
    public void resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordRequest dto) {
        users.resetPassword(id, dto.newPassword());
    }

    @PostMapping("/{id}/pin")
    @RequirePermissions(Permissions.USER__UPDATE)
    public Map<String, Object> setPin(@PathVariable String id, @Valid @RequestBody SetPinRequest dto) {
        return users.setPin(id, dto.pin());
    }

    @DeleteMapping("/{id}/pin")
    @RequirePermissions(Permissions.USER__UPDATE)
    public Map<String, Object> clearPin(@PathVariable String id) {
        return users.clearPin(id);
    }

    @PostMapping("/{id}/unlock")
    @RequirePermissions(Permissions.USER__UPDATE)
    public Map<String, Object> unlock(@PathVariable String id) {
        return users.unlock(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.USER__DELETE)
    public void remove(@PathVariable String id) {
        users.remove(id);
    }

    private static double jsNumber(String v) {
        double n = com.inventoryerp.kernel.common.JsNumbers.number(v);
        return Double.isNaN(n) ? 1 : n;
    }
}
