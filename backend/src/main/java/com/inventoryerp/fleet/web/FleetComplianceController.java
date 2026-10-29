package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.FleetCommands.ComplianceInput;
import com.inventoryerp.fleet.application.FleetCommands.CompliancePatch;
import com.inventoryerp.fleet.application.FleetCommands.ComplianceQuery;
import com.inventoryerp.fleet.application.FleetCommands.ComplianceRenewal;
import com.inventoryerp.fleet.application.FleetComplianceService;
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
import org.springframework.format.annotation.DateTimeFormat;
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
 * Vehicle compliance documents (fleet 1.1, ADR-S009) and the org-wide "expiring soon / expired" list the alerts and
 * reminder job build on.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetComplianceController {

    private final FleetComplianceService compliance;

    public FleetComplianceController(FleetComplianceService compliance) {
        this.compliance = compliance;
    }

    @GetMapping("/compliance-documents")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public PageResult<Map<String, Object>> due(
            @RequestParam(required = false) @OneOf({"attention", "expiring", "expired", "all"}) String due,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false)
            @OneOf({"registration", "insurance", "road_licence", "inspection", "operator_permit", "other"})
            String docType,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return compliance.due(new ComplianceQuery(due, vehicleId, docType, branchId, asOf, page, pageSize));
    }

    @GetMapping("/vehicles/{id}/compliance-documents")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public List<Map<String, Object>> listForVehicle(@PathVariable String id) {
        return compliance.listForVehicle(id);
    }

    @PostMapping("/vehicles/{id}/compliance-documents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_VEHICLE__COMPLIANCE)
    public Map<String, Object> create(@PathVariable String id, @Valid @RequestBody ComplianceInput dto) {
        return compliance.create(id, dto);
    }

    @GetMapping("/compliance-documents/{id}")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return compliance.get(id);
    }

    @PatchMapping("/compliance-documents/{id}")
    @RequirePermissions(Permissions.FLEET_VEHICLE__COMPLIANCE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody CompliancePatch dto) {
        return compliance.update(id, dto);
    }

    @PostMapping("/compliance-documents/{id}/renew")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_VEHICLE__COMPLIANCE)
    public Map<String, Object> renew(@PathVariable String id, @Valid @RequestBody ComplianceRenewal dto) {
        return compliance.renew(id, dto);
    }

    @DeleteMapping("/compliance-documents/{id}")
    @RequirePermissions(Permissions.FLEET_VEHICLE__COMPLIANCE)
    public Map<String, Object> delete(@PathVariable String id) {
        compliance.delete(id);
        return Map.of("ok", true);
    }
}
