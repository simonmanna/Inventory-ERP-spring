package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesCreditRequestService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Credit requests beyond the limit (SG-8). */
@RestController
@RequestMapping("/sales")
public class SalesCreditRequestController {

    private final SalesCreditRequestService svc;

    public SalesCreditRequestController(SalesCreditRequestService svc) {
        this.svc = svc;
    }

    @GetMapping("/credit-requests")
    @RequirePermissions(Permissions.CREDIT__READ)
    public List<Map<String, Object>> list(@RequestParam Map<String, String> q) {
        return svc.list(q);
    }

    @PostMapping("/orders/{id}/credit-request")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__ORDER_WRITE)
    public Map<String, Object> request(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.request(id, SalesController.text(b, "reason"));
    }

    @PostMapping("/credit-requests/{id}/approve")
    @RequirePermissions(Permissions.CREDIT__OVERRIDE)
    public Map<String, Object> approve(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.approve(id, SalesController.text(b, "comment"));
    }

    @PostMapping("/credit-requests/{id}/reject")
    @RequirePermissions(Permissions.CREDIT__OVERRIDE)
    public Map<String, Object> reject(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.reject(id, SalesController.text(b, "comment"));
    }

    @GetMapping("/customers/{id}/credit-by-branch")
    @RequirePermissions(Permissions.CREDIT__READ)
    public List<Map<String, Object>> byBranch(@PathVariable String id) {
        return svc.byBranch(id);
    }
}
