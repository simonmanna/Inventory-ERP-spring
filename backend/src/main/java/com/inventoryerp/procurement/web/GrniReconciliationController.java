package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.GrniReconciliationService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /procurement/reports} (port of {@code grni-reconciliation.controller.ts}). */
@RestController
@RequestMapping("/procurement/reports")
public class GrniReconciliationController {

    private final GrniReconciliationService svc;

    public GrniReconciliationController(GrniReconciliationService svc) {
        this.svc = svc;
    }

    /** GRNI reconciliation: open receipt value, aged, next to the GRNI GL balance it should tie to. */
    @GetMapping("/grni")
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__READ)
    public Map<String, Object> grni(@RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String asOf, @RequestParam(required = false) String minAgeDays) {
        return svc.report(partnerId, asOf, minAgeDays != null && !minAgeDays.isEmpty()
                ? JsNumbers.number(minAgeDays) : null);
    }
}
