package com.inventoryerp.kernel.crud;

import com.inventoryerp.shared.persistence.TenancyFilters;
import org.hibernate.annotations.Filter;

/**
 * The Prisma tenancy extension's injected predicate for a model, for hand-written SQL: {@code organizationId = ?} when
 * the model is in {@code ORG_SCOPED}, plus {@code deletedAt IS NULL} when it is in {@code SOFT_DELETE}. Both sets are
 * generated onto the entities as Hibernate filters, so the entity class is the source of truth.
 */
public final class TenancyScope {

    private TenancyScope() {}

    public static boolean softDeletes(Class<?> entity) {
        return hasFilter(entity, TenancyFilters.SOFT_DELETE);
    }

    public static boolean orgScoped(Class<?> entity) {
        return hasFilter(entity, TenancyFilters.TENANT);
    }

    /** {@code "organizationId" = ? [AND "deletedAt" IS NULL]} (or {@code true}); bind the org id when org-scoped. */
    public static String predicate(Class<?> entity, String alias) {
        String p = alias == null ? "" : alias + ".";
        String s = orgScoped(entity) ? p + "\"organizationId\" = ?" : "true";
        return softDeletes(entity) ? s + " AND " + p + "\"deletedAt\" IS NULL" : s;
    }

    private static boolean hasFilter(Class<?> entity, String name) {
        for (Filter f : entity.getAnnotationsByType(Filter.class)) {
            if (name.equals(f.name())) {
                return true;
            }
        }
        return false;
    }
}
