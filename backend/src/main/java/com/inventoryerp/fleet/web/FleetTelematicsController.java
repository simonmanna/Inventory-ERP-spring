package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.FleetTelematicsCommands.AcknowledgeInput;
import com.inventoryerp.fleet.application.FleetTelematicsCommands.DeviceInput;
import com.inventoryerp.fleet.application.FleetTelematicsCommands.DevicePatch;
import com.inventoryerp.fleet.application.FleetTelematicsCommands.EventQuery;
import com.inventoryerp.fleet.application.FleetTelematicsService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
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
 * Telematics (fleet 1.8, ADR-S009 §7): provider settings, units ↔ vehicles, sync now, live last positions, a
 * vehicle's positions / distance / last position, ignition events and alerts with acknowledgement.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetTelematicsController {

    private final FleetTelematicsService telematics;

    public FleetTelematicsController(FleetTelematicsService telematics) {
        this.telematics = telematics;
    }

    @GetMapping("/telematics/settings")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> settings() {
        return telematics.settings();
    }

    @GetMapping("/telematics/devices")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public List<Map<String, Object>> devices(@RequestParam(required = false) Boolean includeInactive,
            @RequestParam(required = false) String vehicleId) {
        return telematics.devices(Boolean.TRUE.equals(includeInactive), vehicleId);
    }

    @PostMapping("/telematics/devices")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> createDevice(@Valid @RequestBody DeviceInput dto) {
        return telematics.createDevice(dto);
    }

    @PatchMapping("/telematics/devices/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> updateDevice(@PathVariable String id, @Valid @RequestBody DevicePatch dto) {
        return telematics.updateDevice(id, dto);
    }

    @DeleteMapping("/telematics/devices/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> deleteDevice(@PathVariable String id) {
        return telematics.deleteDevice(id);
    }

    /** Pull the provider now for the caller's organization (the job does it every 5 minutes). */
    @PostMapping("/telematics/sync")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public FleetTelematicsService.SyncResult sync() {
        return telematics.syncNow();
    }

    @GetMapping("/telematics/positions/latest")
    @RequirePermissions(Permissions.FLEET_TRIP__READ)
    public List<Map<String, Object>> latest() {
        return telematics.latest();
    }

    @GetMapping("/vehicles/{id}/positions")
    @RequirePermissions(Permissions.FLEET_TRIP__READ)
    public PageResult<Map<String, Object>> positions(@PathVariable String id,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(500) Integer pageSize) {
        return telematics.positions(id, from, to, page, pageSize);
    }

    @GetMapping("/vehicles/{id}/distance")
    @RequirePermissions(Permissions.FLEET_TRIP__READ)
    public Map<String, Object> distance(@PathVariable String id, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return telematics.distance(id, from, to);
    }

    @GetMapping("/vehicles/{id}/last-position")
    @RequirePermissions(Permissions.FLEET_TRIP__READ)
    public Map<String, Object> lastPosition(@PathVariable String id) {
        return telematics.lastPosition(id);
    }

    @GetMapping("/telematics/events")
    @RequirePermissions(Permissions.FLEET_TRIP__READ)
    public PageResult<Map<String, Object>> events(@RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Boolean alertsOnly,
            @RequestParam(required = false) Boolean open,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return telematics.events(new EventQuery(vehicleId, eventType, alertsOnly, open, from, to, page, pageSize));
    }

    @PostMapping("/telematics/events/{id}/acknowledge")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_TRIP__UPDATE)
    public Map<String, Object> acknowledge(@PathVariable String id, @Valid @RequestBody AcknowledgeInput dto) {
        return telematics.acknowledge(id, dto.note());
    }
}
