package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.FleetAttachmentService;
import com.inventoryerp.fleet.application.FleetCommands.StatusChange;
import com.inventoryerp.fleet.application.FleetCommands.VehicleInput;
import com.inventoryerp.fleet.application.FleetCommands.VehiclePatch;
import com.inventoryerp.fleet.application.FleetCommands.VehicleQuery;
import com.inventoryerp.fleet.application.FleetLookupService;
import com.inventoryerp.fleet.application.FleetVehicleService;
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
 * Vehicle &amp; equipment register (fleet 1.1, ADR-S009): list / detail / create / edit / delete, status changes with
 * history, the linked fixed asset (read-only), photos and documents, form lookups and the fleet overview.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetVehicleController {

    private final FleetVehicleService vehicles;
    private final FleetAttachmentService attachments;
    private final FleetLookupService lookups;

    public FleetVehicleController(FleetVehicleService vehicles, FleetAttachmentService attachments,
            FleetLookupService lookups) {
        this.vehicles = vehicles;
        this.attachments = attachments;
        this.lookups = lookups;
    }

    @GetMapping("/overview")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public Map<String, Object> overview() {
        return lookups.overview();
    }

    @GetMapping("/lookups")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public Map<String, Object> lookups() {
        return lookups.lookups();
    }

    @GetMapping("/lookups/assets")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public List<Map<String, Object>> assets(@RequestParam(required = false) String search) {
        return lookups.assets(search);
    }

    @GetMapping("/lookups/partners")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public List<Map<String, Object>> partners(@RequestParam(required = false) String search) {
        return lookups.partners(search);
    }

    @GetMapping("/vehicles")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false)
            @OneOf({"road_vehicle", "trailer", "mobile_plant", "fixed_plant", "other"}) String kind,
            @RequestParam(required = false)
            @OneOf({"active", "in_workshop", "breakdown", "idle", "disposed"}) String status,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) @OneOf({"owned", "leased", "hired", "third_party"}) String ownership,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize,
            @RequestParam(required = false)
            @OneOf({"code", "registrationPlate", "make", "status", "createdAt"}) String sortBy,
            @RequestParam(required = false) @OneOf({"asc", "desc"}) String sortOrder) {
        return vehicles.list(new VehicleQuery(search, categoryId, kind, status, branchId, ownership, page, pageSize,
                sortBy, sortOrder));
    }

    @GetMapping("/vehicles/{id}")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return vehicles.get(id);
    }

    @PostMapping("/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_VEHICLE__CREATE)
    public Map<String, Object> create(@Valid @RequestBody VehicleInput dto) {
        return vehicles.create(dto);
    }

    @PatchMapping("/vehicles/{id}")
    @RequirePermissions(Permissions.FLEET_VEHICLE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody VehiclePatch dto) {
        return vehicles.update(id, dto);
    }

    @DeleteMapping("/vehicles/{id}")
    @RequirePermissions(Permissions.FLEET_VEHICLE__DELETE)
    public Map<String, Object> delete(@PathVariable String id) {
        vehicles.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/vehicles/{id}/status")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_VEHICLE__STATUS)
    public Map<String, Object> changeStatus(@PathVariable String id, @Valid @RequestBody StatusChange dto) {
        return vehicles.changeStatus(id, dto.status(), dto.reason(), "manual", null);
    }

    @GetMapping("/vehicles/{id}/status-history")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public List<Map<String, Object>> statusHistory(@PathVariable String id) {
        return vehicles.statusHistory(id);
    }

    @GetMapping("/vehicles/{id}/asset")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public Map<String, Object> asset(@PathVariable String id) {
        return vehicles.linkedAsset(id);
    }

    @GetMapping("/vehicles/{id}/attachments")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public List<Map<String, Object>> attachments(@PathVariable String id) {
        return attachments.list(id);
    }

    /** Multipart: {@code file} plus optional {@code kind} (photo / document), {@code title}, compliance document. */
    @PostMapping("/vehicles/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_VEHICLE__UPDATE)
    public Map<String, Object> upload(@PathVariable String id,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "complianceDocumentId", required = false) String complianceDocumentId)
            throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return attachments.upload(id, kind, title, complianceDocumentId, file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
    }

    @DeleteMapping("/vehicles/{id}/attachments/{attachmentId}")
    @RequirePermissions(Permissions.FLEET_VEHICLE__UPDATE)
    public Map<String, Object> deleteAttachment(@PathVariable String id, @PathVariable String attachmentId) {
        attachments.delete(id, attachmentId);
        return Map.of("ok", true);
    }
}
