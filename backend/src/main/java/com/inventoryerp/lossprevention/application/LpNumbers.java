package com.inventoryerp.lossprevention.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Decimal helpers of the engines. Quantities and money are never floating point. */
public final class LpNumbers {

    private LpNumbers() {}

    public static final int SCALE = 6;

    /** {@code v} as a decimal: numbers and numeric strings; anything else is {@code null}. */
    public static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal d) {
            return d;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static BigDecimal round(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        return b.signum() == 0 ? BigDecimal.ZERO : a.divide(b, SCALE, RoundingMode.HALF_UP);
    }

    /** Median of the values; zero for an empty list. */
    public static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return divide(sorted.get(mid - 1).add(sorted.get(mid)), BigDecimal.valueOf(2));
    }
}
