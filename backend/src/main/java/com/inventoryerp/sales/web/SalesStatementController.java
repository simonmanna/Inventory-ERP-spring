package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesStatementService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer statements: filters, reconciliation, delivery log, confirmation, disputes and bulk sending (SG-10). */
@RestController
@RequestMapping("/sales/statements")
public class SalesStatementController {

    private final SalesStatementService svc;

    public SalesStatementController(SalesStatementService svc) {
        this.svc = svc;
    }

    @GetMapping("/outstanding")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_READ)
    public List<Map<String, Object>> outstanding(@RequestParam Map<String, String> q) {
        return svc.outstanding(q);
    }

    @PostMapping("/send-bulk")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> bulk(@RequestBody Map<String, Object> b) {
        return svc.sendBulk(SalesController.body(b));
    }

    @PostMapping("/log/{id}/confirm")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> confirm(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.confirm(id, SalesController.body(b));
    }

    @PostMapping("/disputes/{id}/resolve")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> resolve(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.resolve(id, SalesController.body(b));
    }

    @GetMapping("/{partnerId}")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_READ)
    public Map<String, Object> statement(@PathVariable String partnerId, @RequestParam Map<String, String> q) {
        return svc.statement(partnerId, q);
    }

    @GetMapping("/{partnerId}/pdf")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_READ)
    public ResponseEntity<byte[]> pdf(@PathVariable String partnerId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"statement-" + partnerId + ".pdf\"").body(svc.pdf(partnerId, from, to));
    }

    @PostMapping("/{partnerId}/send")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> send(@PathVariable String partnerId, @RequestBody(required = false) Map<String, Object> b) {
        return svc.send(partnerId, SalesController.body(b));
    }

    @PostMapping("/{partnerId}/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__COLLECTIONS_READ)
    public Map<String, Object> dispute(@PathVariable String partnerId, @RequestBody Map<String, Object> b) {
        return svc.dispute(partnerId, SalesController.body(b));
    }
}
