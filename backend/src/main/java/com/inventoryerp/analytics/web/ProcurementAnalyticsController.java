package com.inventoryerp.analytics.web;

import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.AnalyticsFilter;
import com.inventoryerp.analytics.application.Dashboard;
import com.inventoryerp.analytics.application.ProcurementKpiService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Procurement dashboard and its drill-down (ADR-S020); filters in docs/spec/analytics-kpis.md. */
@RestController
@RequestMapping("/analytics/procurement")
@RequirePermissions(Permissions.ANALYTICS__PROCUREMENT)
public class ProcurementAnalyticsController {

    private final ProcurementKpiService kpis;

    public ProcurementAnalyticsController(ProcurementKpiService kpis) {
        this.kpis = kpis;
    }

    @GetMapping
    public Dashboard dashboard(@RequestParam Map<String, String> q) {
        return kpis.dashboard(AnalyticsFilter.of(q));
    }

    @GetMapping("/documents")
    public PageResult<Map<String, Object>> documents(@RequestParam Map<String, String> q) {
        return kpis.documents(q.get("kpi"), AnalyticsFilter.of(q), Paging.of(q));
    }
}
