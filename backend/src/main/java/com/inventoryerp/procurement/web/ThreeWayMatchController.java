package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.threewaymatch.ThreeWayMatchService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /procurement/three-way-match} (port of {@code kernel/three-way-match/three-way-match.controller.ts}). */
@RestController
@RequestMapping("/procurement/three-way-match")
public class ThreeWayMatchController {

    private final ThreeWayMatchService svc;

    public ThreeWayMatchController(ThreeWayMatchService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.PROCUREMENT__THREE_WAY_MATCH__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String purchaseOrderId) {
        return svc.list(status, purchaseOrderId);
    }

    @PostMapping("/recompute/{orderId}")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT__THREE_WAY_MATCH__READ)
    public List<Map<String, Object>> recompute(@PathVariable String orderId) {
        return svc.recomputeForOrder(orderId);
    }

    @PostMapping("/validate-bill/{billId}")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT__THREE_WAY_MATCH__READ)
    public Map<String, Object> validateBill(@PathVariable String billId) {
        return svc.validateBillForPosting(billId);
    }
}
