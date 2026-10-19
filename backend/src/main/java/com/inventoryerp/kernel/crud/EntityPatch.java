package com.inventoryerp.kernel.crud;

import com.inventoryerp.kernel.idempotency.CachedBodyFilter;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies a request record onto an entity the way Prisma applies {@code data: dto}: a component is
 * written when it has a value <em>or</em> its key is present in the raw JSON body (explicit
 * {@code null} clears the column; an absent key leaves it unchanged). Values are coerced to the
 * entity field type (numbers to {@link BigDecimal}, enum values to {@link DbEnum}, ISO strings to
 * {@link Instant}/{@link LocalDate}, objects to JSON columns).
 */
public final class EntityPatch {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    private EntityPatch() {}

    /** Top-level keys of the current request's JSON body (empty outside a request or for non-objects). */
    public static Set<String> presentKeys() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return Set.of();
        }
        HttpServletRequest req = attrs.getRequest();
        Object cached = req.getAttribute(PRESENT_KEYS);
        if (cached instanceof Set<?> s) {
            @SuppressWarnings("unchecked")
            Set<String> keys = (Set<String>) s;
            return keys;
        }
        Set<String> keys = new HashSet<>();
        byte[] raw = (byte[]) req.getAttribute(CachedBodyFilter.RAW_BODY);
        if (raw != null && raw.length > 0) {
            try {
                JsonNode node = MAPPER.readTree(raw);
                if (node != null && node.isObject()) {
                    keys.addAll(node.propertyNames());
                }
            } catch (RuntimeException ignored) {
                // unreadable bodies never reach a handler
            }
        }
        req.setAttribute(PRESENT_KEYS, keys);
        return keys;
    }

    private static final String PRESENT_KEYS = "erp.presentKeys";

    /** True when {@code key} has a value or was sent explicitly (possibly as null). */
    public static boolean present(String key, Object value) {
        return value != null || presentKeys().contains(key);
    }

    /** Apply every record component except {@code skip}. */
    public static <T> T apply(T entity, Record dto, String... skip) {
        Set<String> skipped = Set.of(skip);
        Set<String> keys = presentKeys();
        for (RecordComponent c : dto.getClass().getRecordComponents()) {
            if (skipped.contains(c.getName())) {
                continue;
            }
            Object value;
            try {
                value = c.getAccessor().invoke(dto);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            if (value == null && !keys.contains(c.getName())) {
                continue;
            }
            set(entity, c.getName(), value);
        }
        return entity;
    }

    /** The Prisma {@code data} object for a request record: present components only, in declaration order. */
    public static Map<String, Object> data(Record dto, String... skip) {
        Set<String> skipped = Set.of(skip);
        Set<String> keys = presentKeys();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (RecordComponent c : dto.getClass().getRecordComponents()) {
            if (skipped.contains(c.getName())) {
                continue;
            }
            Object value;
            try {
                value = c.getAccessor().invoke(dto);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            if (value != null || keys.contains(c.getName())) {
                out.put(c.getName(), value);
            }
        }
        return out;
    }

    /** Apply a map of field → value (all keys written). */
    public static <T> T apply(T entity, Map<String, ?> data) {
        data.forEach((k, v) -> set(entity, k, v));
        return entity;
    }

    /** Write one field, coercing the value to the field's type. */
    public static void set(Object entity, String name, Object value) {
        entity = org.hibernate.Hibernate.unproxy(entity);
        Field f = field(entity.getClass(), name);
        try {
            f.set(entity, coerce(f, value));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Read one field by name. */
    public static Object get(Object entity, String name) {
        entity = org.hibernate.Hibernate.unproxy(entity);
        try {
            return field(entity.getClass(), name).get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when the entity declares a field with this name. */
    public static boolean hasField(Class<?> type, String name) {
        try {
            field(type, name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Field field(Class<?> type, String name) {
        return FIELDS.computeIfAbsent(type.getName() + "#" + name, k -> {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // walk up
                }
            }
            throw new IllegalArgumentException("Unknown field " + type.getSimpleName() + "." + name);
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object coerce(Field f, Object value) {
        if (value == null) {
            return null;
        }
        Class<?> t = f.getType();
        JdbcTypeCode code = f.getAnnotation(JdbcTypeCode.class);
        if (code != null && code.value() == SqlTypes.JSON) {
            return value instanceof String s && t == String.class && looksLikeJson(s) ? s : EntityJson.toJson(value);
        }
        if (t.isInstance(value)) {
            return value;
        }
        if (t == String.class) {
            return value instanceof DbEnum e ? e.value() : String.valueOf(value);
        }
        if (t == BigDecimal.class) {
            if (value instanceof Double || value instanceof Float) {
                return new BigDecimal(value.toString());
            }
            if (value instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            return new BigDecimal(value.toString().trim());
        }
        if (t == Integer.class || t == int.class) {
            return ((Number) (value instanceof Number ? value : new BigDecimal(value.toString()))).intValue();
        }
        if (t == Long.class || t == long.class) {
            return ((Number) (value instanceof Number ? value : new BigDecimal(value.toString()))).longValue();
        }
        if (t == Boolean.class || t == boolean.class) {
            return value instanceof Boolean b ? b : Boolean.valueOf(value.toString());
        }
        if (t == Instant.class) {
            return toInstant(value.toString());
        }
        if (t == LocalDate.class) {
            String s = value.toString();
            return s.length() > 10 ? toInstant(s).atZone(ZoneOffset.UTC).toLocalDate() : LocalDate.parse(s);
        }
        if (t.isEnum() && DbEnum.class.isAssignableFrom(t)) {
            String v = value instanceof DbEnum e ? e.value() : value.toString();
            return DbEnum.fromValue((Class) t, v);
        }
        if (Collection.class.isAssignableFrom(t) && value instanceof Collection<?>) {
            return value;
        }
        throw new IllegalArgumentException("Cannot assign " + value.getClass().getSimpleName() + " to "
                + f.getDeclaringClass().getSimpleName() + "." + f.getName());
    }

    /** ISO-8601 date-time (any offset) or a bare date (UTC midnight). */
    public static Instant toInstant(String s) {
        if (s.length() <= 10) {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return OffsetDateTime.parse(s.endsWith("Z") || s.matches(".*[+-]\\d\\d:?\\d\\d$") ? s : s + "Z").toInstant();
    }

    private static boolean looksLikeJson(String s) {
        String t = s.trim();
        return t.startsWith("{") || t.startsWith("[");
    }
}
