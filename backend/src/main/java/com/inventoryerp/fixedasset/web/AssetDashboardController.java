package com.inventoryerp.fixedasset.web;

import com.inventoryerp.fixedasset.application.AssetDashboardService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /fixed-assets-dashboard} (port of {@code asset-dashboard.controller.ts}). */
@RestController
@RequestMapping("/fixed-assets-dashboard")
@RequiresModule("fixed-asset")
@ConditionalOnProperty(name = "app.modules.assets", havingValue = "true")
public class AssetDashboardController {

    private final AssetDashboardService service;

    public AssetDashboardController(AssetDashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public Map<String, Object> summary() {
        return service.getSummary();
    }

    @GetMapping("/upcoming")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public Map<String, Object> upcoming() {
        return service.getUpcoming();
    }

    @GetMapping("/reports/{type}")
    @RequirePermissions(Permissions.ASSET_REPORT__READ)
    public Object reports(@PathVariable String type) {
        return service.getReports(type);
    }
}
