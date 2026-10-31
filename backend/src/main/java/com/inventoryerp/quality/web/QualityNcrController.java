package com.inventoryerp.quality.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quality.application.QualityCapaService;
import com.inventoryerp.quality.application.QualityCommands.CapaActionComplete;
import com.inventoryerp.quality.application.QualityCommands.CapaActionInput;
import com.inventoryerp.quality.application.QualityCommands.CapaActionPatch;
import com.inventoryerp.quality.application.QualityCommands.CapaInput;
import com.inventoryerp.quality.application.QualityCommands.CapaPatch;
import com.inventoryerp.quality.application.QualityCommands.CapaQuery;
import com.inventoryerp.quality.application.QualityCommands.CapaStatusChange;
import com.inventoryerp.quality.application.QualityCommands.NcrInput;
import com.inventoryerp.quality.application.QualityCommands.NcrPatch;
import com.inventoryerp.quality.application.QualityCommands.NcrQuery;
import com.inventoryerp.quality.application.QualityCommands.NcrStatusChange;
import com.inventoryerp.quality.application.QualityNcrService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
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

/** Nonconformance reports and CAPA with their actions (ADR-S013). */
@RestController
@RequestMapping("/quality")
@Validated
public class QualityNcrController {

    private final QualityNcrService ncrs;
    private final QualityCapaService capas;

    public QualityNcrController(QualityNcrService ncrs, QualityCapaService capas) {
        this.ncrs = ncrs;
        this.capas = capas;
    }

    @GetMapping("/ncrs")
    @RequirePermissions(Permissions.QUALITY__READ)
    public PageResult<Map<String, Object>> listNcrs(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"open", "investigating", "action", "closed", "cancelled"})
            String status,
            @RequestParam(required = false) @OneOf({"minor", "major", "critical"}) String severity,
            @RequestParam(required = false) @OneOf({"incoming", "in_process", "final", "customer", "supplier",
                    "internal"}) String source,
            @RequestParam(required = false) String lotId,
            @RequestParam(required = false) String supplierPartnerId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return ncrs.list(new NcrQuery(search, status, severity, source, lotId, supplierPartnerId, page, pageSize));
    }

    @GetMapping("/ncrs/{id}")
    @RequirePermissions(Permissions.QUALITY__READ)
    public Map<String, Object> getNcr(@PathVariable String id) {
        return ncrs.get(id);
    }

    @PostMapping("/ncrs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY__NCR)
    public Map<String, Object> createNcr(@Valid @RequestBody NcrInput dto) {
        return ncrs.create(dto);
    }

    @PatchMapping("/ncrs/{id}")
    @RequirePermissions(Permissions.QUALITY__NCR)
    public Map<String, Object> updateNcr(@PathVariable String id, @Valid @RequestBody NcrPatch dto) {
        return ncrs.update(id, dto);
    }

    @PostMapping("/ncrs/{id}/status")
    @RequirePermissions(Permissions.QUALITY__NCR)
    public Map<String, Object> ncrStatus(@PathVariable String id, @Valid @RequestBody NcrStatusChange dto) {
        return ncrs.changeStatus(id, dto);
    }

    @GetMapping("/capas")
    @RequirePermissions(Permissions.QUALITY__READ)
    public PageResult<Map<String, Object>> listCapas(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"open", "in_progress", "verification", "closed", "cancelled"})
            String status,
            @RequestParam(required = false) String ownerUserId,
            @RequestParam(required = false) String ncrId,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return capas.list(new CapaQuery(search, status, ownerUserId, ncrId, overdue, page, pageSize));
    }

    @GetMapping("/capas/{id}")
    @RequirePermissions(Permissions.QUALITY__READ)
    public Map<String, Object> getCapa(@PathVariable String id) {
        return capas.get(id);
    }

    @PostMapping("/capas")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY__CAPA)
    public Map<String, Object> createCapa(@Valid @RequestBody CapaInput dto) {
        return capas.create(dto);
    }

    @PatchMapping("/capas/{id}")
    @RequirePermissions(Permissions.QUALITY__CAPA)
    public Map<String, Object> updateCapa(@PathVariable String id, @Valid @RequestBody CapaPatch dto) {
        return capas.update(id, dto);
    }

    @PostMapping("/capas/{id}/status")
    @RequirePermissions(Permissions.QUALITY__CAPA)
    public Map<String, Object> capaStatus(@PathVariable String id, @Valid @RequestBody CapaStatusChange dto) {
        return capas.changeStatus(id, dto);
    }

    @PostMapping("/capas/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY__CAPA)
    public Map<String, Object> addAction(@PathVariable String id, @Valid @RequestBody CapaActionInput dto) {
        return capas.addAction(id, dto);
    }

    @PatchMapping("/capa-actions/{actionId}")
    @RequirePermissions(Permissions.QUALITY__CAPA)
    public Map<String, Object> updateAction(@PathVariable String actionId, @Valid @RequestBody CapaActionPatch dto) {
        return capas.updateAction(actionId, dto);
    }

    @PostMapping("/capa-actions/{actionId}/complete")
    @RequirePermissions(Permissions.QUALITY__CAPA)
    public Map<String, Object> completeAction(@PathVariable String actionId,
            @Valid @RequestBody(required = false) CapaActionComplete dto) {
        return capas.completeAction(actionId, dto == null ? new CapaActionComplete(null, null) : dto);
    }

    /** Open actions and CAPAs past their due date (alerts). */
    @GetMapping("/capa-actions/overdue")
    @RequirePermissions(Permissions.QUALITY__READ)
    public Map<String, Object> overdue() {
        return capas.overdue();
    }
}
