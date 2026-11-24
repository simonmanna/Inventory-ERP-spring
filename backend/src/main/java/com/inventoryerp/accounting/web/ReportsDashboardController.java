package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.ReportsDashboardService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /reports/dashboard-*} (port of {@code reports-dashboard.controller.ts}): home dashboard KPIs and activity.
 * Finance audit A4 (owner-approved deviation; Nest: any signed-in user): both need {@code report:accounting}. They
 * answer the cash position, revenue, net income, receivables ageing, and the latest invoices, payments and journal
 * entries with partner names, which used to reach every signed-in user, cashiers included.
 */
@RestController
@RequestMapping("/reports")
@RequirePermissions(Permissions.REPORT__ACCOUNTING)
public class ReportsDashboardController {

    private final ReportsDashboardService dashboard;

    public ReportsDashboardController(ReportsDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard-kpi")
    public Map<String, Object> kpi() {
        return dashboard.kpi();
    }

    /** {@code ActivityQuery}: {@code limit} clamped to 1..50 (default 10). */
    @GetMapping("/dashboard-activity")
    public Map<String, Object> activity(@RequestParam Map<String, String> query) {
        QueryParams.only(query, "limit");
        double n = query.get("limit") != null ? JsNumbers.number(query.get("limit")) : 10;
        if (Double.isNaN(n)) {
            throw new IllegalArgumentException("Argument `take` is missing.");
        }
        return dashboard.activity((int) Math.min(50, Math.max(1, n)));
    }
}
