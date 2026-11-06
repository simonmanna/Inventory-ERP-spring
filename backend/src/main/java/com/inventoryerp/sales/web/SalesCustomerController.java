package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesCustomerService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Customer master extras (SG-5). */
@RestController
@RequestMapping("/sales/customers")
public class SalesCustomerController {

    private final SalesCustomerService svc;

    public SalesCustomerController(SalesCustomerService svc) {
        this.svc = svc;
    }

    @GetMapping("/duplicates")
    @RequirePermissions(Permissions.SALES__CUSTOMER_READ)
    public List<Map<String, Object>> duplicates(@RequestParam Map<String, String> q) {
        return svc.duplicates(q);
    }

    @GetMapping("/export")
    @RequirePermissions(Permissions.SALES__CUSTOMER_READ)
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "text/csv")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customers.csv\"").body(svc.export());
    }

    @PostMapping("/import")
    @RequirePermissions(Permissions.SALES__CUSTOMER_MANAGE)
    public Map<String, Object> importRows(@RequestBody Map<String, Object> b) {
        return svc.importRows(SalesController.body(b));
    }

    @GetMapping("/{id}/profile")
    @RequirePermissions(Permissions.SALES__CUSTOMER_READ)
    public Map<String, Object> profile(@PathVariable String id) {
        return svc.profile(id);
    }

    @PutMapping("/{id}/profile")
    @RequirePermissions(Permissions.SALES__CUSTOMER_MANAGE)
    public Map<String, Object> save(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.saveProfile(id, SalesController.body(b));
    }

    @PostMapping("/{id}/suspend")
    @RequirePermissions(Permissions.SALES__CUSTOMER_MANAGE)
    public Map<String, Object> suspend(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.suspend(id, SalesController.text(b, "reason"));
    }

    @PostMapping("/{id}/reinstate")
    @RequirePermissions(Permissions.SALES__CUSTOMER_MANAGE)
    public Map<String, Object> reinstate(@PathVariable String id) {
        return svc.reinstate(id);
    }

    @PostMapping("/{id}/merge")
    @RequirePermissions(Permissions.SALES__CUSTOMER_MERGE)
    public Map<String, Object> merge(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.merge(id, SalesController.text(b, "intoPartnerId"), SalesController.text(b, "reason"));
    }
}
