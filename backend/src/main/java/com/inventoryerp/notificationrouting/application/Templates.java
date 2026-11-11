package com.inventoryerp.notificationrouting.application;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** {@code {name}} placeholders: the record column, else the payload field, else nothing; {@code {id}} is the record. */
final class Templates {

    private static final Pattern VAR = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private Templates() {}

    static String render(String template, String id, Map<String, Object> record, JsonNode payload) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(value(m.group(1), id, record, payload)));
        }
        m.appendTail(out);
        // Tidy what an empty variable leaves behind: doubled spaces, a dangling separator.
        return out.toString().replaceAll(" {2,}", " ").replaceAll("\\s*[·:,-]\\s*$", "").trim();
    }

    private static String value(String name, String id, Map<String, Object> record, JsonNode payload) {
        if ("id".equals(name)) {
            return id == null ? "" : id;
        }
        if (record != null && record.get(name) != null) {
            return String.valueOf(record.get(name));
        }
        if (payload != null) {
            JsonNode v = payload.get(name);
            if (v != null && !v.isNull() && v.isValueNode()) {
                return v.asString();
            }
        }
        return "";
    }
}
