package com.inventoryerp.costing.web;

import com.inventoryerp.costing.application.AbsorptionSettlementService;
import com.inventoryerp.costing.application.AbsorptionSettlementService.CapacityInput;
import com.inventoryerp.costing.application.AbsorptionSettlementService.SettleInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /costing/normal-capacities}, {@code /costing/absorption-settlements} and {@code /costing/cost-flow-bridge}
 * (Cost Management CM-4, ADR-S034; extension): governed normal capacity and the IAS 2.13 cause-based settlement of
 * absorbed vs actual conversion overhead.
 */
@RestController
@RequestMapping("/costing")
public class AbsorptionSettlementController {

    public record CapacityRequest(
            @NotBlank(message = "workCenterId should not be empty") String workCenterId,
            @NotBlank(message = "costCenterId should not be empty") String costCenterId,
            String basis,
            BigDecimal normalCapacity,
            BigDecimal budgetFixedOverhead,
            BigDecimal fixedOverheadRate,
            Integer periodDays,
            @Size(max = 1000, message = "derivation must be at most 1000 characters") String derivation,
            String rateSource,
            @NotBlank(message = "effectiveFrom should not be empty") String effectiveFrom,
            String reviewDue) {}

    public record SettleRequest(
            @NotBlank(message = "workCenterId should not be empty") String workCenterId,
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to,
            String classification,
            @Size(max = 1000, message = "justification must be at most 1000 characters") String justification) {}

    private final AbsorptionSettlementService settlements;

    public AbsorptionSettlementController(AbsorptionSettlementService settlements) {
        this.settlements = settlements;
    }

    private static SettleInput input(SettleRequest r) {
        return new SettleInput(r.workCenterId(), r.from(), r.to(), r.classification(), r.justification());
    }

    @GetMapping("/normal-capacities")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public List<Map<String, Object>> capacities(@RequestParam(required = false) String workCenterId) {
        return settlements.capacities(workCenterId);
    }

    @PostMapping("/normal-capacities")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> propose(@Valid @RequestBody CapacityRequest r) {
        return settlements.proposeCapacity(new CapacityInput(r.workCenterId(), r.costCenterId(), r.basis(),
                r.normalCapacity(), r.budgetFixedOverhead(), r.fixedOverheadRate(), r.periodDays(), r.derivation(),
                r.rateSource(), r.effectiveFrom(), r.reviewDue()));
    }

    @PostMapping("/normal-capacities/{id}/approve")
    @RequirePermissions(Permissions.ACCOUNTING_POLICY__APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return settlements.approveCapacity(id);
    }

    @PostMapping("/absorption-settlements/preview")
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> preview(@Valid @RequestBody SettleRequest r) {
        return settlements.preview(input(r));
    }

    @PostMapping("/absorption-settlements")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.ADVANCED_COSTING__POST)
    public Map<String, Object> settle(@Valid @RequestBody SettleRequest r) {
        return settlements.settle(input(r));
    }

    @GetMapping("/absorption-settlements")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String workCenterId) {
        return settlements.list(workCenterId);
    }

    @GetMapping("/absorption-settlements/{id}")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return settlements.get(id);
    }

    @PostMapping("/absorption-settlements/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.ADVANCED_COSTING__POST)
    public Map<String, Object> reverse(@PathVariable String id) {
        return settlements.reverse(id);
    }

    @GetMapping("/cost-flow-bridge")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public Map<String, Object> bridge(@RequestParam String from, @RequestParam String to) {
        return settlements.bridge(from, to);
    }
}
