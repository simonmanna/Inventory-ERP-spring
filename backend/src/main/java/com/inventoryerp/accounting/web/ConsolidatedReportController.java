package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.ConsolidatedReportService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /reports/accounting/consolidated} — per-branch side-by-side P&L / trial balance (ADR-S005). */
@RestController
public class ConsolidatedReportController {

    private final ConsolidatedReportService service;

    public ConsolidatedReportController(ConsolidatedReportService service) {
        this.service = service;
    }

    @GetMapping("/reports/accounting/consolidated")
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public Map<String, Object> consolidated(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String statement) {
        return service.consolidated(from, to, statement);
    }
}
