package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.FleetCommands.MeterCorrectionInput;
import com.inventoryerp.fleet.application.FleetCommands.MeterReadingInput;
import com.inventoryerp.fleet.application.FleetMeterService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Odometer / hour-meter readings and approval-gated meter corrections (fleet 1.1, ADR-S009). Corrections are decided
 * in the approvals inbox (entity type {@code fleet_meter_correction}).
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetMeterController {

    private final FleetMeterService meters;

    public FleetMeterController(FleetMeterService meters) {
        this.meters = meters;
    }

    @GetMapping("/vehicles/{id}/meter-readings")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public PageResult<Map<String, Object>> readings(@PathVariable String id,
            @RequestParam(required = false) @OneOf({"odometer", "hours"}) String meterType,
            @RequestParam(required = false) Boolean includeSuperseded,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return meters.list(id, meterType, Boolean.TRUE.equals(includeSuperseded), page, pageSize);
    }

    @PostMapping("/vehicles/{id}/meter-readings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_VEHICLE__METER)
    public Map<String, Object> record(@PathVariable String id, @Valid @RequestBody MeterReadingInput dto) {
        return meters.record(id, dto);
    }

    @GetMapping("/vehicles/{id}/meter-corrections")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public List<Map<String, Object>> corrections(@PathVariable String id) {
        return meters.corrections(id);
    }

    @PostMapping("/vehicles/{id}/meter-corrections")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_VEHICLE__METER)
    public Map<String, Object> requestCorrection(@PathVariable String id,
            @Valid @RequestBody MeterCorrectionInput dto) {
        return meters.requestCorrection(id, dto);
    }

    @GetMapping("/meter-corrections/{id}")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public Map<String, Object> correction(@PathVariable String id) {
        return meters.correction(id);
    }

    /** Apply the decided approval request now (normally automatic once it is decided). */
    @PostMapping("/meter-corrections/{id}/sync")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_VEHICLE__METER)
    public Map<String, Object> sync(@PathVariable String id) {
        return meters.sync(id);
    }
}
