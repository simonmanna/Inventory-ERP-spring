package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.procurement.application.ProcurementPolicyRegistry.Definition;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Purchasing policies (ADR-PROCUREMENT-001 §4): effective-dated, versioned values per key and scope, written as a
 * draft, approved by a second person, then activated (superseding the previous active version of the same key and
 * scope). {@link #resolve} answers the value in force for a document: the most specific active scope wins
 * (project → cost centre → department → category → branch → organisation), else the built-in default, which equals the
 * behaviour before policies existed.
 */
@Service
public class ProcurementPolicyService {

    /** Where a document sits; any field may be null. */
    public record PolicyContext(String branchId, String categoryId, String departmentId, String costCenterId,
            String projectId) {

        public static final PolicyContext ORG = new PolicyContext(null, null, null, null, null);

        public static PolicyContext branch(String branchId) {
            return new PolicyContext(branchId, null, null, null, null);
        }

        public PolicyContext withCategory(String category) {
            return new PolicyContext(branchId, category, departmentId, costCenterId, projectId);
        }
    }

    /** Value in force and where it came from ({@code policyId} null = built-in default). */
    public record Resolved(String key, JsonNode value, String policyId, String scopeType, String scopeId) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("value", value);
            m.put("policyId", policyId);
            m.put("scopeType", scopeType);
            m.put("scopeId", scopeId);
            m.put("source", policyId == null ? "default" : "policy");
            return m;
        }
    }

    /** Draft input. */
    public record DraftInput(String key, String scopeType, String scopeId, String effectiveFrom, JsonNode value,
            String notes) {}

    private static final List<String> SCOPES = List.of("org", "branch", "category", "department", "cost_center",
            "project");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    /** Active rows per organisation (short TTL so every node converges after a change elsewhere). */
    private final Cache<String, List<Map<String, Object>>> active = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(10_000).build();

    public ProcurementPolicyService(JdbcClient jdbc, AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    // ── resolution ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Value in force for {@code key} in {@code ctx}. */
    public Resolved resolve(String key, PolicyContext ctx) {
        Definition def = ProcurementPolicyRegistry.require(key);
        PolicyContext c = ctx == null ? PolicyContext.ORG : ctx;
        List<Map<String, Object>> rows = activeRows(org()).stream().filter(r -> key.equals(r.get("key"))).toList();
        String[][] chain = {{"project", c.projectId()}, {"cost_center", c.costCenterId()},
                {"department", c.departmentId()}, {"category", c.categoryId()}, {"branch", c.branchId()},
                {"org", null}};
        for (String[] link : chain) {
            if (!"org".equals(link[0]) && link[1] == null) {
                continue;
            }
            for (Map<String, Object> r : rows) {
                if (link[0].equals(r.get("scopeType")) && (link[1] == null ? r.get("scopeId") == null
                        : link[1].equals(r.get("scopeId")))) {
                    return new Resolved(key, json(r.get("value")), (String) r.get("id"), link[0], link[1]);
                }
            }
        }
        return new Resolved(key, def.defaultValue(), null, null, null);
    }

    /** Text value (ENUM keys). */
    public String text(String key, PolicyContext ctx) {
        JsonNode v = resolve(key, ctx).value();
        return v == null || v.isNull() ? null : v.asString();
    }

    /** Numeric value or {@code null}. */
    public java.math.BigDecimal number(String key, PolicyContext ctx) {
        JsonNode v = resolve(key, ctx).value();
        return v == null || v.isNull() ? null : v.decimalValue();
    }

    /** String-list value. */
    public List<String> list(String key, PolicyContext ctx) {
        JsonNode v = resolve(key, ctx).value();
        List<String> out = new ArrayList<>();
        if (v != null && v.isArray()) {
            v.forEach(n -> out.add(n.asString()));
        }
        return out;
    }

    private List<Map<String, Object>> activeRows(String orgId) {
        return active.get(orgId, o -> SqlRows.list(jdbc.sql("SELECT id, key, \"scopeType\", \"scopeId\", value FROM"
                + " \"ProcurementPolicy\" WHERE \"organizationId\" = ? AND status = 'active'").param(o)));
    }

    private static JsonNode json(Object v) {
        Object parsed = EntityJson.parseJson(v);
        return parsed instanceof JsonNode n ? n : EntityJson.tree(String.valueOf(v));
    }

    // ── administration ─────────────────────────────────────────────────────────────────────────────────────────

    /** Definitions with the organisation-level value in force. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition d : ProcurementPolicyRegistry.all()) {
            Map<String, Object> m = d.toMap();
            m.put("effective", resolve(d.key(), PolicyContext.ORG).toMap());
            out.add(m);
        }
        return out;
    }

    /** Policy versions, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String key, String status) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ?");
        if (key != null && !key.isBlank()) {
            where.append(" AND key = ?");
            params.add(key);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ProcurementPolicy\" WHERE" + where
                + " ORDER BY key, \"effectiveFrom\" DESC, version DESC LIMIT 500").params(params)).stream()
                .map(ProcurementPolicyService::shape).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"ProcurementPolicy\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (row == null) {
            throw ApiException.notFound("Procurement policy not found");
        }
        return shape(row);
    }

    private static Map<String, Object> shape(Map<String, Object> row) {
        row.put("value", json(row.get("value")));
        return row;
    }

    /** New draft version (not in force until approved by someone else and activated). */
    public Map<String, Object> createDraft(DraftInput in) {
        Definition def = ProcurementPolicyRegistry.require(in.key());
        String scopeType = in.scopeType() == null || in.scopeType().isBlank() ? "org" : in.scopeType();
        if (!SCOPES.contains(scopeType)) {
            throw ApiException.badRequest("scopeType must be one of " + String.join(", ", SCOPES));
        }
        String scopeId = "org".equals(scopeType) ? null : blankToNull(in.scopeId());
        if (!"org".equals(scopeType) && scopeId == null) {
            throw ApiException.badRequest("scopeId is required for a " + scopeType + " policy");
        }
        JsonNode value = ProcurementPolicyRegistry.validate(def.key(), in.value());
        LocalDate from = in.effectiveFrom() == null || in.effectiveFrom().isBlank() ? LocalDate.now()
                : parseDate(in.effectiveFrom());
        String id = UUID.randomUUID().toString();
        tx.executeWithoutResult(st -> {
            int version = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM \"ProcurementPolicy\" WHERE"
                    + " \"organizationId\" = ? AND key = ? AND \"scopeType\" = ? AND \"scopeId\" IS NOT DISTINCT FROM ?")
                    .params(org(), def.key(), scopeType, scopeId).query(Integer.class).single();
            jdbc.sql("INSERT INTO \"ProcurementPolicy\" (id, \"organizationId\", key, \"scopeType\", \"scopeId\", version,"
                    + " status, \"effectiveFrom\", value, notes, \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?,"
                    + " ?, ?, ?, ?, 'draft', ?, ?::jsonb, ?, ?, " + NOW + ", " + NOW + ")")
                    .params(id, org(), def.key(), scopeType, scopeId, version, from, value.toString(), in.notes(),
                            user()).update();
            audit.recordInTx(AuditInput.of("ProcurementPolicy", id, "create", null, Obj.of("key", def.key(),
                    "scopeType", scopeType, "scopeId", scopeId, "version", version, "value", value.toString())));
        });
        return get(id);
    }

    /** Edit a draft's value / effective date / notes. */
    public Map<String, Object> updateDraft(String id, JsonNode value, String effectiveFrom, String notes) {
        Map<String, Object> current = get(id);
        if (!"draft".equals(current.get("status"))) {
            throw ApiException.badRequest("Only a draft policy can be edited");
        }
        JsonNode v = value == null ? (JsonNode) current.get("value")
                : ProcurementPolicyRegistry.validate((String) current.get("key"), value);
        LocalDate from = effectiveFrom == null ? null : parseDate(effectiveFrom);
        int n = jdbc.sql("UPDATE \"ProcurementPolicy\" SET value = ?::jsonb, \"effectiveFrom\" = COALESCE(?,"
                + " \"effectiveFrom\"), notes = COALESCE(?, notes), \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ? AND status = 'draft'").params(v.toString(), from, notes, id, org()).update();
        if (n == 0) {
            throw ApiException.badRequest("Only a draft policy can be edited");
        }
        audit.record(AuditInput.of("ProcurementPolicy", id, "update", Obj.of("value", String.valueOf(current.get("value"))),
                Obj.of("value", v.toString())));
        return get(id);
    }

    /** Maker-checker approval: the author of a draft cannot approve it. */
    public Map<String, Object> approve(String id) {
        Map<String, Object> current = get(id);
        if (user() != null && user().equals(current.get("createdBy"))) {
            throw ApiException.forbidden("A policy must be approved by someone other than its author");
        }
        transition(id, List.of("draft"), "approved", "\"approvedBy\" = ?, \"approvedAt\" = " + NOW, user(),
                "Only a draft policy can be approved");
        return get(id);
    }

    /** Put an approved version in force now (its effective date must have arrived). */
    public Map<String, Object> activate(String id) {
        Map<String, Object> current = get(id);
        if (!"approved".equals(current.get("status"))) {
            throw ApiException.badRequest("Only an approved policy can be activated");
        }
        LocalDate from = toLocalDate(current.get("effectiveFrom"));
        if (from != null && from.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("This policy takes effect on " + from + " and is activated automatically"
                    + " that day");
        }
        tx.executeWithoutResult(st -> activateInTx(current));
        return get(id);
    }

    private void activateInTx(Map<String, Object> current) {
        String orgId = (String) current.get("organizationId");
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("procurement_policy:" + orgId + ":"
                + current.get("key")).query().listOfRows();
        jdbc.sql("UPDATE \"ProcurementPolicy\" SET status = 'superseded', \"supersededAt\" = " + NOW + ", \"updatedAt\" = "
                + NOW + " WHERE \"organizationId\" = ? AND key = ? AND \"scopeType\" = ? AND \"scopeId\" IS NOT DISTINCT"
                + " FROM ? AND status = 'active'").params(orgId, current.get("key"), current.get("scopeType"),
                current.get("scopeId")).update();
        int n = jdbc.sql("UPDATE \"ProcurementPolicy\" SET status = 'active', \"activatedAt\" = " + NOW + ", \"updatedAt\" = "
                + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = 'approved'").params(current.get("id"), orgId)
                .update();
        if (n == 0) {
            throw ApiException.conflict("The policy was changed by another request");
        }
        audit.recordInTx(AuditInput.of("ProcurementPolicy", (String) current.get("id"), "update",
                Obj.of("status", "approved"), Obj.of("status", "active", "key", current.get("key"))));
        active.invalidate(orgId);
    }

    /** Cancel a draft or approved (not yet active) version. */
    public Map<String, Object> cancel(String id) {
        transition(id, List.of("draft", "approved"), "cancelled", null, null,
                "Only a draft or approved policy can be cancelled");
        return get(id);
    }

    /** Daily job: activate approved versions whose effective date arrived. Returns how many were activated. */
    public int activateDue() {
        int count = 0;
        for (Map<String, Object> row : SqlRows.list(jdbc.sql("SELECT * FROM \"ProcurementPolicy\" WHERE"
                + " \"organizationId\" = ? AND status = 'approved' AND \"effectiveFrom\" <= CURRENT_DATE ORDER BY"
                + " \"effectiveFrom\", version").param(org()))) {
            tx.executeWithoutResult(st -> activateInTx(row));
            count++;
        }
        return count;
    }

    private void transition(String id, List<String> from, String to, String set, Object setParam, String error) {
        List<Object> params = new ArrayList<>();
        params.add(to);
        if (setParam != null) {
            params.add(setParam);
        }
        params.addAll(List.of(id, org(), from.toArray(String[]::new)));
        int n = jdbc.sql("UPDATE \"ProcurementPolicy\" SET status = ?" + (set == null ? "" : ", " + set)
                + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = ANY(?::text[])")
                .params(params).update();
        if (n == 0) {
            get(id);
            throw ApiException.badRequest(error);
        }
        audit.record(AuditInput.of("ProcurementPolicy", id, "update", null, Obj.of("status", to)));
        active.invalidate(org());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("effectiveFrom must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }
}
