package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reliability figures of a unit over a period (fleet 1.6), from its downtime intervals:
 *
 * <ul>
 *   <li>period = [from, min(to, now)) — the elapsed part of the period;</li>
 *   <li>downtime = union of all downtime intervals clipped to the period (overlaps count once); unplanned downtime =
 *       union of the unplanned (breakdown) intervals;</li>
 *   <li>availability % = (period − downtime) / period × 100;</li>
 *   <li>failures = unplanned intervals that <i>start</i> inside the period;</li>
 *   <li>MTBF (h) = uptime / failures; MTTR (h) = unplanned downtime / failures (both null without failures).</li>
 * </ul>
 * Hours have 2 decimals ({@code HALF_UP}); an open interval runs to the end of the period.
 */
public final class AvailabilityCalculator {

    private static final BigDecimal HOUR_SECONDS = BigDecimal.valueOf(3600);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private AvailabilityCalculator() {}

    /** A downtime interval; {@code end} null = still down. */
    public record Interval(Instant start, Instant end, boolean unplanned) {}

    /** Figures of one unit (or an aggregate). Seconds are kept for exact aggregation. */
    public record Result(long periodSeconds, long downtimeSeconds, long unplannedSeconds, int failures) {

        public BigDecimal periodHours() {
            return hours(periodSeconds);
        }

        public BigDecimal downtimeHours() {
            return hours(downtimeSeconds);
        }

        public BigDecimal unplannedHours() {
            return hours(unplannedSeconds);
        }

        public BigDecimal plannedHours() {
            return hours(downtimeSeconds - unplannedSeconds);
        }

        public BigDecimal uptimeHours() {
            return hours(periodSeconds - downtimeSeconds);
        }

        /** Null when the period is empty. */
        public BigDecimal availabilityPct() {
            if (periodSeconds <= 0) {
                return null;
            }
            return BigDecimal.valueOf(periodSeconds - downtimeSeconds).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(periodSeconds), 2, RoundingMode.HALF_UP);
        }

        public BigDecimal mtbfHours() {
            return failures == 0 ? null : BigDecimal.valueOf(periodSeconds - downtimeSeconds)
                    .divide(HOUR_SECONDS.multiply(BigDecimal.valueOf(failures)), 2, RoundingMode.HALF_UP);
        }

        public BigDecimal mttrHours() {
            return failures == 0 ? null : BigDecimal.valueOf(unplannedSeconds)
                    .divide(HOUR_SECONDS.multiply(BigDecimal.valueOf(failures)), 2, RoundingMode.HALF_UP);
        }
    }

    public static Result compute(Instant from, Instant to, Instant now, List<Interval> intervals) {
        Instant end = to.isAfter(now) ? now : to;
        if (!end.isAfter(from)) {
            return new Result(0, 0, 0, 0);
        }
        List<Instant[]> all = new ArrayList<>();
        List<Instant[]> unplanned = new ArrayList<>();
        int failures = 0;
        for (Interval i : intervals) {
            Instant s = i.start().isBefore(from) ? from : i.start();
            Instant e = i.end() == null || i.end().isAfter(end) ? end : i.end();
            if (i.unplanned() && !i.start().isBefore(from) && i.start().isBefore(end)) {
                failures++;
            }
            if (!e.isAfter(s)) {
                continue;
            }
            all.add(new Instant[] {s, e});
            if (i.unplanned()) {
                unplanned.add(new Instant[] {s, e});
            }
        }
        return new Result(Duration.between(from, end).toSeconds(), union(all), union(unplanned), failures);
    }

    /** Sum of figures (class / fleet totals): ratios are recomputed from the summed seconds. */
    public static Result aggregate(List<Result> results) {
        long p = 0;
        long d = 0;
        long u = 0;
        int f = 0;
        for (Result r : results) {
            p += r.periodSeconds();
            d += r.downtimeSeconds();
            u += r.unplannedSeconds();
            f += r.failures();
        }
        return new Result(p, d, u, f);
    }

    /** Seconds covered by the union of the intervals. */
    private static long union(List<Instant[]> intervals) {
        intervals.sort(Comparator.comparing(a -> a[0]));
        long total = 0;
        Instant curStart = null;
        Instant curEnd = null;
        for (Instant[] i : intervals) {
            if (curEnd == null || i[0].isAfter(curEnd)) {
                if (curEnd != null) {
                    total += Duration.between(curStart, curEnd).toSeconds();
                }
                curStart = i[0];
                curEnd = i[1];
            } else if (i[1].isAfter(curEnd)) {
                curEnd = i[1];
            }
        }
        if (curEnd != null) {
            total += Duration.between(curStart, curEnd).toSeconds();
        }
        return total;
    }

    static BigDecimal hours(long seconds) {
        return BigDecimal.valueOf(seconds).divide(HOUR_SECONDS, 2, RoundingMode.HALF_UP);
    }
}
