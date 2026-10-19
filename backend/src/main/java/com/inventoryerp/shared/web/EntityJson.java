package com.inventoryerp.shared.web;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders a generated entity exactly like a Prisma row: every scalar column in schema order under
 * its column name, JSON columns embedded as JSON (not strings), relations omitted. Controllers return
 * these maps (never entities); callers add Prisma {@code include}s with {@link Map#put}.
 */
public final class EntityJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Map<Class<?>, List<Col>> COLUMNS = new ConcurrentHashMap<>();

    private EntityJson() {}

    private record Col(Field field, String name, boolean json) {}

    /** One row as an ordered map; {@code null} stays {@code null}. */
    public static Map<String, Object> of(Object entity) {
        if (entity == null) {
            return null;
        }
        // A lazy proxy (em.find of an already-referenced row) has no column fields of its own.
        entity = org.hibernate.Hibernate.unproxy(entity);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Col c : columns(entity.getClass())) {
            try {
                Object v = c.field.get(entity);
                out.put(c.name, c.json ? parseJson(v) : v);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    /** Several rows, preserving order. */
    public static List<Map<String, Object>> list(Collection<?> entities) {
        List<Map<String, Object>> out = new ArrayList<>(entities.size());
        for (Object e : entities) {
            out.add(of(e));
        }
        return out;
    }

    /** Parse a stored JSON column value (String) into a tree; non-strings pass through. */
    public static Object parseJson(Object value) {
        if (value instanceof String s) {
            return MAPPER.readTree(s);
        }
        return value;
    }

    /** Serialize a value for a JSON column. */
    public static String toJson(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    /** Read a JSON column into a tree ({@code null} when the column is null). */
    public static JsonNode tree(String json) {
        return json == null ? null : MAPPER.readTree(json);
    }

    private static List<Col> columns(Class<?> type) {
        return COLUMNS.computeIfAbsent(type, t -> {
            List<Col> cols = new ArrayList<>();
            for (Field f : t.getDeclaredFields()) {
                Column column = f.getAnnotation(Column.class);
                if (column == null) {
                    continue;
                }
                f.setAccessible(true);
                JdbcTypeCode code = f.getAnnotation(JdbcTypeCode.class);
                boolean json = code != null && code.value() == SqlTypes.JSON;
                cols.add(new Col(f, column.name().isEmpty() ? f.getName() : column.name(), json));
            }
            return cols;
        });
    }
}
