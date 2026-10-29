package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.FleetCategoryService;
import com.inventoryerp.fleet.application.FleetCommands.CategoryInput;
import com.inventoryerp.fleet.application.FleetCommands.CategoryPatch;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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

/** Vehicle / equipment classes (fleet 1.1, ADR-S009): read with the register, managed under {@code fleet:settings}. */
@RestController
@RequestMapping("/fleet/categories")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetCategoryController {

    private final FleetCategoryService categories;

    public FleetCategoryController(FleetCategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) Boolean includeInactive) {
        return categories.list(Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> create(@Valid @RequestBody CategoryInput dto) {
        return categories.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody CategoryPatch dto) {
        return categories.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> delete(@PathVariable String id) {
        categories.delete(id);
        return Map.of("ok", true);
    }
}
