package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.crud.TenancyScope;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Row access for the quality services on the module's entities: insert / update / find under the tenancy scope
 * ({@code "organizationId" = ?}, plus {@code "deletedAt" IS NULL} for soft-deleting tables). Rows come back via
 * {@link SqlRows} in schema column order (timestamps as {@link Instant}, dates as {@link LocalDate}).
 */
@Component
public class QualityDb {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;

    public QualityDb(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    static String org() {
        return TenantContext.organizationId();
    }

    static String table(Class<?> entity) {
        jakarta.persistence.Table t = entity.getAnnotation(jakarta.persistence.Table.class);
        return t == null ? entity.getSimpleName() : t.name();
    }

    /** Tenancy predicate of {@code entity} (binds the org id). */
    static String scope(Class<?> entity, String alias) {
        return TenancyScope.predicate(entity, alias);
    }

    /** Insert; {@code id}, {@code organizationId}, {@code createdAt} / {@code updatedAt} default. Null values skip. */
    Map<String, Object> insert(Class<?> entity, Map<String, Object> data) {
        List<String> cols = EntityJson.columnNames(entity);
        Map<String, Object> d = new LinkedHashMap<>();
        if (data.get("id") == null) {
            d.put("id", UUID.randomUUID().toString());
        }
        if (TenancyScope.orgScoped(entity) && data.get("organizationId") == null) {
            d.put("organizationId", org());
        }
        d.putAll(data);
        StringBuilder names = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : d.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            requireColumn(cols, entity, e.getKey());
            append(names, vals, e.getKey());
            vals.append('?');
            params.add(param(e.getValue()));
        }
        for (String ts : List.of("createdAt", "updatedAt")) {
            if (cols.contains(ts) && !d.containsKey(ts)) {
                append(names, vals, ts);
                vals.append(NOW);
            }
        }
        return SqlRows.one(entity, jdbc.sql("INSERT INTO \"" + table(entity) + "\" (" + names + ") VALUES (" + vals
                + ") RETURNING *").params(params));
    }

    /** Update by id under the tenancy scope; {@code null} values write NULL. Returns the row or null if absent. */
    Map<String, Object> update(Class<?> entity, String id, Map<String, Object> data) {
        List<String> cols = EntityJson.columnNames(entity);
        StringBuilder set = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            requireColumn(cols, entity, e.getKey());
            if (set.length() > 0) {
                set.append(", ");
            }
            set.append('"').append(e.getKey()).append("\" = ");
            if (e.getValue() == null) {
                set.append("NULL");
            } else {
                set.append('?');
                params.add(param(e.getValue()));
            }
        }
        if (cols.contains("updatedAt") && !data.containsKey("updatedAt")) {
            if (set.length() > 0) {
                set.append(", ");
            }
            set.append("\"updatedAt\" = ").append(NOW);
        }
        params.add(id);
        if (TenancyScope.orgScoped(entity)) {
            params.add(org());
        }
        return SqlRows.one(entity, jdbc.sql("UPDATE \"" + table(entity) + "\" SET " + set + " WHERE id = ? AND "
                + scope(entity, null) + " RETURNING *").params(params));
    }

    /** Row by id under the tenancy scope, or null. {@code lock} = {@code FOR UPDATE}. */
    Map<String, Object> byId(Class<?> entity, String id, boolean lock) {
        if (id == null) {
            return null;
        }
        List<Object> params = new ArrayList<>(List.of(id));
        if (TenancyScope.orgScoped(entity)) {
            params.add(org());
        }
        return SqlRows.one(entity, jdbc.sql("SELECT * FROM \"" + table(entity) + "\" WHERE id = ? AND "
                + scope(entity, null) + (lock ? " FOR UPDATE" : "")).params(params));
    }

    /** Row by id or 404 with {@code message}. */
    Map<String, Object> require(Class<?> entity, String id, boolean lock, String message) {
        Map<String, Object> row = byId(entity, id, lock);
        if (row == null) {
            throw ApiException.notFound(message);
        }
        return row;
    }

    /** Rows of {@code entity} under the tenancy scope; {@code extra} is {@code " AND ... ORDER BY ..."}. */
    List<Map<String, Object>> list(Class<?> entity, String extra, Object... params) {
        List<Object> all = new ArrayList<>();
        if (TenancyScope.orgScoped(entity)) {
            all.add(org());
        }
        all.addAll(List.of(params));
        return SqlRows.list(entity, jdbc.sql("SELECT * FROM \"" + table(entity) + "\" WHERE " + scope(entity, null)
                + extra).params(all));
    }

    /** Does an org-scoped row of {@code table} with this id exist (optionally not soft-deleted)? */
    boolean exists(String table, String id, boolean softDelete) {
        return jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?"
                + (softDelete ? " AND \"deletedAt\" IS NULL" : "")).params(id, org()).query(Long.class).single() > 0;
    }

    private static void requireColumn(List<String> cols, Class<?> entity, String col) {
        if (!cols.contains(col)) {
            throw new IllegalStateException("Unknown column " + col + " of " + entity.getSimpleName());
        }
    }

    private static void append(StringBuilder names, StringBuilder vals, String col) {
        if (names.length() > 0) {
            names.append(", ");
            vals.append(", ");
        }
        names.append('"').append(col).append('"');
    }

    static Object param(Object v) {
        if (v instanceof Instant i) {
            return SqlRows.ts(i);
        }
        if (v instanceof LocalDate d) {
            return java.sql.Date.valueOf(d);
        }
        return v;
    }
}
