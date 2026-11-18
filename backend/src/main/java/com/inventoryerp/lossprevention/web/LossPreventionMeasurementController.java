package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.lossprevention.application.LpMeasurementCommands.CreateDeviceInput;
import com.inventoryerp.lossprevention.application.LpMeasurementCommands.UpdateDeviceInput;
import com.inventoryerp.lossprevention.application.LpMeasurementService;
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

/**
 * {@code /loss-prevention/measurement-devices} (LP3): pour spouts, flow meters and dispensers. Meters are not cameras:
 * the routes need the module, not the {@code lpVision} licence feature.
 */
@RestController
@RequestMapping("/loss-prevention/measurement-devices")
@RequiresModule("loss-prevention")
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionMeasurementController {

    private final LpMeasurementService devices;

    public LossPreventionMeasurementController(LpMeasurementService devices) {
        this.devices = devices;
    }

    @GetMapping
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public List<Map<String, Object>> list(@RequestParam(required = false) String branchId,
            @RequestParam(required = false) Boolean includeRetired) {
        return devices.list(branchId, Boolean.TRUE.equals(includeRetired));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> create(@Valid @RequestBody CreateDeviceInput dto) {
        return devices.create(dto);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> get(@PathVariable String id) {
        return devices.get(id);
    }

    @PutMapping("/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateDeviceInput dto) {
        return devices.update(id, dto);
    }

    @PostMapping("/{id}/retire")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> retire(@PathVariable String id) {
        return devices.retire(id);
    }
}
