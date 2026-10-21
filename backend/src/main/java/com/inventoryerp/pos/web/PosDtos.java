package com.inventoryerp.pos.web;

import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.shared.web.ApiException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** class-validator wording and DTO → JS-object helpers shared by the POS controllers. */
final class PosDtos {

    private PosDtos() {}

    static final String STR = "must be a string";
    static final String NUM = "must be a number conforming to the specified constraints";
    static final String INT = "must be an integer number";
    static final String BOOL = "must be a boolean value";
    static final String ARR = "must be an array";
    static final String OBJ = "must be an object";
    static final String UUID_MSG = "must be a UUID";
    static final String EMAIL = "must be an email";
    static final String MIN1 = "must contain at least 1 elements";
    static final String POSITIVE = "must be a positive number";
    static final String DATE_MSG = "must be a valid ISO 8601 date string";
    static final String NOT_EMPTY = "should not be empty";

    /** class-validator {@code @IsUUID()} (any version). */
    static final String UUID_RE = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /** validator.js isISO8601 (non-strict). */
    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";

    /**
     * The JS object a validated DTO is in Nest: top-level keys present in the body (explicit {@code null} kept),
     * nested records as maps without their absent ({@code null}) fields.
     */
    static Map<String, Object> data(Record dto) {
        Map<String, Object> top = EntityPatch.data(dto);
        Map<String, Object> out = new LinkedHashMap<>();
        top.forEach((k, v) -> out.put(k, js(v)));
        return out;
    }

    /** Body record → map; {@code null} body → empty map. */
    static Map<String, Object> dataOrEmpty(Record dto) {
        return dto == null ? new LinkedHashMap<>() : data(dto);
    }

    static Object js(Object v) {
        if (v instanceof Record r) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (RecordComponent c : r.getClass().getRecordComponents()) {
                try {
                    Object val = c.getAccessor().invoke(r);
                    if (val != null) {
                        m.put(c.getName(), js(val));
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return m;
        }
        if (v instanceof Collection<?> c) {
            List<Object> l = new ArrayList<>();
            c.forEach(o -> l.add(js(o)));
            return l;
        }
        return v;
    }

    /** Nest {@code ParseIntPipe} after {@code DefaultValuePipe(def)}. */
    static int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        if (!raw.matches("-?\\d+")) {
            throw ApiException.badRequest("Validation failed (numeric string is expected)");
        }
        return Integer.parseInt(raw);
    }

    /**
     * {@code raw ? parseInt(raw, 10) : undefined}: leading-integer parse; {@code NaN} reaches Prisma as an invalid
     * {@code skip}/{@code take} (500).
     */
    static Integer jsParseInt(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*([+-]?\\d+)").matcher(raw);
        if (!m.find()) {
            throw new IllegalStateException("Argument `take` is missing: NaN");
        }
        return Integer.parseInt(m.group(1).replace("+", ""));
    }

    /** {@code raw !== undefined ? raw === 'true' : undefined}. */
    static Boolean jsBool(String raw) {
        return raw == null ? null : "true".equals(raw);
    }
}
