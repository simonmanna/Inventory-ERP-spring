package com.inventoryerp.kernel.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Live revocation for POS cashier tokens (port of {@code pos-session-check.ts}). Asks the database,
 * per request, whether the token's user may still operate a POS. Answers are cached for 10 seconds per
 * user (bounded to 5,000 entries), which bounds how long a revocation takes to land on every instance.
 * Reads unscoped (the org is scoped by hand): it runs before a tenant context exists.
 */
@Component
public class PosSessionCheck {

    /** Response header the web terminal watches to drop a revoked cashier. */
    public static final String POS_SESSION_HEADER = "X-Pos-Session";

    /** Row loader, swappable in tests. */
    public interface UserLookup {
        Optional<UserRow> find(String organizationId, String userId);
    }

    public record UserRow(boolean isActive, Instant deletedAt, PosEligibility.Employee employee) {}

    private final UserLookup lookup;
    private final Cache<String, Boolean> cache;

    @org.springframework.beans.factory.annotation.Autowired
    public PosSessionCheck(JdbcClient jdbc) {
        this(jdbcLookup(jdbc));
    }

    public PosSessionCheck(UserLookup lookup) {
        this.lookup = lookup;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).maximumSize(5_000).build();
    }

    public boolean posUserStillAllowed(String organizationId, String userId) {
        return cache.get(organizationId + ":" + userId, k -> lookup.find(organizationId, userId)
                .map(u -> PosEligibility.userMayOperatePos(u.isActive(), u.deletedAt(), u.employee()))
                .orElse(false));
    }

    private static UserLookup jdbcLookup(JdbcClient jdbc) {
        return (organizationId, userId) -> jdbc.sql("""
                        SELECT u."isActive", u."deletedAt", e."employmentStatus"::text AS "employmentStatus",
                               e."deletedAt" AS "employeeDeletedAt", e.id AS "employeeId"
                        FROM "User" u LEFT JOIN "HrEmployee" e ON e."userId" = u.id
                        WHERE u.id = ? AND u."organizationId" = ?
                        LIMIT 1""")
                .params(userId, organizationId)
                .query((rs, n) -> {
                    Timestamp deleted = rs.getTimestamp("deletedAt");
                    Timestamp empDeleted = rs.getTimestamp("employeeDeletedAt");
                    PosEligibility.Employee emp = rs.getString("employeeId") == null ? null
                            : new PosEligibility.Employee(rs.getString("employmentStatus"),
                                    empDeleted == null ? null : empDeleted.toInstant());
                    return new UserRow(rs.getBoolean("isActive"), deleted == null ? null : deleted.toInstant(), emp);
                })
                .optional();
    }
}
