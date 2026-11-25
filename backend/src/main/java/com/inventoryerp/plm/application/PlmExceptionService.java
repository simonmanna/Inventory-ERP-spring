package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.plm.application.PlmCommands.GrantException;
import com.inventoryerp.plm.application.PlmCommands.RevokeException;
import com.inventoryerp.plm.domain.PlmLifecycleException;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorized exceptions to the lifecycle policy (ADR-S086 §6, owner decision 2026-11-24): a holder of
 * {@code plm:exception} allows one activity (purchase, produce, sell, pos, replenish) for one product under PLM, with a
 * reason, up to a last valid day at most a year ahead. The guard honours an exception while it is in force; revoking
 * ends it at once. Rows are never deleted: they are the audit trail (who granted, why, until when, who revoked).
 */
@Service
public class PlmExceptionService {

    /** The longest an exception may last, in days from today on the organization's calendar. */
    static final int MAX_DAYS = 366;

    private final PlmDb db;
    private final PlmSupport plm;

    public PlmExceptionService(PlmDb db, PlmSupport plm) {
        this.db = db;
        this.plm = plm;
    }

    /** Exceptions, newest first: of one product or of all; {@code active} keeps the ones in force today. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String productId, Boolean active) {
        StringBuilder sql = new StringBuilder("SELECT x.*, p.code AS \"productCode\", p.name AS \"productName\""
                + " FROM \"PlmLifecycleException\" x JOIN \"Product\" p ON p.id = x.\"productId\" WHERE"
                + " x.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(PlmDb.org()));
        if (PlmSupport.blank(productId) != null) {
            sql.append(" AND x.\"productId\" = ?");
            params.add(productId);
        }
        LocalDate today = plm.orgToday();
        if (Boolean.TRUE.equals(active)) {
            sql.append(" AND x.\"revokedAt\" IS NULL AND x.\"validUntil\" >= ?");
            params.add(today);
        }
        sql.append(" ORDER BY x.\"createdAt\" DESC, x.id LIMIT 500");
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(sql.toString()).params(params));
        Set<String> users = new HashSet<>();
        rows.forEach(r -> {
            users.add(str(r, "createdBy"));
            users.add(str(r, "revokedBy"));
        });
        Map<String, String> names = plm.userNames(users);
        rows.forEach(r -> decorate(r, names, today));
        return rows;
    }

    /** Grant an exception; refused while another one for the same product and activity is in force. */
    @Transactional
    public Map<String, Object> grant(GrantException dto) {
        Map<String, Object> product = SqlRows.one(db.jdbc().sql("SELECT p.id, p.code, i.\"lifecycleState\" FROM"
                + " \"Product\" p LEFT JOIN \"PlmItem\" i ON i.\"productId\" = p.id AND i.\"organizationId\" ="
                + " p.\"organizationId\" WHERE p.id = ? AND p.\"organizationId\" = ?").params(dto.productId(),
                        PlmDb.org()));
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        if (product.get("lifecycleState") == null) {
            throw ApiException.badRequest("Product " + product.get("code") + " is not under lifecycle management.");
        }
        String reason = PlmSupport.blank(dto.reason());
        if (reason == null) {
            throw ApiException.badRequest("reason should not be empty");
        }
        LocalDate until = LocalDate.parse(dto.validUntil());
        LocalDate today = plm.orgToday();
        if (until.isBefore(today)) {
            throw ApiException.badRequest("validUntil must not be in the past.");
        }
        if (until.isAfter(today.plusDays(MAX_DAYS))) {
            throw ApiException.badRequest("An exception lasts at most a year: validUntil must be on or before "
                    + today.plusDays(MAX_DAYS) + ".");
        }
        // One exception in force per product and activity: concurrent grants serialise here.
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param("plm-exception:" + PlmDb.org()
                + ":" + dto.productId() + ":" + dto.activity()).query().listOfRows();
        LocalDate inForce = db.jdbc().sql("SELECT max(\"validUntil\") FROM \"PlmLifecycleException\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND activity = ? AND \"revokedAt\" IS NULL AND"
                + " \"validUntil\" >= ?").params(PlmDb.org(), dto.productId(), dto.activity(), today)
                .query(LocalDate.class).optional().orElse(null);
        if (inForce != null) {
            throw ApiException.badRequest("An exception for " + label(dto.activity()) + " " + product.get("code")
                    + " is already in force until " + inForce + "; revoke it first.");
        }
        Map<String, Object> row = db.insert(PlmLifecycleException.class, Obj.of("productId", dto.productId(),
                "activity", dto.activity(), "reason", reason, "validUntil", until, "createdBy",
                TenantContext.userId()));
        plm.event(DomainEvents.PLM_LIFECYCLE_EXCEPTION_GRANTED, Obj.of("exceptionId", row.get("id"), "productId",
                dto.productId(), "activity", dto.activity(), "lifecycleState", product.get("lifecycleState"),
                "validUntil", until.toString()));
        return one(str(row, "id"));
    }

    /** Revoke an exception now (it stays as history). */
    @Transactional
    public Map<String, Object> revoke(String id, RevokeException dto) {
        Map<String, Object> row = db.byIdForUpdate(PlmLifecycleException.class, id);
        if (row == null) {
            throw ApiException.notFound("Lifecycle exception not found");
        }
        if (row.get("revokedAt") != null) {
            throw ApiException.badRequest("The exception is already revoked.");
        }
        db.update(PlmLifecycleException.class, id, Obj.of("revokedAt", Instant.now(), "revokedBy",
                TenantContext.userId(), "revokeReason", dto == null ? null : PlmSupport.blank(dto.reason())));
        plm.event(DomainEvents.PLM_LIFECYCLE_EXCEPTION_REVOKED, Obj.of("exceptionId", id, "productId",
                row.get("productId"), "activity", row.get("activity")));
        return one(id);
    }

    private Map<String, Object> one(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql("SELECT x.*, p.code AS \"productCode\", p.name AS"
                + " \"productName\" FROM \"PlmLifecycleException\" x JOIN \"Product\" p ON p.id = x.\"productId\" WHERE"
                + " x.id = ? AND x.\"organizationId\" = ?").params(id, PlmDb.org()));
        Set<String> users = new HashSet<>();
        users.add(str(r, "createdBy"));
        users.add(str(r, "revokedBy"));
        decorate(r, plm.userNames(users), plm.orgToday());
        return r;
    }

    private static void decorate(Map<String, Object> r, Map<String, String> names, LocalDate today) {
        r.put("createdByName", names.get(str(r, "createdBy")));
        r.put("revokedByName", names.get(str(r, "revokedBy")));
        Object until = r.get("validUntil");
        LocalDate last = until instanceof LocalDate d ? d : until == null ? null : LocalDate.parse(until.toString());
        r.put("status", r.get("revokedAt") != null ? "revoked" : last != null && last.isBefore(today) ? "expired"
                : "active");
    }

    /** {@code selling}, {@code purchasing}, ... for messages. */
    static String label(String activity) {
        return switch (activity) {
            case "purchase" -> "purchasing";
            case "produce" -> "producing";
            case "sell" -> "selling";
            case "pos" -> "selling at the point of sale";
            default -> "replenishing";
        };
    }
}
