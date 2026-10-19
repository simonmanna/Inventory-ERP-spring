package com.inventoryerp.kernel.staff;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff (login) management (port of {@code users.service.ts}). The public shape ({@code SafeUser})
 * never carries hashes; the linked HR employee is read straight from {@code HrEmployee} (the relation
 * lives on User in the schema, no HR module import). Includes the last-administrator and self-demotion
 * guards.
 */
@Service
public class UsersService {

    private static final List<String> ADMIN_PERMS = List.of("user:update", "role:update");

    /** Create body. */
    public record CreateUser(String email, String password, String firstName, String lastName, Boolean isActive,
            List<String> roleIds) {}

    /** Update body. */
    public record UpdateUser(String firstName, String lastName, String email, Boolean isActive, List<String> roleIds) {}

    private record RoleRow(String id, String name) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final PasswordService password;
    private final PermissionService permissionCache;

    public UsersService(JdbcClient jdbc, AuditService audit, EventBus events, PasswordService password,
            PermissionService permissionCache) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.password = password;
        this.permissionCache = permissionCache;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String search, Integer page, Integer pageSize, Boolean linked) {
        int p = Math.max(1, page == null ? 1 : page);
        int ps = Math.min(100, Math.max(1, pageSize == null ? 25 : pageSize));
        String orgId = TenantContext.organizationId();
        StringBuilder where = new StringBuilder("u.\"organizationId\" = :org AND u.\"deletedAt\" IS NULL");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", orgId);
        if (Boolean.TRUE.equals(linked)) {
            where.append(" AND EXISTS (SELECT 1 FROM \"HrEmployee\" e WHERE e.\"userId\" = u.id)");
        } else if (Boolean.FALSE.equals(linked)) {
            where.append(" AND NOT EXISTS (SELECT 1 FROM \"HrEmployee\" e WHERE e.\"userId\" = u.id)");
        }
        if (search != null && !search.isEmpty()) {
            where.append(" AND (u.email ILIKE :q OR u.\"firstName\" ILIKE :q OR u.\"lastName\" ILIKE :q)");
            params.put("q", "%" + likeEscape(search) + "%");
        }
        params.put("limit", ps);
        params.put("offset", (p - 1) * ps);
        List<Map<String, Object>> data = jdbc.sql("SELECT u.* FROM \"User\" u WHERE " + where
                        + " ORDER BY u.\"isActive\" DESC, u.\"firstName\" ASC LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, n) -> safe(rs))
                .list();
        long total = jdbc.sql("SELECT count(*) FROM \"User\" u WHERE " + where).params(params).query(Long.class).single();
        attach(data);
        return PageResult.of(data, p, ps, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        return load(id).orElseThrow(() -> ApiException.notFound("User " + id + " not found"));
    }

    @Transactional
    public Map<String, Object> create(CreateUser dto) {
        String orgId = TenantContext.optionalOrganizationId();
        if (orgId == null) {
            throw ApiException.forbidden("Tenant context required");
        }
        boolean exists = jdbc.sql("SELECT count(*) FROM \"User\" WHERE \"organizationId\" = ? AND email = ? AND \"deletedAt\" IS NULL")
                .params(orgId, dto.email()).query(Long.class).single() > 0;
        if (exists) {
            throw ApiException.conflict("A user with email \"" + dto.email() + "\" already exists");
        }
        List<RoleRow> roles = resolveRoles(orgId, dto.roleIds());
        String hash = password.hash(dto.password());
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO "User" (id, "organizationId", email, "passwordHash", "firstName", "lastName", "isActive", "updatedAt")
                        VALUES (?, ?, ?, ?, ?, ?, ?, now())""")
                .params(id, orgId, dto.email(), hash, dto.firstName(), dto.lastName(), dto.isActive() == null || dto.isActive())
                .update();
        for (RoleRow r : roles) {
            jdbc.sql("INSERT INTO \"_UserRoles\" (\"A\", \"B\") VALUES (?, ?)").params(r.id(), id).update();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("email", dto.email());
        values.put("firstName", dto.firstName());
        values.put("roleIds", roles.stream().map(RoleRow::id).toList());
        audit.recordInTx(AuditInput.of("User", id, "create", null, values));
        events.publish(DomainEvents.USER_CREATED, Map.of("id", id, "organizationId", orgId));
        permissionCache.evictAll();
        return findOne(id);
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateUser dto) {
        String orgId = TenantContext.organizationId();
        Map<String, Object> current = load(id).orElseThrow(() -> ApiException.notFound("User " + id + " not found"));
        if (dto.email() != null && !dto.email().equals(current.get("email"))) {
            boolean collision = jdbc.sql("""
                            SELECT count(*) FROM "User" WHERE "organizationId" = ? AND email = ? AND id <> ? AND "deletedAt" IS NULL""")
                    .params(orgId, dto.email(), id).query(Long.class).single() > 0;
            if (collision) {
                throw ApiException.conflict("Email \"" + dto.email() + "\" is already in use");
            }
        }
        List<RoleRow> resolved = null;
        if (dto.roleIds() != null) {
            resolved = resolveRoles(orgId, dto.roleIds());
            guardLastAdmin(id, resolved.stream().map(RoleRow::id).toList());
        }
        String acting = TenantContext.userId();
        if (id.equals(acting)) {
            if (Boolean.FALSE.equals(dto.isActive())) {
                throw ApiException.forbidden("You cannot deactivate your own account");
            }
            if (resolved != null && !holdsAdminRole(resolved.stream().map(RoleRow::id).toList())) {
                throw ApiException.forbidden("You cannot remove your own admin role");
            }
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("email", current.get("email"));
        before.put("firstName", current.get("firstName"));
        before.put("lastName", current.get("lastName"));
        before.put("isActive", current.get("isActive"));
        before.put("roleIds", roleIds(current));
        jdbc.sql("""
                        UPDATE "User" SET email = COALESCE(?, email), "firstName" = COALESCE(?, "firstName"),
                          "lastName" = COALESCE(?, "lastName"), "isActive" = COALESCE(?, "isActive"), "updatedAt" = now()
                        WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                .params(dto.email(), dto.firstName(), dto.lastName(), dto.isActive(), id, orgId)
                .update();
        if (resolved != null) {
            jdbc.sql("DELETE FROM \"_UserRoles\" WHERE \"B\" = ?").param(id).update();
            for (RoleRow r : resolved) {
                jdbc.sql("INSERT INTO \"_UserRoles\" (\"A\", \"B\") VALUES (?, ?)").params(r.id(), id).update();
            }
        }
        // One person, one name: a rename carries onto the linked HR record.
        if ((dto.firstName() != null && !dto.firstName().isEmpty()) || dto.lastName() != null) {
            StringBuilder set = new StringBuilder();
            List<Object> params = new ArrayList<>();
            if (dto.firstName() != null && !dto.firstName().isEmpty()) {
                set.append("\"firstName\" = ?, ");
                params.add(dto.firstName());
            }
            if (dto.lastName() != null) {
                set.append("\"lastName\" = ?, ");
                params.add(dto.lastName().isEmpty() ? null : dto.lastName());
            }
            params.add(acting);
            params.add(id);
            params.add(orgId);
            jdbc.sql("UPDATE \"HrEmployee\" SET " + set + "\"updatedBy\" = ?, \"updatedAt\" = now()"
                            + " WHERE \"userId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(params)
                    .update();
        }
        Map<String, Object> after = findOne(id);
        Map<String, Object> afterValues = new LinkedHashMap<>();
        afterValues.put("email", after.get("email"));
        afterValues.put("firstName", after.get("firstName"));
        afterValues.put("lastName", after.get("lastName"));
        afterValues.put("isActive", after.get("isActive"));
        afterValues.put("roleIds", roleIds(after));
        audit.recordInTx(AuditInput.of("User", id, "update", before, afterValues));
        events.publish(DomainEvents.USER_UPDATED, Map.of("id", id, "organizationId", orgId));
        permissionCache.evictAll();
        return after;
    }

    @Transactional
    public void resetPassword(String id, String newPassword) {
        String orgId = TenantContext.organizationId();
        findOne(id);
        String hash = password.hash(newPassword);
        jdbc.sql("""
                        UPDATE "User" SET "passwordHash" = ?, "failedLoginCount" = 0, "lockedUntil" = NULL, "updatedAt" = now()
                        WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                .params(hash, id, orgId)
                .update();
        jdbc.sql("UPDATE \"RefreshToken\" SET \"revokedAt\" = now() WHERE \"userId\" = ? AND \"organizationId\" = ? AND \"revokedAt\" IS NULL")
                .params(id, orgId)
                .update();
        audit.recordInTx(AuditInput.of("User", id, "update", null,
                Map.of("passwordReset", true, "allSessionsRevoked", true)));
        events.publish(DomainEvents.USER_PASSWORD_RESET, Map.of("id", id, "organizationId", orgId));
    }

    /** Manager sets/resets a POS PIN; clears a PIN lockout. */
    @Transactional
    public Map<String, Object> setPin(String id, String pin) {
        if (pin == null || !pin.matches("^\\d{4,8}$")) {
            throw ApiException.badRequest("PIN must be 4–8 digits");
        }
        String orgId = TenantContext.organizationId();
        String existingPin = pinHash(id);
        String hash = password.hash(pin);
        jdbc.sql("""
                        UPDATE "User" SET "pinHash" = ?, "pinHashRounds" = 10, "failedLoginCount" = 0, "lockedUntil" = NULL,
                          "updatedAt" = now() WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                .params(hash, id, orgId)
                .update();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("posPinSet", true);
        values.put("hadPin", existingPin != null);
        values.put("setBy", TenantContext.userId());
        audit.recordInTx(AuditInput.of("User", id, "update", null, values));
        events.publish(DomainEvents.USER_UPDATED, Map.of("id", id, "organizationId", orgId));
        return findOne(id);
    }

    @Transactional
    public Map<String, Object> clearPin(String id) {
        String orgId = TenantContext.organizationId();
        pinHash(id);
        jdbc.sql("""
                        UPDATE "User" SET "pinHash" = NULL, "pinHashRounds" = NULL, "updatedAt" = now()
                        WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                .params(id, orgId)
                .update();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("posPinCleared", true);
        values.put("setBy", TenantContext.userId());
        audit.recordInTx(AuditInput.of("User", id, "update", null, values));
        events.publish(DomainEvents.USER_UPDATED, Map.of("id", id, "organizationId", orgId));
        return findOne(id);
    }

    @Transactional
    public Map<String, Object> unlock(String id) {
        String orgId = TenantContext.organizationId();
        findOne(id);
        jdbc.sql("""
                        UPDATE "User" SET "failedLoginCount" = 0, "lockedUntil" = NULL, "updatedAt" = now()
                        WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                .params(id, orgId)
                .update();
        Map<String, Object> after = load(id).orElseThrow();
        // Nest's unlock response includes roles but not the employee relation.
        after.put("employee", null);
        audit.recordInTx(AuditInput.of("User", id, "update", null, Map.of("unlocked", true, "failedLoginCount", 0)));
        events.publish(DomainEvents.USER_UNLOCKED, Map.of("id", id, "organizationId", orgId));
        return after;
    }

    /** Soft delete + session revocation. */
    @Transactional
    public void remove(String id) {
        String orgId = TenantContext.organizationId();
        Map<String, Object> current = findOne(id);
        if (id.equals(TenantContext.userId())) {
            throw ApiException.forbidden("You cannot delete your own account");
        }
        guardLastAdmin(id, roleIds(current));
        jdbc.sql("""
                        UPDATE "User" SET "deletedAt" = now(), "isActive" = false, "updatedAt" = now()
                        WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                .params(id, orgId)
                .update();
        jdbc.sql("UPDATE \"RefreshToken\" SET \"revokedAt\" = now() WHERE \"userId\" = ? AND \"organizationId\" = ? AND \"revokedAt\" IS NULL")
                .params(id, orgId)
                .update();
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("email", current.get("email"));
        old.put("firstName", current.get("firstName"));
        audit.recordInTx(AuditInput.of("User", id, "delete", old, null));
        events.publish(DomainEvents.USER_DELETED, Map.of("id", id, "organizationId", orgId));
        permissionCache.evictAll();
    }

    // ---- helpers ----

    private String pinHash(String id) {
        return jdbc.sql("SELECT \"pinHash\" FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, TenantContext.organizationId())
                .query((rs, n) -> new String[] {rs.getString(1)})
                .optional()
                .orElseThrow(() -> ApiException.notFound("User " + id + " not found"))[0];
    }

    private java.util.Optional<Map<String, Object>> load(String id) {
        java.util.Optional<Map<String, Object>> row = jdbc.sql(
                        "SELECT u.* FROM \"User\" u WHERE u.id = ? AND u.\"organizationId\" = ? AND u.\"deletedAt\" IS NULL")
                .params(id, TenantContext.organizationId())
                .query((rs, n) -> safe(rs))
                .optional();
        row.ifPresent(r -> attach(List.of(r)));
        return row;
    }

    private List<RoleRow> resolveRoles(String orgId, List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<RoleRow> roles = jdbc.sql("SELECT id, name FROM \"Role\" WHERE id IN (:ids) AND \"organizationId\" = :org AND \"deletedAt\" IS NULL")
                .param("ids", roleIds)
                .param("org", orgId)
                .query((rs, n) -> new RoleRow(rs.getString("id"), rs.getString("name")))
                .list();
        if (roles.size() != roleIds.size()) {
            Set<String> found = new HashSet<>(roles.stream().map(RoleRow::id).toList());
            List<String> missing = roleIds.stream().filter(r -> !found.contains(r)).toList();
            throw ApiException.badRequest("Unknown role id(s) for this organization: " + String.join(", ", missing));
        }
        return roles;
    }

    /** Refuse to drop the last active admin (holder of user:update or role:update). */
    private void guardLastAdmin(String targetUserId, List<String> remainingRoleIds) {
        if (holdsAdminRole(remainingRoleIds)) {
            return;
        }
        long others = jdbc.sql("""
                        SELECT count(*) FROM "User" u WHERE u."organizationId" = :org AND u.id <> :id AND u."isActive" = true
                          AND u."deletedAt" IS NULL AND EXISTS (SELECT 1 FROM "_UserRoles" ur JOIN "Role" r ON r.id = ur."A"
                            WHERE ur."B" = u.id AND r.permissions && CAST(:perms AS text[]))""")
                .param("org", TenantContext.organizationId())
                .param("id", targetUserId)
                .param("perms", "{" + String.join(",", ADMIN_PERMS) + "}")
                .query(Long.class)
                .single();
        if (others == 0) {
            throw ApiException.forbidden(
                    "Refusing to remove the last administrator. Promote another user to an admin role first.");
        }
    }

    private boolean holdsAdminRole(List<String> roleIds) {
        if (roleIds.isEmpty()) {
            return false;
        }
        return jdbc.sql("""
                        SELECT count(*) FROM "Role" WHERE id IN (:ids) AND "organizationId" = :org AND "deletedAt" IS NULL
                          AND permissions && CAST(:perms AS text[])""")
                .param("ids", roleIds)
                .param("org", TenantContext.organizationId())
                .param("perms", "{" + String.join(",", ADMIN_PERMS) + "}")
                .query(Long.class)
                .single() > 0;
    }

    @SuppressWarnings("unchecked")
    private static List<String> roleIds(Map<String, Object> user) {
        return ((List<Map<String, Object>>) user.get("roles")).stream().map(r -> (String) r.get("id")).toList();
    }

    /** {@code toSafe(u)} without roles/employee (attached afterwards). */
    private static Map<String, Object> safe(ResultSet rs) throws SQLException {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", rs.getString("id"));
        u.put("email", rs.getString("email"));
        u.put("firstName", rs.getString("firstName"));
        u.put("lastName", rs.getString("lastName"));
        u.put("isActive", rs.getBoolean("isActive"));
        u.put("lastLoginAt", instant(rs.getTimestamp("lastLoginAt")));
        u.put("failedLoginCount", rs.getInt("failedLoginCount"));
        u.put("lockedUntil", instant(rs.getTimestamp("lockedUntil")));
        String mfa = rs.getString("mfaSecret");
        u.put("mfaEnrolled", mfa != null && !mfa.isEmpty());
        String pin = rs.getString("pinHash");
        u.put("hasPin", pin != null && !pin.isEmpty());
        u.put("defaultBranchId", rs.getString("defaultBranchId"));
        u.put("createdAt", instant(rs.getTimestamp("createdAt")));
        u.put("updatedAt", instant(rs.getTimestamp("updatedAt")));
        u.put("roles", new ArrayList<Map<String, Object>>());
        u.put("employee", null);
        return u;
    }

    @SuppressWarnings("unchecked")
    private void attach(List<Map<String, Object>> users) {
        if (users.isEmpty()) {
            return;
        }
        List<String> ids = users.stream().map(u -> (String) u.get("id")).toList();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        users.forEach(u -> byId.put((String) u.get("id"), u));
        jdbc.sql("""
                        SELECT ur."B" AS "userId", r.id, r.name FROM "_UserRoles" ur JOIN "Role" r ON r.id = ur."A"
                        WHERE ur."B" IN (:ids) ORDER BY r."createdAt", r.id""")
                .param("ids", ids)
                .query((rs, n) -> {
                    Map<String, Object> role = new LinkedHashMap<>();
                    role.put("id", rs.getString("id"));
                    role.put("name", rs.getString("name"));
                    ((List<Map<String, Object>>) byId.get(rs.getString("userId")).get("roles")).add(role);
                    return role;
                })
                .list();
        jdbc.sql("""
                        SELECT "userId", id, "employeeCode", "firstName", "lastName", "employmentStatus"::text AS "employmentStatus"
                        FROM "HrEmployee" WHERE "userId" IN (:ids)""")
                .param("ids", ids)
                .query((rs, n) -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("id", rs.getString("id"));
                    e.put("employeeCode", rs.getString("employeeCode"));
                    e.put("firstName", rs.getString("firstName"));
                    e.put("lastName", rs.getString("lastName"));
                    e.put("employmentStatus", rs.getString("employmentStatus"));
                    byId.get(rs.getString("userId")).put("employee", e);
                    return e;
                })
                .list();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
