package com.inventoryerp.projects.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.application.ContractService;
import com.inventoryerp.projects.application.ContractService.ClaimInput;
import com.inventoryerp.projects.application.ContractService.OnerousAssessmentInput;
import com.inventoryerp.projects.application.ContractService.SubcontractInput;
import com.inventoryerp.projects.application.ContractService.VariationInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Contract commercials under {@code /projects} (Cost Management CM-8b, ADR-S039; extension): IFRS 15.35 criteria,
 * variations, advances, progress claims, retentions, subcontracts, revenue recognition, onerous contracts and the
 * contract position.
 */
@RestController
@RequestMapping("/projects")
public class ContractController {

    public record CriteriaRequest(Boolean criterionA, Boolean criterionB, Boolean criterionC,
            @NotBlank(message = "evidence should not be empty") String evidence) {}

    public record VariationRequest(
            @NotBlank(message = "description should not be empty") String description,
            BigDecimal priceDelta,
            BigDecimal costDelta,
            String wbsId,
            String costElement,
            String modification) {}

    public record AdvanceRequest(BigDecimal amount, BigDecimal recoveryPct, String date) {}

    public record ClaimRequest(String claimDate, BigDecimal cumulativeCertified, BigDecimal retentionPct,
            String retentionCondition, String retentionReleaseDate, String taxId) {}

    public record SubcontractRequest(
            @NotBlank(message = "supplierId should not be empty") String supplierId,
            @NotBlank(message = "wbsId should not be empty") String wbsId,
            String scope,
            BigDecimal value,
            BigDecimal retentionPct,
            String retentionCondition) {}

    public record ValuationRequest(BigDecimal cumulativeValue, String date) {}

    public record BackChargeRequest(BigDecimal amount, String reason) {}

    public record ReleaseRequest(String evidence) {}

    public record RevenueRequest(@NotBlank(message = "periodEnd should not be empty") String periodEnd) {}

    public record OnerousRequest(String asOf, List<String> indicators, BigDecimal probableClaims,
            BigDecimal costOfExiting, String exitEvidence, BigDecimal impairmentRecognised,
            String impairmentReference) {}

    private final ContractService contracts;

    public ContractController(ContractService contracts) {
        this.contracts = contracts;
    }

    // ---- criteria and variations --------------------------------------------------------------------------------------

    @PostMapping("/{id}/revenue-criteria")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> criteria(@PathVariable String id, @Valid @RequestBody CriteriaRequest r) {
        return contracts.assessCriteria(id, r.criterionA(), r.criterionB(), r.criterionC(), r.evidence());
    }

    @GetMapping("/{id}/revenue-criteria")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public List<Map<String, Object>> criteriaAssessments(@PathVariable String id) {
        return contracts.criteriaAssessments(id);
    }

    @PostMapping("/revenue-criteria/{assessmentId}/approve")
    @RequirePermissions(Permissions.CONTRACT__APPROVE)
    public Map<String, Object> approveCriteria(@PathVariable String assessmentId) {
        return contracts.approveCriteria(assessmentId);
    }

    @GetMapping("/{id}/variations")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public List<Map<String, Object>> variations(@PathVariable String id) {
        return contracts.variations(id);
    }

    @PostMapping("/{id}/variations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> variation(@PathVariable String id, @Valid @RequestBody VariationRequest r) {
        return contracts.createVariation(id, new VariationInput(r.description(), r.priceDelta(), r.costDelta(),
                r.wbsId(), r.costElement(), r.modification()));
    }

    @PostMapping("/variations/{variationId}/approve")
    @RequirePermissions(Permissions.CONTRACT__APPROVE)
    public Map<String, Object> approveVariation(@PathVariable String variationId) {
        return contracts.decideVariation(variationId, true);
    }

    @PostMapping("/variations/{variationId}/reject")
    @RequirePermissions(Permissions.CONTRACT__APPROVE)
    public Map<String, Object> rejectVariation(@PathVariable String variationId) {
        return contracts.decideVariation(variationId, false);
    }

    // ---- advances and claims ------------------------------------------------------------------------------------------

    @GetMapping("/{id}/advances")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public List<Map<String, Object>> advances(@PathVariable String id) {
        return contracts.advances(id);
    }

    @PostMapping("/{id}/advances")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CONTRACT__POST)
    public Map<String, Object> advance(@PathVariable String id, @RequestBody AdvanceRequest r) {
        return contracts.createAdvance(id, r.amount(), r.recoveryPct(), r.date());
    }

    @GetMapping("/{id}/claims")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public List<Map<String, Object>> claims(@PathVariable String id) {
        return contracts.claims(id);
    }

    @PostMapping("/{id}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> claim(@PathVariable String id, @RequestBody ClaimRequest r) {
        return contracts.createClaim(id, new ClaimInput(r.claimDate(), r.cumulativeCertified(), r.retentionPct(),
                r.retentionCondition(), r.retentionReleaseDate(), r.taxId()));
    }

    @GetMapping("/claims/{claimId}")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public Map<String, Object> getClaim(@PathVariable String claimId) {
        return contracts.claim(claimId);
    }

    @PostMapping("/claims/{claimId}/certify")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CONTRACT__POST)
    public Map<String, Object> certify(@PathVariable String claimId) {
        return contracts.certifyClaim(claimId);
    }

    @PostMapping("/claims/{claimId}/cancel")
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> cancelClaim(@PathVariable String claimId) {
        return contracts.cancelClaim(claimId);
    }

    // ---- subcontracts and retentions ----------------------------------------------------------------------------------

    @GetMapping("/{id}/subcontracts")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public List<Map<String, Object>> subcontracts(@PathVariable String id) {
        return contracts.subcontracts(id);
    }

    @PostMapping("/{id}/subcontracts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> subcontract(@PathVariable String id, @Valid @RequestBody SubcontractRequest r) {
        return contracts.createSubcontract(id, new SubcontractInput(r.supplierId(), r.wbsId(), r.scope(), r.value(),
                r.retentionPct(), r.retentionCondition()));
    }

    @PostMapping("/subcontracts/{subcontractId}/valuations")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CONTRACT__POST)
    public Map<String, Object> valuation(@PathVariable String subcontractId, @RequestBody ValuationRequest r) {
        return contracts.valuation(subcontractId, r.cumulativeValue(), r.date());
    }

    @PostMapping("/subcontracts/{subcontractId}/complete")
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> completeSubcontract(@PathVariable String subcontractId) {
        return contracts.closeSubcontract(subcontractId, false);
    }

    @PostMapping("/subcontracts/{subcontractId}/cancel")
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> cancelSubcontract(@PathVariable String subcontractId) {
        return contracts.closeSubcontract(subcontractId, true);
    }

    @GetMapping("/{id}/retentions")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public List<Map<String, Object>> retentions(@PathVariable String id) {
        return contracts.retentions(id);
    }

    @PostMapping("/retentions/{retentionId}/back-charge")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CONTRACT__POST)
    public Map<String, Object> backCharge(@PathVariable String retentionId, @RequestBody BackChargeRequest r) {
        return contracts.backCharge(retentionId, r.amount(), r.reason());
    }

    @PostMapping("/retentions/{retentionId}/release")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CONTRACT__POST)
    public Map<String, Object> release(@PathVariable String retentionId, @RequestBody(required = false)
            ReleaseRequest r) {
        return contracts.releaseRetention(retentionId, r == null ? null : r.evidence());
    }

    // ---- revenue recognition ------------------------------------------------------------------------------------------

    @GetMapping("/{id}/revenue-runs")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public List<Map<String, Object>> runs(@PathVariable String id) {
        return contracts.runs(id);
    }

    @GetMapping("/{id}/revenue-runs/preview")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public Map<String, Object> preview(@PathVariable String id, @RequestParam String periodEnd) {
        return contracts.previewRevenue(id, periodEnd);
    }

    @PostMapping("/{id}/revenue-runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CONTRACT__POST)
    public Map<String, Object> run(@PathVariable String id, @Valid @RequestBody RevenueRequest r) {
        return contracts.runRevenue(id, r.periodEnd());
    }

    @PostMapping("/revenue-runs/{runId}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CONTRACT__POST)
    public Map<String, Object> reverseRun(@PathVariable String runId) {
        return contracts.reverseRevenue(runId);
    }

    // ---- onerous contracts --------------------------------------------------------------------------------------------

    @GetMapping("/{id}/onerous-assessments")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public List<Map<String, Object>> onerous(@PathVariable String id) {
        return contracts.onerousAssessments(id);
    }

    @PostMapping("/{id}/onerous-assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CONTRACT__MANAGE)
    public Map<String, Object> assess(@PathVariable String id, @RequestBody OnerousRequest r) {
        return contracts.assessOnerous(id, new OnerousAssessmentInput(r.asOf(), r.indicators(), r.probableClaims(),
                r.costOfExiting(), r.exitEvidence(), r.impairmentRecognised(), r.impairmentReference()));
    }

    @PostMapping("/onerous-assessments/{assessmentId}/approve")
    @RequirePermissions(Permissions.CONTRACT__APPROVE)
    public Map<String, Object> approveOnerous(@PathVariable String assessmentId) {
        return contracts.approveOnerous(assessmentId);
    }

    @PostMapping("/onerous-assessments/{assessmentId}/reject")
    @RequirePermissions(Permissions.CONTRACT__APPROVE)
    public Map<String, Object> rejectOnerous(@PathVariable String assessmentId) {
        return contracts.rejectOnerous(assessmentId);
    }

    @PostMapping("/onerous-assessments/{assessmentId}/post")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CONTRACT__POST)
    public Map<String, Object> postOnerous(@PathVariable String assessmentId) {
        return contracts.postOnerous(assessmentId);
    }

    // ---- position -----------------------------------------------------------------------------------------------------

    @GetMapping("/{id}/contract-position")
    @RequirePermissions(Permissions.CONTRACT__READ)
    public Map<String, Object> position(@PathVariable String id) {
        return contracts.position(id);
    }
}
