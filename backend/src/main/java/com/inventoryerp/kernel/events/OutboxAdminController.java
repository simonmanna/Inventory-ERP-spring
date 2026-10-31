package com.inventoryerp.kernel.events;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /admin/outbox}: dead event rows of the organization and their retry (extension, PR-1). */
@RestController
@RequestMapping("/admin/outbox")
public class OutboxAdminController {

    private final OutboxAdminService svc;

    public OutboxAdminController(OutboxAdminService svc) {
        this.svc = svc;
    }

    @GetMapping("/dead")
    @RequirePermissions(Permissions.SETTING__READ)
    public Map<String, Object> dead(@RequestParam(required = false) String eventName) {
        return svc.dead(eventName);
    }

    @PostMapping("/dead/retry")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> retryAll(@RequestParam(required = false) String eventName) {
        return svc.retryAll(eventName);
    }

    @PostMapping("/{id}/retry")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> retry(@PathVariable String id) {
        return svc.retry(id);
    }
}
