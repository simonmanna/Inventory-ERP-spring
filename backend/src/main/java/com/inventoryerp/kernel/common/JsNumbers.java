package com.inventoryerp.kernel.common;

/** JavaScript number coercion used by Nest controllers ({@code Number(x) || default}). */
public final class JsNumbers {

    private JsNumbers() {}

    /** {@code Number(value)}: NaN for non-numeric input, 0 for blank, trimmed decimal/exponent parsing. */
    public static double number(String value) {
        if (value == null) {
            return Double.NaN;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return 0;
        }
        try {
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Long.parseLong(v.substring(2), 16);
            }
            if (v.equals("Infinity") || v.equals("+Infinity")) {
                return Double.POSITIVE_INFINITY;
            }
            if (v.equals("-Infinity")) {
                return Double.NEGATIVE_INFINITY;
            }
            if (!v.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) {
                return Double.NaN;
            }
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** {@code Number(value) || fallback} truncated to an int (as used for page/pageSize). */
    public static int orDefault(String value, int fallback) {
        double n = number(value);
        if (Double.isNaN(n) || n == 0) {
            return fallback;
        }
        return (int) n;
    }
}
