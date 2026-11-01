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

    /** Labour split (ADR-S034): rate and efficiency; together they explain actual cost − standard hours × rate. */
    public record Labour(BigDecimal rate, BigDecimal efficiency) {}

    /**
     * {@code rate = actual cost − actual hours × standard rate}; {@code efficiency = (actual − standard hours) ×
     * standard rate}.
     */
    public static Labour labour(BigDecimal actualHours, BigDecimal standardHours, BigDecimal actualCost,
            BigDecimal standardRate) {
        BigDecimal rate = actualCost.subtract(actualHours.multiply(standardRate));
        return new Labour(round(rate), efficiency(actualHours, standardHours, standardRate));
    }

    /** Fixed overhead split (ADR-S034): spending and volume; together actual − standard hours × standard rate. */
    public record Overhead(BigDecimal spending, BigDecimal volume) {}

    /** {@code spending = actual − budget}; {@code volume = budget − standard hours × standard rate}. */
    public static Overhead fixedOverhead(BigDecimal actual, BigDecimal budget, BigDecimal standardHours,
            BigDecimal standardRate) {
        return new Overhead(round(actual.subtract(budget)), round(budget.subtract(standardHours.multiply(
                standardRate))));
    }

    /** One component of a mix: actual and standard quantity and standard unit cost. */
    public record MixLine(BigDecimal actualQty, BigDecimal standardQty, BigDecimal standardUnit) {}

    /** Material mix and yield split of the usage variance: {@code mix + yield = Σ (actual − standard qty) × std}. */
    public record Mix(BigDecimal mix, BigDecimal yield) {}

    /**
     * Mix: each component's actual quantity against its standard share of the total actual quantity; yield: the
     * total actual quantity in standard proportions against the standard quantities.
     */
    public static Mix mix(java.util.List<MixLine> lines) {
        BigDecimal totalActual = BigDecimal.ZERO;
        BigDecimal totalStandard = BigDecimal.ZERO;
        for (MixLine l : lines) {
            totalActual = totalActual.add(l.actualQty());
            totalStandard = totalStandard.add(l.standardQty());
        }
        BigDecimal mix = BigDecimal.ZERO;
        BigDecimal usage = BigDecimal.ZERO;
        for (MixLine l : lines) {
            BigDecimal inStandardMix = totalStandard.signum() == 0 ? BigDecimal.ZERO
                    : totalActual.multiply(l.standardQty()).divide(totalStandard, 12, RoundingMode.HALF_UP);
            mix = mix.add(l.actualQty().subtract(inStandardMix).multiply(l.standardUnit()));
            usage = usage.add(l.actualQty().subtract(l.standardQty()).multiply(l.standardUnit()));
        }
        BigDecimal roundedMix = round(mix);
        return new Mix(roundedMix, round(usage).subtract(roundedMix));
    }

    public static BigDecimal round(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
