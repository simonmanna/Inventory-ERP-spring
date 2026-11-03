package com.inventoryerp.costing.web;

import com.inventoryerp.costing.application.LandedCostAccrualService;
import com.inventoryerp.costing.application.LandedCostAccrualService.RuleInput;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /procurement/landed-cost-accrual-rules} and {@code /procurement/landed-cost-accruals} (Cost Management CM-6,
 * ADR-S036; extension): estimated landed cost accrued at receipt and cleared by the actual voucher.
 */
@RestController
@RequestMapping("/procurement")
public class LandedCostAccrualController {

    public record RuleRequest(
            String scopeType,
            String scopeId,
            BigDecimal ratePct,
            @Size(max = 500, message = "notes must be at most 500 characters") String notes,
            @Size(max = 200, message = "name must be at most 200 characters") String name,
            String effectiveFrom,
            String effectiveTo,
            String rateType,
            BigDecimal rate,
            String currencyCode,
            String uomId,
            String weightBasis,
            Integer expectedSettlementDays) {}

    public record AccrueRequest(@NotBlank(message = "goodsReceiptId should not be empty") String goodsReceiptId) {}

    public record ClearRequest(@NotBlank(message = "voucherId should not be empty") String voucherId,
            BigDecimal amount) {}

    private final LandedCostAccrualService accruals;

    public LandedCostAccrualController(LandedCostAccrualService accruals) {
        this.accruals = accruals;
    }

    @GetMapping("/landed-cost-accrual-rules")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> rules() {
        return accruals.rules();
    }

    @PostMapping("/landed-cost-accrual-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LANDED_COST__CONFIGURE)
    public Map<String, Object> saveRule(@Valid @RequestBody RuleRequest r) {
        return accruals.saveRule(new RuleInput(r.scopeType(), r.scopeId(), r.ratePct(), r.notes(), r.name(),
                r.effectiveFrom(), r.effectiveTo(), r.rateType(), r.rate(), r.currencyCode(), r.uomId(),
                r.weightBasis(), r.expectedSettlementDays()));
    }

    @DeleteMapping("/landed-cost-accrual-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.LANDED_COST__CONFIGURE)
    public void deleteRule(@PathVariable String id) {
        accruals.deleteRule(id);
    }

    @GetMapping("/landed-cost-accruals")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return accruals.list(status);
    }

    @GetMapping("/landed-cost-accruals/reconciliation")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public Map<String, Object> reconciliation() {
        return accruals.reconciliation();
    }

    @GetMapping("/landed-cost-accruals/monitor")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public Map<String, Object> monitor() {
        return accruals.monitor();
    }

    @GetMapping("/landed-cost-accruals/{id}")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return accruals.get(id);
    }

    @PostMapping("/landed-cost-accruals")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.LANDED_COST__POST)
    public Map<String, Object> accrue(@Valid @RequestBody AccrueRequest r) {
        return accruals.accrue(r.goodsReceiptId());
    }

    @PostMapping("/landed-cost-accruals/{id}/clear")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.LANDED_COST__POST)
    public Map<String, Object> clear(@PathVariable String id, @Valid @RequestBody ClearRequest r) {
        return accruals.clear(id, r.voucherId(), r.amount());
    }

    @PostMapping("/landed-cost-accruals/{id}/close")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.LANDED_COST__POST)
    public Map<String, Object> close(@PathVariable String id) {
        return accruals.close(id);
    }

    @PostMapping("/landed-cost-accruals/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.LANDED_COST__REVERSE)
    public Map<String, Object> reverse(@PathVariable String id) {
        return accruals.reverse(id);
    }
}
