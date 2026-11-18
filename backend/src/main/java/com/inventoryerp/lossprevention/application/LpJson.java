package com.inventoryerp.lossprevention.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Reads a stored JSON column as plain maps and lists. Numbers stay exact decimals. */
public final class LpJson {

    private LpJson() {}

    /** The object as a map; anything that is not a JSON object is an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        Object plain = plain(value);
        return plain instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static Object plain(Object value) {
        if (!(value instanceof JsonNode node)) {
            return value;
        }
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            node.properties().forEach(e -> out.put(e.getKey(), plain(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(n -> out.add(plain(n)));
            return out;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.isNull() || node.isMissingNode() ? null : node.asString();
    }
}
