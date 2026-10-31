package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finite calendar of one work centre (ADR-S017), pure: weekly working windows, closures, the load already placed and
 * the parallel slots. A minute is usable when the centre works and fewer than {@code slots} operations occupy it.
 * {@link #forward} and {@link #backward} walk minute by minute, like {@link ShopFloorService}'s scheduler: no shifts
 * configured means the centre works around the clock. The business clock is UTC.
 */
public final class PlanningCalendar {

    private static final long SEARCH_LIMIT = 2L * 366 * 24 * 60;

    /** An operation's place: first used minute, and the minute after the last used one. */
    public record Slot(Instant start, Instant end) {}

    private final Map<Integer, List<int[]>> shifts;
    private final Set<LocalDate> closed;
    private final List<Instant[]> busy;
    private final int slots;

    /**
     * @param shifts weekday (0 = Sunday) → {@code [startMinute, endMinute)} windows
     * @param closed closure dates
     * @param busy occupied {@code [start, end)} intervals
     * @param slots parallel operations the centre can run (at least 1)
     */
    public PlanningCalendar(Map<Integer, List<int[]>> shifts, Set<LocalDate> closed, List<Instant[]> busy, int slots) {
        this.shifts = shifts;
        this.closed = closed;
        this.busy = busy;
        this.slots = Math.max(1, slots);
    }

    /** Does the centre work in the minute starting at {@code t}? */
    public boolean working(Instant t) {
        ZonedDateTime z = t.atZone(ZoneOffset.UTC);
        if (closed.contains(z.toLocalDate())) {
            return false;
        }
        if (shifts.isEmpty()) {
            return true;
        }
        int minute = z.getHour() * 60 + z.getMinute();
        for (int[] w : shifts.getOrDefault(z.getDayOfWeek().getValue() % 7, List.of())) {
            if (minute >= w[0] && minute < w[1]) {
                return true;
            }
        }
        return false;
    }

    private boolean usable(Instant t) {
        if (!working(t)) {
            return false;
        }
        int n = 0;
        for (Instant[] b : busy) {
            if (!t.isBefore(b[0]) && t.isBefore(b[1])) {
                n++;
            }
        }
        return n < slots;
    }

    /** The first {@code minutes} usable minutes from {@code from}; null when none fit within two years. */
    public Slot forward(Instant from, long minutes) {
        Instant t = from.truncatedTo(ChronoUnit.MINUTES);
        if (minutes <= 0) {
            return new Slot(t, t);
        }
        Instant start = null;
        Instant last = null;
        long remaining = minutes;
        for (long step = 0; remaining > 0; step++) {
            if (step > SEARCH_LIMIT) {
                return null;
            }
            if (usable(t)) {
                if (start == null) {
                    start = t;
                }
                last = t;
                remaining--;
            }
            t = t.plus(1, ChronoUnit.MINUTES);
        }
        return new Slot(start, last.plus(1, ChronoUnit.MINUTES));
    }

    /** The last {@code minutes} usable minutes before {@code end}; null when none fit within two years. */
    public Slot backward(Instant end, long minutes) {
        Instant t = end.truncatedTo(ChronoUnit.MINUTES);
        if (minutes <= 0) {
            return new Slot(t, t);
        }
        Instant start = null;
        Instant stop = null;
        long remaining = minutes;
        for (long step = 0; remaining > 0; step++) {
            if (step > SEARCH_LIMIT) {
                return null;
            }
            Instant minute = t.minus(1, ChronoUnit.MINUTES);
            if (usable(minute)) {
                if (stop == null) {
                    stop = t;
                }
                start = minute;
                remaining--;
            }
            t = minute;
        }
        return new Slot(start, stop);
    }

    /** Working minutes of one day for one slot, ignoring load. */
    public long workingMinutes(LocalDate day) {
        if (closed.contains(day)) {
            return 0;
        }
        if (shifts.isEmpty()) {
            return 1440;
        }
        long sum = 0;
        for (int[] w : shifts.getOrDefault(day.getDayOfWeek().getValue() % 7, List.of())) {
            sum += Math.max(0, w[1] - w[0]);
        }
        return Math.min(sum, 1440);
    }

    /** Working minutes of one slot inside {@code [from, to)}, ignoring load. */
    public long workingMinutesBetween(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return 0;
        }
        long sum = 0;
        LocalDate last = to.minusMillis(1).atZone(ZoneOffset.UTC).toLocalDate();
        for (LocalDate d = from.atZone(ZoneOffset.UTC).toLocalDate(); !d.isAfter(last); d = d.plusDays(1)) {
            if (closed.contains(d)) {
                continue;
            }
            Instant midnight = d.atStartOfDay(ZoneOffset.UTC).toInstant();
            List<int[]> windows = shifts.isEmpty() ? List.of(new int[] {0, 1440})
                    : shifts.getOrDefault(d.getDayOfWeek().getValue() % 7, List.of());
            for (int[] w : windows) {
                sum += CapacityCalculator.overlapMinutes(midnight.plus(w[0], ChronoUnit.MINUTES),
                        midnight.plus(w[1], ChronoUnit.MINUTES), from, to);
            }
        }
        return sum;
    }

    /** Has this centre a weekly calendar at all? */
    public boolean hasShifts() {
        return !shifts.isEmpty();
    }

    /**
     * Run minutes of an operation: the quantity at the centre's rate when it has one (t/h for a crushing unit, units
     * per hour from the shop-floor profile), else the routing's fixed time; ÷ the centre's efficiency, rounded up.
     */
    public static long operationMinutes(int plannedMinutes, BigDecimal qty, BigDecimal ratePerHour,
            BigDecimal efficiencyPct) {
        BigDecimal base = BigDecimal.valueOf(Math.max(0, plannedMinutes));
        if (ratePerHour != null && ratePerHour.signum() > 0 && qty != null && qty.signum() > 0) {
            base = qty.multiply(BigDecimal.valueOf(60)).divide(ratePerHour, 6, RoundingMode.CEILING);
        }
        BigDecimal eff = efficiencyPct != null && efficiencyPct.signum() > 0 ? efficiencyPct : BigDecimal.valueOf(100);
        return base.multiply(BigDecimal.valueOf(100)).divide(eff, 0, RoundingMode.CEILING).longValue();
    }
}
