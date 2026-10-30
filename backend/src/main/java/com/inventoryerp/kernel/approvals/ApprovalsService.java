package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.domain.ApprovalDecision;
import com.inventoryerp.kernel.domain.ApprovalPolicy;
import com.inventoryerp.kernel.domain.ApprovalRequest;
import com.inventoryerp.kernel.domain.ApprovalStatus;
import com.inventoryerp.kernel.domain.ApprovalStep;
import com.inventoryerp.kernel.domain.ApprovalWorkflow;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Generic multi-step approval runner (port of {@code approvals.service.ts}). A request must clear every
 * step whose {@code [minAmount, maxAmount)} band contains the document amount, in stepOrder order; each
 * step needs {@code requiredCount} distinct approvers holding any of its permissions. No workflow for an
 * entity type ⇒ auto-approve ({@code null}). Public method signatures are used by other modules.
 */
@Service
public class ApprovalsService {

    private final EntityManager em;
    private final AuditService audit;
    private final EventBus events;
    private final PermissionService permissions;

    public ApprovalsService(EntityManager em, AuditService audit, EventBus events, PermissionService permissions) {
        this.em = em;
        this.audit = audit;
        this.events = events;
        this.permissions = permissions;
    }

    private record Workflow(ApprovalWorkflow row, List<ApprovalStep> steps) {}

    /** Most-specific active workflow for (entityType, amount). */
    private Workflow resolveWorkflow(String entityType, double amount) {
        List<ApprovalWorkflow> wfs = em.createQuery("select w from ApprovalWorkflow w where w.organizationId = :org"
                        + " and w.entityType = :type and w.isActive = true order by w.minAmount desc nulls last",
                        ApprovalWorkflow.class)
                .setParameter("org", TenantContext.organizationId())
                .setParameter("type", entityType)
                .getResultList();
        for (ApprovalWorkflow w : wfs) {
            if (w.getMinAmount() == null || amount >= w.getMinAmount().doubleValue()) {
                return new Workflow(w, steps(w.getId()));
            }
        }
        return null;
    }

    private List<ApprovalStep> steps(String workflowId) {
        return em.createQuery("select s from ApprovalStep s where s.workflowId = :id order by s.stepOrder asc",
                        ApprovalStep.class)
                .setParameter("id", workflowId)
                .getResultList();
    }

    private static List<ApprovalStep> applicableSteps(Workflow wf, double amount) {
        List<ApprovalStep> out = new ArrayList<>();
        for (ApprovalStep s : wf.steps()) {
            boolean lo = s.getMinAmount() == null || amount >= s.getMinAmount().doubleValue();
            boolean hi = s.getMaxAmount() == null || amount < s.getMaxAmount().doubleValue();
            if (lo && hi) {
                out.add(s);
            }
        }
        return out;
    }

    /** {@code Number(snapshot.amount ?? 0)}. */
    static double amountOf(Map<String, Object> snapshot) {
        Object a = snapshot == null ? null : snapshot.get("amount");
        if (a == null) {
            return 0;
        }
        if (a instanceof Number n) {
            return n.doubleValue();
        }
        return com.inventoryerp.kernel.common.JsNumbers.number(String.valueOf(a));
    }

    /** Create a request (or {@code null} when no workflow/step applies). */
    @Transactional
    public Map<String, Object> requestApproval(String entityType, String entityId, Map<String, Object> snapshot,
            String policyId) {
        String orgId = TenantContext.organizationId();
        double amount = amountOf(snapshot);
        Workflow wf = resolveWorkflow(entityType, amount);
        if (wf == null) {
            return null;
        }
        List<ApprovalStep> steps = applicableSteps(wf, amount);
        if (steps.isEmpty()) {
            return null;
        }
        ApprovalStep first = steps.getFirst();
        ApprovalRequest req = new ApprovalRequest();
        req.setOrganizationId(orgId);
        req.setEntityType(entityType);
        req.setEntityId(entityId);
        req.setSnapshot(EntityJson.toJson(snapshot));
        req.setWorkflowId(wf.row().getId());
        req.setCurrentStep(first.getStepOrder());
        req.setRequiredCount(first.getRequiredCount());
        req.setCreatedById(TenantContext.userId());
        em.persist(req);
        em.flush();
        em.refresh(req);
        Map<String, Object> nv = new LinkedHashMap<>();
        nv.put("entityType", req.getEntityType());
        nv.put("entityId", req.getEntityId());
        nv.put("stepOrder", first.getStepOrder());
        audit.record(AuditInput.of("ApprovalRequest", req.getId(), "create", null, nv));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("organizationId", orgId);
        ev.put("requestId", req.getId());
        ev.put("entityType", req.getEntityType());
        ev.put("entityId", req.getEntityId());
        ev.put("stepOrder", first.getStepOrder());
        events.publish("approval.requested", ev);
        return EntityJson.of(req);
    }

    /** Decide the request's current step; one decision per (request, step, approver). */
    @Transactional
    public Map<String, Object> decide(String requestId, String status, String comment) {
        String userId = TenantContext.userId();
        if (userId == null) {
            throw ApiException.badRequest("Not authenticated");
        }
        String orgId = TenantContext.organizationId();
        List<ApprovalRequest> found = em.createQuery("select r from ApprovalRequest r where r.id = :id"
                        + " and r.organizationId = :org", ApprovalRequest.class)
                .setParameter("id", requestId)
                .setParameter("org", orgId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        if (found.isEmpty()) {
            throw ApiException.notFound("Approval request not found");
        }
        ApprovalRequest req = found.getFirst();
        if (req.getStatus() != ApprovalStatus.PENDING) {
            throw ApiException.badRequest("Already resolved");
        }
        Workflow wf = req.getWorkflowId() == null ? null : workflow(req.getWorkflowId());
        ApprovalPolicy policy = req.getPolicyId() == null ? null : em.find(ApprovalPolicy.class, req.getPolicyId());
        List<ApprovalDecision> decisions = decisions(req.getId());
        double amount = amountOf(snapshot(req));
        List<String> approverPermissions;
        int requiredCount;
        if (wf != null) {
            ApprovalStep step = wf.steps().stream().filter(s -> s.getStepOrder().equals(req.getCurrentStep())).findFirst()
                    .orElse(null);
            approverPermissions = step == null ? List.of() : nn(step.getApproverPermissions());
            requiredCount = step == null ? req.getRequiredCount() : step.getRequiredCount();
        } else {
            approverPermissions = policy == null ? List.of() : nn(policy.getApproverPermissions());
            requiredCount = req.getRequiredCount();
        }

        List<String> perms = permissions.current();
        if (!approverPermissions.isEmpty() && approverPermissions.stream().noneMatch(perms::contains)) {
            throw ApiException.badRequest("You are not allowed to decide on this step");
        }
        if (req.getCreatedById() != null && req.getCreatedById().equals(userId) && !approverPermissions.isEmpty()) {
            throw ApiException.badRequest("You cannot approve a request you created");
        }
        if (wf != null && Boolean.TRUE.equals(wf.row().getEnforceDistinctApprovers()) && decisions.stream()
                .anyMatch(d -> userId.equals(d.getApproverId()) && d.getStepOrder() < req.getCurrentStep())) {
            throw ApiException.badRequest("You already approved an earlier step (segregation of duties)");
        }
        if (decisions.stream().anyMatch(d -> userId.equals(d.getApproverId()) && d.getStepOrder().equals(req.getCurrentStep()))) {
            throw ApiException.badRequest("You already decided on this step");
        }

        ApprovalDecision decision = new ApprovalDecision();
        decision.setOrganizationId(orgId);
        decision.setRequestId(req.getId());
        decision.setApproverId(userId);
        decision.setStepOrder(req.getCurrentStep());
        decision.setStatus(DbEnum.fromValue(ApprovalStatus.class, status));
        decision.setComment(comment);
        em.persist(decision);
        em.flush();

        List<ApprovalDecision> stepDecisions = em.createQuery("select d from ApprovalDecision d where d.requestId = :id"
                        + " and d.stepOrder = :step", ApprovalDecision.class)
                .setParameter("id", req.getId())
                .setParameter("step", req.getCurrentStep())
                .getResultList();
        long approvals = stepDecisions.stream().filter(d -> d.getStatus() == ApprovalStatus.APPROVED).count();
        long rejections = stepDecisions.stream().filter(d -> d.getStatus() == ApprovalStatus.REJECTED).count();

        ApprovalStatus nextStatus = ApprovalStatus.PENDING;
        int nextStep = req.getCurrentStep();
        int nextRequired = requiredCount;
        Instant decidedAt = null;
        Integer advancedTo = null;
        int decidedStep = req.getCurrentStep();
        if (rejections > 0) {
            nextStatus = ApprovalStatus.REJECTED;
            decidedAt = Instant.now();
        } else if (approvals >= requiredCount) {
            List<ApprovalStep> remaining = wf == null ? List.of()
                    : applicableSteps(wf, amount).stream().filter(s -> s.getStepOrder() > req.getCurrentStep()).toList();
            if (!remaining.isEmpty()) {
                nextStep = remaining.getFirst().getStepOrder();
                nextRequired = remaining.getFirst().getRequiredCount();
                advancedTo = nextStep;
            } else {
                nextStatus = ApprovalStatus.APPROVED;
                decidedAt = Instant.now();
            }
        }
        req.setStatus(nextStatus);
        req.setCurrentStep(nextStep);
        req.setRequiredCount(nextRequired);
        if (decidedAt != null) {
            req.setDecidedAt(decidedAt);
        }
        em.flush();

        if (advancedTo != null) {
            Map<String, Object> ev = event(orgId, req);
            ev.put("stepOrder", advancedTo);
            events.publish("approval.step_advanced", ev);
        }
        if (nextStatus != ApprovalStatus.PENDING) {
            Map<String, Object> ev = event(orgId, req);
            ev.put("status", nextStatus.value());
            events.publish("approval.decided", ev);
        }
        Map<String, Object> nv = new LinkedHashMap<>();
        nv.put("status", nextStatus.value());
        nv.put("stepOrder", decidedStep);
        nv.put("comment", comment);
        audit.recordInTx(AuditInput.of("ApprovalRequest", req.getId(), "approved".equals(status) ? "approve" : "reject",
                null, nv));
        return EntityJson.of(req);
    }

    /**
     * {@code {needsApproval: true, requestId}} when a pending request exists or was created,
     * {@code {needsApproval: false}} when already approved, {@code null} when no workflow applies.
     */
    @Transactional
    public Map<String, Object> checkOrRequestApproval(String entityType, String entityId, Map<String, Object> snapshot) {
        List<ApprovalRequest> existing = em.createQuery("select r from ApprovalRequest r where r.organizationId = :org"
                        + " and r.entityType = :type and r.entityId = :eid and r.status in :st", ApprovalRequest.class)
                .setParameter("org", TenantContext.organizationId())
                .setParameter("type", entityType)
                .setParameter("eid", entityId)
                .setParameter("st", List.of(ApprovalStatus.PENDING, ApprovalStatus.APPROVED))
                .setMaxResults(1)
                .getResultList();
        Map<String, Object> out = new LinkedHashMap<>();
        if (!existing.isEmpty()) {
            if (existing.getFirst().getStatus() == ApprovalStatus.APPROVED) {
                // P1-4 (ADR-PROCUREMENT-001): an approval covers the amount it was granted for. A larger amount on
                // the same entity (an edited order, a second supplier payment keyed by the partner) needs a new
                // decision — the earlier one is superseded, never stretched.
                double approved = amountOf(snapshot(existing.getFirst()));
                double now = amountOf(snapshot);
                if (now <= approved + 0.005) {
                    out.put("needsApproval", false);
                    return out;
                }
                existing.getFirst().setStatus(ApprovalStatus.CANCELLED);
                em.flush();
                Map<String, Object> again = requestApproval(entityType, entityId, snapshot, null);
                if (again == null) {
                    out.put("needsApproval", false);
                    return out;
                }
                out.put("needsApproval", true);
                out.put("requestId", again.get("id"));
                return out;
            }
            out.put("needsApproval", true);
            out.put("requestId", existing.getFirst().getId());
            return out;
        }
        Map<String, Object> req = requestApproval(entityType, entityId, snapshot, null);
        if (req != null) {
            out.put("needsApproval", true);
            out.put("requestId", req.get("id"));
            return out;
        }
        return null;
    }

    /** Record a synchronously-authorized manager override as an already-approved request. */
    @Transactional
    public Map<String, Object> recordSynchronousOverride(String entityType, String entityId, String approverId,
            Map<String, Object> snapshot, String comment) {
        String orgId = TenantContext.organizationId();
        double amount = amountOf(snapshot);
        Workflow wf = resolveWorkflow(entityType, amount);
        List<ApprovalStep> steps = wf == null ? List.of() : applicableSteps(wf, amount);
        int lastStep = steps.isEmpty() ? 1 : steps.getLast().getStepOrder();
        ApprovalRequest req = new ApprovalRequest();
        req.setOrganizationId(orgId);
        req.setEntityType(entityType);
        req.setEntityId(entityId);
        req.setSnapshot(EntityJson.toJson(snapshot));
        req.setWorkflowId(wf == null ? null : wf.row().getId());
        req.setCurrentStep(lastStep);
        req.setRequiredCount(1);
        req.setStatus(ApprovalStatus.APPROVED);
        req.setDecidedAt(Instant.now());
        req.setCreatedById(TenantContext.userId());
        em.persist(req);
        em.flush();
        ApprovalDecision d = new ApprovalDecision();
        d.setOrganizationId(orgId);
        d.setRequestId(req.getId());
        d.setApproverId(approverId);
        d.setStepOrder(lastStep);
        d.setStatus(ApprovalStatus.APPROVED);
        d.setComment(comment == null ? "Synchronous manager override" : comment);
        em.persist(d);
        em.flush();
        Map<String, Object> nv = new LinkedHashMap<>();
        nv.put("entityType", entityType);
        nv.put("synchronous", true);
        nv.put("approverId", approverId);
        audit.recordInTx(AuditInput.of("ApprovalRequest", req.getId(), "approve", null, nv));
        em.refresh(req);
        return EntityJson.of(req);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String entityType) {
        StringBuilder q = new StringBuilder("select r from ApprovalRequest r where r.organizationId = :org");
        if (status != null) {
            q.append(" and r.status = :status");
        }
        if (entityType != null) {
            q.append(" and r.entityType = :type");
        }
        var query = em.createQuery(q + " order by r.createdAt desc", ApprovalRequest.class)
                .setParameter("org", TenantContext.organizationId())
                .setMaxResults(100);
        if (status != null) {
            query.setParameter("status", DbEnum.fromValue(ApprovalStatus.class, status));
        }
        if (entityType != null) {
            query.setParameter("type", entityType);
        }
        return query.getResultList().stream().map(this::withIncludes).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        List<ApprovalRequest> rows = em.createQuery("select r from ApprovalRequest r where r.id = :id"
                        + " and r.organizationId = :org", ApprovalRequest.class)
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .getResultList();
        return rows.isEmpty() ? null : withIncludes(rows.getFirst());
    }

    /** Request + {@code decisions}, {@code policy}, {@code workflow.steps}. */
    private Map<String, Object> withIncludes(ApprovalRequest r) {
        Map<String, Object> out = EntityJson.of(r);
        out.put("decisions", EntityJson.list(decisions(r.getId())));
        out.put("policy", r.getPolicyId() == null ? null : EntityJson.of(em.find(ApprovalPolicy.class, r.getPolicyId())));
        Workflow wf = r.getWorkflowId() == null ? null : workflow(r.getWorkflowId());
        out.put("workflow", wf == null ? null : workflowJson(wf.row(), wf.steps()));
        return out;
    }

    static Map<String, Object> workflowJson(ApprovalWorkflow w, List<ApprovalStep> steps) {
        Map<String, Object> out = EntityJson.of(w);
        out.put("steps", EntityJson.list(steps));
        return out;
    }

    private Workflow workflow(String id) {
        ApprovalWorkflow w = em.find(ApprovalWorkflow.class, id);
        return w == null ? null : new Workflow(w, steps(id));
    }

    private List<ApprovalDecision> decisions(String requestId) {
        return em.createQuery("select d from ApprovalDecision d where d.requestId = :id", ApprovalDecision.class)
                .setParameter("id", requestId)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(ApprovalRequest r) {
        Object parsed = EntityJson.parseJson(r.getSnapshot());
        if (parsed instanceof JsonNode node && node.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            node.properties().forEach(e -> m.put(e.getKey(), e.getValue().isNumber() ? e.getValue().numberValue()
                    : e.getValue().isString() ? e.getValue().asString() : null));
            return m;
        }
        return Map.of();
    }

    private static Map<String, Object> event(String orgId, ApprovalRequest req) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("organizationId", orgId);
        ev.put("requestId", req.getId());
        ev.put("entityType", req.getEntityType());
        ev.put("entityId", req.getEntityId());
        return ev;
    }

    private static List<String> nn(List<String> l) {
        return l == null ? List.of() : l;
    }
}
