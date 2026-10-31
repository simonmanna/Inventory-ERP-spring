package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Load against capacity (ADR-S017), pure: interval overlap, utilisation and the aggregate crushing plan. */
public final class CapacityCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private CapacityCalculator() {}

    /** Minutes of {@code [start, end)} inside {@code [from, to)}. */
    public static long overlapMinutes(Instant start, Instant end, Instant from, Instant to) {
        Instant a = start.isAfter(from) ? start : from;
        Instant b = end.isBefore(to) ? end : to;
        return b.isAfter(a) ? Duration.between(a, b).toMinutes() : 0;
    }

    /** Load as a percentage of capacity (2 decimals, HALF_UP); null without capacity. */
    public static BigDecimal utilisationPct(long loadMinutes, long capacityMinutes) {
        if (capacityMinutes <= 0) {
            return null;
        }
        return BigDecimal.valueOf(loadMinutes).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(capacityMinutes), 2, RoundingMode.HALF_UP);
    }

    /** {@code minutes} expressed in hours (2 decimals). */
    public static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(SIXTY, 2, RoundingMode.HALF_UP);
    }

    /** Quantity a rate per hour moves in {@code minutes}; null without a rate. */
    public static BigDecimal quantity(long minutes, BigDecimal ratePerHour) {
        if (ratePerHour == null || ratePerHour.signum() <= 0) {
            return null;
        }
        return ratePerHour.multiply(BigDecimal.valueOf(minutes)).divide(SIXTY, 3, RoundingMode.HALF_UP);
    }

    /** One size fraction of a grading profile against what must be made of it. */
    public record Fraction(String productId, BigDecimal expectedPct, BigDecimal netDemand) {}

    /**
     * The aggregate plan of one grading profile: the crusher makes every fraction at once, so the feed is set by the
     * tightest fraction ({@code net ÷ expected %}); {@code hours} at the bottleneck rate, null without one.
     */
    public record AggregatePlan(BigDecimal feedTonnes, BigDecimal hours, String drivingProductId) {}

    public static AggregatePlan aggregate(List<Fraction> fractions, BigDecimal bottleneckTph) {
        BigDecimal feed = BigDecimal.ZERO;
        String driver = null;
        for (Fraction f : fractions) {
            if (f.expectedPct() == null || f.expectedPct().signum() <= 0 || f.netDemand().signum() <= 0) {
                continue;
            }
            BigDecimal need = f.netDemand().multiply(HUNDRED).divide(f.expectedPct(), 3, RoundingMode.CEILING);
            if (need.compareTo(feed) > 0) {
                feed = need;
                driver = f.productId();
            }
        }
        BigDecimal hours = bottleneckTph == null || bottleneckTph.signum() <= 0 ? null
                : feed.divide(bottleneckTph, 2, RoundingMode.CEILING);
        return new AggregatePlan(feed, hours, driver);
    }

    /** Expected output of a fraction at {@code feedTonnes}. */
    public static BigDecimal expectedOutput(BigDecimal feedTonnes, BigDecimal expectedPct) {
        return feedTonnes.multiply(expectedPct).divide(HUNDRED, 3, RoundingMode.HALF_UP);
    }
}
