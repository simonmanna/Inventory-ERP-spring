package com.inventoryerp.kernel.tenancy;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default entity listener (registered in {@code META-INF/orm.xml}). On persist it assigns
 * {@code organizationId} from the tenant context when the entity has the column and it is unset
 * (Nest tenancy extension: {@code create} injects organizationId when undefined). On update it
 * refuses a write to another tenant's row.
 */
public class OrganizationScopeListener {

    private static final Map<Class<?>, Optional<Field>> ORG_FIELD = new ConcurrentHashMap<>();

    @PrePersist
    void assignOrganization(Object entity) {
        String orgId = TenantContext.optionalOrganizationId();
        if (orgId == null) {
            return;
        }
        field(entity.getClass()).ifPresent(f -> {
            try {
                if (f.get(entity) == null) {
                    f.set(entity, orgId);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @PreUpdate
    void refuseCrossTenantUpdate(Object entity) {
        String orgId = TenantContext.optionalOrganizationId();
        if (orgId == null) {
            return;
        }
        field(entity.getClass()).ifPresent(f -> {
            try {
                Object current = f.get(entity);
                if (current != null && !orgId.equals(current)) {
                    throw new IllegalStateException("Refusing to update " + entity.getClass().getSimpleName()
                            + " of another organization");
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static Optional<Field> field(Class<?> type) {
        return ORG_FIELD.computeIfAbsent(type, t -> {
            // Only the Nest ORG_SCOPED models (those carrying the tenant filter); e.g. Setting is excluded.
            boolean scoped = java.util.Arrays.stream(t.getAnnotationsByType(org.hibernate.annotations.Filter.class))
                    .anyMatch(f -> com.inventoryerp.shared.persistence.TenancyFilters.TENANT.equals(f.name()));
            if (!scoped) {
                return Optional.empty();
            }
            try {
                Field f = t.getDeclaredField("organizationId");
                f.setAccessible(true);
                return Optional.of(f);
            } catch (NoSuchFieldException e) {
                return Optional.empty();
            }
        });
    }
}
