package com.inventoryerp.projects.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.application.SubcontractComplianceService;
import com.inventoryerp.projects.application.SubcontractComplianceService.DocumentInput;
import com.inventoryerp.projects.application.SubcontractComplianceService.RequirementInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * Subcontractor compliance documents, lien waivers, gating settings and overrides (ADR-S074 §2; extension, no NestJS
 * counterpart).
 */
@RestController
@RequestMapping("/subcontract-compliance")
public class SubcontractComplianceController {

    public record DocumentRequest(String partnerId, String subcontractId, String documentType, String documentNumber,
            String issuer, String validFrom, String validTo, BigDecimal amount, String waiverType, String waiverScope,
            String valuationId, String paymentId, String throughDate, String documentId, String notes) {

        DocumentInput input() {
            return new DocumentInput(partnerId, subcontractId, documentType, documentNumber, issuer, validFrom, validTo,
                    amount, waiverType, waiverScope, valuationId, paymentId, throughDate, documentId, notes);
        }
    }

    public record RequirementItem(String documentType, String mode) {}

    public record RequirementsRequest(List<RequirementItem> requirements) {}

    public record RejectRequest(String reason) {}

    public record OverrideRequest(String subcontractId, String gate, String billId, String reason) {}

    private final SubcontractComplianceService compliance;

    public SubcontractComplianceController(SubcontractComplianceService compliance) {
        this.compliance = compliance;
    }

    @GetMapping("/requirements")
    @RequirePermissions(Permissions.COMPLIANCE__READ)
    public List<Map<String, Object>> requirements() {
        return compliance.requirements();
    }

    @PutMapping("/requirements")
    @RequirePermissions(Permissions.COMPLIANCE__MANAGE)
    public List<Map<String, Object>> setRequirements(@RequestBody RequirementsRequest r) {
        return compliance.setRequirements(r.requirements() == null ? null : r.requirements().stream()
                .map(i -> i == null ? null : new RequirementInput(i.documentType(), i.mode())).toList());
    }

    @GetMapping("/documents")
    @RequirePermissions(Permissions.COMPLIANCE__READ)
    public List<Map<String, Object>> documents(@RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String subcontractId,
            @RequestParam(required = false) String documentType) {
        return compliance.documents(partnerId, subcontractId, documentType);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMPLIANCE__MANAGE)
    public Map<String, Object> create(@RequestBody DocumentRequest r) {
        return compliance.createDocument(r.input());
    }

    @GetMapping("/documents/{id}")
    @RequirePermissions(Permissions.COMPLIANCE__READ)
    public Map<String, Object> document(@PathVariable String id) {
        return compliance.document(id);
    }

    @PatchMapping("/documents/{id}")
    @RequirePermissions(Permissions.COMPLIANCE__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody DocumentRequest r) {
        return compliance.updateDocument(id, r.input());
    }

    @PostMapping("/documents/{id}/verify")
    @RequirePermissions(Permissions.COMPLIANCE__MANAGE)
    public Map<String, Object> verify(@PathVariable String id) {
        return compliance.verify(id);
    }

    @PostMapping("/documents/{id}/reject")
    @RequirePermissions(Permissions.COMPLIANCE__MANAGE)
    public Map<String, Object> reject(@PathVariable String id, @RequestBody RejectRequest r) {
        return compliance.reject(id, r.reason());
    }

    @GetMapping("/subcontracts/{subcontractId}")
    @RequirePermissions(Permissions.COMPLIANCE__READ)
    public Map<String, Object> subcontract(@PathVariable String subcontractId) {
        return compliance.subcontractView(subcontractId);
    }

    @GetMapping("/suppliers/{partnerId}")
    @RequirePermissions(Permissions.COMPLIANCE__READ)
    public Map<String, Object> supplier(@PathVariable String partnerId) {
        return compliance.supplierView(partnerId);
    }

    @PostMapping("/overrides")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMPLIANCE__OVERRIDE)
    public Map<String, Object> override(@RequestBody OverrideRequest r) {
        return compliance.createOverride(r.subcontractId(), r.gate(), r.billId(), r.reason());
    }
}
