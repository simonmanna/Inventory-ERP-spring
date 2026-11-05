package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesHandoverService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery &amp; fulfilment handover of sales orders and sales invoices (same model as the branch cash sale delivery
 * tab). Additive routes beyond the NestJS reference.
 */
@RestController
public class SalesHandoverController {

    private final SalesHandoverService svc;

    public SalesHandoverController(SalesHandoverService svc) {
        this.svc = svc;
    }

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    @GetMapping("/sales/orders/{id}/handover")
    @RequirePermissions(Permissions.SALES__ORDER_READ)
    public Map<String, Object> order(@PathVariable String id) {
        return svc.get(SalesHandoverService.SALES_ORDER, id);
    }

    @PatchMapping("/sales/orders/{id}/handover")
    @RequirePermissions(Permissions.SALES__ORDER_WRITE)
    public Map<String, Object> updateOrder(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return svc.update(SalesHandoverService.SALES_ORDER, id, body(b));
    }

    @GetMapping("/sales/invoices/{id}/handover")
    @RequirePermissions(Permissions.INVOICE__READ)
    public Map<String, Object> invoice(@PathVariable String id) {
        return svc.get(SalesHandoverService.INVOICE, id);
    }

    @PatchMapping("/sales/invoices/{id}/handover")
    @RequirePermissions(Permissions.INVOICE__UPDATE)
    public Map<String, Object> updateInvoice(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> b) {
        return svc.update(SalesHandoverService.INVOICE, id, body(b));
    }
}
