package com.inventoryerp.projects.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.application.ContractService;
import com.inventoryerp.projects.application.ContractSovService;
import com.inventoryerp.projects.application.ContractSovService.SovLineInput;
import com.inventoryerp.projects.application.ProjectCostService;
import com.inventoryerp.projects.application.ProjectFleetChargeService;
import com.inventoryerp.projects.application.ProjectFleetChargeService.FleetChargeInput;
import com.inventoryerp.projects.application.ProjectOverheadService;
import com.inventoryerp.projects.application.ProjectOverheadService.RuleInput;
import com.inventoryerp.projects.application.ProjectPlantRateService;
import com.inventoryerp.projects.application.ProjectPlantRateService.RateInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * Contractor billing, commitments, plant and overhead under {@code /projects} (ADR-S075; extension): schedule of
 * values and the G702 / G703 payment application, subcontract variations, committed cost by WBS, the plant rate
 * table, fleet trips / work orders charged to a project, overhead absorption rules and period runs.
 */
@RestController
@RequestMapping("/projects")
public class ContractorController {

    public record SovLineRequest(String code, String description, String wbsId, BigDecimal scheduledValue,
            BigDecimal retentionPct, Integer sortOrder) {}

    public record SubcontractVariationRequest(String description, BigDecimal valueDelta) {}

    public record PlantRateRequest(String equipmentType, String equipmentId, String name, String unit,
            BigDecimal rate, String validFrom, String validTo, String costCenterId, Boolean isActive, String notes) {}

    public record FleetChargeRequest(String sourceType, String sourceId, String wbsId, String siteId, String date,
            String notes) {}

    public record OverheadRuleRequest(String projectId, String name, String basis, String costElement,
            BigDecimal rate, String validFrom, String validTo, Boolean isActive) {}

    public record OverheadRunRequest(String period) {}

    private final ContractSovService sov;
    private final ContractService contracts;
    private final ProjectCostService costs;
    private final ProjectPlantRateService plantRates;
    private final ProjectFleetChargeService fleet;
    private final ProjectOverheadService overhead;

    public ContractorController(ContractSovService sov, ContractService contracts, ProjectCostService costs,
            ProjectPlantRateService plantRates, ProjectFleetChargeService fleet, ProjectOverheadService overhead) {
        this.sov = sov;
        this.contracts = contracts;
        this.costs = costs;
        this.plantRates = plantRates;
        this.fleet = fleet;
        this.overhead = overhead;
    }

    // ---- schedule of values and payment application ---------------------------------------------------------------

    @GetMapping("/{id}/sov")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public Map<String, Object> schedule(@PathVariable String id) {
        return sov.schedule(id);
    }

    @PostMapping("/{id}/sov")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> addSovLine(@PathVariable String id, @RequestBody SovLineRequest r) {
        return sov.create(id, input(r));
    }

    @PatchMapping("/sov/{lineId}")
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> updateSovLine(@PathVariable String lineId, @RequestBody SovLineRequest r) {
        return sov.update(lineId, input(r));
    }

    @DeleteMapping("/sov/{lineId}")
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> deleteSovLine(@PathVariable String lineId) {
        return sov.delete(lineId);
    }

    @GetMapping("/claims/{claimId}/payment-application")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public Map<String, Object> paymentApplication(@PathVariable String claimId) {
        return sov.paymentApplication(claimId);
    }

    @GetMapping("/claims/{claimId}/payment-application/pdf")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public ResponseEntity<byte[]> paymentApplicationPdf(@PathVariable String claimId) {
        byte[] pdf = sov.paymentApplicationPdf(claimId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payment-application-" + claimId
                        + ".pdf\"").body(pdf);
    }

    // ---- subcontract variations ------------------------------------------------------------------------------------

    @PostMapping("/subcontracts/{subcontractId}/variations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> subcontractVariation(@PathVariable String subcontractId,
            @RequestBody SubcontractVariationRequest r) {
        return contracts.createSubcontractVariation(subcontractId, r.description(), r.valueDelta());
    }

    @PostMapping("/subcontract-variations/{variationId}/approve")
    @RequirePermissions(Permissions.CONTRACT__APPROVE)
    public Map<String, Object> approveSubcontractVariation(@PathVariable String variationId) {
        return contracts.decideSubcontractVariation(variationId, true);
    }

    @PostMapping("/subcontract-variations/{variationId}/reject")
    @RequirePermissions(Permissions.CONTRACT__APPROVE)
    public Map<String, Object> rejectSubcontractVariation(@PathVariable String variationId) {
        return contracts.decideSubcontractVariation(variationId, false);
    }

    // ---- committed cost --------------------------------------------------------------------------------------------

    @GetMapping("/{id}/commitments")
    @RequirePermissions(Permissions.PROJECT__READ)
    public Map<String, Object> commitments(@PathVariable String id) {
        return costs.commitments(id);
    }

    // ---- plant rates and fleet charges -----------------------------------------------------------------------------

    @GetMapping("/plant-rates")
    @RequirePermissions(Permissions.PROJECT__READ)
    public List<Map<String, Object>> plantRates(@RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) Boolean activeOnly) {
        return plantRates.list(equipmentId, activeOnly);
    }

    @PostMapping("/plant-rates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> createPlantRate(@RequestBody PlantRateRequest r) {
        return plantRates.create(input(r));
    }

    @PatchMapping("/plant-rates/{rateId}")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> updatePlantRate(@PathVariable String rateId, @RequestBody PlantRateRequest r) {
        return plantRates.update(rateId, input(r));
    }

    @DeleteMapping("/plant-rates/{rateId}")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> deactivatePlantRate(@PathVariable String rateId) {
        return plantRates.deactivate(rateId);
    }

    @GetMapping("/{id}/costs/fleet")
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> fleetCandidates(@PathVariable String id) {
        return fleet.candidates(id);
    }

    @PostMapping("/{id}/costs/fleet")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> chargeFleet(@PathVariable String id, @RequestBody FleetChargeRequest r) {
        return fleet.charge(id, new FleetChargeInput(r.sourceType(), r.sourceId(), r.wbsId(), r.siteId(), r.date(),
                r.notes()));
    }

    // ---- overhead absorption ---------------------------------------------------------------------------------------

    @GetMapping("/overhead-rules")
    @RequirePermissions(Permissions.PROJECT__READ)
    public List<Map<String, Object>> overheadRules(@RequestParam(required = false) String projectId) {
        return overhead.rules(projectId);
    }

    @PostMapping("/overhead-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> createOverheadRule(@RequestBody OverheadRuleRequest r) {
        return overhead.createRule(input(r));
    }

    @PatchMapping("/overhead-rules/{ruleId}")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> updateOverheadRule(@PathVariable String ruleId, @RequestBody OverheadRuleRequest r) {
        return overhead.updateRule(ruleId, input(r));
    }

    @DeleteMapping("/overhead-rules/{ruleId}")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> deleteOverheadRule(@PathVariable String ruleId) {
        return overhead.deleteRule(ruleId);
    }

    @GetMapping("/{id}/overhead-runs")
    @RequirePermissions(Permissions.PROJECT__READ)
    public List<Map<String, Object>> overheadRuns(@PathVariable String id) {
        return overhead.runs(id);
    }

    @GetMapping("/{id}/overhead-runs/preview")
    @RequirePermissions(Permissions.PROJECT__READ)
    public Map<String, Object> previewOverhead(@PathVariable String id, @RequestParam(required = false) String period) {
        return overhead.preview(id, period);
    }

    @PostMapping("/{id}/overhead-runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> runOverhead(@PathVariable String id, @RequestBody OverheadRunRequest r) {
        return overhead.run(id, r.period());
    }

    @PostMapping("/overhead-runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> runOverheadAll(@RequestBody OverheadRunRequest r) {
        return overhead.runAll(r.period());
    }

    @PostMapping("/overhead-runs/{runId}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> reverseOverhead(@PathVariable String runId) {
        return overhead.reverse(runId);
    }

    private static SovLineInput input(SovLineRequest r) {
        return new SovLineInput(r.code(), r.description(), r.wbsId(), r.scheduledValue(), r.retentionPct(),
                r.sortOrder());
    }

    private static RateInput input(PlantRateRequest r) {
        return new RateInput(r.equipmentType(), r.equipmentId(), r.name(), r.unit(), r.rate(), r.validFrom(),
                r.validTo(), r.costCenterId(), r.isActive(), r.notes());
    }

    private static RuleInput input(OverheadRuleRequest r) {
        return new RuleInput(r.projectId(), r.name(), r.basis(), r.costElement(), r.rate(), r.validFrom(), r.validTo(),
                r.isActive());
    }
}
