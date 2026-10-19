package com.inventoryerp.kernel.auth;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Loads users with their roles the way the Nest auth code does ({@code user.findFirst({ include:
 * { roles: true } })} through the tenancy extension: organization-scoped, soft-deleted rows excluded).
 */
@Component
public class UserAccounts {

    /** A role as included on a user. */
    public record RoleRef(String id, String name, List<String> permissions) {}

    /** The user columns the auth flows need, plus roles. */
    public record UserWithRoles(String id, String organizationId, String email, String firstName, String lastName,
            String passwordHash, boolean isActive, String mfaSecret, String mfaSecretIv, String mfaSecretTag,
            int failedLoginCount, Instant lockedUntil, List<RoleRef> roles) {

        public List<String> permissions() {
            LinkedHashSet<String> all = new LinkedHashSet<>();
            for (RoleRef r : roles) {
                all.addAll(r.permissions());
            }
            return List.copyOf(all);
        }

        /** {@code sanitize(user)}: the public login/refresh user shape. */
        public Map<String, Object> sanitize() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("email", email);
            out.put("firstName", firstName);
            out.put("lastName", lastName);
            out.put("roles", roles.stream().map(RoleRef::name).toList());
            out.put("mfaEnrolled", mfaSecret != null && !mfaSecret.isEmpty());
            return out;
        }
    }

    private static final String COLUMNS = """
            u.id, u."organizationId", u.email, u."firstName", u."lastName", u."passwordHash", u."isActive",
            u."mfaSecret", u."mfaSecretIv", u."mfaSecretTag", u."failedLoginCount", u."lockedUntil" """;

    private final JdbcClient jdbc;

    public UserAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** By email within an organization (active only when {@code activeOnly}). */
    public Optional<UserWithRoles> byEmail(String organizationId, String email, boolean activeOnly) {
        return one("u.\"organizationId\" = ? AND u.email = ?" + (activeOnly ? " AND u.\"isActive\" = true" : ""),
                organizationId, email);
    }

    /** By id within an organization (active only when {@code activeOnly}). */
    public Optional<UserWithRoles> byId(String organizationId, String id, boolean activeOnly) {
        return one("u.\"organizationId\" = ? AND u.id = ?" + (activeOnly ? " AND u.\"isActive\" = true" : ""),
                organizationId, id);
    }

    /** Roles of a user (Prisma include, no soft-delete filter on the relation). */
    public List<RoleRef> roles(String userId) {
        return jdbc.sql("""
                        SELECT r.id, r.name, r.permissions FROM "Role" r JOIN "_UserRoles" ur ON ur."A" = r.id
                        WHERE ur."B" = ? ORDER BY r."createdAt", r.id""")
                .param(userId)
                .query((rs, n) -> new RoleRef(rs.getString("id"), rs.getString("name"), array(rs, "permissions")))
                .list();
    }

    private Optional<UserWithRoles> one(String where, Object... params) {
        Optional<UserWithRoles> base = jdbc.sql("SELECT " + COLUMNS + " FROM \"User\" u WHERE " + where
                        + " AND u.\"deletedAt\" IS NULL LIMIT 1")
                .params(params)
                .query((rs, n) -> map(rs, List.of()))
                .optional();
        return base.map(u -> new UserWithRoles(u.id(), u.organizationId(), u.email(), u.firstName(), u.lastName(),
                u.passwordHash(), u.isActive(), u.mfaSecret(), u.mfaSecretIv(), u.mfaSecretTag(), u.failedLoginCount(),
                u.lockedUntil(), roles(u.id())));
    }

    private static UserWithRoles map(ResultSet rs, List<RoleRef> roles) throws SQLException {
        Timestamp locked = rs.getTimestamp("lockedUntil");
        return new UserWithRoles(rs.getString("id"), rs.getString("organizationId"), rs.getString("email"),
                rs.getString("firstName"), rs.getString("lastName"), rs.getString("passwordHash"),
                rs.getBoolean("isActive"), rs.getString("mfaSecret"), rs.getString("mfaSecretIv"),
                rs.getString("mfaSecretTag"), rs.getInt("failedLoginCount"), locked == null ? null : locked.toInstant(),
                roles);
    }

    public static List<String> array(ResultSet rs, String column) throws SQLException {
        Array a = rs.getArray(column);
        if (a == null) {
            return List.of();
        }
        return new ArrayList<>(List.of((String[]) a.getArray()));
    }
}
