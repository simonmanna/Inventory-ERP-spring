package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction limits: the most a user, or a holder of a permission, may approve (CTRL-1). A limit applies when its
 * document type, branch and department are empty or match. A user's own rows win over permission rows and the highest
 * applicable limit counts. Someone no limit applies to keeps the old behaviour: no cap. A rejection is never limited.
 */
@Service
public class ApprovalLimitService {

    /** Create or full update body. Exactly one of {@code userId} and {@code permission} is set. */
    public record LimitInput(String name, String entityType, String userId, String permission, String branchId,
            String departmentId, BigDecimal maxAmount, Boolean isActive) {}

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ApprovalLimitService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ApprovalLimit\" WHERE \"organizationId\" = ?"
                + " ORDER BY name, \"createdAt\"").param(org()));
    }

    /** One limit, or a 404. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return require(id);
    }

    @Transactional
    public Map<String, Object> create(LimitInput in) {
        validate(in);
        String id = UUID.randomUUID().toString();
        Map<String, Object> row = SqlRows.one(jdbc.sql("INSERT INTO \"ApprovalLimit\" (id, \"organizationId\", name,"
                        + " \"entityType\", \"userId\", permission, \"branchId\", \"departmentId\", \"maxAmount\","
                        + " \"isActive\", \"createdBy\", \"updatedBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ") RETURNING *")
                .params(id, org(), in.name().trim(), blank(in.entityType()), blank(in.userId()), blank(in.permission()),
                        blank(in.branchId()), blank(in.departmentId()), in.maxAmount(), in.isActive() == null
                                || in.isActive(), TenantContext.userId(), TenantContext.userId()));
        audit.recordInTx(AuditInput.of("ApprovalLimit", id, "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> update(String id, LimitInput in) {
        validate(in);
        Map<String, Object> before = require(id);
        Map<String, Object> row = SqlRows.one(jdbc.sql("UPDATE \"ApprovalLimit\" SET name = ?, \"entityType\" = ?,"
                        + " \"userId\" = ?, permission = ?, \"branchId\" = ?, \"departmentId\" = ?, \"maxAmount\" = ?,"
                        + " \"isActive\" = ?, \"updatedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                        + " \"organizationId\" = ? RETURNING *")
                .params(in.name().trim(), blank(in.entityType()), blank(in.userId()), blank(in.permission()),
                        blank(in.branchId()), blank(in.departmentId()), in.maxAmount(), in.isActive() == null
                                || in.isActive(), TenantContext.userId(), id, org()));
        audit.recordInTx(AuditInput.of("ApprovalLimit", id, "update", before, row));
        return row;
    }

    @Transactional
    public void remove(String id) {
        Map<String, Object> before = require(id);
        jdbc.sql("DELETE FROM \"ApprovalLimit\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("ApprovalLimit", id, "delete", before, null));
    }

    /** Refuse an approval above the approver's limit; the step stays open for someone with a higher one. */
    public void assertWithin(String entityType, double amount, String branchId, String departmentId, String userId,
            List<String> permissions) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM \"ApprovalLimit\" WHERE"
                + " \"organizationId\" = ? AND \"isActive\" = TRUE").param(org()));
        List<BigDecimal> own = new ArrayList<>();
        List<BigDecimal> held = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!same(r.get("entityType"), entityType) || !same(r.get("branchId"), branchId)
                    || !same(r.get("departmentId"), departmentId)) {
                continue;
            }
            if (userId.equals(r.get("userId"))) {
                own.add(SqlRows.dec(r, "maxAmount"));
            } else if (r.get("permission") != null && permissions.contains(r.get("permission"))) {
                held.add(SqlRows.dec(r, "maxAmount"));
            }
        }
        List<BigDecimal> applicable = own.isEmpty() ? held : own;
        if (applicable.isEmpty()) {
            return;
        }
        BigDecimal limit = applicable.stream().max(BigDecimal::compareTo).orElseThrow();
        if (BigDecimal.valueOf(amount).compareTo(limit) > 0) {
            throw ApiException.badRequest("This amount is above your approval limit of "
                    + limit.stripTrailingZeros().toPlainString());
        }
    }

    /** A limit's condition holds when it is empty or equal to the document's value. */
    private static boolean same(Object condition, String value) {
        return condition == null || condition.equals(value);
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"ApprovalLimit\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (row == null) {
            throw ApiException.notFound("Approval limit not found");
        }
        return row;
    }

    /** Refuse a limit with neither or both subjects, before anything is held for approval. */
    public void assertValid(LimitInput in) {
        validate(in);
    }

    private static void validate(LimitInput in) {
        if ((blank(in.userId()) == null) == (blank(in.permission()) == null)) {
            throw ApiException.badRequest("Give a limit either a user or a permission");
        }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Body of a limit as stored in a controlled change. */
    static Map<String, Object> payload(LimitInput in) {
        return Obj.of("name", in.name(), "entityType", in.entityType(), "userId", in.userId(), "permission",
                in.permission(), "branchId", in.branchId(), "departmentId", in.departmentId(), "maxAmount",
                in.maxAmount(), "isActive", in.isActive());
    }
}
