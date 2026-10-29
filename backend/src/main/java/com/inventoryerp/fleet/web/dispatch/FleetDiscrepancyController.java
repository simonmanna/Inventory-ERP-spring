package com.inventoryerp.fleet.web.dispatch;

import com.inventoryerp.fleet.application.DispatchCommands.DecisionInput;
import com.inventoryerp.fleet.application.DispatchCommands.DiscrepancyQuery;
import com.inventoryerp.fleet.application.FleetDiscrepancyService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery discrepancies (fleet 1.3): list / detail, approve (raises a draft credit note on invoiced goods —
 * Idempotency-Key required), reject (reason required), sync with the approvals inbox. The recorder and the trip's
 * driver cannot decide (403).
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetDiscrepancyController {

    private final FleetDiscrepancyService discrepancies;

    public FleetDiscrepancyController(FleetDiscrepancyService discrepancies) {
        this.discrepancies = discrepancies;
    }

    @GetMapping("/delivery-discrepancies")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) @OneOf({"pending", "approved", "rejected"}) String status,
            @RequestParam(required = false) @OneOf({"shortage", "excess", "returned"}) String kind,
            @RequestParam(required = false) String deliveryOrderId,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return discrepancies.list(new DiscrepancyQuery(status, kind, deliveryOrderId, partnerId, page, pageSize));
    }

    @GetMapping("/delivery-discrepancies/{id}")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return discrepancies.get(id);
    }

    @PostMapping("/delivery-discrepancies/{id}/approve")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_DISPATCH__APPROVE_DISCREPANCY)
    public Map<String, Object> approve(@PathVariable String id, @Valid @RequestBody(required = false) DecisionInput dto) {
        return discrepancies.approve(id, dto == null ? null : dto.notes());
    }

    @PostMapping("/delivery-discrepancies/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__APPROVE_DISCREPANCY)
    public Map<String, Object> reject(@PathVariable String id, @Valid @RequestBody DecisionInput dto) {
        return discrepancies.reject(id, dto.notes());
    }

    /** Apply a decision taken in the approvals inbox now. */
    @PostMapping("/delivery-discrepancies/{id}/sync")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__APPROVE_DISCREPANCY)
    public Map<String, Object> sync(@PathVariable String id) {
        return discrepancies.sync(id);
    }
}
