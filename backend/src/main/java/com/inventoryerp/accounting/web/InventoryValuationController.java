package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /reports/inventory} (port of {@code inventory-valuation.controller.ts}). */
@RestController
@RequestMapping("/reports/inventory")
public class InventoryValuationController {

    private final InventoryValuationService reports;

    public InventoryValuationController(InventoryValuationService reports) {
        this.reports = reports;
    }

    /** Current value = remaining value by costing method; a past {@code asOf} is rebuilt from the stock ledger. */
    @GetMapping("/valuation")
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public Map<String, Object> getValuation(@RequestParam(required = false) String asOf) {
        return reports.valuation(asOf);
    }

    /** Inventory sub-ledger vs the inventory control account, with the variance explained by source. */
    @GetMapping("/gl-tieout")
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public Map<String, Object> getTieOut(@RequestParam(required = false) String asOf,
            @RequestParam(required = false) String tolerance) {
        double tol = 1;
        if (tolerance != null) {
            double n = JsNumbers.number(tolerance);
            tol = Math.max(0, Double.isNaN(n) ? 0 : n);
        }
        return reports.glTieOut(asOf, tol);
    }
}
