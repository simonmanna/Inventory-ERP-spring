package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Responsible-person / approved-by proofs (port of {@code staff-attribution.ts}): attribution ids must be
 * active users of the caller's organization, and an immediately-posting direct stock movement needs a
 * provable approver (self-approval with the permission, or a named approver's override PIN / password).
 */
@Component
public class StaffAttribution {

    /** Permission an approver of an immediately-posting stock movement must hold. */
    public static final String DIRECT_STOCK_APPROVER_PERMISSION = "inventory_doc:approve";

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 10 * 60_000L;

    private final JdbcClient jdbc;
    private final PasswordService passwords;
    private final TransactionTemplate newTx;

    public StaffAttribution(JdbcClient jdbc, PasswordService passwords, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Every non-null id must be an active user of {@code organizationId} (named in declaration order). */
    public void assertActiveStaff(String organizationId, Map<String, String> ids) {
        Set<String> wanted = new LinkedHashSet<>();
        ids.values().forEach(v -> {
            if (v != null && !v.isEmpty()) {
                wanted.add(v);
            }
        });
        if (wanted.isEmpty()) {
            return;
        }
        Set<String> ok = new HashSet<>(jdbc.sql("SELECT id FROM \"User\" WHERE id IN (:ids) AND \"organizationId\" = :org"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL")
                .param("ids", List.copyOf(wanted)).param("org", organizationId).query(String.class).list());
        List<String> bad = new ArrayList<>();
        ids.forEach((k, v) -> {
            if (v != null && !v.isEmpty() && !ok.contains(v)) {
                bad.add(k);
            }
        });
        if (!bad.isEmpty()) {
            throw ApiException.badRequest(String.join(" and ", bad) + " must be an active user of this organization");
        }
    }

    /** Two-key helper preserving the Nest key order {@code responsibleById, approvedById}. */
    public static Map<String, String> people(String responsibleById, String approvedById) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("responsibleById", responsibleById);
        m.put("approvedById", approvedById);
        return m;
    }

    /** Prove that {@code approvedById} approved an immediately-posting direct stock movement. */
    public void assertDirectStockApproval(String organizationId, String actorUserId, List<String> actorPermissions,
            String approvedById, String approverPin) {
        if (actorUserId != null && actorUserId.equals(approvedById)) {
            if (!actorPermissions.contains(DIRECT_STOCK_APPROVER_PERMISSION)) {
                throw ApiException.forbidden("Self-approval of a direct stock movement requires "
                        + DIRECT_STOCK_APPROVER_PERMISSION + "; name an approver and enter their PIN");
            }
            return;
        }
        // Prisma drops an undefined id from the where clause: a missing approvedById matches any active user.
        List<Map<String, Object>> rows = jdbc.sql("SELECT id, email, \"passwordHash\", \"pinHash\" FROM \"User\""
                        + " WHERE \"organizationId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL"
                        + (approvedById != null ? " AND id = ?" : "") + " LIMIT 1")
                .params(approvedById != null ? List.of(organizationId, approvedById) : List.of(organizationId))
                .query().listOfRows();
        if (rows.isEmpty()) {
            throw ApiException.badRequest("approvedById must be an active user of this organization");
        }
        Map<String, Object> approver = rows.getFirst();
        List<String> perms = jdbc.sql("SELECT DISTINCT unnest(r.permissions) FROM \"Role\" r JOIN \"_UserRoles\" ur"
                        + " ON ur.\"A\" = r.id WHERE ur.\"B\" = ?")
                .param(approver.get("id")).query(String.class).list();
        if (!perms.contains(DIRECT_STOCK_APPROVER_PERMISSION)) {
            throw ApiException.forbidden("Approver does not hold " + DIRECT_STOCK_APPROVER_PERMISSION);
        }
        String pin = approverPin == null ? "" : approverPin;
        if (pin.isEmpty()) {
            throw ApiException.badRequest("The approver's PIN is required to post a direct stock movement");
        }
        String email = (String) approver.get("email");
        long recent = jdbc.sql("SELECT count(*) FROM \"LoginAttempt\" WHERE \"organizationId\" = ? AND email = ?"
                        + " AND success = false AND \"createdAt\" >= ? AND reason IN"
                        + " ('override_pin_invalid', 'override_password_invalid')")
                .params(organizationId, email,
                        com.inventoryerp.kernel.crud.SqlRows.ts(Instant.now().minusMillis(LOCKOUT_MS)))
                .query(Long.class).single();
        if (recent >= MAX_FAILED_ATTEMPTS) {
            throw ApiException.unauthorized(
                    "Approvals for this user are temporarily locked after repeated failed attempts");
        }
        boolean byPassword = pin.startsWith("password:");
        String hash = (String) (byPassword ? approver.get("passwordHash") : approver.get("pinHash"));
        if (hash == null) {
            throw ApiException.badRequest("Approver has not set an override PIN");
        }
        if (!passwords.compare(byPassword ? pin.substring(9) : pin, hash)) {
            String reason = byPassword ? "override_password_invalid" : "override_pin_invalid";
            try {
                newTx.executeWithoutResult(s -> jdbc.sql("INSERT INTO \"LoginAttempt\" (id, \"organizationId\", email,"
                                + " success, reason, \"createdAt\") VALUES (?, ?, ?, false, ?, ?)")
                        .params(UUID.randomUUID().toString(), organizationId, email, reason,
                                com.inventoryerp.kernel.crud.SqlRows.ts(Instant.now()))
                        .update());
            } catch (RuntimeException ignored) {
                // best effort, like the Nest .catch(() => undefined)
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid approver credentials");
        }
    }
}
