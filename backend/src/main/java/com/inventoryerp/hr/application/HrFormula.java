package com.inventoryerp.hr.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Safe arithmetic for FORMULA pay elements (HP-1/HP-2): numbers, whitelisted upper-case variables, {@code + - * /},
 * parentheses, comparisons, and {@code MIN, MAX, ROUND(x, n), IF(cond, a, b), ABS}. No scripting engine, no method
 * calls, no loops. Decimal maths ({@link MathContext#DECIMAL64}); a comparison is 1 or 0.
 */
public final class HrFormula {

    /** Variables the payroll engine provides to every formula. */
    public static final Set<String> VARIABLES = Set.of("BASIC", "GROSS", "TAXABLE_GROSS", "DAYS_IN_PERIOD",
            "DAYS_PAID", "WORKING_DAYS", "WORKING_DAYS_PAID", "HOURS", "OT_HOURS", "HOURLY_RATE", "DAILY_RATE",
            "YEARS_OF_SERVICE", "MONTHS_OF_SERVICE", "PRORATION");

    private static final MathContext MC = MathContext.DECIMAL64;

    private final List<String> tokens;
    private final Map<String, BigDecimal> vars;
    private int pos;

    private HrFormula(String text, Map<String, BigDecimal> vars) {
        this.tokens = tokenize(text);
        this.vars = vars;
    }

    /** Evaluate {@code text}; unknown variables or bad syntax throw {@link IllegalArgumentException}. */
    public static BigDecimal eval(String text, Map<String, BigDecimal> vars) {
        HrFormula f = new HrFormula(text, vars);
        BigDecimal v = f.comparison();
        if (f.pos != f.tokens.size()) {
            throw new IllegalArgumentException("Unexpected '" + f.tokens.get(f.pos) + "'");
        }
        return v;
    }

    /** Check syntax and variable names with every variable set to 1; returns the error or {@code null}. */
    public static String validate(String text) {
        if (text == null || text.isBlank()) {
            return "formula is required";
        }
        if (text.length() > 500) {
            return "formula is too long";
        }
        Map<String, BigDecimal> ones = new java.util.HashMap<>();
        VARIABLES.forEach(v -> ones.put(v, BigDecimal.ONE));
        try {
            eval(text, ones);
            return null;
        } catch (IllegalArgumentException | ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || c == '.') {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                out.add(s.substring(i, j).toUpperCase());
                i = j;
            } else if ((c == '<' || c == '>' || c == '=' || c == '!') && i + 1 < s.length() && s.charAt(i + 1) == '=') {
                out.add(s.substring(i, i + 2));
                i += 2;
            } else if ("+-*/(),<>".indexOf(c) >= 0) {
                out.add(String.valueOf(c));
                i++;
            } else {
                throw new IllegalArgumentException("Unexpected character '" + c + "'");
            }
        }
        return out;
    }

    private String peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private void expect(String t) {
        if (!t.equals(peek())) {
            throw new IllegalArgumentException("Expected '" + t + "'");
        }
        pos++;
    }

    private BigDecimal comparison() {
        BigDecimal left = additive();
        String op = peek();
        if (op != null && List.of("<", ">", "<=", ">=", "==", "!=").contains(op)) {
            pos++;
            int c = left.compareTo(additive());
            boolean r = switch (op) {
                case "<" -> c < 0;
                case ">" -> c > 0;
                case "<=" -> c <= 0;
                case ">=" -> c >= 0;
                case "==" -> c == 0;
                default -> c != 0;
            };
            return r ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        return left;
    }

    private BigDecimal additive() {
        BigDecimal v = term();
        while ("+".equals(peek()) || "-".equals(peek())) {
            String op = tokens.get(pos++);
            BigDecimal r = term();
            v = "+".equals(op) ? v.add(r, MC) : v.subtract(r, MC);
        }
        return v;
    }

    private BigDecimal term() {
        BigDecimal v = unary();
        while ("*".equals(peek()) || "/".equals(peek())) {
            String op = tokens.get(pos++);
            BigDecimal r = unary();
            if ("*".equals(op)) {
                v = v.multiply(r, MC);
            } else {
                if (r.signum() == 0) {
                    throw new ArithmeticException("Division by zero");
                }
                v = v.divide(r, MC);
            }
        }
        return v;
    }

    private BigDecimal unary() {
        if ("-".equals(peek())) {
            pos++;
            return unary().negate();
        }
        if ("+".equals(peek())) {
            pos++;
            return unary();
        }
        return primary();
    }

    private BigDecimal primary() {
        String t = peek();
        if (t == null) {
            throw new IllegalArgumentException("Unexpected end of formula");
        }
        pos++;
        if ("(".equals(t)) {
            BigDecimal v = comparison();
            expect(")");
            return v;
        }
        if (Character.isDigit(t.charAt(0)) || t.charAt(0) == '.') {
            try {
                return new BigDecimal(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad number '" + t + "'");
            }
        }
        if ("(".equals(peek())) {
            pos++;
            List<BigDecimal> args = new ArrayList<>();
            if (!")".equals(peek())) {
                args.add(comparison());
                while (",".equals(peek())) {
                    pos++;
                    args.add(comparison());
                }
            }
            expect(")");
            return call(t, args);
        }
        if (!VARIABLES.contains(t)) {
            throw new IllegalArgumentException("Unknown variable " + t);
        }
        BigDecimal v = vars.get(t);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal call(String name, List<BigDecimal> a) {
        switch (name) {
            case "MIN", "MAX" -> {
                if (a.isEmpty()) {
                    throw new IllegalArgumentException(name + " needs arguments");
                }
                BigDecimal r = a.getFirst();
                for (BigDecimal x : a) {
                    r = "MIN".equals(name) ? r.min(x) : r.max(x);
                }
                return r;
            }
            case "ROUND" -> {
                if (a.isEmpty() || a.size() > 2) {
                    throw new IllegalArgumentException("ROUND takes 1 or 2 arguments");
                }
                int scale = a.size() == 2 ? a.get(1).intValue() : 0;
                return a.getFirst().setScale(scale, RoundingMode.HALF_UP);
            }
            case "ABS" -> {
                if (a.size() != 1) {
                    throw new IllegalArgumentException("ABS takes 1 argument");
                }
                return a.getFirst().abs();
            }
            case "IF" -> {
                if (a.size() != 3) {
                    throw new IllegalArgumentException("IF takes 3 arguments");
                }
                return a.getFirst().signum() != 0 ? a.get(1) : a.get(2);
            }
            default -> throw new IllegalArgumentException("Unknown function " + name);
        }
    }
}
