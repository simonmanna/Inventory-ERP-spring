package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.approvals.ApprovalAdminService.Patch;
import com.inventoryerp.kernel.approvals.ApprovalControllers.CreateWorkflowRequest;
import com.inventoryerp.kernel.approvals.ApprovalControllers.StepRequest;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Applies approved changes to workflows, limits and separation-of-duties switches. */
@Component
public class ApprovalConfigApplier implements ConfigChangeApplier {

    static final String WORKFLOW_CREATE = "approval_workflow.create";
    static final String WORKFLOW_UPDATE = "approval_workflow.update";
    static final String WORKFLOW_DELETE = "approval_workflow.delete";
    static final String LIMIT_CREATE = "approval_limit.create";
    static final String LIMIT_UPDATE = "approval_limit.update";
    static final String LIMIT_DELETE = "approval_limit.delete";
    static final String SOD_RULE = "sod_rule.set";

    private final ApprovalAdminService admin;
    private final ApprovalBodies bodies;
    private final ApprovalLimitService limits;
    private final SegregationOfDutiesService sod;
    private final JsonMapper mapper;

    public ApprovalConfigApplier(ApprovalAdminService admin, ApprovalBodies bodies, ApprovalLimitService limits,
            SegregationOfDutiesService sod, JsonMapper mapper) {
        this.admin = admin;
        this.bodies = bodies;
        this.limits = limits;
        this.sod = sod;
        this.mapper = mapper;
    }

    @Override
    public boolean supports(String changeType) {
        return changeType.startsWith("approval_workflow.") || changeType.startsWith("approval_limit.")
                || SOD_RULE.equals(changeType);
    }

    @Override
    public void apply(String changeType, String targetId, Map<String, Object> payload) {
        switch (changeType) {
            case WORKFLOW_CREATE -> {
                CreateWorkflowRequest dto = mapper.convertValue(payload, CreateWorkflowRequest.class);
                List<ApprovalAdminService.Step> steps = dto.steps().stream().map(StepRequest::toStep).toList();
                admin.createWorkflow(dto.name(), dto.entityType(), dto.minAmount(), dto.branchId(), dto.departmentId(),
                        dto.enforceDistinctApprovers(), dto.isActive(), steps, dto.rule());
            }
            case WORKFLOW_UPDATE -> admin.updateWorkflow(targetId, new Patch(payload),
                    payload.get("steps") == null ? null : bodies.steps(payload.get("steps")));
            case WORKFLOW_DELETE -> admin.removeWorkflow(targetId);
            case LIMIT_CREATE -> limits.create(mapper.convertValue(payload, ApprovalLimitService.LimitInput.class));
            case LIMIT_UPDATE -> limits.update(targetId,
                    mapper.convertValue(payload, ApprovalLimitService.LimitInput.class));
            case LIMIT_DELETE -> limits.remove(targetId);
            case SOD_RULE -> sod.setRule(targetId, Boolean.TRUE.equals(payload.get("isActive")),
                    (String) payload.get("notes"));
            default -> throw ApiException.badRequest("Unknown change type " + changeType);
        }
    }
}
