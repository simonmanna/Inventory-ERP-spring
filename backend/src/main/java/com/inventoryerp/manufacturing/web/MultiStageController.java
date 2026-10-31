package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.MultiLevelProductionService;
import com.inventoryerp.manufacturing.application.StageCommands.AllocationRuleInput;
import com.inventoryerp.manufacturing.application.StageCommands.BomStageSetup;
import com.inventoryerp.manufacturing.application.StageCommands.ChildOrders;
import com.inventoryerp.manufacturing.application.StageCommands.CreateMultistageOrder;
import com.inventoryerp.manufacturing.application.StageCommands.InitializeStages;
import com.inventoryerp.manufacturing.application.StageCommands.RoutingStageSetup;
import com.inventoryerp.manufacturing.application.StageCommands.StageConversion;
import com.inventoryerp.manufacturing.application.StageCommands.StageIssue;
import com.inventoryerp.manufacturing.application.StageCommands.StageOutput;
import com.inventoryerp.manufacturing.application.StageCommands.StageQcDecision;
import com.inventoryerp.manufacturing.application.StageCommands.StageReversal;
import com.inventoryerp.manufacturing.application.StageExecutionService;
import com.inventoryerp.manufacturing.application.StageSetupService;
import com.inventoryerp.manufacturing.application.StageWipReportService;
import com.inventoryerp.manufacturing.spi.StageCheckpointResults.CheckpointDecision;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-stage production &amp; WIP between stages (ADR-S011, Phase 2.1): stage set-up on routings, co-products and
 * allocation rules on BOMs, staged order execution (issue / output / conversion / QC gate / reversal), multi-level
 * child orders with pegging, and the stage WIP report. Stock-moving routes require an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/manufacturing")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class MultiStageController {

    private final StageSetupService setup;
    private final StageExecutionService stages;
    private final MultiLevelProductionService multiLevel;
    private final StageWipReportService wip;

    public MultiStageController(StageSetupService setup, StageExecutionService stages,
            MultiLevelProductionService multiLevel, StageWipReportService wip) {
        this.setup = setup;
        this.stages = stages;
        this.multiLevel = multiLevel;
        this.wip = wip;
    }

    // ------------------------------------------------------------------ set-up

    @GetMapping("/routings/{id}/stage-setup")
    @RequirePermissions(Permissions.ROUTING__READ)
    public Map<String, Object> routingSetup(@PathVariable String id) {
        return setup.routingSetup(id);
    }

    @PutMapping("/routings/{id}/stage-setup")
    @RequirePermissions(Permissions.ROUTING__CREATE)
    public Map<String, Object> saveRoutingSetup(@PathVariable String id, @Valid @RequestBody RoutingStageSetup dto) {
        return setup.saveRoutingSetup(id, dto);
    }

    @GetMapping("/boms/{id}/stage-setup")
    @RequirePermissions(Permissions.BOM__READ)
    public Map<String, Object> bomSetup(@PathVariable String id) {
        return setup.bomSetup(id);
    }

    @PutMapping("/boms/{id}/stage-setup")
    @RequirePermissions(Permissions.BOM__UPDATE)
    public Map<String, Object> saveBomSetup(@PathVariable String id, @Valid @RequestBody BomStageSetup dto) {
        return setup.saveBomSetup(id, dto);
    }

    @GetMapping("/cost-allocation-rules")
    @RequirePermissions(Permissions.BOM__READ)
    public Map<String, Object> rules() {
        return setup.rules();
    }

    @PutMapping("/cost-allocation-rules/default")
    @RequirePermissions(Permissions.BOM__UPDATE)
    public Map<String, Object> saveDefaultRule(@Valid @RequestBody AllocationRuleInput dto) {
        return setup.saveDefaultRule(dto);
    }

    // ------------------------------------------------------------------ orders

    @PostMapping("/multistage-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CREATE)
    public Map<String, Object> createMultistage(@Valid @RequestBody CreateMultistageOrder dto) {
        return multiLevel.createMultistage(dto);
    }

    @PostMapping("/production-orders/{id}/stages/initialize")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CREATE)
    public Map<String, Object> initialize(@PathVariable String id,
            @Valid @RequestBody(required = false) InitializeStages dto) {
        return stages.initialize(id, dto);
    }

    @GetMapping("/production-orders/{id}/stages")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    public Map<String, Object> view(@PathVariable String id) {
        return stages.view(id);
    }

    @PostMapping("/production-orders/{id}/stages/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CANCEL)
    public Map<String, Object> reverseOrder(@PathVariable String id,
            @Valid @RequestBody(required = false) StageReversal dto) {
        return stages.reverseOrder(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/production-orders/{id}/child-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CREATE)
    public Map<String, Object> childOrders(@PathVariable String id,
            @Valid @RequestBody(required = false) ChildOrders dto) {
        return multiLevel.childOrders(id, dto);
    }

    @GetMapping("/production-orders/{id}/order-tree")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    public Map<String, Object> tree(@PathVariable String id) {
        return multiLevel.tree(id);
    }

    // ------------------------------------------------------------------ stage execution

    @PostMapping("/production-stages/{id}/issue")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__START)
    public Map<String, Object> issue(@PathVariable String id, @Valid @RequestBody StageIssue dto) {
        return stages.issue(id, dto);
    }

    @PostMapping("/production-stages/{id}/output")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    public Map<String, Object> output(@PathVariable String id, @Valid @RequestBody StageOutput dto) {
        return stages.output(id, dto);
    }

    @PostMapping("/production-stages/{id}/conversion-costs")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    public Map<String, Object> conversion(@PathVariable String id, @Valid @RequestBody StageConversion dto) {
        return stages.conversion(id, dto);
    }

    @PostMapping("/production-stages/{id}/qc-decision")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    public Map<String, Object> qcDecision(@PathVariable String id, @Valid @RequestBody StageQcDecision dto) {
        return stages.recordResult(id, new CheckpointDecision("passed".equals(dto.result()), dto.reference(),
                dto.notes()));
    }

    @PostMapping("/stage-postings/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CANCEL)
    public Map<String, Object> reversePosting(@PathVariable String id,
            @Valid @RequestBody(required = false) StageReversal dto) {
        return stages.reversePosting(id, dto == null ? null : dto.reason());
    }

    // ------------------------------------------------------------------ report

    @GetMapping("/reports/stage-wip")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    public Map<String, Object> stageWip() {
        return wip.report();
    }
}
