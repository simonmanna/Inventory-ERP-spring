package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.DriverCommands.DriverInput;
import com.inventoryerp.fleet.application.DriverCommands.DriverPatch;
import com.inventoryerp.fleet.application.DriverCommands.DriverQuery;
import com.inventoryerp.fleet.application.FleetDriverService;
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
 * Driver &amp; operator profiles (fleet 1.2, ADR-S009): list / detail / create / edit / delete, eligibility check for
 * a vehicle, the expiring-documents query (licence, medical, permit) and the hr employee picker.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetDriverController {

    private final FleetDriverService drivers;

    public FleetDriverController(FleetDriverService drivers) {
        this.drivers = drivers;
    }

    @GetMapping("/drivers")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"active", "suspended", "inactive"}) String status,
            @RequestParam(required = false) @OneOf({"driver", "operator", "driver_operator"}) String driverType,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) @OneOf({"attention", "expired", "expiring"}) String documents,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize,
            @RequestParam(required = false)
            @OneOf({"code", "name", "status", "licenceExpiresOn", "createdAt"}) String sortBy,
            @RequestParam(required = false) @OneOf({"asc", "desc"}) String sortOrder) {
        return drivers.list(new DriverQuery(search, status, driverType, categoryId, documents, page, pageSize, sortBy,
                sortOrder));
    }

    @GetMapping("/drivers/{id}")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return drivers.get(id);
    }

    @PostMapping("/drivers")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DRIVER__CREATE)
    public Map<String, Object> create(@Valid @RequestBody DriverInput dto) {
        return drivers.create(dto);
    }

    @PatchMapping("/drivers/{id}")
    @RequirePermissions(Permissions.FLEET_DRIVER__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody DriverPatch dto) {
        return drivers.update(id, dto);
    }

    @DeleteMapping("/drivers/{id}")
    @RequirePermissions(Permissions.FLEET_DRIVER__DELETE)
    public Map<String, Object> delete(@PathVariable String id) {
        drivers.delete(id);
        return Map.of("ok", true);
    }

    /** May the driver take the vehicle on {@code on} (default today)? {@code {eligible, reasons[]}}. */
    @GetMapping("/drivers/{id}/eligibility")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public Map<String, Object> eligibility(@PathVariable String id, @RequestParam String vehicleId,
            @RequestParam(required = false) LocalDate on,
            @RequestParam(required = false) @OneOf({"assigned", "checked out"}) String action) {
        return drivers.eligibility(id, vehicleId, on, action);
    }

    /** Expired / expiring licences, medical certificates and permits, soonest first (1.9 reminders use it too). */
    @GetMapping("/driver-documents")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public PageResult<Map<String, Object>> documentsDue(
            @RequestParam(required = false) @OneOf({"attention", "expired", "expiring", "all"}) String due,
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return drivers.documentsDue(due, asOf, page, pageSize);
    }

    @GetMapping("/lookups/employees")
    @RequirePermissions(Permissions.FLEET_DRIVER__READ)
    public List<Map<String, Object>> employees(@RequestParam(required = false) String search) {
        return drivers.employees(search);
    }
}
