package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.PrecastCommands.CreateMixDesign;
import com.inventoryerp.manufacturing.application.PrecastCommands.CreatePrecastOrder;
import com.inventoryerp.manufacturing.application.PrecastCommands.CubeBatch;
import com.inventoryerp.manufacturing.application.PrecastCommands.CuringAreaInput;
import com.inventoryerp.manufacturing.application.PrecastCommands.CuringReading;
import com.inventoryerp.manufacturing.application.PrecastCommands.GateDecision;
import com.inventoryerp.manufacturing.application.PrecastCommands.MovePallets;
import com.inventoryerp.manufacturing.application.PrecastCommands.PressingRun;
import com.inventoryerp.manufacturing.application.PrecastCommands.ProductSpecInput;
import com.inventoryerp.manufacturing.application.PrecastCommands.ReviseMixDesign;
import com.inventoryerp.manufacturing.application.PrecastCommands.UpdateCuringArea;
import com.inventoryerp.manufacturing.application.PrecastCommands.UpdateMixDesign;
import com.inventoryerp.manufacturing.application.PrecastProductionService;
import com.inventoryerp.manufacturing.application.PrecastReportService;
import com.inventoryerp.manufacturing.application.PrecastSetupService;
import com.inventoryerp.shared.web.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Concrete block / precast manufacturing (ADR-S014, Phase 2.4): block specifications, curing areas, mix designs
 * (versioned BOMs with batch scaling), precast orders on the multi-stage engine, pressing runs, curing batches
 * (readings, pallet moves, cubing, strength gate, final inspection, pallet labels) and cost-per-block / breakage
 * reports. Stock-moving routes require an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/manufacturing/precast")
@RequiresModule("manufacturing")
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
@Tag(name = "Manufacturing · precast", description = "Concrete blocks / precast: mix designs, pressing, curing")
public class PrecastController {

    private final PrecastSetupService setup;
    private final PrecastProductionService production;
    private final PrecastReportService reports;

    public PrecastController(PrecastSetupService setup, PrecastProductionService production,
            PrecastReportService reports) {
        this.setup = setup;
        this.production = production;
        this.reports = reports;
    }

    // ------------------------------------------------------------------ set-up

    @GetMapping("/product-specs")
    @RequirePermissions(Permissions.BOM__READ)
    @Operation(summary = "Block / precast product specifications")
    public PageResult<Map<String, Object>> specs(@RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return setup.specs(search, page, pageSize);
    }

    @PutMapping("/product-specs/{productId}")
    @RequirePermissions(Permissions.BOM__UPDATE)
    @Operation(summary = "Create or update the block specification of a product")
    public Map<String, Object> saveSpec(@PathVariable String productId, @Valid @RequestBody ProductSpecInput dto) {
        return setup.saveSpec(productId, dto);
    }

    @GetMapping("/curing-areas")
    @RequirePermissions(Permissions.BOM__READ)
    @Operation(summary = "Curing chambers / yards with occupancy")
    public List<Map<String, Object>> areas(@RequestParam(required = false) Boolean active) {
        return setup.areas(active);
    }

    @PostMapping("/curing-areas")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__UPDATE)
    @Operation(summary = "Create a curing area")
    public Map<String, Object> createArea(@Valid @RequestBody CuringAreaInput dto) {
        return setup.createArea(dto);
    }

    @PatchMapping("/curing-areas/{id}")
    @RequirePermissions(Permissions.BOM__UPDATE)
    @Operation(summary = "Update a curing area")
    public Map<String, Object> updateArea(@PathVariable String id, @Valid @RequestBody UpdateCuringArea dto) {
        return setup.updateArea(id, dto);
    }

    // ------------------------------------------------------------------ mix designs

    @GetMapping("/mix-designs")
    @RequirePermissions(Permissions.BOM__READ)
    @Operation(summary = "Mix designs (recipes), one row per BOM version")
    public PageResult<Map<String, Object>> mixDesigns(@RequestParam(required = false) String search,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return setup.mixDesigns(search, productId, status, page, pageSize);
    }

    @GetMapping("/mix-designs/{id}")
    @RequirePermissions(Permissions.BOM__READ)
    @Operation(summary = "Mix design with materials per batch, versions and per-batch scaling")
    public Map<String, Object> mixDesign(@PathVariable String id) {
        return setup.mixDesign(id);
    }

    @PostMapping("/mix-designs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__CREATE)
    @Operation(summary = "Create a draft mix design (BOM version + routing + batch parameters)")
    public Map<String, Object> createMixDesign(@Valid @RequestBody CreateMixDesign dto) {
        return setup.createMixDesign(dto);
    }

    @PatchMapping("/mix-designs/{id}")
    @RequirePermissions(Permissions.BOM__UPDATE)
    @Operation(summary = "Edit a draft mix design")
    public Map<String, Object> updateMixDesign(@PathVariable String id, @Valid @RequestBody UpdateMixDesign dto) {
        return setup.updateMixDesign(id, dto);
    }

    @PostMapping("/mix-designs/{id}/activate")
    @RequirePermissions(Permissions.BOM__ACTIVATE)
    @Operation(summary = "Activate a mix design version (BOM approval rules apply)")
    public Map<String, Object> activate(@PathVariable String id) {
        return setup.activateMixDesign(id);
    }

    @PostMapping("/mix-designs/{id}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BOM__CREATE)
    @Operation(summary = "New draft version of a mix design (ECO)")
    public Map<String, Object> revise(@PathVariable String id,
            @Valid @RequestBody(required = false) ReviseMixDesign dto) {
        return setup.reviseMixDesign(id, dto);
    }

    @GetMapping("/mix-designs/{id}/scale")
    @RequirePermissions(Permissions.BOM__READ)
    @Operation(summary = "Batch scaling by units, batches, cement bags, m³, cycles or pallets")
    public Map<String, Object> scale(@PathVariable String id, @RequestParam(required = false) String basis,
            @RequestParam BigDecimal value, @RequestParam(required = false) Boolean wholeBatches) {
        return setup.scale(id, basis, value, wholeBatches);
    }

    // ------------------------------------------------------------------ orders

    @GetMapping("/orders")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    @Operation(summary = "Precast production orders")
    public PageResult<Map<String, Object>> orders(@RequestParam(required = false) String status,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return production.orders(status, productId, search, page, pageSize);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__CREATE)
    @Operation(summary = "Create a precast order from a mix design and a scaling basis")
    public Map<String, Object> createOrder(@Valid @RequestBody CreatePrecastOrder dto) {
        return production.createOrder(dto);
    }

    @GetMapping("/orders/{id}")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    @Operation(summary = "Precast order with stages, curing batches, cost per acceptable block and variance")
    public Map<String, Object> order(@PathVariable String id) {
        return production.order(id);
    }

    @PostMapping("/orders/{id}/pressing-runs")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    @Operation(summary = "Record a pressing run (backflush, breakage) and start a curing batch")
    public Map<String, Object> press(@PathVariable String id, @Valid @RequestBody PressingRun dto) {
        return production.press(id, dto);
    }

    // ------------------------------------------------------------------ curing batches

    @GetMapping("/curing-batches")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    @Operation(summary = "Curing board: batches by status / area, days cured, ready flag, maturity")
    public PageResult<Map<String, Object>> batches(@RequestParam(required = false) String status,
            @RequestParam(required = false) String curingAreaId, @RequestParam(required = false) String orderId,
            @RequestParam(required = false) Boolean ready, @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return production.batches(status, curingAreaId, orderId, ready, search, page, pageSize);
    }

    @GetMapping("/curing-batches/{id}")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    @Operation(summary = "Curing batch with readings, moves and quality lots")
    public Map<String, Object> batch(@PathVariable String id) {
        return production.batch(id);
    }

    @PostMapping("/curing-batches/{id}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    @Operation(summary = "Record a curing temperature reading (maturity)")
    public Map<String, Object> reading(@PathVariable String id, @Valid @RequestBody CuringReading dto) {
        return production.reading(id, dto);
    }

    @PostMapping("/curing-batches/{id}/move")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    @Operation(summary = "Move a batch's pallets to another curing area / bay")
    public Map<String, Object> move(@PathVariable String id, @Valid @RequestBody MovePallets dto) {
        return production.move(id, dto);
    }

    @PostMapping("/curing-batches/{id}/cube")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__COMPLETE)
    @Operation(summary = "Cube a cured batch: good / second grade / broken")
    public Map<String, Object> cube(@PathVariable String id, @Valid @RequestBody CubeBatch dto) {
        return production.cube(id, dto);
    }

    @PostMapping("/curing-batches/{id}/gate-decision")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    @Operation(summary = "Manual strength gate decision (release to finished goods or fail)")
    public Map<String, Object> gate(@PathVariable String id, @Valid @RequestBody GateDecision dto) {
        return production.gateDecision(id, dto);
    }

    @PostMapping("/curing-batches/{id}/final-inspection")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCTION_ORDER__QC)
    @Operation(summary = "Open the final (e.g. 28-day) inspection lot of a released batch")
    public Map<String, Object> finalInspection(@PathVariable String id) {
        return production.finalInspection(id);
    }

    @GetMapping("/curing-batches/{id}/labels")
    @RequirePermissions(Permissions.PRODUCTION_ORDER__READ)
    @Operation(summary = "Pallet labels with QR codes")
    public Map<String, Object> labels(@PathVariable String id) {
        return production.labels(id);
    }

    // ------------------------------------------------------------------ reports

    @GetMapping("/reports/cost-per-block")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    @Operation(summary = "Cost per acceptable block by order and product")
    public Map<String, Object> costPerBlock(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String productId) {
        return reports.costPerBlock(from, to, productId);
    }

    @GetMapping("/reports/breakage")
    @RequirePermissions(Permissions.PRODUCTION__REPORT)
    @Operation(summary = "Broken blocks by reason and stage, second grade")
    public Map<String, Object> breakage(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String productId) {
        return reports.breakage(from, to, productId);
    }
}
