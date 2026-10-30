package com.inventoryerp.quality.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quality.application.QualityCertificateService;
import com.inventoryerp.quality.application.QualityCertificateService.Certificate;
import com.inventoryerp.quality.application.QualityCommands.CancelInput;
import com.inventoryerp.quality.application.QualityCommands.DowngradeInput;
import com.inventoryerp.quality.application.QualityCommands.LotInput;
import com.inventoryerp.quality.application.QualityCommands.LotQuery;
import com.inventoryerp.quality.application.QualityCommands.ReasonInput;
import com.inventoryerp.quality.application.QualityCommands.RecordResults;
import com.inventoryerp.quality.application.QualityCommands.RejectInput;
import com.inventoryerp.quality.application.QualityCommands.ReleaseInput;
import com.inventoryerp.quality.application.QualityDecisionService;
import com.inventoryerp.quality.application.QualityGateService;
import com.inventoryerp.quality.application.QualityGateService.GateStatus;
import com.inventoryerp.quality.application.QualityLotService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * Inspection lots (ADR-S013): list / detail, manual lots, result entry, decisions (hold, quarantine, release, reject,
 * downgrade, cancel), the certificate of analysis and the stage gate. Reject and downgrade post GL: they require an
 * {@code Idempotency-Key}; quarantine / release / cancel move stock and accept one.
 */
@RestController
@RequestMapping("/quality")
@Validated
public class QualityLotController {

    private final QualityLotService lots;
    private final QualityDecisionService decisions;
    private final QualityCertificateService certificates;
    private final QualityGateService gates;

    public QualityLotController(QualityLotService lots, QualityDecisionService decisions,
            QualityCertificateService certificates, QualityGateService gates) {
        this.lots = lots;
        this.decisions = decisions;
        this.certificates = certificates;
        this.gates = gates;
    }

    @GetMapping("/inspection-lots")
    @RequirePermissions(Permissions.QUALITY__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"open", "hold", "quarantined", "closed", "cancelled"}) String status,
            @RequestParam(required = false) @OneOf({"incoming", "in_process", "final"}) String stage,
            @RequestParam(required = false) @OneOf({"pending", "pass", "fail"}) String result,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String supplierPartnerId,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String batchNumber,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return lots.list(new LotQuery(search, status, stage, result, productId, supplierPartnerId, sourceType, sourceId,
                batchNumber, page, pageSize));
    }

    @GetMapping("/inspection-lots/{id}")
    @RequirePermissions(Permissions.QUALITY__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return lots.get(id);
    }

    @PostMapping("/inspection-lots")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY__INSPECT)
    public Map<String, Object> create(@Valid @RequestBody LotInput dto) {
        return lots.create(dto);
    }

    @PostMapping("/inspection-lots/{id}/results")
    @RequirePermissions(Permissions.QUALITY__INSPECT)
    public Map<String, Object> results(@PathVariable String id, @Valid @RequestBody RecordResults dto) {
        return lots.recordResults(id, dto);
    }

    @PostMapping("/inspection-lots/{id}/hold")
    @RequirePermissions(Permissions.QUALITY__HOLD)
    public Map<String, Object> hold(@PathVariable String id, @Valid @RequestBody(required = false) ReasonInput dto) {
        return decisions.hold(id, dto);
    }

    @PostMapping("/inspection-lots/{id}/quarantine")
    @Idempotent
    @RequirePermissions(Permissions.QUALITY__HOLD)
    public Map<String, Object> quarantine(@PathVariable String id,
            @Valid @RequestBody(required = false) ReasonInput dto) {
        return decisions.quarantine(id, dto);
    }

    @PostMapping("/inspection-lots/{id}/release")
    @Idempotent
    @RequirePermissions(Permissions.QUALITY__RELEASE)
    public Map<String, Object> release(@PathVariable String id, @Valid @RequestBody ReleaseInput dto) {
        return decisions.release(id, dto);
    }

    @PostMapping("/inspection-lots/{id}/reject")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUALITY__DISPOSE)
    public Map<String, Object> reject(@PathVariable String id, @Valid @RequestBody RejectInput dto) {
        return decisions.reject(id, dto);
    }

    @PostMapping("/inspection-lots/{id}/downgrade")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUALITY__DISPOSE)
    public Map<String, Object> downgrade(@PathVariable String id, @Valid @RequestBody DowngradeInput dto) {
        return decisions.downgrade(id, dto);
    }

    @PostMapping("/inspection-lots/{id}/cancel")
    @Idempotent
    @RequirePermissions(Permissions.QUALITY__DISPOSE)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody CancelInput dto) {
        return decisions.cancel(id, dto);
    }

    /** Certificate of analysis (PDF). */
    @GetMapping("/inspection-lots/{id}/certificate")
    @RequirePermissions(Permissions.QUALITY__READ)
    public ResponseEntity<byte[]> certificate(@PathVariable String id) {
        Certificate c = certificates.certificate(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + c.filename().replace("\"", "") + "\"")
                .body(c.content());
    }

    /** Stage gate: is the lot of this source / stage passed? (2.1 calls {@link QualityGateService} directly.) */
    @GetMapping("/gate")
    @RequirePermissions(Permissions.QUALITY__READ)
    public GateStatus gate(@RequestParam String sourceType, @RequestParam String sourceId,
            @RequestParam(required = false) String stageKey) {
        return gates.gate(sourceType, sourceId, stageKey);
    }
}
