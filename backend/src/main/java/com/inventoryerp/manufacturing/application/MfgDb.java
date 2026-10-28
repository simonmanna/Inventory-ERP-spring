package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.crud.TenancyScope;
import com.inventoryerp.kernel.tenancy.TenantContext;
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
 * Prisma-shaped row access for the manufacturing services: {@code create}/{@code update}/{@code findFirst} on a
 * generated entity's table, with the tenancy extension's {@code organizationId} (+ {@code deletedAt IS NULL}) scope
 * injected exactly where Prisma injects it (top-level where clauses, not includes). Rows come back via
 * {@link SqlRows} in schema column order.
 */
@Component
public class MfgDb {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Marks a value for a {@code jsonb} column. */
    record Json(Object value) {}

    /** Marks a raw SQL expression (e.g. {@code "overheadCost" + ?}). */
    record Raw(String sql, Object... params) {}

    private final JdbcClient jdbc;

    public MfgDb(JdbcClient jdbc) {
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

    /** The tenancy predicate of {@code entity} (bind {@link #scopeParams} for it). */
    static String scope(Class<?> entity, String alias) {
        return TenancyScope.predicate(entity, alias);
    }

    static List<Object> scopeParams(Class<?> entity) {
        return TenancyScope.orgScoped(entity) ? List.of(org()) : List.of();
    }

    /** {@code prisma.<model>.create({data})}; {@code organizationId} defaults to the tenant like the extension. */
    Map<String, Object> insert(Class<?> entity, Map<String, Object> data) {
        List<String> cols = EntityJson.columnNames(entity);
        Map<String, Object> d = new LinkedHashMap<>();
        if (cols.contains("id") && data.get("id") == null) {
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
            append(names, vals, e.getKey());
            bind(vals, params, e.getValue());
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

    /**
     * {@code prisma.<model>.update({where: {id}, data})} under the tenancy scope; a missing row is Prisma's P2025
     * (an unhandled 500 in Nest). {@code null} values write NULL; absent keys are left alone.
     */
    Map<String, Object> update(Class<?> entity, String id, Map<String, Object> data) {
        List<Object> where = new ArrayList<>(List.of(id));
        where.addAll(scopeParams(entity));
        List<Map<String, Object>> rows = updateWhere(entity, data, "id = ? AND " + scope(entity, null),
                where.toArray());
        if (rows.isEmpty()) {
            throw new IllegalStateException("An operation failed because it depends on one or more records that were"
                    + " required but not found. No record was found for an update.");
        }
        return rows.getFirst();
    }

    /** {@code updateMany} (caller passes the full where clause incl. tenancy); returns the updated rows. */
    List<Map<String, Object>> updateWhere(Class<?> entity, Map<String, Object> data, String where,
            Object... whereParams) {
        List<String> cols = EntityJson.columnNames(entity);
        StringBuilder set = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (set.length() > 0) {
                set.append(", ");
            }
            set.append('"').append(e.getKey()).append("\" = ");
            if (e.getValue() == null) {
                set.append("NULL");
            } else {
                bind(set, params, e.getValue());
            }
        }
        if (cols.contains("updatedAt") && !data.containsKey("updatedAt")) {
            if (set.length() > 0) {
                set.append(", ");
            }
            set.append("\"updatedAt\" = ").append(NOW);
        }
        params.addAll(List.of(whereParams));
        return SqlRows.list(entity, jdbc.sql("UPDATE \"" + table(entity) + "\" SET " + set + " WHERE " + where
                + " RETURNING *").params(params));
    }

    /**
     * Prisma {@code data: {...body}} from an unvalidated ({@code Partial<Dto>}) body: every key must be a scalar
     * column of {@code entity}; anything else is Prisma's "Unknown argument" validation error (a 500 in Nest).
     */
    static Map<String, Object> spread(Class<?> entity, Map<String, Object> body) {
        List<String> cols = EntityJson.columnNames(entity);
        Map<String, Object> data = new LinkedHashMap<>();
        if (body == null) {
            return data;
        }
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!cols.contains(e.getKey())) {
                throw new IllegalStateException("Invalid `prisma." + entity.getSimpleName() + ".update()` invocation:"
                        + " Unknown argument `" + e.getKey() + "`.");
            }
            if (e.getValue() instanceof Map<?, ?> || e.getValue() instanceof List<?>) {
                data.put(e.getKey(), new Json(e.getValue()));
            } else {
                data.put(e.getKey(), e.getValue());
            }
        }
        return data;
    }

    /** {@code findFirst({where: {id}})} under the tenancy scope, or null. */
    Map<String, Object> byId(Class<?> entity, String id) {
        List<Object> params = new ArrayList<>(List.of(id));
        params.addAll(scopeParams(entity));
        return SqlRows.one(entity, jdbc.sql("SELECT * FROM \"" + table(entity) + "\" WHERE id = ? AND "
                + scope(entity, null) + " LIMIT 1").params(params));
    }

    /** Rows of {@code entity} under the tenancy scope: {@code extra} is {@code " AND ... ORDER BY ..."}. */
    List<Map<String, Object>> list(Class<?> entity, String extra, Object... params) {
        List<Object> all = new ArrayList<>(scopeParams(entity));
        all.addAll(List.of(params));
        return SqlRows.list(entity, jdbc.sql("SELECT * FROM \"" + table(entity) + "\" WHERE " + scope(entity, null)
                + extra).params(all));
    }

    /** Relation rows of an include (no tenancy injection, as Prisma includes): {@code fk = ?} plus order. */
    List<Map<String, Object>> children(Class<?> entity, String fk, Object parentId, String orderBy) {
        return SqlRows.list(entity, jdbc.sql("SELECT * FROM \"" + table(entity) + "\" WHERE \"" + fk + "\" = ?"
                + (orderBy == null ? "" : " ORDER BY " + orderBy)).param(parentId));
    }

    private static void append(StringBuilder names, StringBuilder vals, String col) {
        if (names.length() > 0) {
            names.append(", ");
            vals.append(", ");
        }
        names.append('"').append(col).append('"');
    }

    private static void bind(StringBuilder sql, List<Object> params, Object v) {
        if (v instanceof Raw r) {
            sql.append(r.sql());
            params.addAll(List.of(r.params()));
            return;
        }
        if (v instanceof Json j) {
            sql.append("?::jsonb");
            params.add(j.value() == null ? null : EntityJson.toJson(j.value()));
            return;
        }
        sql.append('?');
        params.add(param(v));
    }

    static Object param(Object v) {
        if (v instanceof Instant i) {
            return SqlRows.ts(i);
        }
        if (v instanceof LocalDate d) {
            return java.sql.Date.valueOf(d);
        }
        if (v instanceof com.inventoryerp.shared.persistence.DbEnum e) {
            return e.value();
        }
        return v;
    }
}
