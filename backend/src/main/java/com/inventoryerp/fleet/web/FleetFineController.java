package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.FleetFineService;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ChargeInput;
import com.inventoryerp.fleet.application.FleetIncidentCommands.DeductionQuery;
import com.inventoryerp.fleet.application.FleetIncidentCommands.DeductionStatusChange;
import com.inventoryerp.fleet.application.FleetIncidentCommands.FineInput;
import com.inventoryerp.fleet.application.FleetIncidentCommands.FinePatch;
import com.inventoryerp.fleet.application.FleetIncidentCommands.FineQuery;
import com.inventoryerp.fleet.application.FleetIncidentCommands.FineStatusChange;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ReverseChargeInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/**
 * Traffic fines (fleet 1.7, ADR-S009 §6): record, dispute / cancel, charge to the company (expense on the vehicle's
 * cost centre) or the driver (salary-deduction request), reverse a charge, and the deduction requests payroll
 * exports. Charge and reverse move money: they require an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetFineController {

    private final FleetFineService fines;

    public FleetFineController(FleetFineService fines) {
        this.fines = fines;
    }

    @GetMapping("/fines")
    @RequirePermissions(Permissions.FLEET_INCIDENT__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"open", "disputed", "charged", "settled", "cancelled"})
            String status,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String driverEmployeeId,
            @RequestParam(required = false) @OneOf({"company", "driver"}) String chargedTo,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return fines.list(new FineQuery(search, status, vehicleId, driverEmployeeId, chargedTo, overdue, page,
                pageSize));
    }

    @GetMapping("/fines/{id}")
    @RequirePermissions(Permissions.FLEET_INCIDENT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return fines.get(id);
    }

    @PostMapping("/fines")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_INCIDENT__CREATE)
    public Map<String, Object> create(@Valid @RequestBody FineInput dto) {
        return fines.create(dto);
    }

    @PatchMapping("/fines/{id}")
    @RequirePermissions(Permissions.FLEET_INCIDENT__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody FinePatch dto) {
        return fines.update(id, dto);
    }

    @PostMapping("/fines/{id}/status")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_INCIDENT__UPDATE)
    public Map<String, Object> status(@PathVariable String id, @Valid @RequestBody FineStatusChange dto) {
        return fines.changeStatus(id, dto);
    }

    @PostMapping("/fines/{id}/charge")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_INCIDENT__CHARGE)
    public Map<String, Object> charge(@PathVariable String id, @Valid @RequestBody ChargeInput dto) {
        return fines.charge(id, dto);
    }

    @PostMapping("/fines/{id}/reverse-charge")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_INCIDENT__CHARGE)
    public Map<String, Object> reverseCharge(@PathVariable String id, @Valid @RequestBody ReverseChargeInput dto) {
        return fines.reverseCharge(id, dto);
    }

    @GetMapping("/fine-deductions")
    @RequirePermissions(Permissions.FLEET_INCIDENT__READ)
    public PageResult<Map<String, Object>> deductions(
            @RequestParam(required = false) @OneOf({"pending", "exported", "settled", "cancelled"}) String status,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return fines.deductions(new DeductionQuery(status, employeeId, page, pageSize));
    }

    @PostMapping("/fine-deductions/{id}/status")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_INCIDENT__CHARGE)
    public Map<String, Object> deductionStatus(@PathVariable String id,
            @Valid @RequestBody DeductionStatusChange dto) {
        return fines.deductionStatus(id, dto);
    }
}
