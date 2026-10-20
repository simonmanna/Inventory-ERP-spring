package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tenant- and soft-delete-scoped reads shared by the inventory services (the Prisma tenancy extension adds
 * {@code organizationId} and, for soft-delete models, {@code deletedAt IS NULL} to every top-level query).
 */
@Component
public class InventoryLookups {

    private final JdbcClient jdbc;

    public InventoryLookups(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public static String org() {
        return TenantContext.organizationId();
    }

    /** {@code product.findFirst({where: {id}})} as a raw row (null when missing). */
    public Map<String, Object> product(String id) {
        if (id == null) {
            return null;
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL LIMIT 1").params(id, org()));
    }

    /** {@code inventoryLocation.findFirst({where: {id}})} (null when missing). */
    public Map<String, Object> location(String id) {
        if (id == null) {
            return null;
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL LIMIT 1").params(id, org()));
    }

    /** Location or the Nest 404 {@code Location not found}. */
    public Map<String, Object> requireLocation(String id) {
        Map<String, Object> loc = location(id);
        if (loc == null) {
            throw ApiException.notFound("Location not found");
        }
        return loc;
    }

    /** The quant row for (product, variant, location), or null. */
    public Map<String, Object> stockItem(String productId, String variantId, String locationId) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                        + " AND \"variantKey\" = ? AND \"locationId\" = ? LIMIT 1")
                .params(org(), productId, variantId == null ? "" : variantId, locationId));
    }

    /** Product id → name for the given ids (soft-delete filtered). */
    public Map<String, String> productNames(Collection<String> ids) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : jdbc.sql("SELECT id, name FROM \"Product\" WHERE id IN (:ids)"
                        + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL")
                .param("ids", List.copyOf(ids)).param("org", org()).query().listOfRows()) {
            out.put((String) r.get("id"), (String) r.get("name"));
        }
        return out;
    }

    /** {@code {id, code, name}} of a location row (Prisma {@code select}). */
    public static Map<String, Object> locSelect(Map<String, Object> loc, String... fields) {
        if (loc == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : fields) {
            out.put(f, loc.get(f));
        }
        return out;
    }

    public static BigDecimal dec(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? BigDecimal.ZERO : (BigDecimal) v;
    }

    public static boolean bool(Map<String, Object> row, String key) {
        return row != null && Boolean.TRUE.equals(row.get(key));
    }

    public static String str(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? null : v.toString();
    }
}
