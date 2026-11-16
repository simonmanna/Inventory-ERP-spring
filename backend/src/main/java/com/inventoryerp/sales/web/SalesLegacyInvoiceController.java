package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesLegacyInvoiceService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase A A4 (plan §6): review of historical Fleet goods invoices on sales orders — the exception queue, the matches
 * of one order, and the two-person reconciliation ({@code sales:billing:override}; the reviewer cannot approve).
 */
@RestController
@RequestMapping("/sales")
public class SalesLegacyInvoiceController {

    private final SalesLegacyInvoiceService legacy;
    private final com.inventoryerp.sales.application.SalesBillingService billing;

    public SalesLegacyInvoiceController(SalesLegacyInvoiceService legacy,
            com.inventoryerp.sales.application.SalesBillingService billing) {
        this.legacy = legacy;
        this.billing = billing;
    }

    /** Phase A A4: run the financial reconciliation of Sales-owned goods now (also nightly). */
    @PostMapping("/billing/reconcile")
    @RequirePermissions(Permissions.SALES__BILLING_OVERRIDE)
    public Map<String, Object> reconcile() {
        return billing.financialReconcile();
    }

    @GetMapping("/legacy-invoice-reviews")
    @RequirePermissions(Permissions.SALES__BILLING_OVERRIDE)
    public List<Map<String, Object>> queue() {
        return legacy.queue();
    }

    @GetMapping("/orders/{id}/legacy-invoices")
    @RequirePermissions(Permissions.SALES__BILLING_OVERRIDE)
    public Map<String, Object> forOrder(@PathVariable String id) {
        return legacy.forOrder(id);
    }

    @PostMapping("/orders/{id}/legacy-invoices/{documentId}/review")
    @RequirePermissions(Permissions.SALES__BILLING_OVERRIDE)
    public Map<String, Object> submit(@PathVariable String id, @PathVariable String documentId,
            @RequestBody Map<String, Object> b) {
        return legacy.submit(id, documentId, SalesController.body(b));
    }

    @PostMapping("/legacy-invoice-reviews/{id}/approve")
    @Idempotent
    @RequirePermissions(Permissions.SALES__BILLING_OVERRIDE)
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return legacy.approve(id, SalesController.body(b));
    }

    @PostMapping("/legacy-invoice-reviews/{id}/exception")
    @RequirePermissions(Permissions.SALES__BILLING_OVERRIDE)
    public Map<String, Object> exception(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return legacy.exception(id, SalesController.body(b));
    }

    @PostMapping("/legacy-invoice-reviews/{id}/reopen")
    @RequirePermissions(Permissions.SALES__BILLING_OVERRIDE)
    public Map<String, Object> reopen(@PathVariable String id) {
        return legacy.reopen(id);
    }
}
