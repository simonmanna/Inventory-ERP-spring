package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Centralized or delegated purchasing per branch (PROC-3). A branch with no active rule is unrestricted, as before.
 * Once it has one, a purchase order for that branch needs either the central permission or a {@code delegated} rule
 * matching the buyer with a limit that covers the order; a {@code central} rule delegates nothing.
 */
@Service
public class ProcurementDelegationService {

    /** Create or update body. */
    public record DelegationInput(String branchId, String mode, String delegateUserId, BigDecimal maxOrderAmount,
            String validFrom, String validTo, Boolean isActive, String notes) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PermissionService permissions;

    public ProcurementDelegationService(JdbcClient jdbc, AuditService audit, PermissionService permissions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.permissions = permissions;
    }

    /** Refuse the current user when the branch's rules do not let them place this order. */
    public void enforce(String branchId, BigDecimal orderAmount) {
        if (branchId == null || branchId.isEmpty()) {
            return;
        }
        String today = LocalDate.now(BusinessZone.get()).toString();
        List<Map<String, Object>> rules = SqlRows.list(jdbc.sql("SELECT * FROM \"ProcurementDelegation\" WHERE"
                + " \"organizationId\" = ? AND \"branchId\" = ? AND \"isActive\" = TRUE AND (\"validFrom\" IS NULL OR"
                + " \"validFrom\" <= ?::date) AND (\"validTo\" IS NULL OR \"validTo\" >= ?::date)")
                .params(org(), branchId, today, today));
        if (rules.isEmpty() || permissions.current().contains(Permissions.PROCUREMENT__DELEGATION__CENTRAL)) {
            return;
        }
        List<Map<String, Object>> mine = rules.stream().filter(r -> "delegated".equals(r.get("mode")))
                .filter(r -> r.get("delegateUserId") == null || r.get("delegateUserId").equals(user())).toList();
        if (mine.isEmpty()) {
            throw ApiException.forbidden("Purchasing for this branch is handled centrally");
        }
        BigDecimal limit = BigDecimal.ZERO;
        for (Map<String, Object> r : mine) {
            if (r.get("maxOrderAmount") == null) {
                return;
            }
            limit = limit.max(SqlRows.dec(r, "maxOrderAmount"));
        }
        if (orderAmount.compareTo(limit) > 0) {
            throw ApiException.forbidden("This order is above your delegated purchasing limit of "
                    + limit.setScale(2, RoundingMode.HALF_UP).toPlainString() + "; central purchasing must place it");
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String branchId) {
        String sql = "SELECT * FROM \"ProcurementDelegation\" WHERE \"organizationId\" = ?";
        if (branchId != null && !branchId.isBlank()) {
            return SqlRows.list(jdbc.sql(sql + " AND \"branchId\" = ? ORDER BY \"createdAt\" DESC").params(org(),
                    branchId));
        }
        return SqlRows.list(jdbc.sql(sql + " ORDER BY \"createdAt\" DESC").param(org()));
    }

    @Transactional
    public Map<String, Object> create(DelegationInput in) {
        validate(in);
        String id = UUID.randomUUID().toString();
        Map<String, Object> row = SqlRows.one(jdbc.sql("INSERT INTO \"ProcurementDelegation\" (id, \"organizationId\","
                        + " \"branchId\", mode, \"delegateUserId\", \"maxOrderAmount\", \"validFrom\", \"validTo\","
                        + " \"isActive\", notes, \"createdBy\", \"updatedBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?,"
                        + " ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?, ?, " + NOW + ", " + NOW + ") RETURNING *")
                .params(id, org(), in.branchId(), in.mode(), in.delegateUserId(), in.maxOrderAmount(), in.validFrom(),
                        in.validTo(), in.isActive() == null || in.isActive(), in.notes(), user(), user()));
        audit.recordInTx(AuditInput.of("ProcurementDelegation", id, "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> update(String id, DelegationInput in) {
        validate(in);
        Map<String, Object> before = require(id);
        Map<String, Object> row = SqlRows.one(jdbc.sql("UPDATE \"ProcurementDelegation\" SET \"branchId\" = ?, mode = ?,"
                        + " \"delegateUserId\" = ?, \"maxOrderAmount\" = ?, \"validFrom\" = ?::date, \"validTo\" ="
                        + " ?::date, \"isActive\" = ?, notes = ?, \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? RETURNING *")
                .params(in.branchId(), in.mode(), in.delegateUserId(), in.maxOrderAmount(), in.validFrom(), in.validTo(),
                        in.isActive() == null || in.isActive(), in.notes(), user(), id, org()));
        audit.recordInTx(AuditInput.of("ProcurementDelegation", id, "update", before, row));
        return row;
    }

    @Transactional
    public void remove(String id) {
        Map<String, Object> before = require(id);
        jdbc.sql("DELETE FROM \"ProcurementDelegation\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .update();
        audit.recordInTx(AuditInput.of("ProcurementDelegation", id, "delete", before, null));
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"ProcurementDelegation\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (row == null) {
            throw ApiException.notFound("Delegation rule not found");
        }
        return row;
    }

    private static void validate(DelegationInput in) {
        if ("central".equals(in.mode()) && (in.delegateUserId() != null || in.maxOrderAmount() != null)) {
            throw ApiException.badRequest("A central rule has no delegate and no limit");
        }
        if (in.validFrom() != null && in.validTo() != null
                && LocalDate.parse(in.validTo()).isBefore(LocalDate.parse(in.validFrom()))) {
            throw ApiException.badRequest("The end date is before the start date");
        }
    }
}
