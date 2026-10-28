package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.common.JsNumbers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code {{dotted.path}}} placeholders against an event payload (port of {@code template-render.service.ts}):
 * a missing path renders empty (never the braces, never "undefined"), values are coerced like JS {@code String(v)},
 * objects are JSON-stringified, whitespace inside the braces is tolerated. Payloads are plain JSON values
 * ({@code Map}/{@code List}/scalars).
 */
public final class TemplateRender {

    /** JS {@code undefined}: a path that does not resolve. */
    public static final Object UNDEFINED = new Object() {
        @Override
        public String toString() {
            return "undefined";
        }
    };

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.$]+)\\s*\\}\\}");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private TemplateRender() {}

    public static String render(String body, Map<String, Object> payload) {
        Matcher m = PLACEHOLDER.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(stringify(resolvePath(payload, m.group(1)))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The distinct placeholder names in a body, in first-seen order. */
    public static List<String> extractVariables(String body) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    /** {@code path.split('.').reduce(...)}: {@link #UNDEFINED} when a step is missing or not an object. */
    public static Object resolvePath(Object obj, String path) {
        Object acc = obj;
        for (String key : path.split("\\.", -1)) {
            if (acc instanceof Map<?, ?> map) {
                acc = map.containsKey(key) ? map.get(key) : UNDEFINED;
            } else if (acc instanceof List<?> list) {
                acc = index(list, key);
            } else {
                return UNDEFINED;
            }
        }
        return acc;
    }

    private static Object index(List<?> list, String key) {
        if ("length".equals(key)) {
            return list.size();
        }
        try {
            int i = Integer.parseInt(key);
            return i >= 0 && i < list.size() && String.valueOf(i).equals(key) ? list.get(i) : UNDEFINED;
        } catch (NumberFormatException e) {
            return UNDEFINED;
        }
    }

    static String stringify(Object value) {
        if (value == null || value == UNDEFINED) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number n) {
            return JsNumbers.toJsString(n.doubleValue());
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
