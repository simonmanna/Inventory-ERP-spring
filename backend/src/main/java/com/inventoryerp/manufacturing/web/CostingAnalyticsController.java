package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.CostAbsorptionService;
import com.inventoryerp.manufacturing.application.CostingCommands.RateInput;
import com.inventoryerp.manufacturing.application.ManufacturingCostingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manufacturing costing &amp; analytics (Track C 2.8, ADR-S018): absorption rates per work centre, actual cost of an
 * order, overhead absorption through the posting engine (requires an {@code Idempotency-Key}), variances, cost by shift,
 * plant, product or month and gross margin.
 */
@RestController
@RequestMapping("/manufacturing/costing")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class CostingAnalyticsController {

    private final CostAbsorptionService absorption;
    private final ManufacturingCostingService costing;

    public CostingAnalyticsController(CostAbsorptionService absorption, ManufacturingCostingService costing) {
        this.absorption = absorption;
        this.costing = costing;
    }

    @GetMapping("/rates")
    @RequirePermissions(Permissions.WORK_CENTER__READ)
    public List<Map<String, Object>> rates() {
        return absorption.rates();
    }

    @PutMapping("/rates/{workCenterId}")
    @RequirePermissions(Permissions.STANDARD_COST__MANAGE)
    public Map<String, Object> saveRate(@PathVariable String workCenterId, @Valid @RequestBody RateInput dto) {
        return absorption.saveRate(workCenterId, dto);
    }

    @DeleteMapping("/rates/{workCenterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.STANDARD_COST__MANAGE)
    public void deleteRate(@PathVariable String workCenterId) {
        absorption.deleteRate(workCenterId);
    }

    @GetMapping("/orders/{id}")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    public Map<String, Object> order(@PathVariable String id) {
        return costing.orderCost(id);
    }

    @PostMapping("/orders/{id}/absorb")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    public Map<String, Object> absorb(@PathVariable String id) {
        return absorption.absorb(id);
    }

    @GetMapping("/orders/{id}/variances")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> orderVariances(@PathVariable String id) {
        return costing.orderVariances(id);
    }

    @GetMapping("/variances")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> variances(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String productId) {
        return costing.variances(from, to, productId);
    }

    @GetMapping("/reports/summary")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> summary(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String groupBy) {
        return costing.summary(from, to, groupBy);
    }

    @GetMapping("/reports/margin")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> margin(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String groupBy) {
        return costing.margin(from, to, groupBy);
    }
}
