package com.inventoryerp.kernel.auth;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.shared.web.EntityJson;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * One-time tokens for password reset, email verification and invites (port of
 * {@code one-time-token.service.ts}). Only the sha256 is stored; TTL 30 min (reset), 24 h (verify),
 * 7 days (invite). Reset requests never reveal whether the org or email exists.
 */
@Service
public class OneTimeTokenService {

    private static final Map<String, Duration> TTL = Map.of(
            "password_reset", Duration.ofMinutes(30),
            "email_verification", Duration.ofHours(24),
            "invite", Duration.ofDays(7));

    /** A consumed token's owner. */
    public record Consumed(String userId, String organizationId) {}

    private final JdbcClient jdbc;
    private final PasswordService password;
    private final EventBus events;
    private final AuditService audit;
    private final NotificationsService notifications;
    private final SecureRandom random = new SecureRandom();

    public OneTimeTokenService(JdbcClient jdbc, PasswordService password, EventBus events, AuditService audit,
            NotificationsService notifications) {
        this.jdbc = jdbc;
        this.password = password;
        this.events = events;
        this.audit = audit;
        this.notifications = notifications;
    }

    /** Issue a token; returns the raw value (the only time it is visible). */
    public String issue(String purpose, String userId, String organizationId, Map<String, Object> payload, Duration ttl) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Duration life = ttl != null ? ttl : TTL.get(purpose);
        jdbc.sql("""
                        INSERT INTO "OneTimeToken" (id, "organizationId", "userId", "tokenHash", purpose, "expiresAt", payload, "createdAt")
                        VALUES (?, ?, ?, ?, ?::"OneTimeTokenPurpose", ?, ?::jsonb, now())""")
                .params(UUID.randomUUID().toString(), organizationId, userId, AuthenticationHashes.sha256(raw), purpose,
                        Timestamp.from(Instant.now().plus(life)), EntityJson.toJson(payload == null ? Map.of() : payload))
                .update();
        return raw;
    }

    /** Consume a token; {@code null} when unknown, wrong purpose, used, expired or org-less. */
    public Consumed consume(String raw, String purpose) {
        record Row(String id, String purpose, Timestamp consumedAt, Timestamp expiresAt, String organizationId, String userId) {}
        Row row = jdbc.sql("""
                        SELECT id, purpose::text AS purpose, "consumedAt", "expiresAt", "organizationId", "userId"
                        FROM "OneTimeToken" WHERE "tokenHash" = ?""")
                .param(AuthenticationHashes.sha256(raw))
                .query((rs, n) -> new Row(rs.getString("id"), rs.getString("purpose"), rs.getTimestamp("consumedAt"),
                        rs.getTimestamp("expiresAt"), rs.getString("organizationId"), rs.getString("userId")))
                .optional()
                .orElse(null);
        if (row == null || !purpose.equals(row.purpose()) || row.consumedAt() != null
                || row.expiresAt().toInstant().isBefore(Instant.now()) || row.organizationId() == null) {
            return null;
        }
        jdbc.sql("UPDATE \"OneTimeToken\" SET \"consumedAt\" = now() WHERE id = ?").param(row.id()).update();
        return new Consumed(row.userId(), row.organizationId());
    }

    /** Always {@code {ok: true}} (enumeration protection). */
    public Map<String, Object> requestReset(String email, String organizationCode, String ip, String userAgent) {
        record Org(String id, String status) {}
        Org org = jdbc.sql("SELECT id, status::text AS status FROM \"Organization\" WHERE code = ?")
                .param(organizationCode)
                .query((rs, n) -> new Org(rs.getString("id"), rs.getString("status")))
                .optional()
                .orElse(null);
        if (org == null || !"active".equals(org.status())) {
            return Map.of("ok", true);
        }
        String userId = jdbc.sql("""
                        SELECT id FROM "User" WHERE "organizationId" = ? AND email = ? AND "isActive" = true LIMIT 1""")
                .params(org.id(), email)
                .query(String.class)
                .optional()
                .orElse(null);
        if (userId != null) {
            Map<String, Object> issuedPayload = new LinkedHashMap<>();
            if (ip != null) {
                issuedPayload.put("ip", ip);
            }
            String token = issue("password_reset", userId, org.id(), issuedPayload, null);
            Map<String, Object> mail = new LinkedHashMap<>();
            mail.put("token", token);
            mail.put("kind", "password_reset");
            notifications.send(new NotificationsService.SendInput(org.id(), userId, "email", "auth",
                    "Reset your password", "Click the link to reset your password. The link expires in 30 minutes.", mail));
            // Public route: no tenant context, so (as in Nest) the fire-and-forget audit is skipped.
            audit.record(new AuditService.AuditInput(
                    "User", userId, "update", null, Map.of("passwordResetRequested", true), ip, userAgent));
        }
        return Map.of("ok", true);
    }

    /** Apply a reset token + new password; an invalid token is an unhandled error (500), as in Nest. */
    public Map<String, Object> applyReset(String raw, String newPassword) {
        Consumed consumed = consume(raw, "password_reset");
        if (consumed == null) {
            throw new IllegalStateException("Invalid or expired reset token");
        }
        String hash = password.hash(newPassword);
        jdbc.sql("UPDATE \"User\" SET \"passwordHash\" = ?, \"failedLoginCount\" = 0, \"lockedUntil\" = NULL, \"updatedAt\" = now() WHERE id = ?")
                .params(hash, consumed.userId())
                .update();
        jdbc.sql("UPDATE \"RefreshToken\" SET \"revokedAt\" = now() WHERE \"userId\" = ? AND \"revokedAt\" IS NULL")
                .param(consumed.userId())
                .update();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("userId", consumed.userId());
        event.put("organizationId", consumed.organizationId());
        event.put("at", com.inventoryerp.kernel.web.JsonConfig.toJsIso(Instant.now()));
        // Public route: no tenant context (Nest skips the audit row; the outbox row carries organizationId '').
        audit.record(AuditService.AuditInput.of("User", consumed.userId(), "update", null,
                Map.of("passwordResetCompleted", true)));
        events.publish("user.password_reset", event);
        return Map.of("ok", true);
    }
}
