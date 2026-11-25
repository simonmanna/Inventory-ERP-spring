package com.inventoryerp.qualitycontrol.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Test quantity of a quantity-controlled check (ADR-S087 §4): {@code ceil(coveredQty × pct / 100)} at the product
 * unit's decimal scale (a whole-unit UoM gives whole units), clamped to {@code [minSampleQty (default 1), coveredQty]}.
 * Computed independently per line: an MO with lines of 100 / 200 / 300 at 10 % tests 10 / 20 / 30.
 */
public final class TestQuantityCalculator {

    private TestQuantityCalculator() {}

    public static BigDecimal testQty(BigDecimal coveredQty, BigDecimal pct, BigDecimal minSampleQty, int scale) {
        if (coveredQty == null || coveredQty.signum() <= 0) {
            return BigDecimal.ZERO.setScale(Math.max(0, scale));
        }
        int s = Math.max(0, Math.min(6, scale));
        BigDecimal raw = pct == null ? coveredQty
                : coveredQty.multiply(pct).divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        BigDecimal qty = raw.setScale(s, RoundingMode.CEILING);
        BigDecimal min = (minSampleQty != null ? minSampleQty : BigDecimal.ONE).setScale(s, RoundingMode.CEILING);
        if (qty.compareTo(min) < 0) {
            qty = min;
        }
        return qty.compareTo(coveredQty) > 0 ? coveredQty : qty;
    }
}
