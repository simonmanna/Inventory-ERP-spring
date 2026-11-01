package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.AvcoLineageCalculator.Distribution;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.inventory.application.InventoryRevaluationService;
import com.inventoryerp.inventory.application.InventoryRevaluationService.LineInput;
import com.inventoryerp.inventory.application.InventoryRevaluationService.RevaluationInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
 * Inventory valuation integrity (Cost Management CM-1, ADR-S031; extension, no NestJS counterpart): cost lineage trace
 * and distribution preview, open adjunct balances and their release, inventory revaluation and NRV review documents.
 */
@RestController
@RequestMapping("/inventory")
public class CostLineageController {

    public record RevaluationLineRequest(
            @NotBlank(message = "productId should not be empty") String productId,
            String variantId,
            @NotBlank(message = "locationId should not be empty") String locationId,
            BigDecimal unitCostAfter,
            BigDecimal netRealisableValue) {}

    public record RevaluationRequest(
            String kind,
            String date,
            @Size(max = 500, message = "reason must be at most 500 characters") String reason,
            String accountId,
            @NotEmpty(message = "lines should not be empty") List<@Valid RevaluationLineRequest> lines) {}

    private final CostLineageService lineage;
    private final InventoryRevaluationService revaluations;

    public CostLineageController(CostLineageService lineage, InventoryRevaluationService revaluations) {
        this.lineage = lineage;
        this.revaluations = revaluations;
    }

    // ---- lineage ----------------------------------------------------------------------------------------------------

    @GetMapping("/cost-layers/{ledgerId}/trace")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public Map<String, Object> trace(@PathVariable String ledgerId) {
        return lineage.trace(ledgerId);
    }

    @GetMapping("/cost-layers/{ledgerId}/distribution")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public Map<String, Object> distribution(@PathVariable String ledgerId, @RequestParam String amount) {
        BigDecimal value;
        try {
            value = new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("amount must be a number");
        }
        Distribution d = lineage.distribute(ledgerId, value);
        return Obj.of("amount", value, "onHand", d.onHand(), "negativeStock", d.negativeStock(), "survival",
                d.survival(), "consumed", d.consumed().stream().map(s -> Obj.of("ledgerId", s.ledgerId(), "moveType",
                        s.moveType(), "referenceType", s.referenceType(), "referenceId", s.referenceId(), "target",
                        lineage.target(s), "amount", s.amount())).toList(), "byTarget", lineage.byTarget(d));
    }

    @GetMapping("/price-differences")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public List<Map<String, Object>> openAdjuncts() {
        return lineage.openAdjuncts();
    }

    @PostMapping("/price-differences/release")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_VALUATION__POST)
    public Map<String, Object> release() {
        return lineage.release();
    }

    // ---- revaluation / NRV ----------------------------------------------------------------------------------------

    @GetMapping("/revaluations")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String kind) {
        return revaluations.list(kind);
    }

    @GetMapping("/revaluations/{id}")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return revaluations.get(id);
    }

    @PostMapping("/revaluations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY_VALUATION__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody RevaluationRequest dto) {
        return revaluations.create(new RevaluationInput(dto.kind(), dto.date(), dto.reason(), dto.accountId(),
                dto.lines().stream().map(l -> new LineInput(l.productId(), l.variantId(), l.locationId(),
                        l.unitCostAfter(), l.netRealisableValue())).toList()));
    }

    @DeleteMapping("/revaluations/{id}")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__MANAGE)
    public Map<String, Object> delete(@PathVariable String id) {
        return revaluations.delete(id);
    }

    @PostMapping("/revaluations/{id}/post")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_VALUATION__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return revaluations.post(id);
    }

    @PostMapping("/revaluations/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_VALUATION__POST)
    public Map<String, Object> reverse(@PathVariable String id) {
        return revaluations.reverse(id);
    }
}
