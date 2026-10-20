package com.inventoryerp.kernel.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Insertion-ordered JSON object literals ({@code {a: 1, b: null}}), the shape Nest services return. */
public final class Obj {

    private Obj() {}

    /** {@code of("a", 1, "b", null)} → {@code {a: 1, b: null}}; null values are kept. */
    public static Map<String, Object> of(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Obj.of needs key/value pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** {@code {...base, ...extra}} (later keys win, first-seen order kept). */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.putAll(extra);
        return m;
    }
}
