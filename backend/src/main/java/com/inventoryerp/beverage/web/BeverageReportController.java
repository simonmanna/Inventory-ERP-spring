package com.inventoryerp.beverage.web;

import com.inventoryerp.beverage.application.BeverageReportService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /beverage/reports} variance, yield, bartender and dashboard (port of {@code beverage-report.controller.ts}). */
@RestController
@RequestMapping("/beverage/reports")
@RequiresModule("beverage")
@ConditionalOnProperty(name = "app.modules.beverage", havingValue = "true")
public class BeverageReportController {

    private record Range(Instant from, Instant to) {}

    /** {@code to} defaults to now, {@code from} to 30 days before {@code to}. */
    private static Range parseRange(String from, String to) {
        Instant toDate = to != null && !to.isEmpty() ? EntityPatch.toInstant(to) : Instant.now();
        Instant fromDate = from != null && !from.isEmpty() ? EntityPatch.toInstant(from)
                : toDate.minus(Duration.ofDays(30));
        return new Range(fromDate, toDate);
    }

    private static String blankToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private final BeverageReportService reports;

    public BeverageReportController(BeverageReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/variance")
    @RequirePermissions(Permissions.BEVERAGE__READ)
    public List<Map<String, Object>> variance(@RequestParam(required = false) String locationId) {
        return reports.variance(blankToNull(locationId));
    }

    @GetMapping("/yield")
    @RequirePermissions(Permissions.BEVERAGE__READ)
    public List<Map<String, Object>> yieldReport(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String locationId) {
        Range r = parseRange(from, to);
        return reports.yieldReport(r.from(), r.to(), blankToNull(locationId));
    }

    @GetMapping("/bartender")
    @RequirePermissions(Permissions.BEVERAGE__READ)
    public List<Map<String, Object>> bartender(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Range r = parseRange(from, to);
        return reports.bartender(r.from(), r.to());
    }

    @GetMapping("/dashboard")
    @RequirePermissions(Permissions.BEVERAGE__READ)
    public Map<String, Object> dashboard(@RequestParam(required = false) String locationId) {
        return reports.dashboard(blankToNull(locationId));
    }
}
