package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.ProcurementPolicyService;
import com.inventoryerp.procurement.application.ProcurementPolicyService.DraftInput;
import com.inventoryerp.procurement.application.ProcurementPolicyService.PolicyContext;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code /procurement/policies} — configurable purchasing policies (ADR-PROCUREMENT-001; no Nest counterpart): key
 * catalogue with the value in force, versions per scope, maker-checker approval, activation and resolution.
 */
@RestController
@RequestMapping("/procurement/policies")
public class ProcurementPolicyController {

    /** New draft version. */
    public record DraftRequest(@NotBlank(message = "key is required") String key,
            @OneOf({"org", "branch", "category", "department", "cost_center", "project"}) String scopeType,
            String scopeId, String effectiveFrom, JsonNode value, String notes) {}

    /** Draft edit. */
    public record UpdateRequest(JsonNode value, String effectiveFrom, String notes) {}

    private final ProcurementPolicyService policies;

    public ProcurementPolicyController(ProcurementPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping("/definitions")
    @RequirePermissions(Permissions.PROCUREMENT_POLICY__READ)
    public List<Map<String, Object>> definitions() {
        return policies.definitions();
    }

    @GetMapping("/resolve")
    @RequirePermissions(Permissions.PROCUREMENT_POLICY__READ)
    public Map<String, Object> resolve(@RequestParam String key, @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String categoryId, @RequestParam(required = false) String departmentId,
            @RequestParam(required = false) String costCenterId, @RequestParam(required = false) String projectId) {
        return policies.resolve(key, new PolicyContext(branchId, categoryId, departmentId, costCenterId, projectId))
                .toMap();
    }

    @GetMapping
    @RequirePermissions(Permissions.PROCUREMENT_POLICY__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String key,
            @RequestParam(required = false) String status) {
        return policies.list(key, status);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT_POLICY__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return policies.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT_POLICY__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody DraftRequest dto) {
        return policies.createDraft(new DraftInput(dto.key(), dto.scopeType(), dto.scopeId(), dto.effectiveFrom(),
                dto.value(), dto.notes()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT_POLICY__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateRequest dto) {
        return policies.updateDraft(id, dto.value(), dto.effectiveFrom(), dto.notes());
    }

    @PostMapping("/{id}/approve")
    @RequirePermissions(Permissions.PROCUREMENT_POLICY__APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return policies.approve(id);
    }

    @PostMapping("/{id}/activate")
    @RequirePermissions(Permissions.PROCUREMENT_POLICY__MANAGE)
    public Map<String, Object> activate(@PathVariable String id) {
        return policies.activate(id);
    }

    @PostMapping("/{id}/cancel")
    @RequirePermissions(Permissions.PROCUREMENT_POLICY__MANAGE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return policies.cancel(id);
    }
}
