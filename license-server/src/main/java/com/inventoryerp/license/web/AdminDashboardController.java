package com.inventoryerp.license.web;

import com.inventoryerp.license.application.AdminAudits;
import com.inventoryerp.license.application.DashboardService;
import com.inventoryerp.license.shared.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The business overview, and the admin audit trail behind it. */
@RestController
@RequestMapping("/admin")
@RequireAdmin
public class AdminDashboardController {

    private final DashboardService dashboard;
    private final AdminAudits audits;

    public AdminDashboardController(DashboardService dashboard, AdminAudits audits) {
        this.dashboard = dashboard;
        this.audits = audits;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return dashboard.overview();
    }

    /** Installs that have stopped checking in — the early warning for a customer in trouble. */
    @GetMapping("/attention/silent")
    public List<DashboardService.Attention> silent() {
        return dashboard.silentInstalls(Instant.now());
    }

    /** Installs running more than they pay for. The upsell list. */
    @GetMapping("/attention/over-limit")
    public List<DashboardService.Attention> overLimit() {
        return dashboard.overLimitInstalls();
    }

    @GetMapping("/audit")
    public PageResult<AdminDtos.AuditResponse> audit(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return PageResult.of(audits.recent(page, pageSize), AdminDtos.AuditResponse::of);
    }
}
