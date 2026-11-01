package com.inventoryerp.costing.web;

import com.inventoryerp.accounting.application.AccountingPolicyService;
import com.inventoryerp.accounting.application.AccountingPolicyService.ProposalInput;
import com.inventoryerp.costing.application.CostingSettingsService;
import com.inventoryerp.costing.application.CostingSettingsService.SettingsInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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

/**
 * {@code /costing/settings} and {@code /costing/policies} (Cost Management CM-2, ADR-S032; extension): the unified
 * Costing &amp; Valuation settings façade, governed accounting policy elections and cost eligibility, and the branch
 * dimension compliance report.
 */
@RestController
@RequestMapping("/costing")
public class CostingSettingsController {

    public record SettingsRequest(String defaultCostingMethod, Boolean allowNegativeStock, Boolean landedCostEnabled,
            String defaultAllocationMethod, String dimensionEnforcement, Map<String, String> accounts) {}

    public record ProposalRequest(
            @NotBlank(message = "election should not be empty") String election,
            String scopeType,
            String scopeId,
            @NotBlank(message = "value should not be empty") String value,
            String effectiveFrom,
            @NotBlank(message = "justification should not be empty")
            @Size(max = 2000, message = "justification must be at most 2000 characters") String justification) {}

    public record RejectRequest(@Size(max = 500, message = "reason must be at most 500 characters") String reason) {}

    private final CostingSettingsService settings;
    private final AccountingPolicyService policies;

    public CostingSettingsController(CostingSettingsService settings, AccountingPolicyService policies) {
        this.settings = settings;
        this.policies = policies;
    }

    @GetMapping("/settings")
    @RequirePermissions(Permissions.COSTING_SETTINGS__READ)
    public Map<String, Object> get() {
        return settings.get();
    }

    @PutMapping("/settings")
    @RequirePermissions(Permissions.COSTING_SETTINGS__MANAGE)
    public Map<String, Object> update(@RequestBody SettingsRequest dto) {
        return settings.update(new SettingsInput(dto.defaultCostingMethod(), dto.allowNegativeStock(),
                dto.landedCostEnabled(), dto.defaultAllocationMethod(), dto.dimensionEnforcement(), dto.accounts()));
    }

    @PostMapping("/settings/accounts/ensure")
    @RequirePermissions(Permissions.COSTING_SETTINGS__MANAGE)
    public Map<String, Object> ensureAccounts() {
        return settings.ensureAccounts();
    }

    @GetMapping("/dimension-compliance")
    @RequirePermissions(Permissions.COSTING_SETTINGS__READ)
    public Map<String, Object> dimensionCompliance(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return settings.dimensionCompliance(from, to);
    }

    @GetMapping("/policies/catalogue")
    @RequirePermissions(Permissions.ACCOUNTING_POLICY__READ)
    public List<Map<String, Object>> catalogue() {
        return policies.catalogue();
    }

    @GetMapping("/policies")
    @RequirePermissions(Permissions.ACCOUNTING_POLICY__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String election) {
        return policies.list(election);
    }

    @GetMapping("/policies/{id}")
    @RequirePermissions(Permissions.ACCOUNTING_POLICY__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return policies.get(id);
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ACCOUNTING_POLICY__MANAGE)
    public Map<String, Object> propose(@Valid @RequestBody ProposalRequest dto) {
        return policies.propose(new ProposalInput(dto.election(), dto.scopeType(), dto.scopeId(), dto.value(),
                dto.effectiveFrom(), dto.justification()));
    }

    @PostMapping("/policies/{id}/approve")
    @RequirePermissions(Permissions.ACCOUNTING_POLICY__APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return policies.approve(id);
    }

    @PostMapping("/policies/{id}/reject")
    @RequirePermissions(Permissions.ACCOUNTING_POLICY__APPROVE)
    public Map<String, Object> reject(@PathVariable String id, @Valid @RequestBody(required = false) RejectRequest dto) {
        return policies.reject(id, dto == null ? null : dto.reason());
    }

    @GetMapping("/eligibility")
    @RequirePermissions(Permissions.ACCOUNTING_POLICY__READ)
    public Map<String, Object> eligibility() {
        return Map.of("classes", AccountingPolicyService.ELIGIBILITY, "capitalisable",
                AccountingPolicyService.CAPITALISABLE, "classifications", policies.classifications(),
                "unclassifiedAccounts", policies.unclassifiedAccounts());
    }
}
