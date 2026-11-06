package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesCreditFlowService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Credit notes without a return, allocation, refund and write-off (SG-7). */
@RestController
@RequestMapping("/sales")
public class SalesCreditFlowController {

    private final SalesCreditFlowService svc;

    public SalesCreditFlowController(SalesCreditFlowService svc) {
        this.svc = svc;
    }

    @GetMapping("/credit-notes")
    @RequirePermissions(Permissions.SALES__RETURN_READ)
    public List<Map<String, Object>> list(@RequestParam Map<String, String> q) {
        return svc.list(q);
    }

    @PostMapping("/credit-notes")
    @Idempotent
    @RequirePermissions(Permissions.SALES__CREDIT_NOTE_WRITE)
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        return svc.create(SalesController.body(b));
    }

    @PostMapping("/credit-notes/{id}/allocate")
    @Idempotent
    @RequirePermissions(Permissions.SALES__CREDIT_NOTE_WRITE)
    public Map<String, Object> allocate(@PathVariable String id, @RequestBody Map<String, Object> b) {
        Object amount = SalesController.body(b).get("amount");
        return svc.allocate(id, SalesController.text(b, "documentId"), amount == null ? null : new BigDecimal(amount.toString()));
    }

    @PostMapping("/credit-notes/{id}/refund")
    @Idempotent
    @RequirePermissions(Permissions.SALES__CREDIT_NOTE_WRITE)
    public Map<String, Object> refund(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return svc.refund(id, SalesController.body(b));
    }

    @GetMapping("/credit-notes/{id}/pdf")
    @RequirePermissions(Permissions.SALES__RETURN_READ)
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"credit-note-" + id + ".pdf\"").body(svc.pdf(id));
    }

    @PostMapping("/invoices/{id}/write-off")
    @Idempotent
    @RequirePermissions(Permissions.SALES__WRITE_OFF_POST)
    public Map<String, Object> writeOff(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.writeOff(id, SalesController.body(b));
    }
}
