package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.CostingService;
import com.inventoryerp.manufacturing.application.EngineeringService;
import com.inventoryerp.manufacturing.application.MfgCommands.ReviseBom;
import com.inventoryerp.manufacturing.application.MfgCommands.SubstituteList;
import com.inventoryerp.manufacturing.application.PlmChangeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Costing & engineering (ADR-S006 package D): roll-up, standards, variances, where-used, substitutes, ECOs. */
@RestController
@RequestMapping("/manufacturing")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class CostingController {

    private final CostingService costing;
    private final EngineeringService engineering;
    private final PlmChangeService changes;

    public CostingController(CostingService costing, EngineeringService engineering, PlmChangeService changes) {
        this.costing = costing;
        this.engineering = engineering;
        this.changes = changes;
    }

    @GetMapping("/boms/{id}/cost")
    @RequirePermissions(Permissions.BOM__READ)
    public Map<String, Object> cost(@PathVariable String id) {
        return costing.rollup(id);
    }

    @PostMapping("/boms/{id}/standard-cost")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__UPDATE)
    public Map<String, Object> freeze(@PathVariable String id) {
        return costing.freezeStandard(id);
    }

    /** ADR-S008: roll-up → draft product standard (body: {@code effectiveFrom}, {@code convertMethod}). */
    @PostMapping("/boms/{id}/product-standard")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.STANDARD_COST__MANAGE)
    public Map<String, Object> proposeProductStandard(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        Object from = dto == null ? null : dto.get("effectiveFrom");
        Object convert = dto == null ? null : dto.get("convertMethod");
        return costing.proposeProductStandard(id, from == null ? null : from.toString(), Boolean.TRUE.equals(convert));
    }

    @GetMapping("/boms/{id}/standard-costs")
    @RequirePermissions(Permissions.BOM__READ)
    public List<Map<String, Object>> standards(@PathVariable String id) {
        return costing.standards(id);
    }

    @GetMapping("/boms/{id}/substitutes")
    @RequirePermissions(Permissions.BOM__READ)
    public List<Map<String, Object>> substitutes(@PathVariable String id) {
        return costing.substitutes(id);
    }

    @PutMapping("/boms/{id}/lines/{lineId}/substitutes")
    @RequirePermissions(Permissions.BOM__UPDATE)
    public List<Map<String, Object>> saveSubstitutes(@PathVariable String id, @PathVariable String lineId,
            @Valid @RequestBody SubstituteList dto) {
        return costing.saveSubstitutes(id, lineId, dto.substitutes());
    }

    @PostMapping("/boms/{id}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__CREATE)
    public Map<String, Object> revise(@PathVariable String id, @Valid @RequestBody(required = false) ReviseBom dto) {
        return engineering.revise(id, dto);
    }

    @GetMapping("/boms/{id}/compare/{otherId}")
    @RequirePermissions(Permissions.BOM__READ)
    public Map<String, Object> compare(@PathVariable String id, @PathVariable String otherId) {
        return engineering.compare(id, otherId);
    }

    @GetMapping("/engineering-changes")
    @RequirePermissions(Permissions.BOM__READ)
    public List<Map<String, Object>> changes(@RequestParam(required = false) String status) {
        return engineering.list(status);
    }

    @GetMapping("/engineering-changes/{id}")
    @RequirePermissions(Permissions.BOM__READ)
    public Map<String, Object> change(@PathVariable String id) {
        return engineering.get(id);
    }

    @PostMapping("/engineering-changes/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__ACTIVATE)
    public Map<String, Object> approve(@PathVariable String id) {
        return changes.approveDirect(id);
    }

    @PostMapping("/engineering-changes/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__ACTIVATE)
    public Map<String, Object> reject(@PathVariable String id) {
        return changes.cancel(id, null);
    }

    @PostMapping("/engineering-changes/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__ACTIVATE)
    public Map<String, Object> apply(@PathVariable String id) {
        return changes.apply(id, true);
    }

    @GetMapping("/reports/variances")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> variances(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String orderId) {
        return costing.variances(from, to, orderId);
    }

    @GetMapping("/reports/where-used/{productId}")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public List<Map<String, Object>> whereUsed(@PathVariable String productId) {
        return costing.whereUsed(productId);
    }

    @GetMapping("/production-orders/{id}/availability")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    public List<Map<String, Object>> availability(@PathVariable String id) {
        return costing.availability(id);
    }
}
