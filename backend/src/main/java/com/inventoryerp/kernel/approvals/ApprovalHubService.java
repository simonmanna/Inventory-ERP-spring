package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.approvals.ApprovalAssignees.Person;
import com.inventoryerp.kernel.approvals.ApprovalsService.Deciders;
import com.inventoryerp.kernel.approvals.ApprovalsService.Scope;
import com.inventoryerp.kernel.approvals.ApprovalsService.Workflow;
import com.inventoryerp.kernel.domain.ApprovalDecision;
import com.inventoryerp.kernel.domain.ApprovalPolicy;
import com.inventoryerp.kernel.domain.ApprovalRequest;
import com.inventoryerp.kernel.domain.ApprovalStatus;
import com.inventoryerp.kernel.domain.ApprovalStep;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approval hub and the rules builder's helpers (ADR-S061): an inbox of requests with their current stage and
 * whether the caller can decide, a stage-by-stage timeline with named assignees, a summary for badges, a dry run of the
 * rules for a sample document, and the field / operator / assignee catalogue of the builder. Read-only: deciding still
 * goes through {@link ApprovalsService#decide}.
 */
@Service
public class ApprovalHubService {

    /** Inbox scopes: awaiting the caller's decision, raised by the caller, everything (needs approvals:read). */
    public static final List<String> SCOPES = List.of("mine", "requested", "all");

    private static final int INBOX_LIMIT = 300;
    private static final List<String> REFERENCE_KEYS = List.of("reference", "number", "documentNumber", "code",
            "orderNumber", "poNumber", "invoiceNumber", "billNumber", "requestNumber", "receiptNumber", "claimNumber");
    private static final List<String> TITLE_KEYS = List.of("title", "subject", "description", "name", "supplierName",
            "vendorName", "customerName", "partyName", "employeeName", "productName", "reason", "memo", "notes");
    private static final Set<String> HIDDEN_DETAILS = Set.of("makerIds", "priority", "createdBy", "organizationId");

    private final EntityManager em;
    private final ApprovalsService approvals;
    private final ApprovalAssignees assignees;
    private final PermissionService permissions;

    public ApprovalHubService(EntityManager em, ApprovalsService approvals, ApprovalAssignees assignees,
            PermissionService permissions) {
        this.em = em;
        this.approvals = approvals;
        this.assignees = assignees;
        this.permissions = permissions;
    }

    /** Workflows looked up once per call. */
    private final class Lookups {
        final Map<String, Workflow> workflows = new HashMap<>();
        final Map<String, ApprovalPolicy> policies = new HashMap<>();

        Workflow workflow(String id) {
            return id == null ? null : workflows.computeIfAbsent(id, approvals::workflow);
        }

        ApprovalPolicy policy(String id) {
            return id == null ? null : policies.computeIfAbsent(id, k -> em.find(ApprovalPolicy.class, k));
        }
    }

    // ---- inbox -------------------------------------------------------------------------------------------------

    /** Inbox cards, highest priority first, then newest. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> inbox(String scope, String status, String entityType, String priority,
            String search) {
        String s = scope == null ? "mine" : scope;
        List<String> perms = permissions.current();
        if ("all".equals(s) && !perms.contains(Permissions.APPROVALS__READ)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.APPROVALS__READ);
        }
        String userId = TenantContext.userId();
        List<ApprovalRequest> rows = query("mine".equals(s) ? "pending" : status, entityType, priority,
                "requested".equals(s) ? userId : null);
        Lookups lk = new Lookups();
        Map<String, List<ApprovalDecision>> decisions = decisionsOf(rows.stream().map(ApprovalRequest::getId).toList());
        List<Card> cards = new ArrayList<>();
        for (ApprovalRequest r : rows) {
            List<ApprovalDecision> ds = decisions.getOrDefault(r.getId(), List.of());
            boolean can = canDecide(r, lk, ds, userId, perms);
            if ("mine".equals(s) && !can) {
                continue;
            }
            cards.add(new Card(r, ds, can));
        }
        Map<String, Person> people = assignees.users(cards.stream().map(c -> c.request().getCreatedById()).toList());
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        cards.sort(Comparator.<Card>comparingInt(c -> rank(c.request().getPriority())).reversed()
                .thenComparing(c -> c.request().getCreatedAt(), Comparator.reverseOrder()));
        for (Card c : cards) {
            Map<String, Object> item = card(c.request(), lk, c.decisions(), c.canDecide(), people);
            if (needle == null || haystack(item).contains(needle)) {
                out.add(item);
            }
        }
        return out;
    }

    private record Card(ApprovalRequest request, List<ApprovalDecision> decisions, boolean canDecide) {}

    /** Badge counts: awaiting me (and how many of those are high or critical), my open requests, decided today. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        String userId = TenantContext.userId();
        List<String> perms = permissions.current();
        List<ApprovalRequest> pending = query("pending", null, null, null);
        Map<String, List<ApprovalDecision>> decisions = decisionsOf(pending.stream().map(ApprovalRequest::getId).toList());
        Lookups lk = new Lookups();
        int mine = 0;
        int urgent = 0;
        int requested = 0;
        for (ApprovalRequest r : pending) {
            if (userId != null && userId.equals(r.getCreatedById())) {
                requested++;
            }
            if (canDecide(r, lk, decisions.getOrDefault(r.getId(), List.of()), userId, perms)) {
                mine++;
                if (rank(r.getPriority()) >= rank("high")) {
                    urgent++;
                }
            }
        }
        Long today = em.createQuery("select count(d) from ApprovalDecision d where d.organizationId = :org"
                        + " and d.approverId = :me and d.decidedAt >= :since", Long.class)
                .setParameter("org", TenantContext.organizationId())
                .setParameter("me", userId)
                .setParameter("since", Instant.now().truncatedTo(ChronoUnit.DAYS))
                .getSingleResult();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("awaitingMe", mine);
        out.put("awaitingMeUrgent", urgent);
        out.put("requestedPending", requested);
        out.put("decidedToday", today);
        return out;
    }

    // ---- timeline ----------------------------------------------------------------------------------------------

    /** One request with every stage of its rule, the assignees, the decisions and the document details. */
    @Transactional(readOnly = true)
    public Map<String, Object> timeline(String id) {
        List<ApprovalRequest> found = em.createQuery("select r from ApprovalRequest r where r.id = :id"
                        + " and r.organizationId = :org", ApprovalRequest.class)
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .getResultList();
        if (found.isEmpty()) {
            throw ApiException.notFound("Approval request not found");
        }
        ApprovalRequest r = found.getFirst();
        Lookups lk = new Lookups();
        List<ApprovalDecision> ds = decisionsOf(List.of(r.getId())).getOrDefault(r.getId(), List.of());
        String userId = TenantContext.userId();
        boolean can = canDecide(r, lk, ds, userId, permissions.current());

        Set<String> userIds = new LinkedHashSet<>();
        userIds.add(r.getCreatedById());
        ds.forEach(d -> userIds.add(d.getApproverId()));
        Map<String, Object> snapshot = ApprovalsService.snapshot(r);
        Scope scope = approvals.scope(snapshot, r.getCreatedById());
        Workflow wf = lk.workflow(r.getWorkflowId());
        List<StageView> stages = stages(r, wf, lk.policy(r.getPolicyId()), scope);
        stages.forEach(st -> userIds.addAll(st.deciders().users() == null ? Set.of() : st.deciders().users()));
        Map<String, Person> people = assignees.users(userIds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request", card(r, lk, ds, can, people));
        Map<String, Object> rule = null;
        if (wf != null) {
            rule = new LinkedHashMap<>();
            rule.put("id", wf.row().getId());
            rule.put("name", wf.row().getName());
            rule.put("description", wf.row().getDescription());
            rule.put("conditions", EntityJson.parseJson(wf.row().getConditions()));
        }
        out.put("workflow", rule);
        List<Map<String, Object>> stageJson = new ArrayList<>();
        for (StageView st : stages) {
            stageJson.add(stageJson(st, ds, people));
        }
        out.put("stages", stageJson);
        out.put("details", details(snapshot));
        out.put("snapshot", snapshot);
        out.put("canDecide", can);
        return out;
    }

    private record StageView(Integer stepOrder, String name, String state, int requiredCount, Deciders deciders,
            List<String> roleIds) {}

    private List<StageView> stages(ApprovalRequest r, Workflow wf, ApprovalPolicy policy, Scope scope) {
        List<StageView> out = new ArrayList<>();
        if (wf == null) {
            Deciders d = approvals.deciders(null, null, policy, scope, r.getCreatedById());
            out.add(new StageView(r.getCurrentStep(), policy == null ? "Approval" : policy.getName(),
                    state(r, r.getCurrentStep(), true), r.getRequiredCount(), d, List.of()));
            return out;
        }
        Set<Integer> applicable = new LinkedHashSet<>();
        ApprovalsService.applicableSteps(wf, scope).forEach(s -> applicable.add(s.getStepOrder()));
        for (ApprovalStep s : wf.steps()) {
            boolean applies = applicable.contains(s.getStepOrder());
            Deciders d = applies ? approvals.deciders(wf, s, null, scope, r.getCreatedById())
                    : new Deciders("permission", null, ApprovalsService.nn(s.getApproverPermissions()));
            out.add(new StageView(s.getStepOrder(), s.getName(), state(r, s.getStepOrder(), applies),
                    s.getRequiredCount(), d, ApprovalsService.nn(s.getApproverRoleIds())));
        }
        return out;
    }

    /** approved, current, rejected, cancelled, waiting, not_reached or skipped. */
    private static String state(ApprovalRequest r, int stepOrder, boolean applies) {
        if (!applies) {
            return "skipped";
        }
        int current = r.getCurrentStep();
        return switch (r.getStatus()) {
            case APPROVED -> "approved";
            case PENDING -> stepOrder < current ? "approved" : stepOrder == current ? "current" : "waiting";
            case REJECTED -> stepOrder < current ? "approved" : stepOrder == current ? "rejected" : "not_reached";
            default -> stepOrder < current ? "approved" : stepOrder == current ? "cancelled" : "not_reached";
        };
    }

    private Map<String, Object> stageJson(StageView st, List<ApprovalDecision> ds, Map<String, Person> people) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepOrder", st.stepOrder());
        m.put("name", st.name());
        m.put("state", st.state());
        m.put("requiredCount", st.requiredCount());
        List<Map<String, Object>> decided = new ArrayList<>();
        long approvedCount = 0;
        for (ApprovalDecision d : ds) {
            if (!d.getStepOrder().equals(st.stepOrder())) {
                continue;
            }
            if (d.getStatus() == ApprovalStatus.APPROVED) {
                approvedCount++;
            }
            Map<String, Object> dj = new LinkedHashMap<>();
            dj.put("approver", person(d.getApproverId(), people));
            dj.put("status", d.getStatus().value());
            dj.put("comment", d.getComment());
            dj.put("decidedAt", d.getDecidedAt());
            decided.add(dj);
        }
        m.put("approvals", approvedCount);
        m.put("decisions", decided);
        m.put("assignee", assignee(st.deciders(), st.roleIds(), people));
        return m;
    }

    private Map<String, Object> assignee(Deciders d, List<String> roleIds, Map<String, Person> people) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", d.kind());
        List<Map<String, Object>> users = new ArrayList<>();
        if (d.users() != null) {
            d.users().stream().sorted().forEach(u -> users.add(person(u, people)));
        }
        String label = switch (d.kind()) {
            case ApprovalsService.DEPARTMENT_MANAGER -> "Department head";
            case ApprovalsService.ROLE -> "Role: " + String.join(", ", assignees.roleNames(roleIds).values());
            case ApprovalsService.USER -> users.size() == 1 ? String.valueOf(users.getFirst().get("name"))
                    : users.size() + " assigned users";
            default -> d.permissions().isEmpty() ? "Anyone with approval access"
                    : "Holders of " + String.join(", ", d.permissions());
        };
        m.put("label", label);
        m.put("permissions", d.permissions());
        m.put("users", users);
        return m;
    }

    // ---- rules builder helpers ---------------------------------------------------------------------------------

    /**
     * Dry run: which rule a document would match and which stages would apply, with their assignees. Writes nothing.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> simulate(String entityType, Map<String, Object> snapshot, String requesterId) {
        String requester = requesterId == null ? TenantContext.userId() : requesterId;
        Scope scope = approvals.scope(snapshot, requester);
        Workflow wf = approvals.resolveWorkflow(entityType, scope);
        Map<String, Object> out = new LinkedHashMap<>();
        List<ApprovalStep> steps = wf == null ? List.of() : ApprovalsService.applicableSteps(wf, scope);
        out.put("needsApproval", !steps.isEmpty());
        out.put("priority", ApprovalsService.priorityOf(snapshot, wf == null ? null : wf.row()));
        out.put("workflow", wf == null ? null : Map.of("id", wf.row().getId(), "name", wf.row().getName()));
        List<Map<String, Object>> stages = new ArrayList<>();
        if (wf != null) {
            Set<Integer> applicable = new LinkedHashSet<>();
            steps.forEach(s -> applicable.add(s.getStepOrder()));
            List<Deciders> all = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (ApprovalStep s : wf.steps()) {
                Deciders d = approvals.deciders(wf, s, null, scope, requester);
                all.add(d);
                if (d.users() != null) {
                    ids.addAll(d.users());
                }
            }
            Map<String, Person> people = assignees.users(ids);
            for (int i = 0; i < wf.steps().size(); i++) {
                ApprovalStep s = wf.steps().get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("stepOrder", s.getStepOrder());
                m.put("name", s.getName());
                m.put("applies", applicable.contains(s.getStepOrder()));
                m.put("requiredCount", s.getRequiredCount());
                m.put("assignee", assignee(all.get(i), ApprovalsService.nn(s.getApproverRoleIds()), people));
                stages.add(m);
            }
        }
        out.put("stages", stages);
        return out;
    }

    /**
     * Catalogue for the rules builder: the fields a condition can test for a document type (the engine's own keys
     * plus every scalar key seen on that type's recent snapshots), the operators, scopes, priorities, and the users
     * and roles a stage can be assigned to.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> meta(String entityType) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        field(fields, "amount", "Total Amount", "number", null);
        field(fields, "branchId", "Branch", "branch", null);
        field(fields, "departmentId", "Department", "department", null);
        field(fields, "requesterId", "Requester", "user", null);
        field(fields, "priority", "Priority", "priority", null);
        if (entityType != null && !entityType.isBlank()) {
            List<ApprovalRequest> recent = em.createQuery("select r from ApprovalRequest r where r.organizationId = :org"
                            + " and r.entityType = :type order by r.createdAt desc", ApprovalRequest.class)
                    .setParameter("org", TenantContext.organizationId())
                    .setParameter("type", entityType)
                    .setMaxResults(50)
                    .getResultList();
            Map<String, Object> samples = new TreeMap<>();
            for (ApprovalRequest r : recent) {
                ApprovalsService.snapshot(r).forEach((k, v) -> {
                    if (v != null && !(v instanceof Map<?, ?>) && !HIDDEN_DETAILS.contains(k)) {
                        samples.putIfAbsent(k, v);
                    }
                });
            }
            samples.forEach((k, v) -> field(fields, k, humanize(k), typeOf(k, v), sample(v)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fields", new ArrayList<>(fields.values()));
        out.put("operators", ApprovalConditions.OPERATORS);
        out.put("approverScopes", List.of("permission", ApprovalsService.DEPARTMENT_MANAGER, ApprovalsService.USER,
                ApprovalsService.ROLE));
        out.put("priorities", ApprovalsService.PRIORITIES);
        out.put("users", assignees.allUsers());
        out.put("roles", assignees.allRoles().stream().map(p -> Map.of("id", p.id(), "name", p.name())).toList());
        return out;
    }

    private static void field(Map<String, Map<String, Object>> fields, String key, String label, String type,
            Object sample) {
        if (fields.containsKey(key)) {
            return;
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("type", type);
        f.put("sample", sample);
        fields.put(key, f);
    }

    private static String typeOf(String key, Object v) {
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Collection<?>) {
            return "list";
        }
        String s = String.valueOf(v);
        if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return "date";
        }
        return key.endsWith("Id") ? "id" : "text";
    }

    private static Object sample(Object v) {
        if (v instanceof Collection<?> c) {
            return c.size() + " items";
        }
        String s = String.valueOf(v);
        return s.length() > 60 ? s.substring(0, 60) : v;
    }

    // ---- shared ------------------------------------------------------------------------------------------------

    private List<ApprovalRequest> query(String status, String entityType, String priority, String createdById) {
        StringBuilder q = new StringBuilder("select r from ApprovalRequest r where r.organizationId = :org");
        if (status != null) {
            q.append(" and r.status = :status");
        }
        if (entityType != null) {
            q.append(" and r.entityType = :type");
        }
        if (priority != null) {
            q.append(" and r.priority = :priority");
        }
        if (createdById != null) {
            q.append(" and r.createdById = :me");
        }
        var query = em.createQuery(q + " order by r.createdAt desc", ApprovalRequest.class)
                .setParameter("org", TenantContext.organizationId())
                .setMaxResults(INBOX_LIMIT);
        if (status != null) {
            query.setParameter("status", DbEnum.fromValue(ApprovalStatus.class, status));
        }
        if (entityType != null) {
            query.setParameter("type", entityType);
        }
        if (priority != null) {
            query.setParameter("priority", priority);
        }
        if (createdById != null) {
            query.setParameter("me", createdById);
        }
        return query.getResultList();
    }

    private Map<String, List<ApprovalDecision>> decisionsOf(List<String> requestIds) {
        Map<String, List<ApprovalDecision>> out = new HashMap<>();
        if (requestIds.isEmpty()) {
            return out;
        }
        em.createQuery("select d from ApprovalDecision d where d.requestId in :ids order by d.decidedAt asc",
                        ApprovalDecision.class)
                .setParameter("ids", requestIds)
                .getResultList()
                .forEach(d -> out.computeIfAbsent(d.getRequestId(), k -> new ArrayList<>()).add(d));
        return out;
    }

    /**
     * Whether {@link ApprovalsService#decide} would accept this user on the current stage, short of transaction
     * limits and the separation-of-duties pairs (those are checked when deciding).
     */
    private boolean canDecide(ApprovalRequest r, Lookups lk, List<ApprovalDecision> ds, String userId,
            List<String> perms) {
        if (userId == null || r.getStatus() != ApprovalStatus.PENDING) {
            return false;
        }
        Workflow wf = lk.workflow(r.getWorkflowId());
        ApprovalStep step = ApprovalsService.currentStep(wf, r);
        Scope scope = approvals.scope(ApprovalsService.snapshot(r), r.getCreatedById());
        Deciders d = approvals.deciders(wf, step, lk.policy(r.getPolicyId()), scope, r.getCreatedById());
        if (d.refusal(userId, perms) != null) {
            return false;
        }
        boolean selfGuard = d.guarded() || ControlledConfigService.ENTITY_TYPE.equals(r.getEntityType());
        if (selfGuard && userId.equals(r.getCreatedById())) {
            return false;
        }
        if (wf != null && Boolean.TRUE.equals(wf.row().getEnforceDistinctApprovers()) && ds.stream()
                .anyMatch(x -> userId.equals(x.getApproverId()) && x.getStepOrder() < r.getCurrentStep())) {
            return false;
        }
        return ds.stream().noneMatch(x -> userId.equals(x.getApproverId())
                && x.getStepOrder().equals(r.getCurrentStep()));
    }

    private Map<String, Object> card(ApprovalRequest r, Lookups lk, List<ApprovalDecision> ds, boolean canDecide,
            Map<String, Person> people) {
        Map<String, Object> snapshot = ApprovalsService.snapshot(r);
        Workflow wf = lk.workflow(r.getWorkflowId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("entityType", r.getEntityType());
        m.put("entityId", r.getEntityId());
        m.put("reference", reference(snapshot, r.getEntityId()));
        m.put("title", firstText(snapshot, TITLE_KEYS));
        m.put("amount", snapshot.get("amount") instanceof Number n ? n : ApprovalConditions.number(snapshot.get("amount")));
        m.put("currency", firstText(snapshot, List.of("currency", "currencyCode")));
        m.put("priority", r.getPriority());
        m.put("status", r.getStatus().value());
        m.put("createdAt", r.getCreatedAt());
        m.put("decidedAt", r.getDecidedAt());
        m.put("requester", person(r.getCreatedById(), people));
        m.put("workflowName", wf == null ? null : wf.row().getName());
        Map<String, Object> stage = new LinkedHashMap<>();
        if (wf != null && !wf.steps().isEmpty()) {
            Scope scope = approvals.scope(snapshot, r.getCreatedById());
            List<ApprovalStep> applicable = ApprovalsService.applicableSteps(wf, scope);
            int index = 0;
            for (int i = 0; i < applicable.size(); i++) {
                if (applicable.get(i).getStepOrder().equals(r.getCurrentStep())) {
                    index = i + 1;
                }
            }
            ApprovalStep cur = ApprovalsService.currentStep(wf, r);
            stage.put("stepOrder", r.getCurrentStep());
            stage.put("name", cur == null ? null : cur.getName());
            stage.put("index", index);
            stage.put("total", applicable.size());
        } else {
            stage.put("stepOrder", r.getCurrentStep());
            stage.put("name", null);
            stage.put("index", 1);
            stage.put("total", 1);
        }
        m.put("stage", stage);
        m.put("decisionCount", ds.size());
        m.put("canDecide", canDecide);
        return m;
    }

    private static Map<String, Object> person(String id, Map<String, Person> people) {
        if (id == null) {
            return null;
        }
        Person p = people.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", p == null || p.name().isBlank() ? (p == null ? "Unknown user" : p.email()) : p.name());
        m.put("email", p == null ? null : p.email());
        return m;
    }

    private static String reference(Map<String, Object> snapshot, String entityId) {
        String found = firstText(snapshot, REFERENCE_KEYS);
        if (found != null) {
            return found;
        }
        for (var e : snapshot.entrySet()) {
            if (e.getKey().endsWith("Number") && e.getValue() instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return entityId == null ? null : entityId.substring(0, Math.min(8, entityId.length())).toUpperCase(Locale.ROOT);
    }

    private static String firstText(Map<String, Object> snapshot, List<String> keys) {
        for (String k : keys) {
            Object v = snapshot.get(k);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /** Scalar snapshot entries as labelled rows, ids and internal keys left out. */
    static List<Map<String, Object>> details(Map<String, Object> snapshot) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : snapshot.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null || v instanceof Map<?, ?> || HIDDEN_DETAILS.contains(k) || k.endsWith("Id")
                    || k.endsWith("Ids") || "id".equals(k)) {
                continue;
            }
            Object shown = v;
            if (v instanceof Collection<?> c) {
                boolean scalars = c.stream().allMatch(x -> !(x instanceof Map<?, ?>) && !(x instanceof Collection<?>));
                shown = scalars ? String.join(", ", c.stream().map(String::valueOf).toList()) : c.size() + " items";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", k);
            row.put("label", humanize(k));
            row.put("value", shown);
            out.add(row);
        }
        return out;
    }

    /** {@code totalAmount} → {@code Total Amount}, {@code supplier_name} → {@code Supplier Name}. */
    static String humanize(String key) {
        String spaced = key.replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim();
        StringBuilder b = new StringBuilder();
        for (String w : spaced.split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static String haystack(Map<String, Object> item) {
        Object requester = item.get("requester") instanceof Map<?, ?> p ? p.get("name") : null;
        return (item.get("reference") + " " + item.get("title") + " " + item.get("entityType") + " " + requester + " "
                + item.get("workflowName")).toLowerCase(Locale.ROOT);
    }

    private static int rank(String priority) {
        int i = ApprovalsService.PRIORITIES.indexOf(priority == null ? "medium" : priority);
        return i < 0 ? 1 : i;
    }
}
