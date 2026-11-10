package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.ReplenishmentService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
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

/**
 * {@code /inventory/replenishment} (INV-3): rules, alerts, the scan and requests for purchase requisitions. Bodies are
 * records with exactly the fields the web screen and the service use (unknown fields are 400); the business checks and
 * their messages (levels, route, source location, reason, alert ids) stay in {@link ReplenishmentService}.
 */
@RestController
@RequestMapping("/inventory/replenishment")
public class ReplenishmentController {

    /** {@code POST /rules}: one rule per product and location. */
    public record CreateRuleRequest(String productId, String locationId, BigDecimal minQty, BigDecimal maxQty,
            BigDecimal reorderQty, String supplierId, BigDecimal leadTimeDays, Boolean autoRequisition, String route,
            String sourceLocationId) {}

    /** {@code PATCH /rules/:id}: omitted = unchanged, explicit {@code null} clears (maxQty, reorderQty, supplierId). */
    public record UpdateRuleRequest(BigDecimal minQty, BigDecimal maxQty, BigDecimal reorderQty, String supplierId,
            BigDecimal leadTimeDays, Boolean autoRequisition, Boolean isActive, String route,
            String sourceLocationId) {}

    /** {@code POST /alerts/:id/dismiss}. */
    public record DismissRequest(String reason) {}

    /** {@code POST /requisition}. */
    public record RequisitionRequest(List<String> alertIds) {}

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
    public Map<String, Object> createRule(@Valid @RequestBody CreateRuleRequest dto) {
        return replenishment.createRule(EntityPatch.data(dto));
    }

    @PatchMapping("/rules/{id}")
    @RequirePermissions(Permissions.INVENTORY__REPLENISH)
    public Map<String, Object> updateRule(@PathVariable String id, @Valid @RequestBody UpdateRuleRequest dto) {
        return replenishment.updateRule(id, EntityPatch.data(dto));
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
    public Map<String, Object> dismiss(@PathVariable String id, @Valid @RequestBody DismissRequest dto) {
        return replenishment.dismiss(id, EntityPatch.data(dto));
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
    public Map<String, Object> requestRequisition(@Valid @RequestBody RequisitionRequest dto) {
        return replenishment.requestRequisition(EntityPatch.data(dto));
    }
}
