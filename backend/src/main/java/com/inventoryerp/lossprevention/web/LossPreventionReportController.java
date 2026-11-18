package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.inventoryerp.lossprevention.application.LpCommands.ReportQuery;
import com.inventoryerp.lossprevention.application.LpReportService;

/** {@code /loss-prevention/dashboard} and {@code /reports} (ADR-S077): what is open, and the owner's reports. */
@RestController
@RequestMapping("/loss-prevention")
@RequiresModule("loss-prevention")
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionReportController {

    private final LpReportService reports;

    public LossPreventionReportController(LpReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/dashboard")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> dashboard() {
        return reports.dashboard();
    }

    @GetMapping("/reports/performance")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> performance(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.performance(new ReportQuery(from, to, branchId));
    }

    @GetMapping("/reports/by-cashier")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> byCashier(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.byCashier(new ReportQuery(from, to, branchId));
    }

    @GetMapping("/reports/bar-variance")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> barVariance(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.barVariance(new ReportQuery(from, to, branchId));
    }
}
