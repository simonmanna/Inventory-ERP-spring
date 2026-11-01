package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.MfgCommands.FirmPlannedOrders;
import com.inventoryerp.manufacturing.application.MfgCommands.MrpRunInput;
import com.inventoryerp.manufacturing.application.MfgCommands.PlanningParams;
import com.inventoryerp.manufacturing.application.MrpCapacityService;
import com.inventoryerp.manufacturing.application.MrpPlanningService;
import com.inventoryerp.manufacturing.application.MrpSettingsService;
import com.inventoryerp.manufacturing.application.MrpSettingsService.SettingsInput;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Time-phased MRP runs, planned orders and per-product planning parameters (ADR-S006 package B). */
@RestController
@RequestMapping("/manufacturing")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class MrpPlanningController {

    private final MrpPlanningService mrp;
    private final MrpCapacityService capacity;
    private final MrpSettingsService settings;

    public MrpPlanningController(MrpPlanningService mrp, MrpCapacityService capacity, MrpSettingsService settings) {
        this.mrp = mrp;
        this.capacity = capacity;
        this.settings = settings;
    }

    /** PR-5: rough-cut capacity of a run's planned orders over scheduled work. */
    @GetMapping("/mrp/runs/{id}/capacity")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public Map<String, Object> capacity(@PathVariable String id, @RequestParam(required = false) String bucket) {
        return capacity.runCapacity(id, bucket);
    }

    /** PR-5: nightly run settings. */
    @GetMapping("/mrp/settings")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public Map<String, Object> settings() {
        return settings.get();
    }

    @PutMapping("/mrp/settings")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
    public Map<String, Object> updateSettings(@RequestBody SettingsInput dto) {
        return settings.update(dto);
    }

    @GetMapping("/planning-params")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public List<Map<String, Object>> params(@RequestParam(required = false) String productId) {
        return mrp.listParams(productId);
    }

    @PutMapping("/planning-params/{productId}")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
    public Map<String, Object> upsertParams(@PathVariable String productId, @Valid @RequestBody PlanningParams dto) {
        return mrp.upsertParams(productId, dto);
    }

    @PostMapping("/mrp/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_PLAN__CONFIRM)
    public Map<String, Object> run(@Valid @RequestBody(required = false) MrpRunInput dto) {
        return mrp.run(dto);
    }

    @GetMapping("/mrp/runs")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public List<Map<String, Object>> runs(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return mrp.listRuns(limit, offset);
    }

    @GetMapping("/mrp/runs/{id}")
    @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return mrp.get(id);
    }

    @PostMapping("/mrp/runs/{id}/firm")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_PLAN__CONFIRM)
    public Map<String, Object> firmRun(@PathVariable String id,
            @Valid @RequestBody(required = false) FirmPlannedOrders dto) {
        return mrp.firmRun(id, dto == null ? null : dto.ids());
    }

    @PostMapping("/mrp/planned-orders/{id}/firm")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_PLAN__CONFIRM)
    public Map<String, Object> firm(@PathVariable String id) {
        return mrp.firm(id);
    }

    @PostMapping("/mrp/planned-orders/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return mrp.cancel(id);
    }
}
