package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.SupplierPerformanceService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /procurement/reports/supplier-performance}: on-time, fill rate, quality and price variance per supplier. */
@RestController
@RequestMapping("/procurement/reports")
public class SupplierPerformanceController {

    private final SupplierPerformanceService svc;

    public SupplierPerformanceController(SupplierPerformanceService svc) {
        this.svc = svc;
    }

    @GetMapping("/supplier-performance")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__READ)
    public Map<String, Object> report(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String partnerId) {
        return svc.report(from, to, partnerId);
    }
}
