package com.inventoryerp.gatepass.web;

import com.inventoryerp.gatepass.application.GatePassPdf;
import com.inventoryerp.gatepass.application.GatePassService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Gate passes (ADR-S085, docs/spec/gate-pass.md). */
@RestController
@RequestMapping("/gate-passes")
public class GatePassController {

    public record LineRequest(@NotBlank String sourceLineId,
            @NotNull @DecimalMin(value = "0", inclusive = false, message = "quantity must be greater than 0")
                    BigDecimal quantity) {}

    public record IssueRequest(
            @NotBlank String sourceType,
            @NotBlank String sourceId,
            @NotBlank String bearerType,
            @NotBlank @Size(max = 120) String bearerName,
            @Size(max = 60) String bearerIdNumber,
            @Size(max = 40) String bearerPhone,
            @Size(max = 30) String vehiclePlate,
            @Size(max = 60) String vehicleType,
            @Size(max = 120) String transportCompany,
            @Size(max = 300) String destination,
            @Size(max = 300) String purpose,
            @Size(max = 1000) String remarks,
            @Min(1) @Max(168) Integer validHours,
            @Valid @Size(max = 500) List<LineRequest> lines) {}

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    public record GateOutRequest(@Size(max = 20) String verifyCode, @Size(max = 30) String plate,
            @Size(max = 500) String note) {}

    private final GatePassService svc;

    public GatePassController(GatePassService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(value = {Permissions.GATE_PASS__READ, Permissions.GATE_PASS__CHECK}, anyOf = true)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return svc.list(query, QueryParams.page(query, "status", "sourceType", "sourceId", "branchId", "q", "from", "to"));
    }

    /** Issue dialog data for one source: summary, warnings, prefill, lines with what is left, passes so far. */
    @GetMapping("/source/{sourceType}/{sourceId}")
    @RequirePermissions(value = {Permissions.GATE_PASS__READ, Permissions.GATE_PASS__ISSUE}, anyOf = true)
    public Map<String, Object> source(@PathVariable String sourceType, @PathVariable String sourceId) {
        return svc.sourceView(sourceType, sourceId);
    }

    /** Gate check by number or scanned QR text. */
    @GetMapping("/verify")
    @RequirePermissions(Permissions.GATE_PASS__CHECK)
    public Map<String, Object> verify(@RequestParam(required = false) String code) {
        return svc.verify(code);
    }

    @GetMapping("/{id}")
    @RequirePermissions(value = {Permissions.GATE_PASS__READ, Permissions.GATE_PASS__CHECK}, anyOf = true)
    public Map<String, Object> get(@PathVariable String id) {
        return svc.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.GATE_PASS__ISSUE)
    public Map<String, Object> issue(@Valid @RequestBody IssueRequest r) {
        return svc.issue(new GatePassService.IssueInput(r.sourceType(), r.sourceId(), r.bearerType(), r.bearerName(),
                r.bearerIdNumber(), r.bearerPhone(), r.vehiclePlate(), r.vehicleType(), r.transportCompany(),
                r.destination(), r.purpose(), r.remarks(), r.validHours(), r.lines() == null ? null
                        : r.lines().stream().map(l -> new GatePassService.LineInput(l.sourceLineId(), l.quantity()))
                                .toList()));
    }

    @GetMapping("/{id}/pdf")
    @RequirePermissions(value = {Permissions.GATE_PASS__READ, Permissions.GATE_PASS__ISSUE}, anyOf = true)
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        GatePassPdf.Pdf pdf = svc.pdf(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.filename() + "\"")
                .body(pdf.content());
    }

    @PostMapping("/{id}/gate-out")
    @RequirePermissions(Permissions.GATE_PASS__CHECK)
    public Map<String, Object> gateOut(@PathVariable String id, @Valid @RequestBody(required = false) GateOutRequest r) {
        return svc.gateOut(id, r == null ? null : new GatePassService.GateOutInput(r.verifyCode(), r.plate(), r.note()));
    }

    @PostMapping("/{id}/refuse")
    @RequirePermissions(Permissions.GATE_PASS__CHECK)
    public Map<String, Object> refuse(@PathVariable String id, @Valid @RequestBody ReasonRequest r) {
        return svc.refuse(id, r.reason());
    }

    @PostMapping("/{id}/void")
    @RequirePermissions(Permissions.GATE_PASS__VOID)
    public Map<String, Object> voidPass(@PathVariable String id, @Valid @RequestBody ReasonRequest r) {
        return svc.voidPass(id, r.reason());
    }
}
