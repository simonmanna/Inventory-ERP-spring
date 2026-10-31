package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controlled configuration changes (CTRL-1). With an active workflow for {@value #ENTITY_TYPE}, a change to approval
 * workflows, limits, separation-of-duties switches or document numbering is held instead of applied, and a second
 * person approves it in the normal inbox; the approval applies it. Without such a workflow nothing is held. Changing
 * the {@value #ENTITY_TYPE} workflow itself is held too, so the control cannot be lifted by one person.
 */
@Service
public class ControlledConfigService {

    public static final String ENTITY_TYPE = "config_change";

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final ApprovalsService approvals;
    private final AuditService audit;
    private final JsonMapper mapper;
    private final List<ConfigChangeApplier> appliers;

    public ControlledConfigService(JdbcClient jdbc, ApprovalsService approvals, AuditService audit, JsonMapper mapper,
            List<ConfigChangeApplier> appliers) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.audit = audit;
        this.mapper = mapper;
        this.appliers = appliers;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * Hold the change for approval. Returns {@code null} when changes are not controlled here (the caller applies it
     * now), else the body the route answers with: {@code {pendingApproval: true, changeId, requestId}}.
     */
    @Transactional
    public Map<String, Object> holdIfControlled(String changeType, String targetId, Map<String, Object> payload) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> request = approvals.requestApproval(ENTITY_TYPE, id, Obj.of("amount", 0, "changeType",
                changeType, "targetId", targetId, "summary", EntityJson.toJson(payload)), null);
        if (request == null) {
            return null;
        }
        jdbc.sql("INSERT INTO \"ControlledConfigChange\" (id, \"organizationId\", \"changeType\", \"targetId\", payload,"
                        + " \"approvalRequestId\", \"requestedBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " ?::jsonb, ?, ?, " + NOW + ", " + NOW + ")")
                .params(id, org(), changeType, targetId, EntityJson.toJson(payload), request.get("id"),
                        TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ControlledConfigChange", id, "create", null, Obj.of("changeType", changeType,
                "targetId", targetId)));
        return Obj.of("pendingApproval", true, "changeId", id, "requestId", request.get("id"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        String sql = "SELECT * FROM \"ControlledConfigChange\" WHERE \"organizationId\" = ?";
        if (status != null && !status.isEmpty()) {
            return SqlRows.list(jdbc.sql(sql + " AND status = ? ORDER BY \"createdAt\" DESC LIMIT 200")
                    .params(org(), status));
        }
        return SqlRows.list(jdbc.sql(sql + " ORDER BY \"createdAt\" DESC LIMIT 200").param(org()));
    }

    /** Apply or drop a held change after the decision. Idempotent: the outbox delivers at least once. */
    @Transactional
    public void onDecision(String changeId, boolean approved, String deciderId) {
        Map<String, Object> change = SqlRows.one(jdbc.sql("SELECT * FROM \"ControlledConfigChange\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(changeId, org()));
        if (change == null || !"pending".equals(change.get("status"))) {
            return;
        }
        String type = (String) change.get("changeType");
        String targetId = (String) change.get("targetId");
        if (approved) {
            ConfigChangeApplier applier = appliers.stream().filter(a -> a.supports(type)).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("No applier for change type " + type));
            applier.apply(type, targetId, payload(change.get("payload")));
        }
        jdbc.sql("UPDATE \"ControlledConfigChange\" SET status = ?, \"decidedBy\" = ?, \"decidedAt\" = " + NOW + ","
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(approved ? "applied" : "rejected", deciderId, changeId, org()).update();
        audit.recordInTx(AuditInput.of("ControlledConfigChange", changeId, approved ? "approve" : "reject", null,
                Obj.of("changeType", type, "targetId", targetId)));
    }

    /** The user who cleared the request, for the subscriber. */
    @Transactional(readOnly = true)
    public String lastApprover(String requestId) {
        return jdbc.sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? AND"
                        + " \"organizationId\" = ? ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(requestId, org()).query(String.class).optional().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Object json) {
        if (json instanceof JsonNode node) {
            return mapper.convertValue(node, LinkedHashMap.class);
        }
        return Map.of();
    }
}
