package com.inventoryerp.sales.web;

import com.inventoryerp.fleet.application.FleetDeliveryNoteService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesDeliveryService;
import com.inventoryerp.sales.application.SalesMilestoneService;
import com.inventoryerp.sales.application.SalesRecurringOrderService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Deliveries without the Fleet module, milestones and recurring orders (SG-9). Not gated by the Fleet switch. */
@RestController
@RequestMapping("/sales")
public class SalesDeliveryController {

    private final SalesDeliveryService deliveries;
    private final SalesMilestoneService milestones;
    private final SalesRecurringOrderService recurring;

    public SalesDeliveryController(SalesDeliveryService deliveries, SalesMilestoneService milestones,
            SalesRecurringOrderService recurring) {
        this.deliveries = deliveries;
        this.milestones = milestones;
        this.recurring = recurring;
    }

    @GetMapping("/deliveries")
    @RequirePermissions(Permissions.SALES__DELIVERY_READ)
    public List<Map<String, Object>> list(@RequestParam Map<String, String> q) {
        return deliveries.list(q);
    }

    @GetMapping("/deliveries/reconciliation")
    @RequirePermissions(Permissions.SALES__DELIVERY_READ)
    public List<Map<String, Object>> reconciliation(@RequestParam Map<String, String> q) {
        return deliveries.reconciliation(q);
    }

    @GetMapping("/deliveries/{id}")
    @RequirePermissions(Permissions.SALES__DELIVERY_READ)
    public Map<String, Object> get(@PathVariable String id) {
        return deliveries.get(id);
    }

    @GetMapping("/deliveries/{id}/note")
    @RequirePermissions(Permissions.SALES__DELIVERY_READ)
    public ResponseEntity<byte[]> note(@PathVariable String id) {
        FleetDeliveryNoteService.Pdf pdf = deliveries.note(id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.filename() + "\"").body(pdf.content());
    }

    @PostMapping("/orders/{id}/deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__DELIVERY_WRITE)
    public Map<String, Object> create(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return deliveries.create(id, SalesController.body(b));
    }

    @PostMapping("/deliveries/{id}/approve")
    @RequirePermissions(Permissions.SALES__DELIVERY_APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return deliveries.approve(id);
    }

    @PostMapping("/deliveries/{id}/issue")
    @Idempotent
    @RequirePermissions(Permissions.SALES__DELIVERY_WRITE)
    public Map<String, Object> issue(@PathVariable String id) {
        return deliveries.issue(id);
    }

    @PostMapping("/deliveries/{id}/confirm")
    @Idempotent
    @RequirePermissions(Permissions.SALES__DELIVERY_WRITE)
    public Map<String, Object> confirm(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return deliveries.confirm(id, SalesController.body(b));
    }

    @PostMapping("/deliveries/{id}/photos")
    @RequirePermissions(Permissions.SALES__DELIVERY_WRITE)
    public Map<String, Object> photo(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return deliveries.addPhoto(id, SalesController.body(b));
    }

    @GetMapping("/orders/{id}/milestones")
    @RequirePermissions(Permissions.SALES__ORDER_READ)
    public List<Map<String, Object>> milestones(@PathVariable String id) {
        return milestones.list(id);
    }

    @PutMapping("/orders/{id}/milestones")
    @RequirePermissions(Permissions.SALES__ORDER_WRITE)
    public List<Map<String, Object>> saveMilestones(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return milestones.save(id, SalesController.body(b));
    }

    @PostMapping("/orders/{id}/milestones/{milestoneId}/invoice")
    @Idempotent
    @RequirePermissions(Permissions.SALES__INVOICE_CREATE)
    public Map<String, Object> invoiceMilestone(@PathVariable String id, @PathVariable String milestoneId) {
        return milestones.invoice(id, milestoneId);
    }

    @GetMapping("/recurring-orders")
    @RequirePermissions(Permissions.SALES__ORDER_READ)
    public List<Map<String, Object>> recurring() {
        return recurring.list();
    }

    @PostMapping("/orders/{id}/recurring")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__ORDER_WRITE)
    public Map<String, Object> addRecurring(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return recurring.create(id, SalesController.body(b));
    }

    @PostMapping("/recurring-orders/{id}/stop")
    @RequirePermissions(Permissions.SALES__ORDER_WRITE)
    public Map<String, Object> stopRecurring(@PathVariable String id) {
        return recurring.stop(id);
    }
}
