package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesPricelistService;
import com.inventoryerp.sales.application.SalesPricingEngine;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Pricelists, price rules, margin floors and price resolution (ADR-S011 phase S2). */
@RestController
@RequestMapping("/sales")
public class SalesPricingController {

    private final SalesPricelistService svc;
    private final SalesPricingEngine engine;

    public SalesPricingController(SalesPricelistService svc, SalesPricingEngine engine) {
        this.svc = svc;
        this.engine = engine;
    }

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    @GetMapping("/pricelists")
    @RequirePermissions(Permissions.SALES__PRICING_READ)
    public List<Map<String, Object>> list() {
        return svc.list();
    }

    @GetMapping("/pricelists/{id}")
    @RequirePermissions(Permissions.SALES__PRICING_READ)
    public Map<String, Object> get(@PathVariable String id) {
        return svc.get(id);
    }

    @PostMapping("/pricelists")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        return svc.save(null, body(b));
    }

    @PutMapping("/pricelists/{id}")
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.save(id, body(b));
    }

    @DeleteMapping("/pricelists/{id}")
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> delete(@PathVariable String id) {
        return svc.delete(id);
    }

    @PostMapping("/pricelists/{id}/assign")
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> assign(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.assign(id, body(b));
    }

    @PostMapping("/pricelists/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> createItem(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.saveItem(id, null, body(b));
    }

    @PutMapping("/pricelists/{id}/items/{itemId}")
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> updateItem(@PathVariable String id, @PathVariable String itemId,
            @RequestBody Map<String, Object> b) {
        return svc.saveItem(id, itemId, body(b));
    }

    @DeleteMapping("/pricelists/{id}/items/{itemId}")
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> deleteItem(@PathVariable String id, @PathVariable String itemId) {
        return svc.deleteItem(id, itemId);
    }

    @GetMapping("/margin-floors")
    @RequirePermissions(Permissions.SALES__PRICING_READ)
    public List<Map<String, Object>> floors() {
        return svc.floors();
    }

    @PostMapping("/margin-floors")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> createFloor(@RequestBody Map<String, Object> b) {
        return svc.saveFloor(null, body(b));
    }

    @PutMapping("/margin-floors/{id}")
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> updateFloor(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return svc.saveFloor(id, body(b));
    }

    @DeleteMapping("/margin-floors/{id}")
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    public Map<String, Object> deleteFloor(@PathVariable String id) {
        return svc.deleteFloor(id);
    }

    @PostMapping("/pricing/resolve")
    @RequirePermissions(Permissions.SALES__PRICING_READ)
    public Map<String, Object> resolve(@RequestBody Map<String, Object> b) {
        return engine.resolve(body(b));
    }

    /** ADR-S022: the organization's pricing tier order ({@code precedence}) and the tiers available. */
    @GetMapping("/pricing/policy")
    @RequirePermissions(Permissions.SALES__PRICING_READ)
    public Map<String, Object> policy() {
        return engine.pricingPolicy();
    }

    @PutMapping("/pricing/policy")
    @RequirePermissions(Permissions.SALES__PRICING_MANAGE)
    @SuppressWarnings("unchecked")
    public Map<String, Object> savePolicy(@RequestBody Map<String, Object> b) {
        Object p = body(b).get("precedence");
        return engine.savePrecedence(p instanceof java.util.List<?> l ? (java.util.List<String>) l : null);
    }
}
