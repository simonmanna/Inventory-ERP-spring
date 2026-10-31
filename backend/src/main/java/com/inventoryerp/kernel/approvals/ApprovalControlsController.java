package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * {@code /approval-controls}: transaction limits, separation-of-duties switches and held configuration changes
 * (CTRL-1). A write answers {@code {pendingApproval: true, changeId, requestId}} instead of the row when the
 * organization controls configuration changes.
 */
@RestController
@RequestMapping("/approval-controls")
@Validated
public class ApprovalControlsController {

    /** Create or update a limit. */
    public record LimitRequest(
            @NotBlank(message = "name should not be empty") String name,
            String entityType,
            String userId,
            String permission,
            String branchId,
            String departmentId,
            @NotNull(message = "maxAmount must be a number") @DecimalMin(value = "0",
                    message = "maxAmount must not be negative") BigDecimal maxAmount,
            Boolean isActive) {

        ApprovalLimitService.LimitInput input() {
            return new ApprovalLimitService.LimitInput(name, entityType, userId, permission, branchId, departmentId,
                    maxAmount, isActive);
        }
    }

    /** Switch a pair. */
    public record SodRuleRequest(@NotNull(message = "isActive must be a boolean value") Boolean isActive, String notes) {}

    private final ApprovalLimitService limits;
    private final SegregationOfDutiesService sod;
    private final ControlledConfigService changes;

    public ApprovalControlsController(ApprovalLimitService limits, SegregationOfDutiesService sod,
            ControlledConfigService changes) {
        this.limits = limits;
        this.sod = sod;
        this.changes = changes;
    }

    @GetMapping("/limits")
    @RequirePermissions(Permissions.APPROVALS__READ)
    public List<Map<String, Object>> listLimits() {
        return limits.list();
    }

    @PostMapping("/limits")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.APPROVALS__MANAGE)
    public Map<String, Object> createLimit(@Valid @RequestBody LimitRequest dto) {
        ApprovalLimitService.LimitInput in = dto.input();
        limits.assertValid(in);
        Map<String, Object> held = changes.holdIfControlled(ApprovalConfigApplier.LIMIT_CREATE, null,
                ApprovalLimitService.payload(in));
        return held != null ? held : limits.create(in);
    }

    @PatchMapping("/limits/{id}")
    @RequirePermissions(Permissions.APPROVALS__MANAGE)
    public Map<String, Object> updateLimit(@PathVariable String id, @Valid @RequestBody LimitRequest dto) {
        ApprovalLimitService.LimitInput in = dto.input();
        limits.assertValid(in);
        limits.get(id);
        Map<String, Object> held = changes.holdIfControlled(ApprovalConfigApplier.LIMIT_UPDATE, id,
                ApprovalLimitService.payload(in));
        return held != null ? held : limits.update(id, in);
    }

    @DeleteMapping("/limits/{id}")
    @RequirePermissions(Permissions.APPROVALS__MANAGE)
    public Map<String, Object> removeLimit(@PathVariable String id) {
        limits.get(id);
        Map<String, Object> held = changes.holdIfControlled(ApprovalConfigApplier.LIMIT_DELETE, id, Map.of());
        if (held != null) {
            return held;
        }
        limits.remove(id);
        return Map.of("ok", true);
    }

    @GetMapping("/sod-rules")
    @RequirePermissions(Permissions.APPROVALS__READ)
    public List<Map<String, Object>> sodRules() {
        return sod.list();
    }

    @PutMapping("/sod-rules/{pairKey}")
    @RequirePermissions(Permissions.APPROVALS__MANAGE)
    public Object setSodRule(@PathVariable String pairKey, @Valid @RequestBody SodRuleRequest dto) {
        sod.assertSwitchable(pairKey, dto.isActive());
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("isActive", dto.isActive());
        payload.put("notes", dto.notes());
        Map<String, Object> held = changes.holdIfControlled(ApprovalConfigApplier.SOD_RULE, pairKey, payload);
        return held != null ? held : sod.setRule(pairKey, dto.isActive(), dto.notes());
    }

    @GetMapping("/config-changes")
    @RequirePermissions(Permissions.APPROVALS__READ)
    public List<Map<String, Object>> configChanges(
            @RequestParam(required = false) @OneOf({"pending", "applied", "rejected"}) String status) {
        return changes.list(status);
    }
}
