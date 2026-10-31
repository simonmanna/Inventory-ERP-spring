package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesComplaintService;
import com.inventoryerp.sales.application.SalesReturnService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Returns (RMA), customer complaints and quality certificates (ADR-S011 S7). */
@RestController
@RequestMapping("/sales")
public class SalesReturnController {

    private final SalesReturnService returns;
    private final SalesComplaintService complaints;

    public SalesReturnController(SalesReturnService returns, SalesComplaintService complaints) {
        this.returns = returns;
        this.complaints = complaints;
    }

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    private static String text(Map<String, Object> b, String k) {
        Object v = body(b).get(k);
        return v == null ? null : v.toString();
    }

    @GetMapping("/returns")
    @RequirePermissions(Permissions.SALES__RETURN_READ)
    public List<Map<String, Object>> list(@RequestParam Map<String, String> q) {
        return returns.list(q);
    }

    @GetMapping("/returns/{id}")
    @RequirePermissions(Permissions.SALES__RETURN_READ)
    public Map<String, Object> get(@PathVariable String id) {
        return returns.get(id);
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__RETURN_WRITE)
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        return returns.create(body(b));
    }

    @PostMapping("/returns/{id}/approve")
    @RequirePermissions(Permissions.SALES__RETURN_APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return returns.approve(id);
    }

    @PostMapping("/returns/{id}/reject")
    @RequirePermissions(Permissions.SALES__RETURN_APPROVE)
    public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return returns.reject(id, text(b, "reason"));
    }

    @PostMapping("/returns/{id}/cancel")
    @RequirePermissions(Permissions.SALES__RETURN_WRITE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return returns.cancel(id);
    }

    @PostMapping("/returns/{id}/receive")
    @Idempotent
    @RequirePermissions(Permissions.SALES__RETURN_WRITE)
    public Map<String, Object> receive(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return returns.receive(id, body(b));
    }

    @PostMapping("/returns/{id}/inspect")
    @Idempotent
    @RequirePermissions(Permissions.SALES__RETURN_WRITE)
    public Map<String, Object> inspect(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return returns.inspect(id, body(b));
    }

    @PostMapping("/returns/{id}/close")
    @Idempotent
    @RequirePermissions(Permissions.SALES__RETURN_APPROVE)
    public Map<String, Object> close(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return returns.close(id, body(b));
    }

    @GetMapping("/complaints")
    @RequirePermissions(Permissions.SALES__COMPLAINT_READ)
    public List<Map<String, Object>> complaints(@RequestParam Map<String, String> q) {
        return complaints.list(q);
    }

    @PostMapping("/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__COMPLAINT_WRITE)
    public Map<String, Object> complain(@RequestBody Map<String, Object> b) {
        return complaints.create(body(b));
    }

    @PutMapping("/complaints/{id}")
    @RequirePermissions(Permissions.SALES__COMPLAINT_WRITE)
    public Map<String, Object> updateComplaint(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return complaints.update(id, body(b));
    }

    @GetMapping("/quality-certificates")
    @RequirePermissions(Permissions.SALES__ORDER_READ)
    public List<Map<String, Object>> certificates(@RequestParam(required = false) String orderId) {
        return complaints.certificates(orderId);
    }

    @PostMapping("/quality-certificates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__ORDER_WRITE)
    public Map<String, Object> certificate(@RequestBody Map<String, Object> b) {
        return complaints.addCertificate(body(b));
    }
}
