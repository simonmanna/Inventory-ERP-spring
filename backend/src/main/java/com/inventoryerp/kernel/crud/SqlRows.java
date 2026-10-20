package com.inventoryerp.kernel.crud;

import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Raw-SQL rows shaped like Prisma rows: {@code timestamp(3)} (stored UTC) → {@link Instant}, {@code date} →
 * {@link LocalDate}, {@code jsonb} → JSON tree, enums → their string value, arrays → lists. Keys keep the
 * column order of the SELECT.
 */
public final class SqlRows {

    private SqlRows() {}

    public static List<Map<String, Object>> list(JdbcClient.StatementSpec spec) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : spec.query().listOfRows()) {
            out.add(normalize(row));
        }
        return out;
    }

    public static Map<String, Object> one(JdbcClient.StatementSpec spec) {
        List<Map<String, Object>> rows = list(spec);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * A {@code SELECT *} row rendered like the Prisma row of {@code entity}: every scalar column in schema
     * order (columns missing from the row are omitted), values normalized.
     */
    public static Map<String, Object> as(Class<?> entity, Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String col : EntityJson.columnNames(entity)) {
            if (row.containsKey(col)) {
                out.put(col, value(row.get(col)));
            }
        }
        return out;
    }

    /** Rows of {@code entity} in Prisma shape. */
    public static List<Map<String, Object>> list(Class<?> entity, JdbcClient.StatementSpec spec) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : spec.query().listOfRows()) {
            out.add(as(entity, row));
        }
        return out;
    }

    /** First row of {@code entity} in Prisma shape, or {@code null}. */
    public static Map<String, Object> one(Class<?> entity, JdbcClient.StatementSpec spec) {
        List<Map<String, Object>> rows = list(entity, spec);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Timestamp parameter for a {@code timestamp(3)} (UTC wall clock) column. */
    public static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.valueOf(instant.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    public static Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, value(v)));
        return out;
    }

    public static Object value(Object v) {
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof PGobject pg) {
            String type = pg.getType();
            if ("json".equals(type) || "jsonb".equals(type)) {
                return EntityJson.parseJson(pg.getValue());
            }
            return pg.getValue();
        }
        if (v instanceof Array arr) {
            try {
                Object[] items = (Object[]) arr.getArray();
                return new ArrayList<>(Arrays.asList(items));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return v;
    }

    /** Null-safe BigDecimal of a column. */
    public static BigDecimal dec(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    public static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }
}
