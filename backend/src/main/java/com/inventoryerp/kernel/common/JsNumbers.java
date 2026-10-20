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

    /** A JS number for JSON output: integral values as {@link Long} (no ".0"), others as {@link Double}. */
    public static Number js(double n) {
        if (!Double.isNaN(n) && !Double.isInfinite(n) && n == Math.rint(n) && Math.abs(n) < 9.007199254740992E15) {
            return (long) n;
        }
        return n;
    }

    /** JS {@code Number(decimal)} for JSON output; {@code null} stays {@code null}. */
    public static Number js(java.math.BigDecimal v) {
        return v == null ? null : js(v.doubleValue());
    }

    /** JS {@code Math.round(v * 10^dp) / 10^dp}. */
    public static double round(double v, int dp) {
        double f = Math.pow(10, dp);
        return Math.floor(v * f + 0.5) / f;
    }

    /**
     * decimal.js {@code Decimal#toString()}: plain notation without trailing zeros, exponential when the
     * exponent is {@code <= -7} or {@code >= 21} ("1e-7", "1.5e+21").
     */
    public static String dec(java.math.BigDecimal v) {
        if (v == null) {
            return null;
        }
        if (v.signum() == 0) {
            return "0";
        }
        java.math.BigDecimal s = v.stripTrailingZeros();
        int exponent = s.precision() - s.scale() - 1;
        if (exponent <= -7 || exponent >= 21) {
            String digits = s.unscaledValue().abs().toString();
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return (s.signum() < 0 ? "-" : "") + mantissa + "e" + (exponent > 0 ? "+" : "") + exponent;
        }
        return s.toPlainString();
    }

    /** JS {@code String(n)} for the common range: integers without ".0", otherwise the shortest repr. */
    public static String toJsString(double n) {
        if (Double.isNaN(n)) {
            return "NaN";
        }
        if (Double.isInfinite(n)) {
            return n > 0 ? "Infinity" : "-Infinity";
        }
        if (n == Math.rint(n) && Math.abs(n) < 1e21) {
            return new java.math.BigDecimal(n).toPlainString();
        }
        String s = Double.toString(n);
        if (s.contains("E")) {
            String[] parts = s.split("E");
            String mantissa = parts[0].endsWith(".0") ? parts[0].substring(0, parts[0].length() - 2) : parts[0];
            int exp = Integer.parseInt(parts[1]);
            if (exp >= -6) {
                return new java.math.BigDecimal(s).toPlainString();
            }
            return mantissa + "e" + exp;
        }
        return s;
    }
}
