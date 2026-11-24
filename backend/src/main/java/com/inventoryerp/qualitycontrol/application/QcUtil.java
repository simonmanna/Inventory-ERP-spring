package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Small helpers shared by the quality-control services. */
final class QcUtil {

    private QcUtil() {}

    static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String str(Map<String, Object> row, String key) {
        return SqlRows.str(row, key);
    }

    static BigDecimal dec(Map<String, Object> row, String key) {
        return SqlRows.dec(row, key);
    }

    static boolean bool(Map<String, Object> row, String key) {
        return Boolean.TRUE.equals(row.get(key));
    }

    static int integer(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    static int page(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    static int size(Integer size) {
        return size == null ? 20 : Math.max(1, Math.min(200, size));
    }

    static String like(String s) {
        return "%" + s.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** Appends {@code AND column = ?} when {@code value} is set. */
    static void eq(StringBuilder where, List<Object> params, String column, Object value) {
        if (value instanceof String s ? has(s) : value != null) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }

    static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
