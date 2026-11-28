package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.InventoryFeatureUsageService;
import com.inventoryerp.inventory.application.InventoryFeatureUsageService.FeatureUsage;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IPR R6 (Phase 0, task T07): the organisation's feature-usage map beside the integrity report, with the deferred P2
 * findings its features promote to go-live blockers. Read-only; new route (no Nest counterpart).
 */
@RestController
@RequestMapping("/inventory")
public class InventoryFeatureUsageController {

    private final InventoryFeatureUsageService featureUsage;

    public InventoryFeatureUsageController(InventoryFeatureUsageService featureUsage) {
        this.featureUsage = featureUsage;
    }

    /** Which inventory-related features the organisation uses, and the P2s they promote (same permission as integrity). */
    @GetMapping("/reports/feature-usage")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public FeatureUsage featureUsage() {
        return featureUsage.report();
    }
}
