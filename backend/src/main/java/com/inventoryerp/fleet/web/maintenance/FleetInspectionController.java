package com.inventoryerp.fleet.web.maintenance;

import com.inventoryerp.fleet.application.FleetDispatchEligibilityService;
import com.inventoryerp.fleet.application.FleetInspectionService;
import com.inventoryerp.fleet.application.MaintenanceCommands.DefectCloseInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.DefectInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.DefectQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.DefectWorkOrderInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.InspectionInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.InspectionQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.InspectionTemplateInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.InspectionTemplatePatch;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Inspections / pre-trip checklists (DVIR), defects and the dispatch-eligibility check (fleet 1.6).
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetInspectionController {

    private final FleetInspectionService inspections;
    private final FleetDispatchEligibilityService eligibility;

    public FleetInspectionController(FleetInspectionService inspections,
            FleetDispatchEligibilityService eligibility) {
        this.inspections = inspections;
        this.eligibility = eligibility;
    }

    @GetMapping("/inspection-templates")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public List<Map<String, Object>> templates(@RequestParam(required = false) String categoryId,
            @RequestParam(required = false) @OneOf({"pre_trip", "post_trip", "periodic"}) String kind,
            @RequestParam(required = false) Boolean includeInactive) {
        return inspections.templates(categoryId, kind, Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/inspection-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> createTemplate(@Valid @RequestBody InspectionTemplateInput dto) {
        return inspections.createTemplate(dto);
    }

    @PatchMapping("/inspection-templates/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> updateTemplate(@PathVariable String id,
            @Valid @RequestBody InspectionTemplatePatch dto) {
        return inspections.updateTemplate(id, dto);
    }

    @DeleteMapping("/inspection-templates/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__SCHEDULE)
    public Map<String, Object> deleteTemplate(@PathVariable String id) {
        inspections.deleteTemplate(id);
        return Map.of("ok", true);
    }

    @GetMapping("/inspections")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) @OneOf({"pass", "fail"}) String result,
            @RequestParam(required = false) @OneOf({"pre_trip", "post_trip", "periodic"}) String kind,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return inspections.inspections(new InspectionQuery(vehicleId, result, kind, from, to, page, pageSize));
    }

    @GetMapping("/inspections/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return inspections.inspection(id);
    }

    @PostMapping("/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__INSPECT)
    public Map<String, Object> record(@Valid @RequestBody InspectionInput dto) {
        return inspections.record(dto);
    }

    /** Multipart {@code file} (image) plus optional {@code itemCode}. */
    @PostMapping("/inspections/{id}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__INSPECT)
    public Map<String, Object> photo(@PathVariable String id,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "itemCode", required = false) String itemCode) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return inspections.addPhoto(id, itemCode, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @GetMapping("/defects")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public PageResult<Map<String, Object>> defects(
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false)
            @OneOf({"open", "in_repair", "resolved", "dismissed", "all"}) String status,
            @RequestParam(required = false) @OneOf({"minor", "major", "critical"}) String severity,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return inspections.defects(new DefectQuery(vehicleId, status, severity, page, pageSize));
    }

    @GetMapping("/defects/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public Map<String, Object> defect(@PathVariable String id) {
        return inspections.defect(id);
    }

    @PostMapping("/defects")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__INSPECT)
    public Map<String, Object> report(@Valid @RequestBody DefectInput dto) {
        return inspections.report(dto);
    }

    @PostMapping("/defects/{id}/work-order")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__CREATE)
    public Map<String, Object> toWorkOrder(@PathVariable String id,
            @Valid @RequestBody(required = false) DefectWorkOrderInput dto) {
        return inspections.toWorkOrder(id, dto);
    }

    @PostMapping("/defects/{id}/close")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__INSPECT)
    public Map<String, Object> close(@PathVariable String id, @Valid @RequestBody DefectCloseInput dto) {
        return inspections.close(id, dto);
    }

    /** May the unit be dispatched now? (status operational and no open critical defect). */
    @GetMapping("/vehicles/{id}/dispatch-eligibility")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public Map<String, Object> eligibility(@PathVariable String id) {
        return eligibility.check(id).toMap();
    }
}
