package com.inventoryerp.quarry.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Extraction vs target of a plan (ADR-S012 §5): variance, achievement and the pace against a straight-line target
 * to date. Status: {@code not_started} (before the period), {@code ahead} / {@code on_track} / {@code behind} (±5 %
 * of the target to date) while the period runs, {@code achieved} / {@code missed} after it ends.
 */
public final class TargetVariance {

    static final BigDecimal BAND = new BigDecimal("0.05");

    private TargetVariance() {}

    public record Variance(BigDecimal target, BigDecimal actual, BigDecimal variance, BigDecimal achievedPct,
            BigDecimal expectedToDate, BigDecimal paceVariance, long elapsedDays, long totalDays, String status) {}

    public static Variance of(BigDecimal target, BigDecimal actual, LocalDate start, LocalDate end, LocalDate asOf) {
        BigDecimal t = target.setScale(3, RoundingMode.HALF_UP);
        BigDecimal a = (actual == null ? BigDecimal.ZERO : actual).setScale(3, RoundingMode.HALF_UP);
        long total = ChronoUnit.DAYS.between(start, end) + 1;
        long elapsed = Math.max(0, Math.min(total, ChronoUnit.DAYS.between(start, asOf) + 1));
        BigDecimal expected = t.multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP);
        BigDecimal achieved = t.signum() == 0 ? BigDecimal.ZERO
                : a.multiply(BigDecimal.valueOf(100)).divide(t, 1, RoundingMode.HALF_UP);
        String status;
        if (asOf.isAfter(end)) {
            status = a.compareTo(t) >= 0 ? "achieved" : "missed";
        } else if (elapsed == 0) {
            status = "not_started";
        } else {
            BigDecimal low = expected.multiply(BigDecimal.ONE.subtract(BAND));
            BigDecimal high = expected.multiply(BigDecimal.ONE.add(BAND));
            status = a.compareTo(high) > 0 ? "ahead" : a.compareTo(low) >= 0 ? "on_track" : "behind";
        }
        return new Variance(t, a, a.subtract(t), achieved, expected, a.subtract(expected), elapsed, total, status);
    }
}
