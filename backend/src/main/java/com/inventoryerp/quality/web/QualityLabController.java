package com.inventoryerp.quality.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quality.application.QualityCommands.LabCsvImport;
import com.inventoryerp.quality.application.QualityCommands.LabFetch;
import com.inventoryerp.quality.application.QualityCommands.ReportQuery;
import com.inventoryerp.quality.application.QualityLabService;
import com.inventoryerp.quality.application.QualityReportService;
import com.inventoryerp.quality.application.QualityTraceabilityService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
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

/** Laboratory imports (LabResultAdapter SPI), batch traceability and quality reports (ADR-S013). */
@RestController
@RequestMapping("/quality")
@Validated
public class QualityLabController {

    private final QualityLabService lab;
    private final QualityTraceabilityService trace;
    private final QualityReportService reports;

    public QualityLabController(QualityLabService lab, QualityTraceabilityService trace, QualityReportService reports) {
        this.lab = lab;
        this.trace = trace;
        this.reports = reports;
    }

    @GetMapping("/lab-adapters")
    @RequirePermissions(Permissions.QUALITY__READ)
    public List<Map<String, Object>> adapters() {
        return lab.adapters();
    }

    @GetMapping("/lab-imports")
    @RequirePermissions(Permissions.QUALITY__READ)
    public PageResult<Map<String, Object>> imports(@RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return lab.list(page, pageSize);
    }

    @GetMapping("/lab-imports/{id}")
    @RequirePermissions(Permissions.QUALITY__READ)
    public Map<String, Object> importDetail(@PathVariable String id) {
        return lab.get(id);
    }

    @PostMapping("/lab-imports/csv")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY__INSPECT)
    public Map<String, Object> importCsv(@Valid @RequestBody LabCsvImport dto) {
        return lab.importCsv(dto);
    }

    @PostMapping("/lab-imports/fetch")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY__INSPECT)
    public Map<String, Object> fetch(@Valid @RequestBody LabFetch dto) {
        return lab.fetch(dto);
    }

    @GetMapping("/traceability")
    @RequirePermissions(Permissions.QUALITY__READ)
    public Map<String, Object> traceability(@RequestParam String productId, @RequestParam String batchNumber,
            @RequestParam(required = false) @OneOf({"backward", "forward"}) String direction,
            @RequestParam(required = false) @Min(1) @Max(10) Integer depth) {
        return trace.trace(productId, batchNumber, direction, depth);
    }

    @GetMapping("/reports/summary")
    @RequirePermissions(Permissions.QUALITY__READ)
    public Map<String, Object> summary(
            @RequestParam(required = false) @OneOf({"product", "batch", "work_center", "supplier"}) String groupBy,
            @RequestParam(required = false) @OneOf({"incoming", "in_process", "final"}) String stage,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summary(new ReportQuery(groupBy, stage, from, to));
    }
}
