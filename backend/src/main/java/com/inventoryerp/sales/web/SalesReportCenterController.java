package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesReportingService;
import com.inventoryerp.sales.application.SalesReportingService.Export;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sales report center (ADR-S057): catalog, executive dashboard, filter lookups, report runs and Excel / CSV export.
 * Every route needs {@code sales:report:read}; reports with a stricter permission (commissions) check it on run.
 */
@RestController
@RequestMapping("/sales/report-center")
public class SalesReportCenterController {

    private final SalesReportingService reports;

    public SalesReportCenterController(SalesReportingService reports) {
        this.reports = reports;
    }

    @GetMapping("/catalog")
    @RequirePermissions(Permissions.SALES__REPORT_READ)
    public Map<String, Object> catalog() {
        return reports.catalog();
    }

    @GetMapping("/dashboard")
    @RequirePermissions(Permissions.SALES__REPORT_READ)
    public Map<String, Object> dashboard(@RequestParam Map<String, String> q) {
        return reports.dashboard(q);
    }

    @GetMapping("/lookups")
    @RequirePermissions(Permissions.SALES__REPORT_READ)
    public Map<String, Object> lookups() {
        return reports.lookups();
    }

    @GetMapping("/run/{key}")
    @RequirePermissions(Permissions.SALES__REPORT_READ)
    public Map<String, Object> run(@PathVariable String key, @RequestParam Map<String, String> q) {
        return reports.run(key, q);
    }

    @GetMapping("/export/{key}")
    @RequirePermissions(Permissions.SALES__REPORT_READ)
    public ResponseEntity<byte[]> export(@PathVariable String key, @RequestParam Map<String, String> q) {
        Export e = reports.export(key, q);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, e.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename() + "\"")
                .body(e.content());
    }
}
