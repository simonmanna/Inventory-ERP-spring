package com.inventoryerp.kernel.auth;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.auth.UserAccounts.UserWithRoles;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.JwtTokenService;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Login (with lockout + LoginAttempt audit), MFA, refresh-token rotation, sessions and password change
 * (port of {@code auth.service.ts}). Error messages are verbatim from Nest.
 *
 * <p>Deviations (bug fixes, documented in docs/spec/kernel.md): MFA tokens and enrollments are looked
 * up by their unique key (Nest queried {@code organizationId: ''} and could never find them), and the
 * stored MFA secret is decrypted with its IV/tag columns.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Lockout policy. */
    static final int MAX_FAILED_ATTEMPTS = 10;
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    static final Duration MFA_WINDOW = Duration.ofMinutes(5);

    /** Transport details recorded with sessions and login attempts. */
    public record RequestInfo(String ip, String userAgent, String deviceLabel) {}

    private final JdbcClient jdbc;
    private final UserAccounts users;
    private final JwtTokenService jwt;
    private final PasswordService password;
    private final AuditService audit;
    private final EventBus events;
    private final MfaService mfa;
    private final EncryptionService encryption;
    private final PermissionService permissions;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;

    public AuthService(JdbcClient jdbc, UserAccounts users, JwtTokenService jwt, PasswordService password,
            AuditService audit, EventBus events, MfaService mfa, EncryptionService encryption,
            PermissionService permissions, JsonMapper mapper, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.users = users;
        this.jwt = jwt;
        this.password = password;
        this.audit = audit;
        this.events = events;
        this.mfa = mfa;
        this.encryption = encryption;
        this.permissions = permissions;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
    }

    /** Step 1: password. MFA-enrolled users get {@code {mfaToken, requiresMfa: true}} instead of tokens. */
    public Map<String, Object> login(String organizationCode, String email, String plainPassword, RequestInfo req) {
        record Org(String id, String status) {}
        Org org = jdbc.sql("SELECT id, status::text AS status FROM \"Organization\" WHERE code = ?")
                .param(organizationCode)
                .query((rs, n) -> new Org(rs.getString("id"), rs.getString("status")))
                .optional()
                .orElse(null);
        if (org == null || !"active".equals(org.status())) {
            recordAttempt(null, email, false, false, req.ip(), req.userAgent(), "org_inactive_or_missing");
            throw ApiException.unauthorized("Invalid credentials");
        }
        return TenantContext.run(TenantStore.organization(org.id()), () -> {
            UserWithRoles user = users.byEmail(org.id(), email, true).orElse(null);
            // Lockout check (done even when the user is missing to slow enumeration).
            if (user != null && user.lockedUntil() != null && user.lockedUntil().isAfter(Instant.now())) {
                recordAttempt(org.id(), email, false, false, req.ip(), req.userAgent(), "account_locked");
                throw ApiException.unauthorized("Account temporarily locked; try again later");
            }
            boolean passwordOk = user != null && password.compare(plainPassword, user.passwordHash());
            if (!passwordOk) {
                recordAttempt(org.id(), email, false, false, req.ip(), req.userAgent(), "bad_credentials");
                recordFailedLogin(user);
                throw ApiException.unauthorized("Invalid credentials");
            }
            if (user.mfaSecret() != null && !user.mfaSecret().isEmpty()) {
                String secret = readMfaSecret(user.mfaSecret(), user.mfaSecretIv(), user.mfaSecretTag());
                if (secret == null) {
                    throw ApiException.unauthorized("MFA misconfigured; contact your administrator");
                }
                String mfaToken = AuthenticationHashes.randomBase64Url(24);
                insertPending(org.id(), "mfa:" + AuthenticationHashes.sha256(mfaToken), user.id(), "MFA",
                        Map.of("userId", user.id()));
                recordAttempt(org.id(), email, true, true, req.ip(), req.userAgent(), null);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("mfaToken", mfaToken);
                out.put("requiresMfa", true);
                return out;
            }
            recordAttempt(org.id(), email, true, false, req.ip(), req.userAgent(), null);
            recordSuccessfulLogin(user);
            return issueTokens(user, org.id(), req);
        });
    }

    /** Step 2: exchange the mfaToken + TOTP code for tokens. */
    public Map<String, Object> mfaLogin(String mfaToken, String code, RequestInfo req) {
        String key = "mfa:" + AuthenticationHashes.sha256(mfaToken);
        Pending pending = findPending(key).orElse(null);
        if (pending == null || !"pending".equals(pending.status())) {
            throw ApiException.unauthorized("MFA token invalid or expired");
        }
        if (Instant.now().toEpochMilli() - pending.createdAt().toEpochMilli() > MFA_WINDOW.toMillis()) {
            deletePending(pending.organizationId(), key);
            throw ApiException.unauthorized("MFA token expired");
        }
        String userId = pending.requestHash();
        return TenantContext.run(TenantStore.organization(pending.organizationId()), () -> {
            UserWithRoles user = users.byId(pending.organizationId(), userId, true).orElse(null);
            if (user == null || user.mfaSecret() == null || user.mfaSecret().isEmpty()) {
                throw ApiException.unauthorized("MFA not enrolled");
            }
            String secret = readMfaSecret(user.mfaSecret(), user.mfaSecretIv(), user.mfaSecretTag());
            if (secret == null) {
                throw ApiException.unauthorized("MFA misconfigured; contact your administrator");
            }
            if (!mfa.verify(secret, code)) {
                recordAttempt(pending.organizationId(), user.email(), false, true, req.ip(), req.userAgent(), "bad_mfa_code");
                recordFailedLogin(user);
                deletePending(pending.organizationId(), key);
                throw ApiException.unauthorized("Invalid MFA code");
            }
            deletePending(pending.organizationId(), key);
            recordAttempt(pending.organizationId(), user.email(), true, true, req.ip(), req.userAgent(), null);
            recordSuccessfulLogin(user);
            return issueTokens(user, pending.organizationId(), req);
        });
    }

    /** Enroll MFA for the current user: returns the secret + QR; persisted only after verification. */
    public Map<String, Object> enrollMfa(AuthUser auth) {
        UserWithRoles user = users.byId(auth.organizationId(), auth.sub(), false)
                .orElseThrow(() -> ApiException.badRequest("User not found"));
        if (user.mfaSecret() != null && !user.mfaSecret().isEmpty()) {
            throw ApiException.badRequest("MFA already enrolled; disable first");
        }
        String secret = mfa.generateSecret();
        EncryptionService.Encrypted enc = encryption.encrypt(secret);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("userId", user.id());
        stored.put("iv", enc == null ? null : enc.iv());
        stored.put("tag", enc == null ? null : enc.tag());
        insertPending(user.organizationId(), "mfa-enroll:" + AuthenticationHashes.sha256(user.id()),
                enc == null ? secret : enc.ciphertext(), "MFA-ENROLL", stored);
        Map<String, String> qr = mfa.buildEnrollmentQr(user.email(), secret);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("secret", secret);
        out.put("qrDataUrl", qr.get("qrDataUrl"));
        return out;
    }

    /** Verify the user can produce a valid code, then persist the (re-encrypted) secret. */
    public Map<String, Object> verifyMfaEnrollment(AuthUser auth, String code) {
        String key = "mfa-enroll:" + AuthenticationHashes.sha256(auth.sub());
        Pending pending = findPending(auth.organizationId(), key).orElse(null);
        if (pending == null || !"pending".equals(pending.status())) {
            throw ApiException.badRequest("No MFA enrollment in progress");
        }
        JsonNode stored = pending.responseJson();
        String iv = stored == null || stored.get("iv") == null || stored.get("iv").isNull() ? null : stored.get("iv").asString();
        String tag = stored == null || stored.get("tag") == null || stored.get("tag").isNull() ? null : stored.get("tag").asString();
        String ciphertext = pending.requestHash();
        String secret;
        try {
            secret = iv != null && tag != null ? encryption.decrypt(new EncryptionService.Encrypted(ciphertext, iv, tag)) : ciphertext;
        } catch (RuntimeException e) {
            secret = ciphertext;
        }
        if (!mfa.verify(secret, code)) {
            throw ApiException.badRequest("Invalid TOTP code; scan the QR and retry");
        }
        EncryptionService.Encrypted enc = encryption.encrypt(secret);
        jdbc.sql("""
                        UPDATE "User" SET "mfaSecret" = ?, "mfaSecretIv" = ?, "mfaSecretTag" = ?, "mfaEnrolledAt" = now(),
                               "updatedAt" = now() WHERE id = ? AND "organizationId" = ?""")
                .params(enc == null ? secret : enc.ciphertext(), enc == null ? null : enc.iv(), enc == null ? null : enc.tag(),
                        auth.sub(), auth.organizationId())
                .update();
        deletePending(pending.organizationId(), key);
        return Map.of("ok", true);
    }

    public Map<String, Object> disableMfa(AuthUser auth, String code) {
        UserWithRoles user = users.byId(auth.organizationId(), auth.sub(), false).orElse(null);
        if (user == null || user.mfaSecret() == null || user.mfaSecret().isEmpty()) {
            throw ApiException.badRequest("MFA not enrolled");
        }
        String secret = readMfaSecret(user.mfaSecret(), user.mfaSecretIv(), user.mfaSecretTag());
        if (secret == null || !mfa.verify(secret, code)) {
            throw ApiException.badRequest("Invalid TOTP code");
        }
        jdbc.sql("""
                        UPDATE "User" SET "mfaSecret" = NULL, "mfaSecretIv" = NULL, "mfaSecretTag" = NULL,
                               "mfaEnrolledAt" = NULL, "updatedAt" = now() WHERE id = ? AND "organizationId" = ?""")
                .params(auth.sub(), auth.organizationId())
                .update();
        return Map.of("ok", true);
    }

    /** Rotate a refresh token: the presented row is revoked and points at its replacement. */
    public Map<String, Object> refresh(String refreshToken, RequestInfo req) {
        String presentedHash = AuthenticationHashes.hashRefreshToken(refreshToken);
        String orgId = jdbc.sql("SELECT \"organizationId\" FROM \"RefreshToken\" WHERE \"tokenHash\" = ? LIMIT 1")
                .param(presentedHash)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired refresh token"));
        return TenantContext.run(TenantStore.organization(orgId), () -> tx.execute(s -> {
            record Existing(String id, String userId, Timestamp revokedAt, Timestamp expiresAt, String deviceLabel,
                    String ipAddress, String userAgent) {}
            Existing existing = jdbc.sql("""
                            SELECT id, "userId", "revokedAt", "expiresAt", "deviceLabel", "ipAddress", "userAgent"
                            FROM "RefreshToken" WHERE "tokenHash" = ? AND "organizationId" = ? LIMIT 1 FOR UPDATE""")
                    .params(presentedHash, orgId)
                    .query((rs, n) -> new Existing(rs.getString("id"), rs.getString("userId"), rs.getTimestamp("revokedAt"),
                            rs.getTimestamp("expiresAt"), rs.getString("deviceLabel"), rs.getString("ipAddress"),
                            rs.getString("userAgent")))
                    .optional()
                    .orElse(null);
            if (existing == null || existing.revokedAt() != null || existing.expiresAt().toInstant().isBefore(Instant.now())) {
                throw ApiException.unauthorized("Invalid or expired refresh token");
            }
            UserWithRoles user = userIncludingDeleted(orgId, existing.userId());
            if (user == null || !user.isActive()) {
                throw ApiException.unauthorized("User no longer active");
            }
            var replacement = AuthenticationHashes.newRefreshTokenValue();
            String newId = UUID.randomUUID().toString();
            jdbc.sql("""
                            INSERT INTO "RefreshToken" (id, "organizationId", "userId", "tokenHash", "expiresAt", "deviceLabel",
                              "ipAddress", "userAgent", "createdAt")
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())""")
                    .params(newId, orgId, existing.userId(), replacement.hash(), Timestamp.from(replacement.expiresAt()),
                            existing.deviceLabel(), req.ip() != null ? req.ip() : existing.ipAddress(),
                            req.userAgent() != null ? req.userAgent() : existing.userAgent())
                    .update();
            jdbc.sql("UPDATE \"RefreshToken\" SET \"revokedAt\" = now(), \"replacedById\" = ? WHERE id = ?")
                    .params(newId, existing.id())
                    .update();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accessToken", jwt.signAccess(user.id(), orgId, user.email()));
            out.put("refreshToken", replacement.value());
            out.put("user", user.sanitize());
            out.put("permissions", user.permissions());
            Map<String, Object> organization = organization(orgId);
            if (organization != null) {
                out.put("organization", organization);
            }
            return out;
        }));
    }

    public Map<String, Object> me(AuthUser auth) {
        UserWithRoles user = users.byId(auth.organizationId(), auth.sub(), false)
                .orElseThrow(() -> ApiException.bare(HttpStatus.UNAUTHORIZED));
        Map<String, Object> out = user.sanitize();
        out.put("permissions", user.permissions());
        out.put("mfaEnrolled", user.mfaSecret() != null && !user.mfaSecret().isEmpty());
        return out;
    }

    public List<Map<String, Object>> listSessions(AuthUser auth) {
        return jdbc.sql("""
                        SELECT id, "deviceLabel", "ipAddress", "userAgent", "createdAt", "expiresAt" FROM "RefreshToken"
                        WHERE "organizationId" = ? AND "userId" = ? AND "revokedAt" IS NULL AND "expiresAt" > now()
                        ORDER BY "createdAt" DESC""")
                .params(auth.organizationId(), auth.sub())
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("deviceLabel", rs.getString("deviceLabel"));
                    row.put("ipAddress", rs.getString("ipAddress"));
                    row.put("userAgent", rs.getString("userAgent"));
                    row.put("createdAt", rs.getTimestamp("createdAt").toInstant());
                    row.put("expiresAt", rs.getTimestamp("expiresAt").toInstant());
                    return row;
                })
                .list();
    }

    public Map<String, Object> revokeSession(AuthUser auth, String sessionId) {
        int updated = jdbc.sql("""
                        UPDATE "RefreshToken" SET "revokedAt" = now()
                        WHERE id = ? AND "userId" = ? AND "organizationId" = ?""")
                .params(sessionId, auth.sub(), auth.organizationId())
                .update();
        if (updated == 0) {
            throw ApiException.badRequest("Session not found");
        }
        return Map.of("ok", true);
    }

    /** Requires the current password; revokes every refresh token of the user. */
    public void changePassword(AuthUser auth, String currentPassword, String newPassword) {
        UserWithRoles user = users.byId(auth.organizationId(), auth.sub(), false)
                .orElseThrow(() -> ApiException.badRequest("User not found"));
        if (!password.compare(currentPassword, user.passwordHash())) {
            throw ApiException.badRequest("Current password is incorrect");
        }
        String hash = password.hash(newPassword);
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                            UPDATE "User" SET "passwordHash" = ?, "failedLoginCount" = 0, "lockedUntil" = NULL, "updatedAt" = now()
                            WHERE id = ? AND "organizationId" = ?""")
                    .params(hash, user.id(), auth.organizationId())
                    .update();
            jdbc.sql("""
                            UPDATE "RefreshToken" SET "revokedAt" = now()
                            WHERE "userId" = ? AND "organizationId" = ? AND "revokedAt" IS NULL""")
                    .params(user.id(), auth.organizationId())
                    .update();
        });
        audit.record(AuditService.AuditInput.of("User", user.id(), "update", null, Map.of("passwordChanged", true)));
        events.publish("user.password_changed", event(user.id(), user.organizationId()));
    }

    // ---- helpers ----

    private Map<String, Object> issueTokens(UserWithRoles user, String organizationId, RequestInfo req) {
        String accessToken = jwt.signAccess(user.id(), organizationId, user.email());
        var refresh = AuthenticationHashes.newRefreshTokenValue();
        jdbc.sql("""
                        INSERT INTO "RefreshToken" (id, "organizationId", "userId", "tokenHash", "expiresAt", "deviceLabel",
                          "ipAddress", "userAgent", "createdAt")
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())""")
                .params(UUID.randomUUID().toString(), organizationId, user.id(), refresh.hash(),
                        Timestamp.from(refresh.expiresAt()), req.deviceLabel(), req.ip(), req.userAgent())
                .update();
        audit.record(AuditService.AuditInput.of("User", user.id(), "login"));
        events.publish("user.logged_in", event(user.id(), organizationId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", accessToken);
        out.put("refreshToken", refresh.value());
        out.put("user", user.sanitize());
        out.put("permissions", user.permissions());
        Map<String, Object> organization = organization(organizationId);
        if (organization != null) {
            out.put("organization", organization);
        }
        return out;
    }

    private static Map<String, Object> event(String userId, String organizationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("organizationId", organizationId);
        payload.put("at", JsonConfig.toJsIso(Instant.now()));
        return payload;
    }

    private Map<String, Object> organization(String organizationId) {
        return jdbc.sql("SELECT id, code, name, \"currencyCode\", timezone FROM \"Organization\" WHERE id = ?")
                .param(organizationId)
                .query((rs, n) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", rs.getString("id"));
                    o.put("code", rs.getString("code"));
                    o.put("name", rs.getString("name"));
                    o.put("currencyCode", rs.getString("currencyCode"));
                    o.put("timezone", rs.getString("timezone"));
                    return o;
                })
                .optional()
                .orElse(null);
    }

    /** {@code refreshToken.include.user}: the relation is loaded regardless of soft delete. */
    private UserWithRoles userIncludingDeleted(String orgId, String userId) {
        record Row(String email, boolean isActive, String firstName, String lastName, String mfaSecret) {}
        Row row = jdbc.sql("SELECT email, \"isActive\", \"firstName\", \"lastName\", \"mfaSecret\" FROM \"User\" WHERE id = ?")
                .param(userId)
                .query((rs, n) -> new Row(rs.getString("email"), rs.getBoolean("isActive"), rs.getString("firstName"),
                        rs.getString("lastName"), rs.getString("mfaSecret")))
                .optional()
                .orElse(null);
        if (row == null) {
            return null;
        }
        return new UserWithRoles(userId, orgId, row.email(), row.firstName(), row.lastName(), null, row.isActive(),
                row.mfaSecret(), null, null, 0, null, users.roles(userId));
    }

    private void recordAttempt(String organizationId, String email, boolean success, boolean mfaRequired, String ip,
            String userAgent, String reason) {
        try {
            jdbc.sql("""
                            INSERT INTO "LoginAttempt" (id, "organizationId", email, success, "mfaRequired", "ipAddress",
                              "userAgent", reason, "createdAt")
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())""")
                    .params(UUID.randomUUID().toString(), organizationId, email, success, mfaRequired, ip, userAgent, reason)
                    .update();
        } catch (RuntimeException err) {
            log.warn("Failed to record login attempt: {}", err.toString());
        }
    }

    /** Increment the failed-login counter and lock at the threshold. */
    private void recordFailedLogin(UserWithRoles user) {
        if (user == null) {
            return;
        }
        int next = user.failedLoginCount() + 1;
        boolean locked = next >= MAX_FAILED_ATTEMPTS;
        jdbc.sql("""
                        UPDATE "User" SET "failedLoginCount" = ?, "lockedUntil" = ?, "updatedAt" = now()
                        WHERE id = ? AND "organizationId" = ?""")
                .params(next, locked ? Timestamp.from(Instant.now().plus(LOCKOUT_DURATION)) : null, user.id(),
                        user.organizationId())
                .update();
        if (locked) {
            log.warn("User {} locked after {} failed attempts", user.email(), next);
            recordAttempt(user.organizationId(), user.email(), false, user.mfaSecret() != null, null, null, "account_locked");
        }
    }

    private void recordSuccessfulLogin(UserWithRoles user) {
        jdbc.sql("""
                        UPDATE "User" SET "failedLoginCount" = 0, "lockedUntil" = NULL, "lastLoginAt" = now(), "updatedAt" = now()
                        WHERE id = ? AND "organizationId" = ?""")
                .params(user.id(), user.organizationId())
                .update();
    }

    /** Decrypted MFA secret; a row without IV/tag (or one that fails to decrypt) is treated as plaintext. */
    private String readMfaSecret(String mfaSecret, String iv, String tag) {
        if (mfaSecret == null || mfaSecret.isEmpty()) {
            return null;
        }
        if (iv == null || tag == null) {
            return mfaSecret;
        }
        try {
            return encryption.decrypt(new EncryptionService.Encrypted(mfaSecret, iv, tag));
        } catch (RuntimeException e) {
            return mfaSecret;
        }
    }

    private record Pending(String organizationId, String requestHash, String status, Instant createdAt, JsonNode responseJson) {}

    private void insertPending(String organizationId, String key, String requestHash, String method, Map<String, Object> json) {
        jdbc.sql("""
                        INSERT INTO "IdempotencyRecord" (id, "organizationId", key, "requestHash", method, path, "statusCode",
                          "responseJson", status, "createdAt")
                        VALUES (?, ?, ?, ?, ?, 'pending', 0, ?::jsonb, 'pending', now())""")
                .params(UUID.randomUUID().toString(), organizationId, key, requestHash, method, mapper.writeValueAsString(json))
                .update();
    }

    private Optional<Pending> findPending(String key) {
        return jdbc.sql("""
                        SELECT "organizationId", "requestHash", status, "createdAt", "responseJson"::text AS "responseJson"
                        FROM "IdempotencyRecord" WHERE key = ? ORDER BY "createdAt" DESC LIMIT 1""")
                .param(key)
                .query((rs, n) -> pending(rs))
                .optional();
    }

    private Optional<Pending> findPending(String organizationId, String key) {
        return jdbc.sql("""
                        SELECT "organizationId", "requestHash", status, "createdAt", "responseJson"::text AS "responseJson"
                        FROM "IdempotencyRecord" WHERE "organizationId" = ? AND key = ?""")
                .params(organizationId, key)
                .query((rs, n) -> pending(rs))
                .optional();
    }

    private Pending pending(java.sql.ResultSet rs) throws java.sql.SQLException {
        String json = rs.getString("responseJson");
        return new Pending(rs.getString("organizationId"), rs.getString("requestHash"), rs.getString("status"),
                rs.getTimestamp("createdAt").toInstant(), json == null ? null : mapper.readTree(json));
    }

    private void deletePending(String organizationId, String key) {
        try {
            jdbc.sql("DELETE FROM \"IdempotencyRecord\" WHERE \"organizationId\" = ? AND key = ?")
                    .params(organizationId, key)
                    .update();
        } catch (RuntimeException ignored) {
            // best effort, as in Nest (.catch(() => undefined))
        }
    }
}
