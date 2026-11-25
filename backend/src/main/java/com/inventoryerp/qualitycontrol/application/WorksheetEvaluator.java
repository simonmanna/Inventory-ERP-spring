package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Worksheet templates (ADR-S087 §10): the definition check run when a template is saved, and the authoritative
 * evaluation of a submitted worksheet. The frontend validates for convenience only.
 *
 * <p>Field: {@code {key, label, type: text|number|checkbox|select|date|photo, required, min, max, decimals, options,
 * passValues, expected, maxLength, showIf: {field, equals}}}.
 *
 * <ul>
 *   <li>Structural problems are refused (400): unknown answer keys, wrong types, too many decimals, a select value that
 *       is not an option, text longer than {@code maxLength}, a missing required answer.</li>
 *   <li>Rule violations fail the worksheet (per-field reason): a number outside {@code [min, max]}, a select answer not
 *       in {@code passValues}, a checkbox different from {@code expected}.</li>
 *   <li>A field whose {@code showIf} is false is hidden: neither required nor evaluated (its answer is dropped).</li>
 * </ul>
 */
public final class WorksheetEvaluator {

    static final Set<String> TYPES = Set.of("text", "number", "checkbox", "select", "date", "photo");
    private static final Set<String> FIELD_KEYS = Set.of("key", "label", "type", "required", "min", "max", "decimals",
            "options", "passValues", "expected", "maxLength", "showIf");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    private WorksheetEvaluator() {}

    /** Per-field outcome of an evaluation ({@code passed = null} for informational / hidden fields). */
    public record FieldOutcome(String key, String label, Boolean passed, String reason, boolean visible) {}

    /** Result of evaluating a worksheet: the normalized answers (hidden ones dropped) and per-field outcomes. */
    public record Result(boolean passed, Map<String, Object> answers, List<FieldOutcome> fields) {

        public Map<String, Object> toMap() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (FieldOutcome f : fields) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", f.key());
                m.put("label", f.label());
                m.put("passed", f.passed());
                m.put("reason", f.reason());
                m.put("visible", f.visible());
                out.add(m);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            m.put("fields", out);
            return m;
        }
    }

    // ------------------------------------------------------------------ definition

    /** Refuses (400) an invalid template definition. */
    public static void validateDefinition(JsonNode fields) {
        if (fields == null || !fields.isArray() || fields.isEmpty()) {
            throw ApiException.badRequest("A worksheet template needs at least one field.");
        }
        if (fields.size() > 100) {
            throw ApiException.badRequest("A worksheet template has at most 100 fields.");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode f : fields) {
            if (!f.isObject()) {
                throw ApiException.badRequest("Each worksheet field must be an object.");
            }
            for (String name : f.propertyNames()) {
                if (!FIELD_KEYS.contains(name)) {
                    throw ApiException.badRequest("Unknown worksheet field property " + name + ".");
                }
            }
            String key = text(f, "key");
            if (key == null || !KEY.matcher(key).matches()) {
                throw ApiException.badRequest("Worksheet field key " + key
                        + " must start with a lower-case letter and use a-z, 0-9 and _ (at most 40).");
            }
            if (!seen.add(key)) {
                throw ApiException.badRequest("Worksheet field key " + key + " is used twice.");
            }
            if (text(f, "label") == null) {
                throw ApiException.badRequest("Worksheet field " + key + " needs a label.");
            }
            String type = text(f, "type");
            if (type == null || !TYPES.contains(type)) {
                throw ApiException.badRequest("Worksheet field " + key + " has an unknown type " + type + ".");
            }
            BigDecimal min = num(f, "min");
            BigDecimal max = num(f, "max");
            if (min != null && max != null && min.compareTo(max) > 0) {
                throw ApiException.badRequest("Worksheet field " + key + ": min is greater than max.");
            }
            if (f.has("decimals") && !f.get("decimals").isNull()
                    && (!f.get("decimals").isInt() || f.get("decimals").asInt() < 0 || f.get("decimals").asInt() > 6)) {
                throw ApiException.badRequest("Worksheet field " + key + ": decimals must be 0 to 6.");
            }
            if (f.has("maxLength") && !f.get("maxLength").isNull()
                    && (!f.get("maxLength").isInt() || f.get("maxLength").asInt() < 1)) {
                throw ApiException.badRequest("Worksheet field " + key + ": maxLength must be a positive integer.");
            }
            if ("select".equals(type)) {
                List<String> options = strings(f.get("options"));
                if (options.isEmpty()) {
                    throw ApiException.badRequest("Worksheet field " + key + " needs options.");
                }
                for (String pv : strings(f.get("passValues"))) {
                    if (!options.contains(pv)) {
                        throw ApiException.badRequest("Worksheet field " + key + ": pass value " + pv
                                + " is not an option.");
                    }
                }
            }
            if (f.has("expected") && !f.get("expected").isNull() && !"checkbox".equals(type)) {
                throw ApiException.badRequest("Worksheet field " + key + ": expected applies to checkboxes only.");
            }
            JsonNode showIf = f.get("showIf");
            if (showIf != null && !showIf.isNull()) {
                String ref = text(showIf, "field");
                if (ref == null || !seen.contains(ref) || ref.equals(key)) {
                    throw ApiException.badRequest("Worksheet field " + key
                            + ": showIf must refer to an earlier field.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ evaluation

    /** Evaluates {@code answers} against the pinned template {@code fields}. */
    public static Result evaluate(JsonNode fields, Map<String, Object> answers) {
        Map<String, Object> in = answers != null ? answers : Map.of();
        Set<String> known = new HashSet<>();
        for (JsonNode f : fields) {
            known.add(text(f, "key"));
        }
        for (String k : in.keySet()) {
            if (!known.contains(k)) {
                throw ApiException.badRequest("Unknown worksheet field " + k + ".");
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        List<FieldOutcome> outcomes = new ArrayList<>();
        boolean passed = true;
        for (JsonNode f : fields) {
            String key = text(f, "key");
            String label = text(f, "label");
            if (!visible(f, normalized)) {
                outcomes.add(new FieldOutcome(key, label, null, "hidden", false));
                continue;
            }
            Object raw = in.get(key);
            boolean blank = raw == null || (raw instanceof String s && s.isBlank());
            if (blank) {
                if (f.path("required").asBoolean(false)) {
                    throw ApiException.badRequest("Worksheet field " + label + " is required.");
                }
                outcomes.add(new FieldOutcome(key, label, null, "not answered", true));
                continue;
            }
            FieldOutcome o = evaluateField(f, key, label, raw, normalized);
            outcomes.add(o);
            if (Boolean.FALSE.equals(o.passed())) {
                passed = false;
            }
        }
        return new Result(passed, normalized, outcomes);
    }

    private static FieldOutcome evaluateField(JsonNode f, String key, String label, Object raw,
            Map<String, Object> normalized) {
        String type = text(f, "type");
        switch (type) {
            case "number" -> {
                BigDecimal v = toDecimal(raw, label);
                if (f.has("decimals") && f.get("decimals").isInt()
                        && Math.max(0, v.stripTrailingZeros().scale()) > f.get("decimals").asInt()) {
                    throw ApiException.badRequest("Worksheet field " + label + " has more than "
                            + f.get("decimals").asInt() + " decimals.");
                }
                normalized.put(key, v);
                BigDecimal min = num(f, "min");
                BigDecimal max = num(f, "max");
                if (min != null && v.compareTo(min) < 0) {
                    return new FieldOutcome(key, label, false, "below minimum " + plain(min), true);
                }
                if (max != null && v.compareTo(max) > 0) {
                    return new FieldOutcome(key, label, false, "above maximum " + plain(max), true);
                }
                return new FieldOutcome(key, label, min != null || max != null ? Boolean.TRUE : null, null, true);
            }
            case "checkbox" -> {
                if (!(raw instanceof Boolean b)) {
                    throw ApiException.badRequest("Worksheet field " + label + " must be true or false.");
                }
                normalized.put(key, b);
                JsonNode expected = f.get("expected");
                if (expected == null || expected.isNull()) {
                    return new FieldOutcome(key, label, null, null, true);
                }
                boolean ok = expected.asBoolean() == b;
                return new FieldOutcome(key, label, ok, ok ? null : "expected " + expected.asBoolean(), true);
            }
            case "select" -> {
                String v = String.valueOf(raw);
                if (!strings(f.get("options")).contains(v)) {
                    throw ApiException.badRequest("Worksheet field " + label + ": " + v + " is not an option.");
                }
                normalized.put(key, v);
                List<String> pass = strings(f.get("passValues"));
                if (pass.isEmpty()) {
                    return new FieldOutcome(key, label, null, null, true);
                }
                boolean ok = pass.contains(v);
                return new FieldOutcome(key, label, ok, ok ? null : "value " + v + " does not pass", true);
            }
            case "date" -> {
                try {
                    normalized.put(key, LocalDate.parse(String.valueOf(raw)).toString());
                } catch (DateTimeParseException e) {
                    throw ApiException.badRequest("Worksheet field " + label + " must be a date (YYYY-MM-DD).");
                }
                return new FieldOutcome(key, label, null, null, true);
            }
            default -> { // text, photo (a DMS document id)
                if (!(raw instanceof String s)) {
                    throw ApiException.badRequest("Worksheet field " + label + " must be text.");
                }
                if (f.has("maxLength") && f.get("maxLength").isInt() && s.length() > f.get("maxLength").asInt()) {
                    throw ApiException.badRequest("Worksheet field " + label + " is longer than "
                            + f.get("maxLength").asInt() + " characters.");
                }
                normalized.put(key, s.trim());
                return new FieldOutcome(key, label, null, null, true);
            }
        }
    }

    private static boolean visible(JsonNode f, Map<String, Object> normalized) {
        JsonNode showIf = f.get("showIf");
        if (showIf == null || showIf.isNull()) {
            return true;
        }
        Object actual = normalized.get(text(showIf, "field"));
        JsonNode equals = showIf.get("equals");
        if (actual == null || equals == null || equals.isNull()) {
            return false;
        }
        if (actual instanceof BigDecimal d) {
            return equals.isNumber() ? d.compareTo(equals.decimalValue()) == 0 : Objects.equals(plain(d), equals.asString());
        }
        if (actual instanceof Boolean b) {
            return equals.isBoolean() ? equals.asBoolean() == b : String.valueOf(b).equals(equals.asString());
        }
        return String.valueOf(actual).equals(equals.asString());
    }

    private static BigDecimal toDecimal(Object raw, String label) {
        if (raw instanceof Boolean) {
            throw ApiException.badRequest("Worksheet field " + label + " must be a number.");
        }
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Worksheet field " + label + " must be a number.");
        }
    }

    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.asString().isBlank() ? null : v.asString();
    }

    private static BigDecimal num(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isNumber()) {
            throw ApiException.badRequest("Worksheet field " + text(node, "key") + ": " + field + " must be a number.");
        }
        return v.decimalValue();
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (!n.isNull()) {
                    out.add(n.asString());
                }
            }
        }
        return out;
    }
}
