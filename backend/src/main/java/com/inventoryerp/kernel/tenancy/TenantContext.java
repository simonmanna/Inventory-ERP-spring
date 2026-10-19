package com.inventoryerp.kernel.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Request-scoped tenant context (port of {@code tenant-context.service.ts}). Set once per request by
 * the authentication filter (or explicitly by jobs / public flows via {@link #run}) and read
 * everywhere; the tenant-aware transaction manager uses it to enable the Hibernate tenant filter and
 * {@code SET LOCAL app.org_id}.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantStore> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** Mutable holder; only the permission set changes after creation (authoritative DB set). */
    public static final class TenantStore {
        private final String organizationId;
        private final String userId;
        private final String email;
        private final String posUserId;
        private final String deviceId;
        private final String branchId;
        private volatile List<String> permissions;

        public TenantStore(String organizationId, String userId, String email, String posUserId, String deviceId,
                String branchId) {
            this.organizationId = organizationId;
            this.userId = userId;
            this.email = email;
            this.posUserId = posUserId;
            this.deviceId = deviceId;
            this.branchId = branchId;
        }

        public static TenantStore organization(String organizationId) {
            return new TenantStore(organizationId, null, null, null, null, null);
        }

        public static TenantStore user(String organizationId, String userId) {
            return new TenantStore(organizationId, userId, null, null, null, null);
        }

        public String organizationId() {
            return organizationId;
        }

        public String userId() {
            return userId;
        }

        public String email() {
            return email;
        }

        public String posUserId() {
            return posUserId;
        }

        public String deviceId() {
            return deviceId;
        }

        public String branchId() {
            return branchId;
        }

        public List<String> permissions() {
            return permissions;
        }

        public void setPermissions(List<String> permissions) {
            this.permissions = permissions;
        }
    }

    public static Optional<TenantStore> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Throws when there is no tenant context: fail loud rather than leak across tenants. */
    public static String organizationId() {
        TenantStore s = CURRENT.get();
        if (s == null || s.organizationId == null || s.organizationId.isEmpty()) {
            throw new IllegalStateException("No tenant context: an organizationId is required for this operation.");
        }
        return s.organizationId;
    }

    public static String optionalOrganizationId() {
        TenantStore s = CURRENT.get();
        return s == null ? null : s.organizationId;
    }

    public static String userId() {
        TenantStore s = CURRENT.get();
        return s == null ? null : s.userId;
    }

    public static String posUserId() {
        TenantStore s = CURRENT.get();
        return s == null ? null : s.posUserId;
    }

    /** Permissions published by the permission guard (or loaded on demand by {@code PermissionService}). */
    public static List<String> permissions() {
        TenantStore s = CURRENT.get();
        List<String> p = s == null ? null : s.permissions;
        return p == null ? List.of() : p;
    }

    public static boolean permissionsLoaded() {
        TenantStore s = CURRENT.get();
        return s != null && s.permissions != null;
    }

    public static void setPermissions(List<String> permissions) {
        TenantStore s = CURRENT.get();
        if (s != null) {
            s.setPermissions(permissions);
        }
    }

    /** Field-level authorization helper (route-level access is settled by the guard). */
    public static boolean has(String permission) {
        return permissions().contains(permission);
    }

    /** Run {@code body} inside {@code store}, restoring the previous context afterwards. */
    public static <T> T run(TenantStore store, Supplier<T> body) {
        TenantStore previous = CURRENT.get();
        set(store);
        try {
            return body.get();
        } finally {
            set(previous);
        }
    }

    public static void run(TenantStore store, Runnable body) {
        run(store, () -> {
            body.run();
            return null;
        });
    }

    /** Run without any tenant (Nest {@code prisma.raw}): no tenant filter, no RLS GUC. */
    public static <T> T unscoped(Supplier<T> body) {
        return run(null, body);
    }

    /** Low-level: used by the request filter only. */
    public static void set(TenantStore store) {
        if (store == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(store);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
