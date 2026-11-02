package com.inventoryerp.reporting.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Statement line formulas: line codes, decimal numbers, {@code + - * /} and parentheses ({@code GP / NET_REV * 100}).
 * A division by zero yields {@code null} (shown blank), never an error. Codes are upper-case letters, digits and
 * {@code _}.
 */
public final class FormulaEvaluator {

    private final String src;
    private final Function<String, BigDecimal> lookup;
    private int pos;

    private FormulaEvaluator(String src, Function<String, BigDecimal> lookup) {
        this.src = src;
        this.lookup = lookup;
    }

    /** Evaluate {@code formula}; {@code lookup} resolves a line code (may return {@code null} = blank). */
    public static BigDecimal evaluate(String formula, Function<String, BigDecimal> lookup) {
        FormulaEvaluator f = new FormulaEvaluator(formula, lookup);
        BigDecimal v = f.expr();
        f.skip();
        if (f.pos != f.src.length()) {
            throw ApiException.badRequest("Invalid formula '" + formula + "' at position " + (f.pos + 1));
        }
        return v;
    }

    /** Line codes referenced by a formula (validation and cycle detection). */
    public static Set<String> references(String formula) {
        Set<String> out = new LinkedHashSet<>();
        evaluate(formula, code -> {
            out.add(code);
            return BigDecimal.ONE;
        });
        return out;
    }

    /** Validate syntax and that every reference is one of {@code known}. */
    public static void validate(String formula, Map<String, ?> known) {
        for (String ref : references(formula)) {
            if (!known.containsKey(ref)) {
                throw ApiException.badRequest("Formula '" + formula + "' references unknown line " + ref);
            }
        }
    }

    private BigDecimal expr() {
        BigDecimal v = term();
        while (true) {
            skip();
            if (peek('+')) {
                pos++;
                v = add(v, term(), false);
            } else if (peek('-')) {
                pos++;
                v = add(v, term(), true);
            } else {
                return v;
            }
        }
    }

    private BigDecimal term() {
        BigDecimal v = factor();
        while (true) {
            skip();
            if (peek('*')) {
                pos++;
                BigDecimal r = factor();
                v = v == null || r == null ? null : v.multiply(r);
            } else if (peek('/')) {
                pos++;
                BigDecimal r = factor();
                v = v == null || r == null || r.signum() == 0 ? null : v.divide(r, 10, RoundingMode.HALF_UP);
            } else {
                return v;
            }
        }
    }

    private BigDecimal factor() {
        skip();
        if (pos >= src.length()) {
            throw ApiException.badRequest("Invalid formula '" + src + "': unexpected end");
        }
        char c = src.charAt(pos);
        if (c == '-') {
            pos++;
            BigDecimal v = factor();
            return v == null ? null : v.negate();
        }
        if (c == '(') {
            pos++;
            BigDecimal v = expr();
            skip();
            if (!peek(')')) {
                throw ApiException.badRequest("Invalid formula '" + src + "': missing )");
            }
            pos++;
            return v;
        }
        int start = pos;
        if (Character.isDigit(c) || c == '.') {
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            try {
                return new BigDecimal(src.substring(start, pos));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Invalid formula '" + src + "': bad number");
            }
        }
        if (Character.isLetter(c) || c == '_') {
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            return lookup.apply(src.substring(start, pos).toUpperCase(java.util.Locale.ROOT));
        }
        throw ApiException.badRequest("Invalid formula '" + src + "' at position " + (pos + 1));
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b, boolean minus) {
        BigDecimal x = a == null ? BigDecimal.ZERO : a;
        BigDecimal y = b == null ? BigDecimal.ZERO : b;
        return minus ? x.subtract(y) : x.add(y);
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    private void skip() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
