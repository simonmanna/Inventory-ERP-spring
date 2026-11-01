package com.inventoryerp.kernel.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.sql.Array;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Authoritative permission set of a user, read from the database (Nest {@code PermissionsGuard} DB
 * mode, {@code PERMISSIONS_DB_LOOKUP}). Inactive or soft-deleted users hold nothing. Cached per user for
 * at most 60 seconds and evicted whenever roles or users change ({@link #evictAll()}).
 */
@Service
public class PermissionService {

    private final JdbcClient jdbc;
    private final Cache<String, List<String>> cache =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(20_000).build();

    public PermissionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Union of the user's role permissions (empty when the user is inactive, deleted or unknown). */
    public List<String> permissionsOf(String organizationId, String userId) {
        if (organizationId == null || userId == null) {
            return List.of();
        }
        return cache.get(organizationId + ":" + userId, k -> load(organizationId, userId));
    }

    /** Permissions of the current request's actor, loaded once and published on the tenant context. */
    public List<String> current() {
        if (TenantContext.permissionsLoaded()) {
            return TenantContext.permissions();
        }
        List<String> granted = permissionsOf(TenantContext.optionalOrganizationId(), TenantContext.userId());
        TenantContext.setPermissions(granted);
        return granted;
    }

    /** Drop every cached entry (role or user changed). */
    public void evictAll() {
        cache.invalidateAll();
        clientCache.invalidateAll();
    }

    private final Cache<String, List<String>> clientCache =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(5_000).build();

    /**
     * Scopes an API client still holds (ADR-S029 §5): the live row's scopes when it is active, not revoked and not
     * expired, else none. Cached for at most 60 seconds; revoking or editing a client evicts the cache.
     */
    public List<String> clientScopes(String organizationId, String clientRowId) {
        if (organizationId == null || clientRowId == null) {
            return List.of();
        }
        return clientCache.get(organizationId + ":" + clientRowId, k -> jdbc.sql("""
                        SELECT scopes FROM "ApiClient" WHERE id = ? AND "organizationId" = ? AND "isActive"
                          AND "revokedAt" IS NULL AND ("expiresAt" IS NULL OR "expiresAt" > now())""")
                .params(clientRowId, organizationId)
                .query((rs, n) -> {
                    Array a = rs.getArray(1);
                    return a == null ? List.<String>of() : List.of((String[]) a.getArray());
                })
                .optional().orElse(List.of()));
    }

    private List<String> load(String organizationId, String userId) {
        List<String[]> rows = jdbc.sql("""
                        SELECT r.permissions FROM "User" u
                        JOIN "_UserRoles" ur ON ur."B" = u.id
                        JOIN "Role" r ON r.id = ur."A"
                        WHERE u.id = ? AND u."organizationId" = ? AND u."isActive" = true AND u."deletedAt" IS NULL
                        ORDER BY r."createdAt", r.id""")
                .params(userId, organizationId)
                .query((rs, n) -> {
                    Array a = rs.getArray(1);
                    return a == null ? new String[0] : (String[]) a.getArray();
                })
                .list();
        Set<String> all = new LinkedHashSet<>();
        for (String[] perms : rows) {
            all.addAll(List.of(perms));
        }
        return List.copyOf(new ArrayList<>(all));
    }
}
