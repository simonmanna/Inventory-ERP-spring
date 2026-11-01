package com.inventoryerp.costing.web;

import com.inventoryerp.costing.application.DistributionCostingService;
import com.inventoryerp.costing.application.DistributionCostingService.TransferInput;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /costing/delivery-cost-*} and {@code /costing/branch-transfers} (Cost Management CM-7, ADR-S037;
 * extension): owned-fleet delivery cost into distribution with empty legs, and same-entity branch transfers.
 */
@RestController
@RequestMapping("/costing")
public class DistributionCostingController {

    public record RunRequest(
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to,
            String branchId,
            Map<String, BigDecimal> ratePerKm) {}

    public record TransferRequest(
            @NotBlank(message = "productId should not be empty") String productId,
            BigDecimal quantity,
            @NotBlank(message = "fromLocationId should not be empty") String fromLocationId,
            @NotBlank(message = "toLocationId should not be empty") String toLocationId,
            BigDecimal freight,
            String freightAccountId,
            Boolean useClearing,
            @Size(max = 500, message = "notes must be at most 500 characters") String notes) {}

    private final DistributionCostingService distribution;

    public DistributionCostingController(DistributionCostingService distribution) {
        this.distribution = distribution;
    }

    @PostMapping("/delivery-cost-runs/preview")
    @RequirePermissions(Permissions.ADVANCED_COSTING__MANAGE)
    public Map<String, Object> preview(@Valid @RequestBody RunRequest r) {
        return distribution.preview(r.from(), r.to(), r.branchId(), r.ratePerKm());
    }

    @PostMapping("/delivery-cost-runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.ADVANCED_COSTING__POST)
    public Map<String, Object> run(@Valid @RequestBody RunRequest r) {
        return distribution.run(r.from(), r.to(), r.branchId(), r.ratePerKm());
    }

    @GetMapping("/delivery-cost-runs")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public List<Map<String, Object>> runs() {
        return distribution.runs();
    }

    @GetMapping("/delivery-cost-runs/{id}")
    @RequirePermissions(Permissions.ADVANCED_COSTING__READ)
    public Map<String, Object> getRun(@PathVariable String id) {
        return distribution.getRun(id);
    }

    @PostMapping("/delivery-cost-runs/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.ADVANCED_COSTING__POST)
    public Map<String, Object> reverse(@PathVariable String id) {
        return distribution.reverseRun(id);
    }

    @GetMapping("/branch-transfers")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public List<Map<String, Object>> transfers() {
        return distribution.transfers();
    }

    @GetMapping("/branch-transfers/clearing")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public Map<String, Object> clearing() {
        return distribution.clearingBalances();
    }

    @GetMapping("/branch-transfers/{id}")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public Map<String, Object> transfer(@PathVariable String id) {
        return distribution.transferRow(id);
    }

    @PostMapping("/branch-transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_VALUATION__POST)
    public Map<String, Object> createTransfer(@Valid @RequestBody TransferRequest r) {
        return distribution.transfer(new TransferInput(r.productId(), r.quantity(), r.fromLocationId(),
                r.toLocationId(), r.freight(), r.freightAccountId(), r.useClearing(), r.notes()));
    }
}
