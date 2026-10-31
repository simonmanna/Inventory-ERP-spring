package com.inventoryerp.analytics.web;

import com.inventoryerp.analytics.application.AnalyticsLookups;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Filter lists shared by the dashboards (ADR-S020). */
@RestController
@RequestMapping("/analytics")
public class AnalyticsLookupController {

    private final AnalyticsLookups lookups;

    public AnalyticsLookupController(AnalyticsLookups lookups) {
        this.lookups = lookups;
    }

    @GetMapping("/filter-options")
    @RequirePermissions(Permissions.ANALYTICS__VIEW)
    public Map<String, Object> options(@RequestParam(required = false) String partnerId) {
        return lookups.options(partnerId);
    }
}
