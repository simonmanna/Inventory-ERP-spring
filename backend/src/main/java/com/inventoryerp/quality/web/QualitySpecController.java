package com.inventoryerp.quality.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quality.application.QualityCommands.PlanInput;
import com.inventoryerp.quality.application.QualityCommands.PlanPatch;
import com.inventoryerp.quality.application.QualityCommands.PlanQuery;
import com.inventoryerp.quality.application.QualityCommands.SpecInput;
import com.inventoryerp.quality.application.QualityCommands.SpecPatch;
import com.inventoryerp.quality.application.QualityCommands.SpecQuery;
import com.inventoryerp.quality.application.QualityPlanService;
import com.inventoryerp.quality.application.QualitySpecService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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

/** Specifications (versioned) and inspection plans (ADR-S013). Core-available: permissions only, no module flag. */
@RestController
@RequestMapping("/quality")
@Validated
public class QualitySpecController {

    private final QualitySpecService specs;
    private final QualityPlanService plans;

    public QualitySpecController(QualitySpecService specs, QualityPlanService plans) {
        this.specs = specs;
        this.plans = plans;
    }

    @GetMapping("/specifications")
    @RequirePermissions(Permissions.QUALITY__READ)
    public PageResult<Map<String, Object>> listSpecs(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"draft", "active", "superseded", "archived"}) String status,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return specs.list(new SpecQuery(search, status, productId, page, pageSize));
    }

    @GetMapping("/specifications/{id}")
    @RequirePermissions(Permissions.QUALITY__READ)
    public Map<String, Object> getSpec(@PathVariable String id) {
        return specs.get(id);
    }

    @PostMapping("/specifications")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY__CONFIGURE)
    public Map<String, Object> createSpec(@Valid @RequestBody SpecInput dto) {
        return specs.create(dto);
    }

    @PatchMapping("/specifications/{id}")
    @RequirePermissions(Permissions.QUALITY__CONFIGURE)
    public Map<String, Object> updateSpec(@PathVariable String id, @Valid @RequestBody SpecPatch dto) {
        return specs.update(id, dto);
    }

    @PostMapping("/specifications/{id}/activate")
    @RequirePermissions(Permissions.QUALITY__CONFIGURE)
    public Map<String, Object> activateSpec(@PathVariable String id) {
        return specs.activate(id);
    }

    @PostMapping("/specifications/{id}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY__CONFIGURE)
    public Map<String, Object> reviseSpec(@PathVariable String id) {
        return specs.revise(id);
    }

    @PostMapping("/specifications/{id}/archive")
    @RequirePermissions(Permissions.QUALITY__CONFIGURE)
    public Map<String, Object> archiveSpec(@PathVariable String id) {
        return specs.archive(id);
    }

    @GetMapping("/inspection-plans")
    @RequirePermissions(Permissions.QUALITY__READ)
    public PageResult<Map<String, Object>> listPlans(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"incoming", "in_process", "final"}) String stage,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return plans.list(new PlanQuery(search, stage, productId, active, page, pageSize));
    }

    @GetMapping("/inspection-plans/{id}")
    @RequirePermissions(Permissions.QUALITY__READ)
    public Map<String, Object> getPlan(@PathVariable String id) {
        return plans.get(id);
    }

    @PostMapping("/inspection-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY__CONFIGURE)
    public Map<String, Object> createPlan(@Valid @RequestBody PlanInput dto) {
        return plans.create(dto);
    }

    @PatchMapping("/inspection-plans/{id}")
    @RequirePermissions(Permissions.QUALITY__CONFIGURE)
    public Map<String, Object> updatePlan(@PathVariable String id, @Valid @RequestBody PlanPatch dto) {
        return plans.update(id, dto);
    }

    @DeleteMapping("/inspection-plans/{id}")
    @RequirePermissions(Permissions.QUALITY__CONFIGURE)
    public Map<String, Object> deletePlan(@PathVariable String id) {
        plans.remove(id);
        return Map.of("id", id, "deleted", true);
    }
}
