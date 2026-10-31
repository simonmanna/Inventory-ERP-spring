package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.ReplenishmentService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /inventory/replenishment} (INV-3): rules, alerts, the scan and requests for purchase requisitions. */
@RestController
@RequestMapping("/inventory/replenishment")
public class ReplenishmentController {

    private final ReplenishmentService replenishment;

    public ReplenishmentController(ReplenishmentService replenishment) {
        this.replenishment = replenishment;
    }

    @GetMapping("/rules")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public PageResult<Map<String, Object>> listRules(@RequestParam Map<String, String> query) {
        return replenishment.listRules(query);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY__REPLENISH)
    public Map<String, Object> createRule(@RequestBody Map<String, Object> dto) {
        return replenishment.createRule(dto);
    }

    @PatchMapping("/rules/{id}")
    @RequirePermissions(Permissions.INVENTORY__REPLENISH)
    public Map<String, Object> updateRule(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return replenishment.updateRule(id, dto);
    }

    @DeleteMapping("/rules/{id}")
    @RequirePermissions(Permissions.INVENTORY__REPLENISH)
    public Map<String, Object> deleteRule(@PathVariable String id) {
        return replenishment.deleteRule(id);
    }

    @GetMapping("/alerts")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public PageResult<Map<String, Object>> listAlerts(@RequestParam Map<String, String> query) {
        return replenishment.listAlerts(query);
    }

    @PostMapping("/alerts/{id}/dismiss")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY__REPLENISH)
    public Map<String, Object> dismiss(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return replenishment.dismiss(id, dto);
    }

    @PostMapping("/scan")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY__REPLENISH)
    public Map<String, Object> scan() {
        return replenishment.scan();
    }

    @PostMapping("/requisition")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY__REPLENISH)
    public Map<String, Object> requestRequisition(@RequestBody Map<String, Object> dto) {
        return replenishment.requestRequisition(dto);
    }
}
