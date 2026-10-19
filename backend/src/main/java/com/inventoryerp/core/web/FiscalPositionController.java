package com.inventoryerp.core.web;

import com.inventoryerp.core.application.FiscalPositionService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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

/**
 * {@code /fiscal-positions} (port of {@code fiscal-position.controller.ts}). PATCH takes the unvalidated
 * {@code Partial<UpsertFiscalPositionDto>} (erased generic).
 */
@RestController
@RequestMapping("/fiscal-positions")
public class FiscalPositionController {

    /** {@code UpsertFiscalPositionDto}. */
    public record UpsertFiscalPositionRequest(
            @NotNull(message = "must be a string") String code,
            @NotNull(message = "must be a string") String name,
            Boolean isActive,
            @Min(value = 0, message = "must not be less than 0") Integer sortOrder) {}

    private final FiscalPositionService positions;

    public FiscalPositionController(FiscalPositionService positions) {
        this.positions = positions;
    }

    @GetMapping
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public List<Map<String, Object>> list() {
        return positions.list();
    }

    @GetMapping("/deleted")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public List<Map<String, Object>> deleted() {
        return positions.deleted();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return positions.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ACCOUNT__CREATE)
    public Map<String, Object> create(@Valid @RequestBody UpsertFiscalPositionRequest dto) {
        return positions.create(dto.code(), dto.name(), dto.isActive(), dto.sortOrder());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return positions.update(id, dto == null ? Map.of() : dto);
    }

    @PatchMapping("/{id}/restore")
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> restore(@PathVariable String id) {
        return positions.restore(id);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__DELETE)
    public Map<String, Object> remove(@PathVariable String id) {
        return positions.remove(id);
    }
}
