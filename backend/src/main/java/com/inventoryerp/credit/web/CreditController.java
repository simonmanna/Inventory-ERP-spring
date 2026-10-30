package com.inventoryerp.credit.web;

import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Credit management routes (ADR-S011 §3): exposure, limits, policy, decisions, review queue, customer hold. */
@RestController
@RequestMapping("/credit")
public class CreditController {

    private final CreditService svc;

    public CreditController(CreditService svc) {
        this.svc = svc;
    }

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    @GetMapping("/partners/{partnerId}/exposure")
    @RequirePermissions(Permissions.CREDIT__READ)
    public Map<String, Object> exposure(@PathVariable String partnerId, @RequestParam(required = false) String branchId) {
        return svc.exposure(partnerId, branchId);
    }

    @PostMapping("/partners/{partnerId}/hold")
    @RequirePermissions(Permissions.CREDIT__MANAGE)
    public Map<String, Object> hold(@PathVariable String partnerId, @RequestBody Map<String, Object> b) {
        Map<String, Object> in = body(b);
        Object reason = in.get("reason");
        return svc.setHold(partnerId, Boolean.TRUE.equals(in.get("hold")), reason == null ? null : reason.toString());
    }

    @GetMapping("/review-queue")
    @RequirePermissions(Permissions.CREDIT__READ)
    public List<Map<String, Object>> review() {
        return svc.reviewQueue();
    }

    @GetMapping("/decisions")
    @RequirePermissions(Permissions.CREDIT__READ)
    public List<Map<String, Object>> decisions(@RequestParam Map<String, String> q) {
        return svc.decisions(q);
    }

    @GetMapping("/limits")
    @RequirePermissions(Permissions.CREDIT__READ)
    public List<Map<String, Object>> limits(@RequestParam(required = false) String partnerId) {
        return svc.limits(partnerId);
    }

    @PostMapping("/limits")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CREDIT__MANAGE)
    public Map<String, Object> createLimit(@RequestBody Map<String, Object> b) {
        return svc.saveLimit(null, body(b));
    }

    @PutMapping("/limits/{id}")
    @RequirePermissions(Permissions.CREDIT__MANAGE)
    public Map<String, Object> updateLimit(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.saveLimit(id, body(b));
    }

    @DeleteMapping("/limits/{id}")
    @RequirePermissions(Permissions.CREDIT__MANAGE)
    public Map<String, Object> deleteLimit(@PathVariable String id) {
        return svc.deleteLimit(id);
    }

    @GetMapping("/policy")
    @RequirePermissions(Permissions.CREDIT__READ)
    public Map<String, Object> policy() {
        return svc.policy();
    }

    @PutMapping("/policy")
    @RequirePermissions(Permissions.CREDIT__MANAGE)
    public Map<String, Object> updatePolicy(@RequestBody Map<String, Object> b) {
        return svc.updatePolicy(body(b));
    }
}
