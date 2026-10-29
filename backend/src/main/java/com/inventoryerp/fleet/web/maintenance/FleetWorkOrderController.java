package com.inventoryerp.fleet.web.maintenance;

import com.inventoryerp.fleet.application.FleetMaintenanceLookupService;
import com.inventoryerp.fleet.application.FleetWorkOrderCostService;
import com.inventoryerp.fleet.application.FleetWorkOrderService;
import com.inventoryerp.fleet.application.MaintenanceCommands.CompleteInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.ExternalBillInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.LabourInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.PartIssueInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.PartReturnInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.ReasonInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.TaskInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.TaskPatch;
import com.inventoryerp.fleet.application.MaintenanceCommands.WorkOrderInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.WorkOrderPatch;
import com.inventoryerp.fleet.application.MaintenanceCommands.WorkOrderQuery;
import com.inventoryerp.kernel.idempotency.Idempotent;
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
 * Fleet maintenance work orders (1.6, ADR-S009 §5): list / detail / create / edit, lifecycle actions, tasks, and the
 * cost lines — spare parts issued from stock (and returned), labour, external workshop bills and reversals. Cost
 * routes move money: {@code Idempotency-Key} required, a retry replays the first answer.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetWorkOrderController {

    private final FleetWorkOrderService workOrders;
    private final FleetWorkOrderCostService costs;
    private final FleetMaintenanceLookupService lookups;

    public FleetWorkOrderController(FleetWorkOrderService workOrders, FleetWorkOrderCostService costs,
            FleetMaintenanceLookupService lookups) {
        this.workOrders = workOrders;
        this.costs = costs;
        this.lookups = lookups;
    }

    @GetMapping("/work-orders")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false)
            @OneOf({"live", "draft", "open", "in_progress", "completed", "closed", "cancelled"}) String status,
            @RequestParam(required = false)
            @OneOf({"preventive", "corrective", "breakdown", "inspection_defect"}) String type,
            @RequestParam(required = false) @OneOf({"low", "normal", "high", "urgent"}) String priority,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize,
            @RequestParam(required = false)
            @OneOf({"code", "reportedAt", "dueOn", "priority", "status", "totalCost"}) String sortBy,
            @RequestParam(required = false) @OneOf({"asc", "desc"}) String sortOrder) {
        return workOrders.list(new WorkOrderQuery(search, vehicleId, status, type, priority, from, to, page, pageSize,
                sortBy, sortOrder));
    }

    @GetMapping("/work-orders/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return workOrders.get(id);
    }

    @PostMapping("/work-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__CREATE)
    public Map<String, Object> create(@Valid @RequestBody WorkOrderInput dto) {
        return workOrders.create(dto);
    }

    @PatchMapping("/work-orders/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody WorkOrderPatch dto) {
        return workOrders.update(id, dto);
    }

    @PostMapping("/work-orders/{id}/open")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> open(@PathVariable String id) {
        return workOrders.open(id);
    }

    @PostMapping("/work-orders/{id}/start")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> start(@PathVariable String id) {
        return workOrders.start(id);
    }

    @PostMapping("/work-orders/{id}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__COMPLETE)
    public Map<String, Object> complete(@PathVariable String id,
            @Valid @RequestBody(required = false) CompleteInput dto) {
        return workOrders.complete(id, dto);
    }

    @PostMapping("/work-orders/{id}/close")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__COMPLETE)
    public Map<String, Object> close(@PathVariable String id) {
        return workOrders.close(id);
    }

    @PostMapping("/work-orders/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return workOrders.cancel(id, dto.reason());
    }

    @PostMapping("/work-orders/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> addTask(@PathVariable String id, @Valid @RequestBody TaskInput dto) {
        return workOrders.addTask(id, dto);
    }

    @PatchMapping("/work-orders/{id}/tasks/{taskId}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> updateTask(@PathVariable String id, @PathVariable String taskId,
            @Valid @RequestBody TaskPatch dto) {
        return workOrders.updateTask(id, taskId, dto);
    }

    @DeleteMapping("/work-orders/{id}/tasks/{taskId}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> deleteTask(@PathVariable String id, @PathVariable String taskId) {
        return workOrders.deleteTask(id, taskId);
    }

    // ------------------------------------------------------------------ cost lines (money)

    @PostMapping("/work-orders/{id}/parts")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__COMPLETE)
    public Map<String, Object> issuePart(@PathVariable String id, @Valid @RequestBody PartIssueInput dto) {
        return costs.issuePart(id, dto);
    }

    @PostMapping("/work-orders/{id}/lines/{lineId}/return")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__COMPLETE)
    public Map<String, Object> returnPart(@PathVariable String id, @PathVariable String lineId,
            @Valid @RequestBody PartReturnInput dto) {
        return costs.returnPart(id, lineId, dto);
    }

    @PostMapping("/work-orders/{id}/labour")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__COMPLETE)
    public Map<String, Object> labour(@PathVariable String id, @Valid @RequestBody LabourInput dto) {
        return costs.addLabour(id, dto);
    }

    @PostMapping("/work-orders/{id}/bills")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__COMPLETE)
    public Map<String, Object> bill(@PathVariable String id, @Valid @RequestBody ExternalBillInput dto) {
        return costs.addBill(id, dto);
    }

    @PostMapping("/work-orders/{id}/lines/{lineId}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__COMPLETE)
    public Map<String, Object> reverse(@PathVariable String id, @PathVariable String lineId,
            @Valid @RequestBody ReasonInput dto) {
        return costs.reverse(id, lineId, dto.reason());
    }

    // ------------------------------------------------------------------ pickers

    @GetMapping("/maintenance/lookups/parts")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public List<Map<String, Object>> parts(@RequestParam(required = false) String search,
            @RequestParam(required = false) String locationId) {
        return lookups.parts(search, locationId);
    }

    @GetMapping("/maintenance/lookups/employees")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public List<Map<String, Object>> employees(@RequestParam(required = false) String search) {
        return lookups.employees(search);
    }
}
