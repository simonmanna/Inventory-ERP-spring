package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Production variances (ADR-S018), pure; positive is unfavourable. They reconcile: actual cost less the standard cost
 * of
 * the good output = material price + material usage + labour efficiency + overhead + yield. Usage is measured against
 * the quantity planned for the whole run, so an output shortfall shows as yield and is not counted twice.
 */
public final class VarianceCalculator {

    private static final int SCALE = 2;

    private VarianceCalculator() {}

    public record Material(BigDecimal price, BigDecimal usage) {}

    /**
     * {@code price = (actual unit − standard unit) × actual qty}; {@code usage = (actual − planned qty) × standard
     * unit}.
     */
    public static Material material(BigDecimal plannedQty, BigDecimal actualQty, BigDecimal actualCost,
            BigDecimal standardUnit) {
        BigDecimal price = actualCost.subtract(actualQty.multiply(standardUnit));
        BigDecimal usage = actualQty.subtract(plannedQty).multiply(standardUnit);
        return new Material(round(price), round(usage));
    }

    /** {@code (expected − actual output) × standard unit cost}. */
    public static BigDecimal yield(BigDecimal expectedOutput, BigDecimal actualOutput, BigDecimal standardUnitCost) {
        return round(expectedOutput.subtract(actualOutput).multiply(standardUnitCost));
    }

    /** {@code (actual − standard hours) × rate}. */
    public static BigDecimal efficiency(BigDecimal actualHours, BigDecimal standardHours, BigDecimal rate) {
        return round(actualHours.subtract(standardHours).multiply(rate));
    }

    public static BigDecimal round(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
