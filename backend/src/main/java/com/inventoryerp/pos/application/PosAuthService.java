package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.JwtTokenService;
import com.inventoryerp.kernel.security.PosEligibility;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** POS PIN authentication (port of {@code pos-auth.service.ts}). */
@Service
public class PosAuthService {

    private static final Logger log = LoggerFactory.getLogger(PosAuthService.class);
    private static final int MAX_FAILED_ATTEMPTS = 10;
    private static final long LOCKOUT_DURATION_MS = 15 * 60 * 1000L;

    private final JdbcClient jdbc;
    private final PasswordService password;
    private final AuditService audit;
    private final JwtTokenService jwt;
    private final EventBus events;
    private final TransactionTemplate tx;

    public PosAuthService(JdbcClient jdbc, PasswordService password, AuditService audit, JwtTokenService jwt,
            EventBus events, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.password = password;
        this.audit = audit;
        this.jwt = jwt;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
    }

    /** Active staff eligible for the tills. */
    public List<Map<String, Object>> listStaff() {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT u.id, u.\"firstName\", u.\"lastName\", u.email,"
                + " u.\"pinHash\" IS NOT NULL AS \"hasPin\", ARRAY(SELECT r.name FROM \"_UserRoles\" ur JOIN \"Role\" r"
                + " ON r.id = ur.\"A\" WHERE ur.\"B\" = u.id ORDER BY r.id) AS roles FROM \"User\" u"
                + " WHERE u.\"organizationId\" = ? AND u.\"isActive\" = true AND u.\"deletedAt\" IS NULL AND "
                + PosEligibility.ELIGIBLE_USER_SQL + " ORDER BY u.id").param(org()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(Obj.of("id", r.get("id"), "firstName", r.get("firstName"), "lastName", r.get("lastName"),
                    "email", r.get("email"), "hasPin", r.get("hasPin"), "roles", r.get("roles")));
        }
        return out;
    }

    /** Verify the POS PIN and mint a POS token. */
    public Map<String, Object> pinLogin(String userId, String pin) {
        String organizationId = org();
        Map<String, Object> user = SqlRows.one(jdbc.sql("SELECT u.*, e.\"employmentStatus\"::text AS \"empStatus\","
                + " e.\"deletedAt\" AS \"empDeletedAt\", e.id AS \"empId\" FROM \"User\" u LEFT JOIN \"HrEmployee\" e"
                + " ON e.\"userId\" = u.id WHERE u.id = ? AND u.\"organizationId\" = ? AND u.\"isActive\" = true"
                + " AND u.\"deletedAt\" IS NULL LIMIT 1").params(userId, organizationId));
        if (user == null) {
            throw ApiException.notFound("Staff not found");
        }
        PosEligibility.Employee emp = user.get("empId") == null ? null
                : new PosEligibility.Employee((String) user.get("empStatus"), (Instant) user.get("empDeletedAt"));
        if (PosEligibility.employmentBlocksPos(emp)) {
            throw ApiException.unauthorized("Your employment is not active. Ask a manager.");
        }
        Instant lockedUntil = (Instant) user.get("lockedUntil");
        if (lockedUntil != null && Instant.now().isBefore(lockedUntil)) {
            long remaining = (long) Math.ceil((lockedUntil.toEpochMilli() - System.currentTimeMillis()) / 1000.0 / 60);
            throw ApiException.unauthorized("Account locked. Try again in " + remaining + " min");
        }
        String pinHash = (String) user.get("pinHash");
        if (pinHash == null) {
            throw ApiException.unauthorized("No POS PIN set. Ask a manager to set one.");
        }
        if (!password.compare(pin, pinHash)) {
            recordFailedAttempt(user);
            throw ApiException.unauthorized("Invalid PIN");
        }
        jdbc.sql("UPDATE \"User\" SET \"failedLoginCount\" = 0, \"lockedUntil\" = NULL, \"lastLoginAt\" = " + NOW
                + ", \"updatedAt\" = " + NOW + " WHERE id = ?").param(user.get("id")).update();
        String email = (String) user.get("email");
        recordAttempt(organizationId, email, true, null);
        List<String> permissions = jdbc.sql("SELECT p FROM (SELECT DISTINCT ON (p) p, ord FROM (SELECT p, row_number()"
                        + " OVER () AS ord FROM \"_UserRoles\" ur JOIN \"Role\" r ON r.id = ur.\"A\","
                        + " unnest(r.permissions) p WHERE ur.\"B\" = ?) s ORDER BY p, ord) d ORDER BY ord")
                .param(user.get("id")).query(String.class).list();
        String posToken = jwt.signPos((String) user.get("id"), organizationId, email);
        audit.record(AuditInput.of("User", (String) user.get("id"), "login", null, Obj.of("posLogin", true)));
        events.publish(DomainEvents.POS_PIN_LOGIN, Obj.of("userId", user.get("id"), "organizationId", organizationId,
                "at", JsonConfig.toJsIso(Instant.now()), "deviceId", null));
        return Obj.of("userId", user.get("id"), "firstName", user.get("firstName"), "lastName", user.get("lastName"),
                "email", email, "permissions", permissions, "posToken", posToken);
    }

    /** The cashier left the terminal. */
    public Map<String, Object> logoff(String userId) {
        events.publish(DomainEvents.POS_PIN_LOGOFF, Obj.of("userId", userId, "organizationId", org(),
                "at", JsonConfig.toJsIso(Instant.now()), "deviceId", null));
        return Obj.of("ok", true);
    }

    private void recordFailedAttempt(Map<String, Object> user) {
        int next = PosSql.intOf(user.get("failedLoginCount")) + 1;
        boolean locked = next >= MAX_FAILED_ATTEMPTS;
        if (locked) {
            jdbc.sql("UPDATE \"User\" SET \"failedLoginCount\" = ?, \"lockedUntil\" = ?, \"updatedAt\" = " + NOW
                            + " WHERE id = ?")
                    .params(next, SqlRows.ts(Instant.now().plusMillis(LOCKOUT_DURATION_MS)), user.get("id")).update();
        } else {
            jdbc.sql("UPDATE \"User\" SET \"failedLoginCount\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(next, user.get("id")).update();
        }
        recordAttempt((String) user.get("organizationId"), (String) user.get("email"), false,
                locked ? "account_locked" : "invalid_pin");
        if (locked) {
            log.warn("User POS PIN locked after {} failed attempts", next);
        }
    }

    private void recordAttempt(String organizationId, String email, boolean success, String reason) {
        try {
            jdbc.sql("INSERT INTO \"LoginAttempt\" (id, \"organizationId\", email, success, reason, \"createdAt\")"
                            + " VALUES (?, ?, ?, ?, ?, " + NOW + ")")
                    .params(UUID.randomUUID().toString(), organizationId, email, success, reason).update();
        } catch (RuntimeException ignored) {
            // never fail the request because of logging
        }
    }

    private Map<String, Object> self(String userId) {
        Map<String, Object> user = SqlRows.one(jdbc.sql("SELECT * FROM \"User\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL LIMIT 1").params(userId, org()));
        if (user == null) {
            throw ApiException.notFound("User not found");
        }
        return user;
    }

    public void changePin(String userId, String currentPin, String newPin) {
        Map<String, Object> user = self(userId);
        String pinHash = (String) user.get("pinHash");
        if (pinHash == null) {
            throw ApiException.badRequest("No POS PIN set. Use the PIN login screen to set one first.");
        }
        if (!password.compare(currentPin, pinHash)) {
            throw ApiException.badRequest("Current PIN is incorrect");
        }
        if (currentPin.equals(newPin)) {
            throw ApiException.badRequest("New PIN must be different from the current PIN");
        }
        jdbc.sql("UPDATE \"User\" SET \"pinHash\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(password.hash(newPin), userId).update();
        audit.record(AuditInput.of("User", userId, "update", null, Obj.of("posPinChanged", true)));
        events.publish("user.pos_pin_changed", Obj.of("userId", userId, "organizationId", user.get("organizationId"),
                "at", JsonConfig.toJsIso(Instant.now())));
    }

    public void changePassword(String userId, String currentPassword, String newPassword) {
        Map<String, Object> user = self(userId);
        if (currentPassword == null || currentPassword.isEmpty()) {
            throw ApiException.badRequest("Your current login password is required");
        }
        if (!password.compare(currentPassword, (String) user.get("passwordHash"))) {
            throw ApiException.badRequest("Current password is incorrect");
        }
        if (currentPassword.equals(newPassword)) {
            throw ApiException.badRequest("New password must be different from the current password");
        }
        String hash = password.hash(newPassword);
        tx.executeWithoutResult(st -> {
            jdbc.sql("UPDATE \"User\" SET \"passwordHash\" = ?, \"failedLoginCount\" = 0, \"lockedUntil\" = NULL,"
                    + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(hash, userId).update();
            jdbc.sql("UPDATE \"RefreshToken\" SET \"revokedAt\" = " + NOW + " WHERE \"userId\" = ? AND \"revokedAt\" IS NULL"
                    + " AND \"organizationId\" = ?").params(userId, org()).update();
        });
        audit.record(AuditInput.of("User", userId, "update", null, Obj.of("passwordChanged", true, "via", "pos")));
        events.publish("user.password_changed", Obj.of("userId", userId, "organizationId", user.get("organizationId"),
                "at", JsonConfig.toJsIso(Instant.now())));
    }
}
