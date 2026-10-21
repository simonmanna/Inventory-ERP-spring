package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.ARR;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.KitchenStationService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/kitchen-stations} (port of {@code kitchen-station.controller.ts}). */
@RestController
@RequestMapping("/pos/kitchen-stations")
public class KitchenStationController {

    public record CreateStationRequest(
            @NotNull(message = STR) @Size(max = 60, message = "must be shorter than or equal to 60 characters") String name,
            @NotNull(message = STR) @Size(max = 32, message = "must be shorter than or equal to 32 characters") String code,
            String color,
            String icon,
            Integer displayOrder,
            Boolean isDefault,
            String printerId) {}

    public record UpdateStationRequest(
            @Size(max = 60, message = "must be shorter than or equal to 60 characters") String name,
            String color,
            String icon,
            Integer displayOrder,
            Boolean isActive,
            Boolean isDefault,
            String printerId) {}

    public record ReorderRequest(@NotNull(message = ARR) List<String> ids) {}

    private final KitchenStationService svc;

    public KitchenStationController(KitchenStationService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String includeInactive) {
        return svc.list(!"false".equals(includeInactive));
    }

    @PostMapping("/seed-defaults")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public List<Map<String, Object>> seedDefaults() {
        svc.ensureDefaults();
        return svc.list(true);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> create(@Valid @RequestBody CreateStationRequest dto) {
        return svc.create(PosDtos.data(dto));
    }

    @PatchMapping("/reorder")
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public List<Map<String, Object>> reorder(@Valid @RequestBody ReorderRequest dto) {
        return svc.reorder(dto.ids());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateStationRequest dto) {
        return svc.update(id, PosDtos.data(dto));
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> remove(@PathVariable String id) {
        return svc.remove(id);
    }
}
