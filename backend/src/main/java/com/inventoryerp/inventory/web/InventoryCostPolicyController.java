package com.inventoryerp.inventory.web;

import com.inventoryerp.inventory.application.InventoryCostPolicyService;
import com.inventoryerp.inventory.application.InventoryCostPolicyService.PolicyInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /inventory/cost-policies} — approved AVCO / FIFO costing policy (ADR-S007; no Nest counterpart). */
@RestController
@RequestMapping("/inventory/cost-policies")
public class InventoryCostPolicyController {

    static final String DAY = "^\\d{4}-\\d{2}-\\d{2}$";
    static final String DAY_MSG = "effectiveFrom must be a date (YYYY-MM-DD)";

    /** Create body. */
    public record CreatePolicyRequest(
            @NotNull(message = "scopeType must be one of the following values: organization, category")
            @OneOf({"organization", "category"}) String scopeType,
            String categoryId,
            @NotNull(message = "method must be one of the following values: AVCO, FIFO, SPECIFIC, STANDARD")
            @OneOf({"AVCO", "FIFO", "SPECIFIC", "STANDARD"}) String method,
            @NotNull(message = DAY_MSG) @Pattern(regexp = DAY, message = DAY_MSG) String effectiveFrom,
            String notes) {}

    /** Patch body (draft only). */
    public record UpdatePolicyRequest(
            @OneOf({"organization", "category"}) String scopeType,
            String categoryId,
            @OneOf({"AVCO", "FIFO", "SPECIFIC", "STANDARD"}) String method,
            @Pattern(regexp = DAY, message = DAY_MSG) String effectiveFrom,
            String notes) {}

    private final InventoryCostPolicyService policies;

    public InventoryCostPolicyController(InventoryCostPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    @RequirePermissions(Permissions.COSTING_POLICY__READ)
    public List<Map<String, Object>> list() {
        return policies.list();
    }

    @GetMapping("/current")
    @RequirePermissions(Permissions.COSTING_POLICY__READ)
    public Map<String, Object> current() {
        return policies.current();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.COSTING_POLICY__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return policies.get(id);
    }

    @GetMapping("/{id}/preview")
    @RequirePermissions(Permissions.COSTING_POLICY__READ)
    public Map<String, Object> preview(@PathVariable String id) {
        return policies.preview(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COSTING_POLICY__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody CreatePolicyRequest dto) {
        return policies.create(new PolicyInput(dto.scopeType(), dto.categoryId(), dto.method(), dto.effectiveFrom(),
                dto.notes()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.COSTING_POLICY__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdatePolicyRequest dto) {
        return policies.update(id, new PolicyInput(dto.scopeType(), dto.categoryId(), dto.method(), dto.effectiveFrom(),
                dto.notes()));
    }

    @PostMapping("/{id}/approve")
    @RequirePermissions(Permissions.COSTING_POLICY__APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return policies.approve(id);
    }

    @PostMapping("/{id}/activate")
    @Idempotent
    @RequirePermissions(Permissions.COSTING_POLICY__APPROVE)
    public Map<String, Object> activate(@PathVariable String id) {
        return policies.activate(id);
    }

    @PostMapping("/{id}/cancel")
    @RequirePermissions(Permissions.COSTING_POLICY__MANAGE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return policies.cancel(id);
    }
}
