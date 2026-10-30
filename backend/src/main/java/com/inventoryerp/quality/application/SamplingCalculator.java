package com.inventoryerp.quality.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Sample size of an inspection lot from its plan's sampling method (ADR-S013 §3):
 *
 * <ul>
 *   <li>{@code fixed}: {@code sampleSize} (default 1);</li>
 *   <li>{@code percent}: ⌈N × samplePct / 100⌉;</li>
 *   <li>{@code sqrt}: ⌈√N⌉ + 1 (the usual rule for bulk material increments);</li>
 *   <li>{@code per_quantity}: ⌈N / perQuantity⌉ (e.g. one sample per 500 t or per 1,000 blocks);</li>
 * </ul>
 * then clamped to {@code minSamples .. maxSamples} and never below 1.
 */
public final class SamplingCalculator {

    private SamplingCalculator() {}

    public record Sampling(String method, Integer sampleSize, BigDecimal samplePct, BigDecimal perQuantity,
            Integer minSamples, Integer maxSamples) {}

    public static int sampleSize(Sampling s, BigDecimal quantity) {
        BigDecimal qty = quantity == null || quantity.signum() < 0 ? BigDecimal.ZERO : quantity;
        String method = s.method() == null ? "fixed" : s.method();
        long n = switch (method) {
            case "percent" -> s.samplePct() == null ? 1
                    : ceil(qty.multiply(s.samplePct()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            case "sqrt" -> ceil(qty.sqrt(MathContext.DECIMAL64)) + 1;
            case "per_quantity" -> s.perQuantity() == null || s.perQuantity().signum() <= 0 ? 1
                    : ceil(qty.divide(s.perQuantity(), 6, RoundingMode.HALF_UP));
            default -> s.sampleSize() == null ? 1 : s.sampleSize();
        };
        int min = s.minSamples() == null ? 1 : Math.max(1, s.minSamples());
        n = Math.max(n, min);
        if (s.maxSamples() != null) {
            n = Math.min(n, s.maxSamples());
        }
        return (int) Math.max(1, Math.min(n, 10_000));
    }

    private static long ceil(BigDecimal v) {
        return v.setScale(0, RoundingMode.CEILING).longValueExact();
    }
}
