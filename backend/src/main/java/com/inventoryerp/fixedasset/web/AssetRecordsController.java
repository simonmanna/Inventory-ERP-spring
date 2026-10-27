package com.inventoryerp.fixedasset.web;

import com.inventoryerp.fixedasset.application.AssetRecordsService;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.AcquisitionRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.AssignmentRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.CheckInOutRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.DisposalRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.InspectionRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.InsuranceRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.MaintenanceRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.RepairRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.RevaluationRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.TransferRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.WarrantyRequest;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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
 * {@code /fixed-assets/:assetId/*} sub-records (ports of the acquisition, assignment, check-in/out, inspection,
 * insurance, maintenance, repair, warranty, revaluation, transfer and disposal controllers). The {@code PATCH :id}
 * bodies are {@code Partial<CreateXDto>} in Nest — a TypeScript-only type, so they reach the service unvalidated.
 */
@RestController
@RequestMapping("/fixed-assets/{assetId}")
@RequiresModule("fixed-asset")
@ConditionalOnProperty(name = "app.modules.assets", havingValue = "true")
public class AssetRecordsController {

    private final AssetRecordsService service;

    public AssetRecordsController(AssetRecordsService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ acquisition

    @GetMapping("/acquisition")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public Map<String, Object> acquisition(@PathVariable String assetId) {
        return service.findAcquisition(assetId);
    }

    @PostMapping("/acquisition")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__CREATE)
    public Map<String, Object> upsertAcquisition(@PathVariable String assetId,
            @Valid @RequestBody AcquisitionRequest dto) {
        return service.upsertAcquisition(assetId, dto);
    }

    @DeleteMapping("/acquisition")
    @RequirePermissions(Permissions.FIXED_ASSET__DELETE)
    public void removeAcquisition(@PathVariable String assetId) {
        service.removeAcquisition(assetId);
    }

    // ------------------------------------------------------------------ assignments

    @GetMapping("/assignments")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> assignments(@PathVariable String assetId) {
        return service.assignments(assetId);
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__UPDATE)
    public Map<String, Object> assign(@PathVariable String assetId, @Valid @RequestBody AssignmentRequest dto) {
        return service.assign(assetId, dto);
    }

    @PatchMapping("/assignments/{id}/return")
    @RequirePermissions(Permissions.FIXED_ASSET__UPDATE)
    public Map<String, Object> returnAsset(@PathVariable String id,
            @RequestParam(required = false) String returnDate, @RequestParam(required = false) String condition) {
        return service.returnAssignment(id, returnDate, condition);
    }

    // ------------------------------------------------------------------ check-in / out

    @GetMapping("/checkinout")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> checkInOuts(@PathVariable String assetId) {
        return service.checkInOuts(assetId);
    }

    @PostMapping("/checkinout")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__CREATE)
    public Map<String, Object> checkOut(@PathVariable String assetId, @Valid @RequestBody CheckInOutRequest dto) {
        return service.checkOut(assetId, dto);
    }

    @PatchMapping("/checkinout/{id}/checkin")
    @RequirePermissions(Permissions.FIXED_ASSET__UPDATE)
    public Map<String, Object> checkIn(@PathVariable String id, @RequestParam(required = false) String returnDate,
            @RequestParam(required = false) String condition) {
        return service.checkIn(id, returnDate, condition);
    }

    // ------------------------------------------------------------------ inspections

    @GetMapping("/inspections")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> inspections(@PathVariable String assetId) {
        return service.inspections(assetId);
    }

    @PostMapping("/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__CREATE)
    public Map<String, Object> inspect(@PathVariable String assetId, @Valid @RequestBody InspectionRequest dto) {
        return service.inspect(assetId, dto);
    }

    // ------------------------------------------------------------------ insurance

    @GetMapping("/insurance")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> insurance(@PathVariable String assetId) {
        return service.insurance(assetId);
    }

    @PostMapping("/insurance")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__CREATE)
    public Map<String, Object> insure(@PathVariable String assetId, @Valid @RequestBody InsuranceRequest dto) {
        return service.insure(assetId, dto);
    }

    @PatchMapping("/insurance/{id}")
    @RequirePermissions(Permissions.FIXED_ASSET__UPDATE)
    public Map<String, Object> updateInsurance(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return service.updateInsurance(id, body);
    }

    // ------------------------------------------------------------------ maintenance

    @GetMapping("/maintenance")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> maintenance(@PathVariable String assetId) {
        return service.maintenance(assetId);
    }

    @GetMapping("/maintenance/due")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> dueMaintenance() {
        return service.dueMaintenance();
    }

    @PostMapping("/maintenance")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__CREATE)
    public Map<String, Object> scheduleMaintenance(@PathVariable String assetId,
            @Valid @RequestBody MaintenanceRequest dto) {
        return service.scheduleMaintenance(assetId, dto);
    }

    @PatchMapping("/maintenance/{id}")
    @RequirePermissions(Permissions.FIXED_ASSET__UPDATE)
    public Map<String, Object> updateMaintenance(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return service.updateMaintenance(id, body);
    }

    // ------------------------------------------------------------------ repairs

    @GetMapping("/repairs")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> repairs(@PathVariable String assetId) {
        return service.repairs(assetId);
    }

    @PostMapping("/repairs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__CREATE)
    public Map<String, Object> logRepair(@PathVariable String assetId, @Valid @RequestBody RepairRequest dto) {
        return service.logRepair(assetId, dto);
    }

    @PatchMapping("/repairs/{id}")
    @RequirePermissions(Permissions.FIXED_ASSET__UPDATE)
    public Map<String, Object> updateRepair(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return service.updateRepair(id, body);
    }

    // ------------------------------------------------------------------ warranties

    @GetMapping("/warranties")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> warranties(@PathVariable String assetId) {
        return service.warranties(assetId);
    }

    @GetMapping("/warranties/expiring")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> expiring(@RequestParam(required = false) String days) {
        double d = days == null ? 0 : JsNumbers.number(days);
        return service.expiringWarranties(Double.isNaN(d) || d == 0 ? 90 : (int) d);
    }

    @PostMapping("/warranties")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__CREATE)
    public Map<String, Object> addWarranty(@PathVariable String assetId, @Valid @RequestBody WarrantyRequest dto) {
        return service.addWarranty(assetId, dto);
    }

    @PatchMapping("/warranties/{id}")
    @RequirePermissions(Permissions.FIXED_ASSET__UPDATE)
    public Map<String, Object> updateWarranty(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return service.updateWarranty(id, body);
    }

    // ------------------------------------------------------------------ revaluations

    @GetMapping("/revaluations")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> revaluations(@PathVariable String assetId) {
        return service.revaluations(assetId);
    }

    @PostMapping("/revaluations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__CREATE)
    public Map<String, Object> revalue(@PathVariable String assetId, @Valid @RequestBody RevaluationRequest dto) {
        return service.revalue(assetId, dto);
    }

    // ------------------------------------------------------------------ transfers

    @GetMapping("/transfers")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> transfers(@PathVariable String assetId) {
        return service.transfers(assetId);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__TRANSFER)
    public Map<String, Object> transfer(@PathVariable String assetId, @Valid @RequestBody TransferRequest dto) {
        return service.transfer(assetId, dto);
    }

    // ------------------------------------------------------------------ disposal

    @GetMapping("/disposal")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public Map<String, Object> disposal(@PathVariable String assetId) {
        return service.disposal(assetId);
    }

    @PostMapping("/disposal")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__DISPOSE)
    public Map<String, Object> dispose(@PathVariable String assetId, @Valid @RequestBody DisposalRequest dto) {
        return service.dispose(assetId, dto);
    }
}
