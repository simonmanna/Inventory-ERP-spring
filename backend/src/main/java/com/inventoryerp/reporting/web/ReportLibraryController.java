package com.inventoryerp.reporting.web;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.reporting.application.ReportEngine;
import com.inventoryerp.reporting.application.ReportEngine.ReportResult;
import com.inventoryerp.reporting.application.ReportExporter;
import com.inventoryerp.reporting.application.ReportExporter.Export;
import com.inventoryerp.reporting.application.ReportPeriods;
import com.inventoryerp.reporting.application.StatementLayoutService;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /reports/library|run|runs} (FR-0, ADR-S043; extension): the report library, running a report with the
 * shared filters, exports, and the run log with reproducibility checks. Each report also requires its own permission.
 */
@RestController
@RequestMapping("/reports")
public class ReportLibraryController {

    private final ReportEngine engine;
    private final ReportExporter exporter;
    private final StatementLayoutService layouts;

    public ReportLibraryController(ReportEngine engine, ReportExporter exporter, StatementLayoutService layouts) {
        this.engine = engine;
        this.exporter = exporter;
        this.layouts = layouts;
    }

    @GetMapping("/library")
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public List<Map<String, Object>> library() {
        return engine.library();
    }

    @GetMapping("/library/{id}")
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public Map<String, Object> definition(@PathVariable String id) {
        ReportDefinition d = engine.definition(id);
        return Obj.of("definition", d, "periodPresets", ReportPeriods.PRESETS.stream().sorted().toList(),
                "compareOptions", d.accepts(FilterKey.COMPARE) ? ReportPeriods.COMPARE.stream().sorted().toList()
                        : List.of(), "layouts", d.accepts(FilterKey.LAYOUT) ? layouts.list().stream().filter(l -> l
                                .get("kind").equals(layoutKind(id))).toList()
                        : List.of(),
                "exportFormats", ReportExporter.FORMATS, "canSchedule", d.engine() && TenantContext.has(
                        Permissions.REPORT_LIBRARY__SCHEDULE));
    }

    /** The statement layout kind a report uses. */
    static String layoutKind(String id) {
        return id.contains("balance-sheet") ? "BS" : id.contains("cash-flow") ? "CF" : "PL";
    }

    @PostMapping("/run/{id}")
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public ReportResult run(@PathVariable String id, @RequestBody(required = false) ReportRequest request) {
        return engine.run(id, request, "USER");
    }

    @PostMapping("/run/{id}/export")
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam String format,
            @RequestBody(required = false) ReportRequest request) {
        if (!ReportExporter.FORMATS.contains(format)) {
            throw com.inventoryerp.shared.web.ApiException.badRequest("format must be one of: " + String.join(", ",
                    ReportExporter.FORMATS));
        }
        Export file = exporter.render(engine.run(id, request, "EXPORT"), format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename())
                        .build().toString())
                .body(file.content());
    }

    @GetMapping("/runs")
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public List<Map<String, Object>> runs(@RequestParam(required = false) String reportId) {
        return engine.runs(reportId);
    }

    @PostMapping("/runs/{runId}/verify")
    @RequirePermissions(Permissions.REPORT_LIBRARY__READ)
    public Map<String, Object> verify(@PathVariable String runId) {
        return engine.verify(runId);
    }
}
