package com.inventoryerp.license.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place JSON is read and written: {@code jsonb} columns, token claims and JWKS documents.
 *
 * <p>A single static mapper keeps serialisation deterministic — token payloads are signed, so two
 * runs must produce byte-identical JSON for the same claims. {@link LinkedHashMap} ordering is
 * preserved rather than sorted, which is what the claim builders rely on.
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object parsed = MAPPER.readValue(json, Object.class);
        return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /** A {@code jsonb} array of strings as an ordered set; tolerant of null, blank and non-arrays. */
    public static Set<String> readStringSet(String json) {
        Set<String> out = new LinkedHashSet<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        Object parsed = MAPPER.readValue(json, Object.class);
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    public static String writeStringCollection(java.util.Collection<String> values) {
        return write(new ArrayList<>(values == null ? List.of() : values));
    }
}
