package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.FleetEmployeeLookup;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ClaimInput;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ClaimPatch;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ClaimQuery;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ClaimStatusChange;
import com.inventoryerp.fleet.application.FleetIncidentCommands.IncidentInput;
import com.inventoryerp.fleet.application.FleetIncidentCommands.IncidentPatch;
import com.inventoryerp.fleet.application.FleetIncidentCommands.IncidentQuery;
import com.inventoryerp.fleet.application.FleetIncidentCommands.IncidentStatusChange;
import com.inventoryerp.fleet.application.FleetIncidentService;
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
 * Incidents / accidents and insurance claims (fleet 1.7, ADR-S009): report, edit, status workflow, photos /
 * documents, claims (submit → approve / reject → settle), and the driver (hr employee) picker.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetIncidentController {

    private final FleetIncidentService incidents;
    private final FleetEmployeeLookup employees;

    public FleetIncidentController(FleetIncidentService incidents, FleetEmployeeLookup employees) {
        this.incidents = incidents;
        this.employees = employees;
    }

    @GetMapping("/incidents")
    @RequirePermissions(Permissions.FLEET_INCIDENT__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"reported", "investigating", "repairing", "closed", "cancelled"})
            String status,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String incidentType,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return incidents.list(new IncidentQuery(search, status, vehicleId, incidentType, from, to, page, pageSize));
    }

    @GetMapping("/incidents/{id}")
    @RequirePermissions(Permissions.FLEET_INCIDENT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return incidents.get(id);
    }

    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_INCIDENT__CREATE)
    public Map<String, Object> create(@Valid @RequestBody IncidentInput dto) {
        return incidents.create(dto);
    }

    @PatchMapping("/incidents/{id}")
    @RequirePermissions(Permissions.FLEET_INCIDENT__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody IncidentPatch dto) {
        return incidents.update(id, dto);
    }

    @PostMapping("/incidents/{id}/status")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_INCIDENT__UPDATE)
    public Map<String, Object> status(@PathVariable String id, @Valid @RequestBody IncidentStatusChange dto) {
        return incidents.changeStatus(id, dto);
    }

    /** Multipart: {@code file} plus optional {@code kind} (photo / document) and {@code title}. */
    @PostMapping("/incidents/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_INCIDENT__UPDATE)
    public Map<String, Object> upload(@PathVariable String id,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "title", required = false) String title) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return incidents.addAttachment(id, kind, title, file.getOriginalFilename(), file.getContentType(),
                file.getBytes());
    }

    @DeleteMapping("/incidents/{id}/attachments/{attachmentId}")
    @RequirePermissions(Permissions.FLEET_INCIDENT__UPDATE)
    public Map<String, Object> deleteAttachment(@PathVariable String id, @PathVariable String attachmentId) {
        incidents.removeAttachment(id, attachmentId);
        return Map.of("ok", true);
    }

    @GetMapping("/insurance-claims")
    @RequirePermissions(Permissions.FLEET_INCIDENT__READ)
    public PageResult<Map<String, Object>> claims(
            @RequestParam(required = false) @OneOf({"draft", "submitted", "approved", "rejected", "settled",
                    "withdrawn"}) String status,
            @RequestParam(required = false) String incidentId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return incidents.claims(new ClaimQuery(status, incidentId, search, page, pageSize));
    }

    @PostMapping("/incidents/{id}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_INCIDENT__UPDATE)
    public Map<String, Object> createClaim(@PathVariable String id, @Valid @RequestBody ClaimInput dto) {
        return incidents.createClaim(id, dto);
    }

    @PatchMapping("/insurance-claims/{id}")
    @RequirePermissions(Permissions.FLEET_INCIDENT__UPDATE)
    public Map<String, Object> updateClaim(@PathVariable String id, @Valid @RequestBody ClaimPatch dto) {
        return incidents.updateClaim(id, dto);
    }

    @PostMapping("/insurance-claims/{id}/status")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_INCIDENT__UPDATE)
    public Map<String, Object> claimStatus(@PathVariable String id, @Valid @RequestBody ClaimStatusChange dto) {
        return incidents.claimStatus(id, dto);
    }

    /** Drivers / operators (hr employees) for the incident and fine forms. */
    @GetMapping("/incident-lookups/employees")
    @RequirePermissions(Permissions.FLEET_INCIDENT__READ)
    public List<Map<String, Object>> employees(@RequestParam(required = false) String search) {
        return employees.search(search);
    }
}
