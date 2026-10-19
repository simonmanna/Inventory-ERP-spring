package com.inventoryerp.kernel.common;

import com.inventoryerp.shared.web.ApiException;

/**
 * Beverage Control weight ↔ volume math (port of {@code beverage-math.ts}); pure functions with the
 * Nest error messages.
 */
public final class BeverageMath {

    private BeverageMath() {}

    /** Input weights/volume (any may be null). */
    public record BottleInput(Object containerVolumeMl, Object emptyBottleWeightG, Object actualEmptyWeightG,
            Object fullBottleWeightG) {}

    public record BottleConfig(double effectiveEmptyWeightG, double liquidWeightG, double conversionFactorMlPerG) {}

    public record ReadingCheck(boolean outOfRange, String reason) {}

    public static BottleConfig computeBottleConfig(BottleInput input) {
        Double volume = num(input.containerVolumeMl());
        Double empty = num(input.actualEmptyWeightG() != null ? input.actualEmptyWeightG() : input.emptyBottleWeightG());
        Double full = num(input.fullBottleWeightG());
        if (volume == null || empty == null || full == null) {
            throw ApiException.badRequest(
                    "Digital-weight products require container volume, empty bottle weight, and full bottle weight.");
        }
        if (volume <= 0) {
            throw ApiException.badRequest("Container volume (ml) must be greater than 0.");
        }
        if (empty >= full) {
            throw ApiException.badRequest("Empty bottle weight must be less than full bottle weight.");
        }
        double liquid = round(full - empty, 6);
        if (liquid <= 0) {
            throw ApiException.badRequest("Liquid weight must be greater than 0.");
        }
        double factor = round(volume / liquid, 10);
        if (factor <= 0) {
            throw ApiException.badRequest("Conversion factor must be greater than 0.");
        }
        return new BottleConfig(empty, liquid, factor);
    }

    public static double remainingMlFromWeight(double measuredWeightG, double effectiveEmptyWeightG, double factor) {
        return round(Math.max(0, measuredWeightG - effectiveEmptyWeightG) * factor, 6);
    }

    public static double weightFromRemainingMl(double remainingMl, double effectiveEmptyWeightG, double factor) {
        if (factor <= 0) {
            return effectiveEmptyWeightG;
        }
        return round(effectiveEmptyWeightG + remainingMl / factor, 6);
    }

    public static ReadingCheck checkReading(double measuredWeightG, double effectiveEmptyWeightG, double fullBottleWeightG) {
        if (measuredWeightG < effectiveEmptyWeightG) {
            return new ReadingCheck(true, "Weight is below the empty bottle weight (below tare).");
        }
        if (fullBottleWeightG > 0 && measuredWeightG > fullBottleWeightG + 0.5) {
            return new ReadingCheck(true, "Weight exceeds the full bottle weight.");
        }
        return new ReadingCheck(false, null);
    }

    /** OUT_OF_RANGE / GOOD / SUSPICIOUS. */
    public static String classifyConfidence(double varianceG, double toleranceG, boolean outOfRange) {
        if (outOfRange) {
            return "OUT_OF_RANGE";
        }
        return Math.abs(varianceG) <= Math.max(0, toleranceG) ? "GOOD" : "SUSPICIOUS";
    }

    private static Double num(Object v) {
        if (v == null || "".equals(v)) {
            return null;
        }
        double n = v instanceof Number x ? x.doubleValue() : JsNumbers.number(String.valueOf(v));
        return Double.isFinite(n) ? n : null;
    }

    /** {@code Math.round(n * 10^dp) / 10^dp} (JS rounds .5 towards +∞). */
    static double round(double n, int dp) {
        double f = Math.pow(10, dp);
        return Math.floor(n * f + 0.5) / f;
    }
}
