package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesCollectionsService;
import java.time.LocalDate;
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

/** Collections: worklist, dunning levels / runs, promises to pay, statement runs (ADR-S011 S8). */
@RestController
@RequestMapping("/sales/collections")
public class SalesCollectionsController {

    private final SalesCollectionsService svc;

    public SalesCollectionsController(SalesCollectionsService svc) {
        this.svc = svc;
    }

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    private static LocalDate date(Map<String, Object> b, String k) {
        Object v = body(b).get(k);
        return v == null || v.toString().isBlank() ? null : LocalDate.parse(v.toString().substring(0, 10));
    }

    @GetMapping("/worklist")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_READ)
    public List<Map<String, Object>> worklist(@RequestParam Map<String, String> q) {
        return svc.worklist(q);
    }

    @GetMapping("/levels")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_READ)
    public List<Map<String, Object>> levels() {
        return svc.levels();
    }

    @PostMapping("/levels")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> createLevel(@RequestBody Map<String, Object> b) {
        return svc.saveLevel(null, body(b));
    }

    @PutMapping("/levels/{id}")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> updateLevel(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.saveLevel(id, body(b));
    }

    @GetMapping("/runs")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_READ)
    public List<Map<String, Object>> runs() {
        return svc.runs();
    }

    @GetMapping("/runs/{id}")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_READ)
    public Map<String, Object> run(@PathVariable String id) {
        return svc.run(id);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> startRun(@RequestBody(required = false) Map<String, Object> b) {
        return svc.run(date(b, "asOf"), "manual");
    }

    @GetMapping("/promises")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_READ)
    public List<Map<String, Object>> promises(@RequestParam(required = false) String partnerId) {
        return svc.promises(partnerId);
    }

    @PostMapping("/promises")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> promise(@RequestBody Map<String, Object> b) {
        return svc.promise(body(b));
    }

    @PostMapping("/promises/{id}/cancel")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> cancelPromise(@PathVariable String id) {
        return svc.cancelPromise(id);
    }

    @PostMapping("/promises/settle")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> settle(@RequestBody(required = false) Map<String, Object> b) {
        LocalDate asOf = date(b, "asOf");
        return Map.of("settled", svc.expirePromises(asOf == null ? LocalDate.now() : asOf));
    }

    @PostMapping("/statements")
    @RequirePermissions(Permissions.SALES__COLLECTIONS_MANAGE)
    public Map<String, Object> statements(@RequestBody(required = false) Map<String, Object> b) {
        return svc.statementRun(date(b, "asOf"));
    }
}
