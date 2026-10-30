package com.inventoryerp.quarry.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quarry.application.QuarryReportService;
import com.inventoryerp.shared.validation.OneOf;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Quarry reports (ADR-S012): extraction vs target, cost per tonne, losses, royalties / levies. Default: month to date. */
@RestController
@RequestMapping("/quarry")
@RequiresModule("quarry")
@ConditionalOnProperty(name = "app.modules.quarry", havingValue = "true")
@Validated
public class QuarryReportController {

    private final QuarryReportService reports;

    public QuarryReportController(QuarryReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports/extraction-vs-target")
    @RequirePermissions(Permissions.QUARRY__REPORT)
    public Map<String, Object> extractionVsTarget(@RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo, @RequestParam(required = false) String siteId) {
        return reports.extractionVsTarget(dateFrom, dateTo, siteId);
    }

    @GetMapping("/reports/cost-per-tonne")
    @RequirePermissions(Permissions.QUARRY__REPORT)
    public Map<String, Object> costPerTonne(@RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo, @RequestParam(required = false) String siteId,
            @RequestParam(required = false) @OneOf({"day", "week", "month", "site"}) String groupBy) {
        return reports.costPerTonne(dateFrom, dateTo, siteId, groupBy);
    }

    @GetMapping("/reports/losses")
    @RequirePermissions(Permissions.QUARRY__REPORT)
    public Map<String, Object> losses(@RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo, @RequestParam(required = false) String siteId) {
        return reports.losses(dateFrom, dateTo, siteId);
    }

    @GetMapping("/reports/royalties")
    @RequirePermissions(Permissions.QUARRY__REPORT)
    public Map<String, Object> royalties(@RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo, @RequestParam(required = false) String siteId) {
        return reports.royalties(dateFrom, dateTo, siteId);
    }
}
