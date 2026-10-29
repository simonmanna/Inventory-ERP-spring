package com.inventoryerp.fleet.web.maintenance;

import com.inventoryerp.fleet.application.FleetDowntimeService;
import com.inventoryerp.fleet.application.MaintenanceCommands.DowntimeEnd;
import com.inventoryerp.fleet.application.MaintenanceCommands.DowntimeInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.DowntimePatch;
import com.inventoryerp.fleet.application.MaintenanceCommands.DowntimeQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.ReliabilityQuery;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Downtime log of units and the availability / MTBF / MTTR report (fleet 1.6). */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetDowntimeController {

    private final FleetDowntimeService downtime;

    public FleetDowntimeController(FleetDowntimeService downtime) {
        this.downtime = downtime;
    }

    @GetMapping("/downtime")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) @OneOf({"planned", "unplanned"}) String kind,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) Boolean open,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return downtime.list(new DowntimeQuery(vehicleId, kind, reasonCode, open, from, to, page, pageSize));
    }

    @GetMapping("/downtime/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return downtime.get(id);
    }

    @PostMapping("/downtime")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__CREATE)
    public Map<String, Object> start(@Valid @RequestBody DowntimeInput dto) {
        return downtime.start(dto);
    }

    @PostMapping("/downtime/{id}/end")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> end(@PathVariable String id, @Valid @RequestBody(required = false) DowntimeEnd dto) {
        return downtime.end(id, dto);
    }

    @PatchMapping("/downtime/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody DowntimePatch dto) {
        return downtime.update(id, dto);
    }

    /** Availability %, MTBF, MTTR per unit or class over a period (default the last 30 days). */
    @GetMapping("/maintenance/reliability")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public Map<String, Object> reliability(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @OneOf({"vehicle", "class"}) String groupBy,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String branchId) {
        return downtime.reliability(new ReliabilityQuery(from, to, groupBy, vehicleId, categoryId, branchId));
    }
}
