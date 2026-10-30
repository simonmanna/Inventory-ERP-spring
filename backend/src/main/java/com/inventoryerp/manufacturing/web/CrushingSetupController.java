package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.CrushingCommands.PlantInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.ProfileInput;
import com.inventoryerp.manufacturing.application.CrushingPlantService;
import com.inventoryerp.manufacturing.application.CrushingProfileService;
import com.inventoryerp.manufacturing.application.CrushingReportService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Crushing &amp; screening set-up (Track C 2.3, ADR-S016): form lookups, crushing plants with their circuit (stages →
 * routing operations, crushers / screens / conveyors → work centres with capacity and meters) and grading profiles
 * (expected yield split per plant × feed; activation builds the system-managed BOM of the multi-stage engine).
 * PUT replaces the editable fields (whole form).
 */
@RestController
@RequestMapping("/manufacturing/crushing")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class CrushingSetupController {

    private final CrushingPlantService plants;
    private final CrushingProfileService profiles;
    private final CrushingReportService reports;

    public CrushingSetupController(CrushingPlantService plants, CrushingProfileService profiles,
            CrushingReportService reports) {
        this.plants = plants;
        this.profiles = profiles;
        this.reports = reports;
    }

    // ------------------------------------------------------------------ lookups

    @GetMapping("/lookups")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public Map<String, Object> lookups() {
        return reports.lookups();
    }

    @GetMapping("/lookups/products")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public List<Map<String, Object>> products(@RequestParam(required = false) String search) {
        return reports.products(search);
    }

    // ------------------------------------------------------------------ plants

    @GetMapping("/plants")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public List<Map<String, Object>> plants(@RequestParam(required = false) Boolean includeInactive) {
        return plants.list(includeInactive);
    }

    @PostMapping("/plants")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CRUSHING__SETUP)
    public Map<String, Object> createPlant(@Valid @RequestBody PlantInput dto) {
        return plants.create(dto);
    }

    @GetMapping("/plants/{id}")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public Map<String, Object> plant(@PathVariable String id) {
        return plants.get(id);
    }

    @PutMapping("/plants/{id}")
    @RequirePermissions(Permissions.CRUSHING__SETUP)
    public Map<String, Object> updatePlant(@PathVariable String id, @Valid @RequestBody PlantInput dto) {
        return plants.update(id, dto);
    }

    @DeleteMapping("/plants/{id}")
    @RequirePermissions(Permissions.CRUSHING__SETUP)
    public Map<String, Object> deletePlant(@PathVariable String id) {
        plants.remove(id);
        return Map.of("id", id, "deleted", true);
    }

    // ------------------------------------------------------------------ grading profiles

    @GetMapping("/grading-profiles")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public List<Map<String, Object>> profiles(@RequestParam(required = false) String plantId,
            @RequestParam(required = false) String status) {
        return profiles.list(plantId, status);
    }

    @PostMapping("/grading-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CRUSHING__SETUP)
    public Map<String, Object> createProfile(@Valid @RequestBody ProfileInput dto) {
        return profiles.create(dto);
    }

    @GetMapping("/grading-profiles/{id}")
    @RequirePermissions(Permissions.CRUSHING__READ)
    public Map<String, Object> profile(@PathVariable String id) {
        return profiles.get(id);
    }

    @PutMapping("/grading-profiles/{id}")
    @RequirePermissions(Permissions.CRUSHING__SETUP)
    public Map<String, Object> updateProfile(@PathVariable String id, @Valid @RequestBody ProfileInput dto) {
        return profiles.update(id, dto);
    }

    @DeleteMapping("/grading-profiles/{id}")
    @RequirePermissions(Permissions.CRUSHING__SETUP)
    public Map<String, Object> deleteProfile(@PathVariable String id) {
        profiles.remove(id);
        return Map.of("id", id, "deleted", true);
    }

    @PostMapping("/grading-profiles/{id}/activate")
    @RequirePermissions(Permissions.CRUSHING__SETUP)
    public Map<String, Object> activate(@PathVariable String id) {
        return profiles.activate(id);
    }

    @PostMapping("/grading-profiles/{id}/new-version")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CRUSHING__SETUP)
    public Map<String, Object> newVersion(@PathVariable String id) {
        return profiles.newVersion(id);
    }

    @PostMapping("/grading-profiles/{id}/archive")
    @RequirePermissions(Permissions.CRUSHING__SETUP)
    public Map<String, Object> archive(@PathVariable String id) {
        return profiles.archive(id);
    }
}
