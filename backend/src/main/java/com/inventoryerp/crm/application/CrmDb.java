package com.inventoryerp.crm.application;

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
 * Plain-SQL row access for the CRM sales-suite tables (ADR-S010). Every statement is bound to the current
 * organization; rows come back as JSON-ready maps (numerics as JS numbers, timestamps as {@link Instant}, dates as
 * {@link LocalDate}, {@code text[]} as lists).
 */
@Component
public class CrmDb {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;

    public CrmDb(JdbcClient jdbc) {
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
        for (Map<String, Object> r : jdbc.sql(sql).params(params.stream().map(CrmDb::param).toList()).query()
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
        return jdbc.sql(sql).params(java.util.Arrays.stream(params).map(CrmDb::param).toList()).update();
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

    /**
     * Decimal of a request or row value; blank = 0. A value that is not a number is a 400, never a 500
     * (production-readiness fix P0-3).
     */
    static BigDecimal dec(Object v) {
        return dec(v, null);
    }

    /** {@link #dec(Object)} naming {@code field} in the 400 message. */
    static BigDecimal dec(Object v, String field) {
        if (v == null || v.toString().isBlank()) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Boolean || v instanceof Map<?, ?> || v instanceof Collection<?>) {
            throw notANumber(field, v);
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            throw notANumber(field, v);
        }
    }

    /**
     * Whole number of a request value (JSON number or numeric string); null stays null. Not a whole number = 400
     * {@code "<field> must be an integer number"} (class-validator's {@code @IsInt} message).
     */
    static Integer intOf(Object v, String field) {
        if (v == null || (v instanceof String s && s.isBlank())) {
            return null;
        }
        BigDecimal d = v instanceof Number n && !(v instanceof BigDecimal) ? new BigDecimal(n.toString())
                : v instanceof Boolean || v instanceof Map<?, ?> || v instanceof Collection<?> ? null : tryDec(v);
        if (d == null || d.stripTrailingZeros().scale() > 0 || d.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw ApiException.badRequest(field + " must be an integer number");
        }
        return d.intValueExact();
    }

    private static BigDecimal tryDec(Object v) {
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ApiException notANumber(String field, Object v) {
        return ApiException.badRequest(field == null ? "Invalid number: " + v
                : field + " must be a number conforming to the specified constraints");
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

    /** {@code "a", "b"} placeholders for an IN list. */
    static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(Math.max(1, n), "?"));
    }
}
