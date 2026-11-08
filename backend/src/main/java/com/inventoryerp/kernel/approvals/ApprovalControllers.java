package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
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
import tools.jackson.databind.json.JsonMapper;

/** {@code /approvals}, {@code /approval-policies}, {@code /approval-workflows}. */
public final class ApprovalControllers {

    private ApprovalControllers() {}

    /** {@code ApprovalStepDto}. */
    public record StepRequest(
            @NotNull(message = "must be an integer number") @Min(value = 1, message = "must not be less than 1") Integer stepOrder,
            @NotNull(message = "must be a string") String name,
            @NotNull(message = "each value in approverPermissions must be a string") List<String> approverPermissions,
            @NotNull(message = "must be an integer number") @Min(value = 1, message = "must not be less than 1") Integer requiredCount,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String branchId,
            String departmentId,
            @OneOf({"permission", "department_manager", "user", "role"}) String approverScope,
            Map<String, Object> conditions,
            List<String> approverUserIds,
            List<String> approverRoleIds) {

        ApprovalAdminService.Step toStep() {
            return new ApprovalAdminService.Step(stepOrder, name, approverPermissions, requiredCount, minAmount,
                    maxAmount, branchId, departmentId, approverScope, conditions, approverUserIds, approverRoleIds);
        }
    }

    /** {@code CreateApprovalPolicyDto}. */
    public record CreatePolicyRequest(
            @NotNull(message = "must be a string") String name,
            @NotNull(message = "must be a string") String entityType,
            BigDecimal minAmount,
            @NotNull(message = "each value in approverPermissions must be a string") List<String> approverPermissions,
            @NotNull(message = "must be an integer number") @Min(value = 1, message = "must not be less than 1") Integer requiredCount,
            Boolean isActive) {}

    /** {@code CreateApprovalWorkflowDto}. */
    public record CreateWorkflowRequest(
            @NotNull(message = "must be a string") String name,
            @NotNull(message = "must be a string") String entityType,
            BigDecimal minAmount,
            Boolean enforceDistinctApprovers,
            Boolean isActive,
            @NotNull(message = "must be an array") List<@Valid StepRequest> steps,
            String branchId,
            String departmentId,
            String description,
            Map<String, Object> conditions,
            Integer precedence,
            @OneOf({"low", "medium", "high", "critical"}) String priority) {

        ApprovalAdminService.RuleOptions rule() {
            return new ApprovalAdminService.RuleOptions(description, conditions, precedence, priority);
        }
    }

    /** {@code DecideDto}. */
    public record DecideRequest(
            @NotNull(message = "must be one of the following values: approved, rejected")
            @OneOf({"approved", "rejected"}) String status,
            String comment) {}

    /** Dry run of the rules for a sample document (ADR-S061). */
    public record SimulateRequest(
            @NotNull(message = "must be a string") String entityType,
            Map<String, Object> snapshot,
            String requesterId) {}

    /** {@code RequestApprovalDto}. */
    public record RequestApprovalRequest(
            @NotNull(message = "must be a string") String entityType,
            @NotNull(message = "must be a string") String entityId,
            Map<String, Object> snapshot,
            String policyId) {}

    @RestController
    @RequestMapping("/approvals")
    @Validated
    public static class ApprovalsController {
        private final ApprovalsService approvals;
        private final ApprovalHubService hub;

        public ApprovalsController(ApprovalsService approvals, ApprovalHubService hub) {
            this.approvals = approvals;
            this.hub = hub;
        }

        /** Approval hub inbox (ADR-S061); {@code scope=all} needs approvals:read. */
        @GetMapping("/inbox")
        public List<Map<String, Object>> inbox(
                @RequestParam(required = false) @OneOf({"mine", "requested", "all"}) String scope,
                @RequestParam(required = false) @OneOf({"pending", "approved", "rejected", "cancelled"}) String status,
                @RequestParam(required = false) String entityType,
                @RequestParam(required = false) @OneOf({"low", "medium", "high", "critical"}) String priority,
                @RequestParam(required = false) String q) {
            return hub.inbox(scope, status, entityType, priority, q);
        }

        /** Badge counts of the approval hub. */
        @GetMapping("/summary")
        public Map<String, Object> summary() {
            return hub.summary();
        }

        /** One request with every stage, assignees, decisions and document details. */
        @GetMapping("/{id}/timeline")
        public Map<String, Object> timeline(@PathVariable String id) {
            return hub.timeline(id);
        }

        @GetMapping
        public List<Map<String, Object>> list(
                @RequestParam(required = false) @OneOf({"pending", "approved", "rejected", "cancelled"}) String status,
                @RequestParam(required = false) String entityType) {
            return approvals.list(status, entityType);
        }

        @GetMapping("/{id}")
        public Map<String, Object> findOne(@PathVariable String id) {
            return approvals.findOne(id);
        }

        @PostMapping("/request")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, Object> request(@Valid @RequestBody RequestApprovalRequest dto) {
            return approvals.requestApproval(dto.entityType(), dto.entityId(), dto.snapshot(), dto.policyId());
        }

        @PostMapping("/{id}/decide")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, Object> decide(@PathVariable String id, @Valid @RequestBody DecideRequest dto) {
            return approvals.decide(id, dto.status(), dto.comment());
        }
    }

    @RestController
    @RequestMapping("/approval-policies")
    public static class ApprovalPolicyController {
        private final ApprovalAdminService admin;

        public ApprovalPolicyController(ApprovalAdminService admin) {
            this.admin = admin;
        }

        @GetMapping
        @RequirePermissions(Permissions.APPROVALS__READ)
        public List<Map<String, Object>> list() {
            return admin.listPolicies();
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.APPROVALS__READ)
        public Map<String, Object> findOne(@PathVariable String id) {
            return admin.findPolicy(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.APPROVALS__MANAGE)
        public Map<String, Object> create(@Valid @RequestBody CreatePolicyRequest dto) {
            return admin.createPolicy(dto.name(), dto.entityType(), dto.minAmount(), dto.approverPermissions(),
                    dto.requiredCount(), dto.isActive());
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.APPROVALS__MANAGE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> dto) {
            ApprovalBodies.check(dto, "name", "minAmount", "approverPermissions", "requiredCount", "isActive");
            return admin.updatePolicy(id, new ApprovalAdminService.Patch(dto));
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.APPROVALS__MANAGE)
        public Map<String, Object> remove(@PathVariable String id) {
            admin.removePolicy(id);
            return Map.of("ok", true);
        }
    }

    @RestController
    @RequestMapping("/approval-workflows")
    public static class ApprovalWorkflowController {
        private final ApprovalAdminService admin;
        private final ApprovalBodies bodies;
        private final ControlledConfigService changes;
        private final JsonMapper mapper;
        private final ApprovalHubService hub;

        public ApprovalWorkflowController(ApprovalAdminService admin, ApprovalBodies bodies,
                ControlledConfigService changes, JsonMapper mapper, ApprovalHubService hub) {
            this.admin = admin;
            this.bodies = bodies;
            this.changes = changes;
            this.mapper = mapper;
            this.hub = hub;
        }

        /** Rules-builder catalogue: fields, operators, scopes, priorities, users and roles (ADR-S061). */
        @GetMapping("/meta")
        @RequirePermissions(Permissions.APPROVALS__READ)
        public Map<String, Object> meta(@RequestParam(required = false) String entityType) {
            return hub.meta(entityType);
        }

        /** Which rule and stages a sample document would get. Writes nothing. */
        @PostMapping("/simulate")
        @ResponseStatus(HttpStatus.OK)
        @RequirePermissions(Permissions.APPROVALS__READ)
        public Map<String, Object> simulate(@Valid @RequestBody SimulateRequest dto) {
            return hub.simulate(dto.entityType(), dto.snapshot() == null ? Map.of() : dto.snapshot(),
                    dto.requesterId());
        }

        @GetMapping
        @RequirePermissions(Permissions.APPROVALS__READ)
        public List<Map<String, Object>> list() {
            return admin.listWorkflows();
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.APPROVALS__READ)
        public Map<String, Object> findOne(@PathVariable String id) {
            return admin.findWorkflow(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.APPROVALS__MANAGE)
        @SuppressWarnings("unchecked")
        public Map<String, Object> create(@Valid @RequestBody CreateWorkflowRequest dto) {
            Map<String, Object> held = changes.holdIfControlled(ApprovalConfigApplier.WORKFLOW_CREATE, null,
                    mapper.convertValue(dto, LinkedHashMap.class));
            if (held != null) {
                return held;
            }
            return admin.createWorkflow(dto.name(), dto.entityType(), dto.minAmount(), dto.branchId(),
                    dto.departmentId(), dto.enforceDistinctApprovers(), dto.isActive(),
                    dto.steps().stream().map(StepRequest::toStep).toList(), dto.rule());
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.APPROVALS__MANAGE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> dto) {
            ApprovalBodies.check(dto, "name", "entityType", "minAmount", "branchId", "departmentId",
                    "enforceDistinctApprovers", "isActive", "steps", "description", "conditions", "precedence",
                    "priority");
            List<ApprovalAdminService.Step> steps = dto.get("steps") == null ? null : bodies.steps(dto.get("steps"));
            admin.findWorkflow(id);
            Map<String, Object> held = changes.holdIfControlled(ApprovalConfigApplier.WORKFLOW_UPDATE, id, dto);
            if (held != null) {
                return held;
            }
            return admin.updateWorkflow(id, new ApprovalAdminService.Patch(dto), steps);
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.APPROVALS__MANAGE)
        public Map<String, Object> remove(@PathVariable String id) {
            admin.findWorkflow(id);
            Map<String, Object> held = changes.holdIfControlled(ApprovalConfigApplier.WORKFLOW_DELETE, id, Map.of());
            if (held != null) {
                return held;
            }
            admin.removeWorkflow(id);
            return Map.of("ok", true);
        }
    }
}
