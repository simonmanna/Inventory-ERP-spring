package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plain-SQL row access for the sales tables (ADR-S011). Every statement is bound to the current
 * organization; rows come back as JSON-ready maps (numerics as JS numbers, timestamps as {@link Instant}, dates as
 * {@link LocalDate}, {@code text[]} as lists).
 */
@Component
public class SalesDb {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;

    public SalesDb(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    static String org() {
        return TenantContext.organizationId();
    }

    static String user() {
        return TenantContext.userId();
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Rows of {@code sql} with JSON-ready values. */
    List<Map<String, Object>> rows(String sql, Collection<?> params) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql(sql).params(params.stream().map(SalesDb::param).toList()).query()
                .listOfRows()) {
            out.add(json(r));
        }
        return out;
    }

    List<Map<String, Object>> rows(String sql, Object... params) {
        return rows(sql, java.util.Arrays.asList(params));
    }

    Map<String, Object> row(String sql, Object... params) {
        List<Map<String, Object>> r = rows(sql, params);
        return r.isEmpty() ? null : r.getFirst();
    }

    long count(String sql, Collection<?> params) {
        Long n = jdbc.sql(sql).params(new ArrayList<>(params)).query(Long.class).single();
        return n == null ? 0 : n;
    }

    int update(String sql, Object... params) {
        return jdbc.sql(sql).params(java.util.Arrays.stream(params).map(SalesDb::param).toList()).update();
    }

    /** Org row of {@code table} by id or 404. */
    Map<String, Object> require(String table, String id, String message) {
        Map<String, Object> r = id == null ? null
                : row("SELECT * FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        if (r == null) {
            throw ApiException.notFound(message);
        }
        return r;
    }

    boolean exists(String table, String id) {
        return id != null && count("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?",
                List.of(id, org())) > 0;
    }

    /** The customer a new sales document is for must exist and be active. */
    void requireActivePartner(String partnerId) {
        Map<String, Object> p = partnerId == null ? null : row("SELECT status::text AS status, \"deletedAt\","
                + " \"suspendedAt\", \"suspendedReason\", \"mergedIntoPartnerId\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?", partnerId, org());
        if (p == null) {
            throw ApiException.notFound("Partner not found");
        }
        if (p.get("mergedIntoPartnerId") != null) {
            throw ApiException.badRequest("This customer was merged into another record; use that record");
        }
        if (p.get("suspendedAt") != null) {
            throw ApiException.badRequest("This customer is suspended: " + p.get("suspendedReason"));
        }
        if (!"active".equals(p.get("status")) || p.get("deletedAt") != null) {
            throw ApiException.badRequest("This customer is not active");
        }
    }

    /**
     * Insert {@code data} (null values skipped) into {@code table}; {@code id} and {@code organizationId} default.
     * Returns the inserted row.
     */
    Map<String, Object> insert(String table, Map<String, Object> data) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", newId());
        d.put("organizationId", org());
        d.putAll(data);
        StringBuilder names = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : d.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!names.isEmpty()) {
                names.append(", ");
                vals.append(", ");
            }
            names.append('"').append(e.getKey()).append('"');
            vals.append('?');
            params.add(param(e.getValue()));
        }
        return json(SqlRows.normalize(jdbc.sql("INSERT INTO \"" + table + "\" (" + names + ") VALUES (" + vals
                + ") RETURNING *").params(params).query().listOfRows().getFirst()));
    }

    /**
     * Update the org row {@code id} of {@code table}; null values write NULL. {@code touch} also sets
     * {@code updatedAt}. Returns the row or null when absent.
     */
    Map<String, Object> patch(String table, String id, Map<String, Object> data, boolean touch) {
        StringBuilder set = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (!set.isEmpty()) {
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
        if (touch) {
            set.append(set.isEmpty() ? "" : ", ").append("\"updatedAt\" = ").append(NOW);
        }
        if (set.isEmpty()) {
            return row("SELECT * FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        }
        params.add(id);
        params.add(org());
        List<Map<String, Object>> out = jdbc.sql("UPDATE \"" + table + "\" SET " + set + " WHERE id = ? AND"
                + " \"organizationId\" = ? RETURNING *").params(params).query().listOfRows();
        return out.isEmpty() ? null : json(SqlRows.normalize(out.getFirst()));
    }

    /** JDBC parameter of a Java value (timestamps / dates / lists). */
    static Object param(Object v) {
        if (v instanceof Instant i) {
            return SqlRows.ts(i);
        }
        if (v instanceof LocalDate d) {
            return java.sql.Date.valueOf(d);
        }
        if (v instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toArray(String[]::new);
        }
        return v;
    }

    /** JSON-ready copy of a raw row. */
    static Map<String, Object> json(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            Object val = SqlRows.value(v);
            if (val instanceof BigDecimal b) {
                val = JsNumbers.js(b);
            }
            out.put(k, val);
        });
        return out;
    }

    // ------------------------------------------------------------------ value helpers

    static BigDecimal dec(Object v) {
        if (v == null || v.toString().isBlank()) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    static BigDecimal decOrNull(Object v) {
        return v == null || v.toString().isBlank() ? null : dec(v);
    }

    static String str(Object v) {
        return v == null ? null : v.toString();
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static Instant instant(String iso) {
        if (blank(iso)) {
            return null;
        }
        try {
            return iso.length() == 10 ? LocalDate.parse(iso).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
                    : com.inventoryerp.kernel.crud.EntityPatch.toInstant(iso);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid date: " + iso);
        }
    }

    static LocalDate date(String iso) {
        if (blank(iso)) {
            return null;
        }
        try {
            return LocalDate.parse(iso.length() > 10 ? iso.substring(0, 10) : iso);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid date: " + iso);
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return List.of(v.toString());
    }

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** JSON text of a value (for {@code ?::jsonb} parameters). */
    static String toJson(Object v) {
        return MAPPER.writeValueAsString(v);
    }

    /** A {@code jsonb} parameter of a value (insert / patch maps). */
    static Object jsonb(Object v) {
        try {
            org.postgresql.util.PGobject o = new org.postgresql.util.PGobject();
            o.setType("jsonb");
            o.setValue(toJson(v));
            return o;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static int parseInt(String v, int fallback) {
        try {
            return blank(v) ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code "a", "b"} placeholders for an IN list. */
    static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(Math.max(1, n), "?"));
    }
}
