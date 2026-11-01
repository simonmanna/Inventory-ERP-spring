package com.inventoryerp.kernel.apiclients;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api-clients}: OAuth client-credentials API clients (ADR-S029 §5, extension). The secret is shown once. */
@RestController
@RequestMapping("/api-clients")
public class ApiClientsController {

    public record CreateClientRequest(
            @NotBlank(message = "name should not be empty") @Size(max = 120, message = "name must be at most 120"
                    + " characters") String name,
            @NotEmpty(message = "scopes should not be empty") List<String> scopes,
            Instant expiresAt) {}

    public record UpdateClientRequest(
            @Size(min = 1, max = 120, message = "name must be 1 to 120 characters") String name,
            List<String> scopes,
            Boolean isActive,
            Instant expiresAt,
            Boolean clearExpiry) {}

    private final ApiClientService clients;

    public ApiClientsController(ApiClientService clients) {
        this.clients = clients;
    }

    @GetMapping
    @RequirePermissions(Permissions.API_CLIENT__READ)
    public List<Map<String, Object>> list() {
        return clients.list();
    }

    @GetMapping("/scopes")
    @RequirePermissions(Permissions.API_CLIENT__READ)
    public Map<String, Object> scopes() {
        return clients.scopes();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.API_CLIENT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return clients.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.API_CLIENT__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody CreateClientRequest dto) {
        return clients.create(dto.name(), dto.scopes(), dto.expiresAt());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.API_CLIENT__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateClientRequest dto) {
        return clients.update(id, dto.name(), dto.scopes(), dto.isActive(), dto.expiresAt(),
                Boolean.TRUE.equals(dto.clearExpiry()));
    }

    @PostMapping("/{id}/rotate-secret")
    @RequirePermissions(Permissions.API_CLIENT__MANAGE)
    public Map<String, Object> rotate(@PathVariable String id) {
        return clients.rotate(id);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.API_CLIENT__MANAGE)
    public Map<String, Object> revoke(@PathVariable String id) {
        return clients.revoke(id);
    }
}
