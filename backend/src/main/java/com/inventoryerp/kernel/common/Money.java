package com.inventoryerp.kernel.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Money / quantity math on {@link BigDecimal} (port of {@code kernel/common/money.ts}, ADR-009): ledger
 * amounts are 6 dp; display rounding per currency with HALF_UP. Never compare with {@code equals}.
 */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO;

    /** decimal.js default context (Prisma.Decimal): 20 significant digits, ROUND_HALF_UP. */
    public static final java.math.MathContext DECIMAL_JS = new java.math.MathContext(20, RoundingMode.HALF_UP);

    /** decimal.js {@code a.dividedBy(b)}. */
    public static BigDecimal div(BigDecimal a, BigDecimal b) {
        return a.divide(b, DECIMAL_JS);
    }

    /** decimal.js {@code Decimal.min}. */
    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** decimal.js {@code Decimal.max}. */
    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
    private static final BigDecimal EPSILON = new BigDecimal("0.0001");

    private Money() {}

    public static BigDecimal dec(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        BigDecimal acc = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            acc = acc.add(v);
        }
        return acc;
    }

    public static BigDecimal round(BigDecimal value) {
        return round(value, 2);
    }

    public static BigDecimal round(BigDecimal value, int decimalPlaces) {
        return value.setScale(decimalPlaces, RoundingMode.HALF_UP);
    }

    public static boolean isZero(BigDecimal value) {
        return value.signum() == 0;
    }

    public static boolean eq(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0;
    }

    /** |a − b| ≤ 0.0001. */
    public static boolean approxEqual(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(EPSILON) <= 0;
    }

    public static boolean approxEqual(BigDecimal a, BigDecimal b, BigDecimal epsilon) {
        return a.subtract(b).abs().compareTo(epsilon) <= 0;
    }

    /** Multiply exactly and round to the 6-dp ledger scale (decimal.js {@code toDecimalPlaces(6)} = HALF_UP). */
    public static BigDecimal qtyMul(Object... factors) {
        BigDecimal acc = BigDecimal.ONE;
        for (Object f : factors) {
            acc = acc.multiply(dec(f == null ? 0 : f));
        }
        return acc.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
