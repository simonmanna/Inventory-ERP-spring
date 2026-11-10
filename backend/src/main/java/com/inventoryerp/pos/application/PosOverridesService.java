package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.auth.CredentialGuard;
import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Manager override by PIN / password (port of {@code pos-overrides.service.ts}). An approval is bound to what it
 * authorises: each override kind names the right the approver must also hold, on top of {@code pos:override}.
 * Failed credential attempts lock a manager's overrides for 10 minutes after 5 failures (A-005).
 *
 * <p>Post-parity hardening (owner-approved 2026-11-10): the lockout is counted on {@link CredentialGuard#MANAGER_PIN}
 * (one atomic upsert before the compare, in its own transaction). Nest and the first port counted {@code LoginAttempt}
 * rows, which parallel guesses raced past and which a rolled-back void / cancel transaction erased, so the lockout never
 * engaged on those routes. {@code LoginAttempt} rows are still written as the audit trail.
 */
@Service
public class PosOverridesService {

    /** Approver right per override kind ({@code APPROVER_PERMISSION}). */
    public static final Map<String, String> APPROVER_PERMISSION = Map.of(
            "discount", "pos:discount",
            "price_change", "pos:price_override",
            "void", "pos:void",
            "manual_refund", "pos:refund",
            "write_off", "pos:write_off",
            "shift_handover", "pos:override");

    private static final String PIN_ONLY_SENTINEL = "pin-only-override";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A user row with the union of its roles' permissions. */
    public record Manager(String id, String email, String firstName, String lastName, String passwordHash,
            String pinHash, Set<String> permissions) {}

    private final JdbcClient jdbc;
    private final PasswordService password;
    private final AuditService audit;
    private final EventBus events;
    private final CredentialGuard guard;
    private final com.inventoryerp.kernel.common.DetachedWrites detached;

    public PosOverridesService(JdbcClient jdbc, PasswordService password, AuditService audit, EventBus events,
            CredentialGuard guard, com.inventoryerp.kernel.common.DetachedWrites detached) {
        this.guard = guard;
        this.detached = detached;
        this.jdbc = jdbc;
        this.password = password;
        this.audit = audit;
        this.events = events;
    }

    /** {@code POST /pos/operation-approval}: verify the manager and mint a single-operation grant. */
    public Map<String, Object> authorizeOperation(String managerId, String pin, String operationKey, String endpoint,
            Object payload, String overrideKindIn) {
        if (endpoint == null || !endpoint.startsWith("/pos/") || operationKey == null || operationKey.isEmpty()) {
            throw ApiException.badRequest("A POS operation and key are required");
        }
        String overrideKind = overrideKindIn == null ? "discount" : overrideKindIn;
        if (!APPROVER_PERMISSION.containsKey(overrideKind)) {
            throw ApiException.badRequest("Unknown override kind");
        }
        verifyPinForOverride(managerId, pin, overrideKind);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        jdbc.sql("INSERT INTO \"PosApprovalGrant\" (id, \"organizationId\", \"tokenHash\", \"cashierId\", \"managerId\","
                        + " \"operationKey\", endpoint, \"overrideKind\", \"payloadHash\", \"expiresAt\", \"createdAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ")")
                .params(UUID.randomUUID().toString(), org(), sha256(token), user(), managerId, operationKey, endpoint,
                        overrideKind, BusinessOutcome.approvalPayloadHash(payload),
                        SqlRows.ts(Instant.now().plusMillis(10 * 60_000L)))
                .update();
        return Obj.of("approvalToken", token);
    }

    /**
     * Consume an approval for {@code overrideKind}. A grant redeemed earlier in this request is only accepted when
     * it was issued for this kind.
     */
    public Manager verifyOperationApproval(String managerId, String pin, String overrideKind) {
        BusinessOutcome.Operation op = BusinessOutcome.current();
        if (op != null && managerId != null && managerId.equals(op.approvedById())) {
            if (!overrideKind.equals(op.approvedKind())) {
                throw ApiException.badRequest("This manager approval authorises "
                        + (op.approvedKind() == null ? "another action" : op.approvedKind()) + ", not " + overrideKind);
            }
            return assertCanOverride(managerId, overrideKind);
        }
        if (pin == null || pin.isEmpty()) {
            throw ApiException.badRequest("A current, transaction-bound manager approval is required");
        }
        return verifyPinForOverride(managerId, pin, overrideKind);
    }

    /** Manager sets (or changes) their override PIN. */
    public Map<String, Object> setPin(String userId, String pin) {
        if (pin == null || pin.length() < 4 || pin.length() > 8 || !pin.matches("\\d+")) {
            throw ApiException.badRequest("PIN must be 4–8 digits");
        }
        String hash = password.hash(pin);
        int n = jdbc.sql("UPDATE \"User\" SET \"pinHash\" = ?, \"pinHashRounds\" = 10, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(hash, userId, org()).update();
        if (n == 0) {
            throw new IllegalStateException("No record was found for an update.");
        }
        audit.record(AuditInput.of("User", userId, "update", null, Obj.of("pinSet", true)));
        return Obj.of("ok", true);
    }

    /** {@code POST /pos/override/verify}: verify a manager's credentials. */
    public Map<String, Object> verify(String email, String pin, String pwd, String overrideKind) {
        if (empty(pin) && empty(pwd)) {
            throw ApiException.badRequest("pin or password is required");
        }
        if (empty(email)) {
            if (!empty(pwd)) {
                throw ApiException.badRequest("email is required to verify a manager password");
            }
            return verifyByPinOnly(pin, overrideKind);
        }
        String orgId = org();
        Manager manager = findManager("u.\"organizationId\" = ? AND u.email = ? AND u.\"isActive\" = true",
                orgId, email.toLowerCase());
        if (manager == null) {
            throw ApiException.notFound("Manager not found");
        }
        assertOverrideNotLocked(manager.id(), manager.email());
        boolean ok = false;
        String failedReason = "override_pin_invalid";
        if (!empty(pin) && manager.pinHash() != null) {
            countAttempt(manager.id());
            ok = password.compare(pin, manager.pinHash());
        } else if (!empty(pwd)) {
            countAttempt(manager.id());
            ok = password.compare(pwd, manager.passwordHash());
            failedReason = "override_password_invalid";
        } else if (!empty(pin) && manager.pinHash() == null) {
            throw ApiException.badRequest("Manager has not set a PIN; use their password");
        }
        if (!ok) {
            recordOverrideFailure(manager, failedReason);
            throw ApiException.unauthorized("Invalid credentials");
        }
        clearOverrideFailures(manager.id());
        audit.record(AuditInput.of("User", manager.id(), "login", null,
                Obj.of("overrideVerified", true, "overrideKind", overrideKind)));
        events.publish(DomainEvents.POS_OVERRIDE_APPROVED, Obj.of("organizationId", orgId, "approverId", manager.id(),
                "overrideKind", overrideKind));
        return result(manager, overrideKind);
    }

    private Map<String, Object> result(Manager manager, String overrideKind) {
        return Obj.of("managerId", manager.id(),
                "managerName", manager.firstName() + (empty(manager.lastName()) ? "" : " " + manager.lastName()),
                "managerEmail", manager.email(), "overrideKind", overrideKind);
    }

    private Map<String, Object> verifyByPinOnly(String pin, String overrideKind) {
        String orgId = org();
        String required = APPROVER_PERMISSION.get(overrideKind);
        if (required == null) {
            throw ApiException.badRequest("Unknown override kind");
        }
        assertPinOnlyNotLocked();
        countAttempt(PIN_ONLY_SENTINEL);
        List<Manager> candidates = findManagers("u.\"organizationId\" = ? AND u.\"isActive\" = true"
                + " AND u.\"pinHash\" IS NOT NULL ORDER BY u.id", orgId);
        List<Manager> matches = new ArrayList<>();
        for (Manager u : candidates) {
            if (u.permissions().contains("pos:override") && u.permissions().contains(required)
                    && password.compare(pin, u.pinHash())) {
                matches.add(u);
            }
        }
        if (matches.isEmpty()) {
            recordPinOnlyFailure();
            throw ApiException.unauthorized("Invalid override PIN");
        }
        if (matches.size() > 1) {
            throw ApiException.badRequest("Two managers share this PIN — enter the manager email to identify them");
        }
        Manager manager = matches.getFirst();
        clearOverrideFailures(manager.id());
        clearOverrideFailures(PIN_ONLY_SENTINEL);
        audit.record(AuditInput.of("User", manager.id(), "login", null,
                Obj.of("overrideVerified", true, "overrideKind", overrideKind, "viaPinOnly", true)));
        events.publish(DomainEvents.POS_OVERRIDE_APPROVED, Obj.of("organizationId", orgId, "approverId", manager.id(),
                "overrideKind", overrideKind));
        return result(manager, overrideKind);
    }

    private static final String LOCKED_MESSAGE =
            "Manager overrides are temporarily locked after repeated failed attempts. Try again in a few minutes.";

    private void assertPinOnlyNotLocked() {
        if (guard.lockedFor(org(), CredentialGuard.MANAGER_PIN, PIN_ONLY_SENTINEL).isPresent()) {
            safeAudit(PIN_ONLY_SENTINEL, CredentialGuard.MANAGER_PIN.maxAttempts());
            throw ApiException.unauthorized(LOCKED_MESSAGE);
        }
    }

    private void assertOverrideNotLocked(String managerId, String email) {
        if (guard.lockedFor(org(), CredentialGuard.MANAGER_PIN, managerId).isPresent()) {
            safeAudit(managerId, CredentialGuard.MANAGER_PIN.maxAttempts());
            throw ApiException.unauthorized(LOCKED_MESSAGE);
        }
    }

    /** Count one credential attempt for {@code subject} before comparing; throws when the subject is locked. */
    private void countAttempt(String subject) {
        if (guard.attempt(org(), CredentialGuard.MANAGER_PIN, subject).locked()) {
            safeAudit(subject, CredentialGuard.MANAGER_PIN.maxAttempts());
            throw ApiException.unauthorized(LOCKED_MESSAGE);
        }
    }

    private void safeAudit(String entityId, long recent) {
        try {
            audit.record(AuditInput.of("User", entityId, "login", null,
                    Obj.of("overrideLocked", true, "recentFailedAttempts", recent)));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** Audit row of a failed attempt; detached so a rolled-back business transaction cannot erase it. */
    private void insertAttempt(String email, String reason) {
        String orgId = org();
        detached.submit(() -> {
            try {
                jdbc.sql("INSERT INTO \"LoginAttempt\" (id, \"organizationId\", email, success, reason, \"createdAt\")"
                                + " VALUES (?, ?, ?, false, ?, " + NOW + ")")
                        .params(UUID.randomUUID().toString(), orgId, email, reason).update();
            } catch (RuntimeException ignored) {
                // logging must never break the rejection
            }
        });
    }

    private void recordPinOnlyFailure() {
        insertAttempt(PIN_ONLY_SENTINEL, "override_pin_invalid");
    }

    private void recordOverrideFailure(Manager manager, String reason) {
        insertAttempt(manager.email(), reason);
        try {
            events.publish("pos.override.lockout_warning", Obj.of("organizationId", org(), "managerId", manager.id(),
                    "locked", guard.lockedFor(org(), CredentialGuard.MANAGER_PIN, manager.id()).isPresent()));
        } catch (RuntimeException ignored) {
            // logging must never break the rejection
        }
    }

    private void clearOverrideFailures(String subject) {
        try {
            guard.succeeded(org(), CredentialGuard.MANAGER_PIN, subject);
        } catch (RuntimeException ignored) {
            // best-effort bookkeeping
        }
    }

    /** The approver exists, is active and holds {@code pos:override} plus the kind's right. */
    public Manager assertCanOverride(String overrideById, String overrideKind) {
        String required = APPROVER_PERMISSION.get(overrideKind);
        if (required == null) {
            throw ApiException.badRequest("Unknown override kind");
        }
        Manager manager = overrideById == null ? null
                : findManager("u.id = ? AND u.\"organizationId\" = ? AND u.\"isActive\" = true", overrideById, org());
        if (manager == null) {
            throw ApiException.notFound("Override approver not found");
        }
        if (!manager.permissions().contains("pos:override")) {
            throw ApiException.unauthorized("Approver does not hold pos:override permission");
        }
        if (!manager.permissions().contains(required)) {
            throw ApiException.unauthorized("Approver does not hold " + required + " and cannot authorise " + overrideKind);
        }
        return manager;
    }

    /** Re-verify a manager's PIN (or {@code password:<pwd>}) at consume time (F-OVR). */
    public Manager verifyPinForOverride(String overrideById, String pin, String overrideKind) {
        Manager manager = assertCanOverride(overrideById, overrideKind == null ? "discount" : overrideKind);
        assertOverrideNotLocked(manager.id(), manager.email());
        if (pin.startsWith("password:")) {
            countAttempt(manager.id());
            if (!password.compare(pin.substring(9), manager.passwordHash())) {
                recordOverrideFailure(manager, "override_password_invalid");
                throw ApiException.unauthorized("Invalid manager credentials");
            }
            clearOverrideFailures(manager.id());
            return manager;
        }
        if (manager.pinHash() == null) {
            throw ApiException.badRequest("Manager has not set an override PIN");
        }
        countAttempt(manager.id());
        if (!password.compare(pin, manager.pinHash())) {
            recordOverrideFailure(manager, "override_pin_invalid");
            throw ApiException.unauthorized("Invalid override PIN");
        }
        clearOverrideFailures(manager.id());
        return manager;
    }

    /** The signed-in user verifies their own PIN (e.g. to delete a cart item). */
    public Map<String, Object> verifyCurrentUserPin(String userId, String pin) {
        Map<String, Object> u = SqlRows.one(jdbc.sql("SELECT \"pinHash\" FROM \"User\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(userId, org()));
        if (u == null) {
            throw ApiException.notFound("User not found");
        }
        String pinHash = (String) u.get("pinHash");
        if (pinHash == null) {
            throw ApiException.badRequest("You have not set a PIN. Set one in your profile.");
        }
        String orgId = org();
        java.util.Optional<java.time.Duration> held = guard.lockedFor(orgId, CredentialGuard.POS_PIN, userId);
        CredentialGuard.Attempt attempt = held.isPresent() ? new CredentialGuard.Attempt(true, held.get())
                : guard.attempt(orgId, CredentialGuard.POS_PIN, userId);
        if (attempt.locked()) {
            throw ApiException.unauthorized("Account locked. Try again in " + CredentialGuard.minutes(attempt.remaining())
                    + " min");
        }
        if (pin == null || !password.compare(pin, pinHash)) {
            throw ApiException.unauthorized("Invalid PIN");
        }
        guard.succeeded(orgId, CredentialGuard.POS_PIN, userId);
        audit.record(AuditInput.of("User", userId, "login", null,
                Obj.of("pinVerified", true, "purpose", "delete_item")));
        return Obj.of("ok", true);
    }

    private Manager findManager(String where, Object... params) {
        List<Manager> list = findManagers(where + " LIMIT 1", params);
        return list.isEmpty() ? null : list.getFirst();
    }

    private List<Manager> findManagers(String where, Object... params) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT u.id, u.email, u.\"firstName\", u.\"lastName\","
                + " u.\"passwordHash\", u.\"pinHash\", ARRAY(SELECT DISTINCT p FROM \"_UserRoles\" ur JOIN \"Role\" r"
                + " ON r.id = ur.\"A\", unnest(r.permissions) p WHERE ur.\"B\" = u.id) AS perms FROM \"User\" u WHERE "
                + where).params(params));
        List<Manager> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Set<String> perms = new LinkedHashSet<>(PosSql.strings(r.get("perms")));
            out.add(new Manager((String) r.get("id"), (String) r.get("email"), (String) r.get("firstName"),
                    (String) r.get("lastName"), (String) r.get("passwordHash"), (String) r.get("pinHash"), perms));
        }
        return out;
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
