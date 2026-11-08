package com.inventoryerp.kernel.approvals;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntPredicate;
import tools.jackson.databind.JsonNode;

/**
 * Condition trees of the approval rules builder (ADR-S061). A tree is a group
 * {@code {"logic": "and"|"or", "rules": [...]}} whose rules are leaves
 * {@code {"field": "amount", "operator": "gt", "value": 1000}} or nested groups. Fields are dot paths into the
 * document snapshot; the engine also resolves {@code amount}, {@code branchId}, {@code departmentId} (the requester's
 * when the snapshot has none) and {@code requesterId}. An absent or empty tree always matches.
 *
 * <p>Numbers compare as decimals whenever both sides parse as numbers, everything else compares as case-insensitive
 * text. A field missing from the snapshot fails every positive test and passes {@code neq}, {@code not_in} and
 * {@code is_empty}.
 */
public final class ApprovalConditions {

    /** Operator keys accepted in a leaf. */
    public static final List<String> OPERATORS = List.of("eq", "neq", "gt", "gte", "lt", "lte", "between", "in",
            "not_in", "contains", "not_contains", "starts_with", "is_empty", "is_not_empty");

    private static final Set<String> NO_VALUE = Set.of("is_empty", "is_not_empty");
    private static final Set<String> LIST_VALUE = Set.of("in", "not_in");
    private static final int MAX_DEPTH = 4;
    private static final int MAX_RULES = 40;

    private ApprovalConditions() {}

    /** Whether the tree holds for the values {@code resolve} returns ({@code null} = field absent). */
    public static boolean matches(JsonNode tree, Function<String, Object> resolve) {
        if (isEmpty(tree)) {
            return true;
        }
        return group(tree, resolve);
    }

    /** {@code true} for {@code null}, JSON null, or a group with no rules. */
    public static boolean isEmpty(JsonNode tree) {
        return tree == null || tree.isNull() || tree.isMissingNode()
                || (tree.isObject() && (!tree.has("rules") || tree.get("rules").isEmpty()) && !tree.has("field"));
    }

    private static boolean group(JsonNode g, Function<String, Object> resolve) {
        if (g.has("field")) {
            return leaf(g, resolve);
        }
        boolean any = "or".equalsIgnoreCase(g.path("logic").asString("and"));
        JsonNode rules = g.path("rules");
        if (rules.isEmpty()) {
            return true;
        }
        for (JsonNode r : rules) {
            boolean ok = group(r, resolve);
            if (any && ok) {
                return true;
            }
            if (!any && !ok) {
                return false;
            }
        }
        return !any;
    }

    private static boolean leaf(JsonNode r, Function<String, Object> resolve) {
        String op = r.path("operator").asString("eq");
        Object actual = resolve.apply(r.path("field").asString(""));
        JsonNode expected = r.path("value");
        boolean empty = actual == null || String.valueOf(actual).isBlank()
                || (actual instanceof Collection<?> c && c.isEmpty());
        return switch (op) {
            case "is_empty" -> empty;
            case "is_not_empty" -> !empty;
            case "neq" -> empty || !equal(actual, expected);
            case "not_in" -> empty || !anyEqual(actual, expected);
            case "not_contains" -> empty || !text(actual).contains(text(expected));
            default -> !empty && positive(op, actual, expected);
        };
    }

    private static boolean positive(String op, Object actual, JsonNode expected) {
        return switch (op) {
            case "eq" -> equal(actual, expected);
            case "gt" -> ordered(actual, expected, c -> c > 0);
            case "gte" -> ordered(actual, expected, c -> c >= 0);
            case "lt" -> ordered(actual, expected, c -> c < 0);
            case "lte" -> ordered(actual, expected, c -> c <= 0);
            case "between" -> expected.isArray() && expected.size() == 2
                    && ordered(actual, expected.get(0), c -> c >= 0) && ordered(actual, expected.get(1), c -> c <= 0);
            case "in" -> anyEqual(actual, expected);
            case "contains" -> actual instanceof Collection<?> c
                    ? c.stream().anyMatch(v -> text(v).equals(text(expected)))
                    : text(actual).contains(text(expected));
            case "starts_with" -> text(actual).startsWith(text(expected));
            default -> false;
        };
    }

    private static boolean anyEqual(Object actual, JsonNode list) {
        if (!list.isArray()) {
            return equal(actual, list);
        }
        for (JsonNode v : list) {
            if (equal(actual, v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equal(Object actual, JsonNode expected) {
        if (actual instanceof Collection<?> c) {
            return c.stream().anyMatch(v -> equal(v, expected));
        }
        BigDecimal a = number(actual);
        BigDecimal e = number(expected);
        if (a != null && e != null) {
            return a.compareTo(e) == 0;
        }
        return text(actual).equals(text(expected));
    }

    /**
     * Decimal comparison when both sides are numbers, text comparison when neither is (ISO dates order correctly).
     * A number against text never satisfies an ordering.
     */
    private static boolean ordered(Object actual, JsonNode expected, IntPredicate test) {
        BigDecimal a = number(actual);
        BigDecimal e = number(expected);
        if (a != null && e != null) {
            return test.test(a.compareTo(e));
        }
        if (a != null || e != null) {
            return false;
        }
        return test.test(Integer.signum(text(actual).compareTo(text(expected))));
    }

    static BigDecimal number(Object v) {
        if (v instanceof JsonNode n) {
            if (n.isNumber()) {
                return n.decimalValue();
            }
            if (!n.isString()) {
                return null;
            }
            v = n.asString();
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof JsonNode n) {
            return n.isNull() || n.isMissingNode() ? "" : n.isString() ? n.asString().trim().toLowerCase(Locale.ROOT)
                    : n.toString().toLowerCase(Locale.ROOT);
        }
        return String.valueOf(v).trim().toLowerCase(Locale.ROOT);
    }

    /** Value at a dot path of a plain JSON map ({@code null} when any segment is missing). */
    @SuppressWarnings("unchecked")
    public static Object path(Map<String, Object> root, String field) {
        Object cur = root;
        for (String part : field.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = ((Map<String, Object>) m).get(part);
        }
        return cur;
    }

    /** Rejects a malformed tree with every problem found, as a 400. */
    public static void validate(JsonNode tree, String prefix) {
        if (isEmpty(tree)) {
            return;
        }
        List<String> errors = new ArrayList<>();
        int[] count = {0};
        check(tree, prefix, 1, errors, count);
        if (count[0] > MAX_RULES) {
            errors.add(prefix + " may hold at most " + MAX_RULES + " conditions");
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", errors));
        }
    }

    private static void check(JsonNode node, String at, int depth, List<String> errors, int[] count) {
        if (!node.isObject()) {
            errors.add(at + " must be an object");
            return;
        }
        if (node.has("field")) {
            count[0]++;
            String field = node.path("field").asString("");
            String op = node.path("operator").asString("");
            if (field.isBlank()) {
                errors.add(at + ".field must not be empty");
            }
            if (!OPERATORS.contains(op)) {
                errors.add(at + ".operator must be one of the following values: " + String.join(", ", OPERATORS));
            } else if (!NO_VALUE.contains(op)) {
                JsonNode v = node.path("value");
                if (v.isMissingNode() || v.isNull()) {
                    errors.add(at + ".value is required for " + op);
                } else if ("between".equals(op) && !(v.isArray() && v.size() == 2)) {
                    errors.add(at + ".value must be [from, to] for between");
                } else if (LIST_VALUE.contains(op) && !v.isArray()) {
                    errors.add(at + ".value must be an array for " + op);
                }
            }
            return;
        }
        if (depth > MAX_DEPTH) {
            errors.add(at + " nests deeper than " + MAX_DEPTH + " levels");
            return;
        }
        String logic = node.path("logic").asString("and");
        if (!"and".equalsIgnoreCase(logic) && !"or".equalsIgnoreCase(logic)) {
            errors.add(at + ".logic must be one of the following values: and, or");
        }
        JsonNode rules = node.path("rules");
        if (!rules.isArray()) {
            errors.add(at + ".rules must be an array");
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            check(rules.get(i), at + ".rules." + i, depth + 1, errors, count);
        }
    }
}
