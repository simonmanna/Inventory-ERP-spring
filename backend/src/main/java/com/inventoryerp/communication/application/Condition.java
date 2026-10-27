package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.common.JsNumbers;
import java.util.List;
import java.util.Map;

/**
 * A tiny, safe JSON predicate over an event payload (port of {@code rules/condition.ts}). Shape:
 * {@code {"<path>": <matcher>}} where a matcher is a literal ({@code $eq}) or {@code {"$op": operand}}; an operand
 * may be {@code {"$field": "<path>"}}. Keys are AND-ed; unknown operators fail closed so a malformed rule never
 * spams. Equality follows JS {@code ===} (objects/arrays never equal a payload value).
 */
public final class Condition {

    private Condition() {}

    public static boolean matches(Object condition, Map<String, Object> payload) {
        if (!(condition instanceof Map<?, ?> cond) || cond.isEmpty()) {
            return true;
        }
        for (Map.Entry<?, ?> e : cond.entrySet()) {
            Object matcher = e.getValue();
            Object actual = TemplateRender.resolvePath(payload, String.valueOf(e.getKey()));
            boolean ok;
            if (matcher instanceof Map<?, ?> ops) {
                ok = matchOperators(actual, ops, payload);
            } else {
                // Bare literal ⇒ equality.
                ok = strictEquals(actual, matcher);
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchOperators(Object actual, Map<?, ?> ops, Map<String, Object> payload) {
        for (Map.Entry<?, ?> e : ops.entrySet()) {
            Object operand = resolveOperand(e.getValue(), payload);
            boolean ok = switch (String.valueOf(e.getKey())) {
                case "$eq" -> strictEquals(actual, operand);
                case "$ne" -> !strictEquals(actual, operand);
                case "$gt" -> num(actual) > num(operand);
                case "$gte" -> num(actual) >= num(operand);
                case "$lt" -> num(actual) < num(operand);
                case "$lte" -> num(actual) <= num(operand);
                case "$in" -> operand instanceof List<?> list && list.stream().anyMatch(v -> strictEquals(actual, v));
                case "$exists" -> (actual != TemplateRender.UNDEFINED && actual != null) == truthy(operand);
                default -> false; // unknown operator → fail closed
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** {@code {"$field": "path"}} reads another payload field; anything else is a literal. */
    private static Object resolveOperand(Object operand, Map<String, Object> payload) {
        if (operand instanceof Map<?, ?> m && m.containsKey("$field")) {
            return TemplateRender.resolvePath(payload, String.valueOf(m.get("$field")));
        }
        return operand;
    }

    /** JS {@code ===} over JSON values: primitives by value, numbers as doubles, containers by identity. */
    static boolean strictEquals(Object a, Object b) {
        if (a == b) {
            return !(a instanceof Number n) || !Double.isNaN(n.doubleValue());
        }
        if (a == null || b == null || a == TemplateRender.UNDEFINED || b == TemplateRender.UNDEFINED) {
            return false;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        if (a instanceof String || a instanceof Boolean) {
            return a.equals(b);
        }
        return false;
    }

    private static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.trim().isEmpty()) {
            return JsNumbers.number(s);
        }
        return Double.NaN;
    }

    private static boolean truthy(Object v) {
        if (v == null || v == TemplateRender.UNDEFINED) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }
}
