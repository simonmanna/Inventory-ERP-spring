package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.ReplenishmentService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
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

    private static final String ID_LEN = "must be shorter than or equal to 64 characters";
    private static final String QTY_MAX = "must not be greater than 99999999999999";

    /**
     * {@code POST /rules}: one rule per product and location. Hardening R5a: lengths and the column range are checked
     * here; the business checks (levels, route, source, variant) stay in the service with their own messages.
     */
    public record CreateRuleRequest(
            @Size(max = 64, message = "productId " + ID_LEN) String productId,
            @Size(max = 64, message = "locationId " + ID_LEN) String locationId,
            @DecimalMax(value = "99999999999999", message = "minQty " + QTY_MAX) BigDecimal minQty,
            @DecimalMax(value = "99999999999999", message = "maxQty " + QTY_MAX) BigDecimal maxQty,
            @DecimalMax(value = "99999999999999", message = "reorderQty " + QTY_MAX) BigDecimal reorderQty,
            @Size(max = 64, message = "supplierId " + ID_LEN) String supplierId,
            @DecimalMin(value = "0", message = "leadTimeDays cannot be negative")
            @DecimalMax(value = "3650", message = "leadTimeDays must not be greater than 3650") BigDecimal leadTimeDays,
            Boolean autoRequisition,
            @Size(max = 32, message = "route must be one of: buy, manufacture, transfer") String route,
            @Size(max = 64, message = "sourceLocationId " + ID_LEN) String sourceLocationId,
            @Size(max = 64, message = "variantId " + ID_LEN) String variantId) {}

    /** {@code PATCH /rules/:id}: omitted = unchanged, explicit {@code null} clears (maxQty, reorderQty, supplierId). */
    public record UpdateRuleRequest(
            @DecimalMax(value = "99999999999999", message = "minQty " + QTY_MAX) BigDecimal minQty,
            @DecimalMax(value = "99999999999999", message = "maxQty " + QTY_MAX) BigDecimal maxQty,
            @DecimalMax(value = "99999999999999", message = "reorderQty " + QTY_MAX) BigDecimal reorderQty,
            @Size(max = 64, message = "supplierId " + ID_LEN) String supplierId,
            @DecimalMin(value = "0", message = "leadTimeDays cannot be negative")
            @DecimalMax(value = "3650", message = "leadTimeDays must not be greater than 3650") BigDecimal leadTimeDays,
            Boolean autoRequisition,
            Boolean isActive,
            @Size(max = 32, message = "route must be one of: buy, manufacture, transfer") String route,
            @Size(max = 64, message = "sourceLocationId " + ID_LEN) String sourceLocationId) {}

    /** {@code POST /alerts/:id/dismiss}. */
    public record DismissRequest(
            @Size(max = 1000, message = "reason must be shorter than or equal to 1000 characters") String reason) {}

    /** {@code POST /requisition}. */
    public record RequisitionRequest(
            @Size(max = 500, message = "alertIds must contain no more than 500 elements")
            List<@Size(max = 64, message = ID_LEN) String> alertIds) {}

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
