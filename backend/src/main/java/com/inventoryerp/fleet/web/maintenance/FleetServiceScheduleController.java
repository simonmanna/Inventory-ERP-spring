package com.inventoryerp.fleet.web.maintenance;

import com.inventoryerp.fleet.application.FleetServiceScheduleService;
import com.inventoryerp.fleet.application.MaintenanceCommands.ApplyTemplateInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.DueQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.ScheduleInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.SchedulePatch;
import com.inventoryerp.fleet.application.MaintenanceCommands.ServiceTemplateInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.ServiceTemplatePatch;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
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

/**
 * Preventive maintenance planning (fleet 1.6): service templates per vehicle class, schedules per unit, the due /
 * overdue list and work-order generation for due schedules.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetServiceScheduleController {

    private final FleetServiceScheduleService schedules;

    public FleetServiceScheduleController(FleetServiceScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping("/service-templates")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public List<Map<String, Object>> templates(@RequestParam(required = false) String categoryId,
            @RequestParam(required = false) Boolean includeInactive) {
        return schedules.templates(categoryId, Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/service-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> createTemplate(@Valid @RequestBody ServiceTemplateInput dto) {
        return schedules.createTemplate(dto);
    }

    @PatchMapping("/service-templates/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> updateTemplate(@PathVariable String id, @Valid @RequestBody ServiceTemplatePatch dto) {
        return schedules.updateTemplate(id, dto);
    }

    @DeleteMapping("/service-templates/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> deleteTemplate(@PathVariable String id) {
        schedules.deleteTemplate(id);
        return Map.of("ok", true);
    }

    @PostMapping("/service-templates/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> apply(@PathVariable String id,
            @Valid @RequestBody(required = false) ApplyTemplateInput dto) {
        return schedules.applyTemplate(id, dto);
    }

    /** Due list: {@code state} = attention (due + overdue, default), due, overdue, ok, all. */
    @GetMapping("/service-schedules")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public PageResult<Map<String, Object>> due(
            @RequestParam(required = false) @OneOf({"attention", "due", "overdue", "ok", "all"}) String state,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return schedules.due(new DueQuery(state, vehicleId, categoryId, branchId, asOf, page, pageSize));
    }

    @GetMapping("/vehicles/{id}/service-schedules")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public List<Map<String, Object>> forVehicle(@PathVariable String id) {
        return schedules.forVehicle(id);
    }

    @PostMapping("/vehicles/{id}/service-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> createSchedule(@PathVariable String id, @Valid @RequestBody ScheduleInput dto) {
        return schedules.createSchedule(id, dto);
    }

    @PatchMapping("/service-schedules/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> updateSchedule(@PathVariable String id, @Valid @RequestBody SchedulePatch dto) {
        return schedules.updateSchedule(id, dto);
    }

    @DeleteMapping("/service-schedules/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> deleteSchedule(@PathVariable String id) {
        schedules.deleteSchedule(id);
        return Map.of("ok", true);
    }

    @PostMapping("/service-schedules/{id}/work-order")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__CREATE)
    public Map<String, Object> generateOne(@PathVariable String id) {
        return schedules.generateWorkOrder(id);
    }

    /** Raise work orders for every due / overdue schedule without a live one. */
    @PostMapping("/service-schedules/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> generateDue() {
        return schedules.generateDue("schedule");
    }
}
