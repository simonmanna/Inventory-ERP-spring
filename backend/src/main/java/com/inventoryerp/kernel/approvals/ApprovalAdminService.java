package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.domain.ApprovalPolicy;
import com.inventoryerp.kernel.domain.ApprovalStep;
import com.inventoryerp.kernel.domain.ApprovalWorkflow;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD for approval policies and multi-step workflows (port of the two Nest admin controllers). */
@Service
public class ApprovalAdminService {

    /** One workflow step. */
    public record Step(Integer stepOrder, String name, List<String> approverPermissions, Integer requiredCount,
            BigDecimal minAmount, BigDecimal maxAmount, String branchId, String departmentId, String approverScope) {

        /** A step with no branch or department condition, decided by permission holders. */
        public Step(Integer stepOrder, String name, List<String> approverPermissions, Integer requiredCount,
                BigDecimal minAmount, BigDecimal maxAmount) {
            this(stepOrder, name, approverPermissions, requiredCount, minAmount, maxAmount, null, null, null);
        }
    }

    /** Field-presence aware patch: {@code present} lists the JSON keys sent. */
    public record Patch(Map<String, Object> values) {
        boolean has(String k) {
            return values.containsKey(k);
        }

        Object get(String k) {
            return values.get(k);
        }
    }

    private final EntityManager em;
    private final AuditService audit;

    public ApprovalAdminService(EntityManager em, AuditService audit) {
        this.em = em;
        this.audit = audit;
    }

    // ---- policies ----

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPolicies() {
        return EntityJson.list(em.createQuery("select p from ApprovalPolicy p where p.organizationId = :org"
                        + " order by p.createdAt desc", ApprovalPolicy.class)
                .setParameter("org", TenantContext.organizationId())
                .getResultList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findPolicy(String id) {
        return EntityJson.of(policy(id));
    }

    @Transactional
    public Map<String, Object> createPolicy(String name, String entityType, BigDecimal minAmount,
            List<String> approverPermissions, Integer requiredCount, Boolean isActive) {
        ApprovalPolicy p = new ApprovalPolicy();
        p.setOrganizationId(TenantContext.organizationId());
        p.setName(name);
        p.setEntityType(entityType);
        p.setMinAmount(minAmount);
        p.setApproverPermissions(new ArrayList<>(approverPermissions));
        p.setRequiredCount(requiredCount);
        p.setIsActive(isActive == null || isActive);
        em.persist(p);
        em.flush();
        return EntityJson.of(p);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> updatePolicy(String id, Patch dto) {
        ApprovalPolicy p = policy(id);
        if (dto.has("name") && dto.get("name") != null) {
            p.setName((String) dto.get("name"));
        }
        if (dto.has("minAmount")) {
            p.setMinAmount(dec(dto.get("minAmount")));
        }
        if (dto.has("approverPermissions") && dto.get("approverPermissions") != null) {
            p.setApproverPermissions(new ArrayList<>((List<String>) dto.get("approverPermissions")));
        }
        if (dto.has("requiredCount") && dto.get("requiredCount") != null) {
            p.setRequiredCount(((Number) dto.get("requiredCount")).intValue());
        }
        if (dto.has("isActive") && dto.get("isActive") != null) {
            p.setIsActive((Boolean) dto.get("isActive"));
        }
        em.flush();
        return EntityJson.of(p);
    }

    @Transactional
    public void removePolicy(String id) {
        em.remove(policy(id));
    }

    private ApprovalPolicy policy(String id) {
        var rows = em.createQuery("select p from ApprovalPolicy p where p.id = :id and p.organizationId = :org",
                        ApprovalPolicy.class)
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .getResultList();
        if (rows.isEmpty()) {
            throw ApiException.notFound("Approval policy not found");
        }
        return rows.getFirst();
    }

    // ---- workflows ----

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listWorkflows() {
        return em.createQuery("select w from ApprovalWorkflow w where w.organizationId = :org order by w.createdAt desc",
                        ApprovalWorkflow.class)
                .setParameter("org", TenantContext.organizationId())
                .getResultList()
                .stream()
                .map(w -> ApprovalsService.workflowJson(w, steps(w.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findWorkflow(String id) {
        ApprovalWorkflow w = workflow(id);
        return ApprovalsService.workflowJson(w, steps(id));
    }

    @Transactional
    public Map<String, Object> createWorkflow(String name, String entityType, BigDecimal minAmount,
            Boolean enforceDistinctApprovers, Boolean isActive, List<Step> steps) {
        return createWorkflow(name, entityType, minAmount, null, null, enforceDistinctApprovers, isActive, steps);
    }

    /** A workflow that only applies to one branch and/or department (both {@code null} = everywhere). */
    @Transactional
    public Map<String, Object> createWorkflow(String name, String entityType, BigDecimal minAmount, String branchId,
            String departmentId, Boolean enforceDistinctApprovers, Boolean isActive, List<Step> steps) {
        assertSteps(steps);
        String orgId = TenantContext.organizationId();
        ApprovalWorkflow w = new ApprovalWorkflow();
        w.setOrganizationId(orgId);
        w.setName(name);
        w.setEntityType(entityType);
        w.setMinAmount(minAmount);
        w.setBranchId(branchId);
        w.setDepartmentId(departmentId);
        w.setEnforceDistinctApprovers(enforceDistinctApprovers != null && enforceDistinctApprovers);
        w.setIsActive(isActive == null || isActive);
        em.persist(w);
        em.flush();
        insertSteps(orgId, w.getId(), steps);
        Map<String, Object> nv = new LinkedHashMap<>();
        nv.put("name", w.getName());
        nv.put("entityType", w.getEntityType());
        nv.put("steps", steps.size());
        audit.record(AuditInput.of("ApprovalWorkflow", w.getId(), "create", null, nv));
        return ApprovalsService.workflowJson(w, steps(w.getId()));
    }

    @Transactional
    public Map<String, Object> updateWorkflow(String id, Patch dto, List<Step> steps) {
        String orgId = TenantContext.organizationId();
        ApprovalWorkflow w = workflow(id);
        if (steps != null) {
            assertSteps(steps);
        }
        Map<String, Object> scalar = new LinkedHashMap<>();
        if (dto.has("name") && dto.get("name") != null) {
            w.setName((String) dto.get("name"));
            scalar.put("name", dto.get("name"));
        }
        if (dto.has("entityType") && dto.get("entityType") != null) {
            w.setEntityType((String) dto.get("entityType"));
            scalar.put("entityType", dto.get("entityType"));
        }
        if (dto.has("minAmount")) {
            w.setMinAmount(dec(dto.get("minAmount")));
            scalar.put("minAmount", dto.get("minAmount"));
        }
        if (dto.has("branchId")) {
            w.setBranchId((String) dto.get("branchId"));
            scalar.put("branchId", dto.get("branchId"));
        }
        if (dto.has("departmentId")) {
            w.setDepartmentId((String) dto.get("departmentId"));
            scalar.put("departmentId", dto.get("departmentId"));
        }
        if (dto.has("enforceDistinctApprovers") && dto.get("enforceDistinctApprovers") != null) {
            w.setEnforceDistinctApprovers((Boolean) dto.get("enforceDistinctApprovers"));
            scalar.put("enforceDistinctApprovers", dto.get("enforceDistinctApprovers"));
        }
        if (dto.has("isActive") && dto.get("isActive") != null) {
            w.setIsActive((Boolean) dto.get("isActive"));
            scalar.put("isActive", dto.get("isActive"));
        }
        em.flush();
        if (steps != null) {
            em.createQuery("delete from ApprovalStep s where s.workflowId = :id").setParameter("id", id).executeUpdate();
            insertSteps(orgId, id, steps);
            scalar.put("steps", steps.size());
        }
        audit.recordInTx(AuditInput.of("ApprovalWorkflow", id, "update", null, scalar));
        em.refresh(w);
        return ApprovalsService.workflowJson(w, steps(id));
    }

    @Transactional
    public void removeWorkflow(String id) {
        ApprovalWorkflow w = workflow(id);
        em.createQuery("delete from ApprovalStep s where s.workflowId = :id").setParameter("id", id).executeUpdate();
        em.remove(w);
        em.flush();
        audit.record(AuditInput.of("ApprovalWorkflow", id, "delete"));
    }

    private void insertSteps(String orgId, String workflowId, List<Step> steps) {
        for (Step s : steps) {
            ApprovalStep row = new ApprovalStep();
            row.setOrganizationId(orgId);
            row.setWorkflowId(workflowId);
            row.setStepOrder(s.stepOrder());
            row.setName(s.name());
            row.setApproverPermissions(new ArrayList<>(s.approverPermissions()));
            row.setRequiredCount(s.requiredCount());
            row.setMinAmount(s.minAmount());
            row.setMaxAmount(s.maxAmount());
            row.setBranchId(s.branchId());
            row.setDepartmentId(s.departmentId());
            row.setApproverScope(s.approverScope() == null ? "permission" : s.approverScope());
            em.persist(row);
        }
        em.flush();
    }

    private List<ApprovalStep> steps(String workflowId) {
        return em.createQuery("select s from ApprovalStep s where s.workflowId = :id order by s.stepOrder asc",
                        ApprovalStep.class)
                .setParameter("id", workflowId)
                .getResultList();
    }

    private ApprovalWorkflow workflow(String id) {
        var rows = em.createQuery("select w from ApprovalWorkflow w where w.id = :id and w.organizationId = :org",
                        ApprovalWorkflow.class)
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .getResultList();
        if (rows.isEmpty()) {
            throw ApiException.notFound("Approval workflow not found");
        }
        return rows.getFirst();
    }

    private static void assertSteps(List<Step> steps) {
        if (steps.isEmpty()) {
            throw ApiException.badRequest("A workflow needs at least one step");
        }
        var orders = new HashSet<Integer>();
        for (Step s : steps) {
            if (!orders.add(s.stepOrder())) {
                throw ApiException.badRequest("Duplicate stepOrder values");
            }
        }
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }
}
