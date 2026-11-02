package com.inventoryerp.hr.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
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
 * Row access for the Spring-native HR tables of HP-1…HP-10 (no JPA entities, like {@code reporting} and {@code dms}).
 * Every statement is scoped to the current organisation and skips soft-deleted rows; maps and lists are stored as
 * {@code jsonb}.
 */
@Component
public class HrRows {

    /** Plain maps / lists for jsonb columns; decimals stay exact. */
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final JdbcClient jdbc;

    public HrRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    static String org() {
        String org = TenantContext.organizationId();
        if (org == null) {
            throw ApiException.forbidden("Organization context required");
        }
        return org;
    }

    static String user() {
        return TenantContext.userId();
    }

    public Map<String, Object> insert(String table, Map<String, Object> data) {
        Map<String, Object> row = new LinkedHashMap<>(data);
        row.putIfAbsent("id", UUID.randomUUID().toString());
        row.put("organizationId", org());
        row.putIfAbsent("createdBy", user());
        List<String> cols = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        row.forEach((k, v) -> {
            cols.add("\"" + k + "\"");
            marks.add(mark(v));
            params.add(param(v));
        });
        cols.add("\"createdAt\"");
        marks.add(PosSql.NOW);
        cols.add("\"updatedAt\"");
        marks.add(PosSql.NOW);
        return one(jdbc.sql("INSERT INTO \"" + table + "\" (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", marks) + ") RETURNING *").params(params));
    }

    public Map<String, Object> update(String table, String id, Map<String, Object> data) {
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        data.forEach((k, v) -> {
            sets.add("\"" + k + "\" = " + mark(v));
            params.add(param(v));
        });
        sets.add("\"updatedAt\" = " + PosSql.NOW);
        if (!data.containsKey("updatedBy")) {
            sets.add("\"updatedBy\" = ?");
            params.add(user());
        }
        params.add(id);
        params.add(org());
        Map<String, Object> row = one(jdbc.sql("UPDATE \"" + table + "\" SET " + String.join(", ", sets)
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL RETURNING *").params(params));
        if (row == null) {
            throw ApiException.notFound("Record not found");
        }
        return row;
    }

    public void softDelete(String table, String id) {
        jdbc.sql("UPDATE \"" + table + "\" SET \"deletedAt\" = " + PosSql.NOW + ", \"updatedBy\" = ? WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(user(), id, org()).update();
    }

    public Map<String, Object> find(String table, Object id) {
        if (id == null) {
            return null;
        }
        return one(jdbc.sql("SELECT * FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(id, org()));
    }

    public Map<String, Object> require(String table, Object id, String notFound) {
        Map<String, Object> row = find(table, id);
        if (row == null) {
            throw ApiException.notFound(notFound);
        }
        return row;
    }

    /** {@code SELECT * FROM table WHERE org AND not deleted [AND where] [ORDER BY order]}. */
    public List<Map<String, Object>> list(String table, String where, String order, Object... params) {
        List<Object> p = new ArrayList<>();
        p.add(org());
        p.addAll(bind(params));
        return rows(jdbc.sql("SELECT * FROM \"" + table + "\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + (where == null ? "" : " AND (" + where + ")") + (order == null ? "" : " ORDER BY " + order))
                .params(p));
    }

    public Map<String, Object> first(String table, String where, String order, Object... params) {
        List<Map<String, Object>> rows = list(table, where, (order == null ? "id" : order) + " LIMIT 1", params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public long count(String table, String where, Object... params) {
        List<Object> p = new ArrayList<>();
        p.add(org());
        p.addAll(bind(params));
        return jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + (where == null ? "" : " AND (" + where + ")")).params(p).query(Long.class).single();
    }

    /** Arbitrary query; caller binds the organisation. */
    public List<Map<String, Object>> query(String sql, Object... params) {
        return rows(jdbc.sql(sql).params(bind(params)));
    }

    public Map<String, Object> queryOne(String sql, Object... params) {
        List<Map<String, Object>> r = query(sql, params);
        return r.isEmpty() ? null : r.getFirst();
    }

    /** Rows with Prisma-like values; {@code json} / {@code jsonb} columns parsed into maps and lists. */
    public static List<Map<String, Object>> rows(JdbcClient.StatementSpec spec) {
        return spec.query((rs, n) -> {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            Map<String, Object> m = new LinkedHashMap<>();
            for (int c = 1; c <= md.getColumnCount(); c++) {
                Object v = rs.getObject(c);
                String type = md.getColumnTypeName(c);
                if (v != null && ("jsonb".equals(type) || "json".equals(type))) {
                    v = JSON.readValue(v instanceof org.postgresql.util.PGobject pg ? pg.getValue() : v.toString(),
                            Object.class);
                }
                m.put(md.getColumnLabel(c), SqlRows.value(v));
            }
            return m;
        }).list();
    }

    static Map<String, Object> one(JdbcClient.StatementSpec spec) {
        List<Map<String, Object>> r = rows(spec);
        return r.isEmpty() ? null : r.getFirst();
    }

    private static List<Object> bind(Object... params) {
        List<Object> out = new ArrayList<>();
        for (Object o : params) {
            out.add(param(o));
        }
        return out;
    }

    private static String mark(Object v) {
        return v instanceof Map<?, ?> || v instanceof List<?> ? "CAST(? AS jsonb)" : "?";
    }

    private static Object param(Object v) {
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            return EntityJson.toJson(v);
        }
        if (v instanceof Instant i) {
            return SqlRows.ts(i);
        }
        if (v instanceof LocalDate d) {
            return java.sql.Date.valueOf(d);
        }
        return v;
    }

    // ------------------------------------------------------------------ value helpers

    static BigDecimal dec(Object v) {
        if (v == null || (v instanceof String s && s.isBlank())) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid number: " + v);
        }
    }

    static BigDecimal decOr(Object v, BigDecimal fallback) {
        BigDecimal d = dec(v);
        return d == null ? fallback : d;
    }

    static LocalDate date(Object v) {
        if (v == null || (v instanceof String s && s.isBlank())) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof Instant i) {
            return i.atOffset(java.time.ZoneOffset.UTC).toLocalDate();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        String s = String.valueOf(v);
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid date: " + s);
        }
    }

    static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    static void oneOf(String field, Object value, List<String> allowed) {
        if (!(value instanceof String) || !allowed.contains(value)) {
            throw ApiException.badRequest(field + " must be one of " + String.join(", ", allowed));
        }
    }

    static Map<String, Object> page(List<Map<String, Object>> rows, long total) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        return out;
    }
}
