package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finite calendar of one work centre (ADR-S019), pure: weekly working windows, closures, the load already placed and
 * the parallel slots. A minute is usable when the centre works and fewer than {@code slots} operations occupy it. No
 * shifts configured means the centre works around the clock.
 *
 * <p>Integrity fix 2026-11-24 (MP-7): windows and closure dates are evaluated in the organization's time zone (the
 * zone the shop-floor shifts use), no longer in UTC. Integrity fix 2026-11-24 (MP-6): {@link #forward} and
 * {@link #backward} no longer test every minute against every busy interval; they jump between the points where
 * usability can change (window edges, local midnights, zone transitions, starts / ends of the load), with the load
 * kept as sorted start / end arrays. The result is minute-for-minute what the old minute walk gave.
 */
public final class PlanningCalendar {

    private static final long SEARCH_LIMIT = 2L * 366 * 24 * 60;

    /** An operation's place: first used minute, and the minute after the last used one. */
    public record Slot(Instant start, Instant end) {}

    private final Map<Integer, List<int[]>> shifts;
    private final Set<LocalDate> closed;
    private final int slots;
    private final ZoneId zone;
    private final long[] starts;
    private final long[] ends;

    /** A calendar in UTC (kept for callers and tests without an organization). */
    public PlanningCalendar(Map<Integer, List<int[]>> shifts, Set<LocalDate> closed, List<Instant[]> busy, int slots) {
        this(shifts, closed, busy, slots, ZoneOffset.UTC);
    }

    /**
     * @param shifts weekday (0 = Sunday) → {@code [startMinute, endMinute)} local windows
     * @param closed closure dates (local)
     * @param busy occupied {@code [start, end)} intervals
     * @param slots parallel operations the centre can run (at least 1)
     * @param zone the organization's time zone
     */
    public PlanningCalendar(Map<Integer, List<int[]>> shifts, Set<LocalDate> closed, List<Instant[]> busy, int slots,
            ZoneId zone) {
        this.shifts = shifts;
        this.closed = closed;
        this.slots = Math.max(1, slots);
        this.zone = zone == null ? ZoneOffset.UTC : zone;
        List<Instant[]> valid = new ArrayList<>();
        for (Instant[] b : busy) {
            if (b[0] != null && b[1] != null && b[1].isAfter(b[0])) {
                valid.add(b);
            }
        }
        this.starts = new long[valid.size()];
        this.ends = new long[valid.size()];
        for (int i = 0; i < valid.size(); i++) {
            starts[i] = ceilMillis(valid.get(i)[0]);
            ends[i] = ceilMillis(valid.get(i)[1]);
        }
        Arrays.sort(starts);
        Arrays.sort(ends);
    }

    /** Epoch milliseconds rounded up, so {@code x <= t} compares exactly for whole-millisecond {@code t}. */
    private static long ceilMillis(Instant i) {
        long ms = i.toEpochMilli();
        return i.getNano() % 1_000_000 == 0 ? ms : ms + 1;
    }

    /** The zone the calendar's windows are read in. */
    public ZoneId zone() {
        return zone;
    }

    /** Does the centre work in the minute starting at {@code t}? */
    public boolean working(Instant t) {
        ZonedDateTime z = t.atZone(zone);
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

    /** Operations occupying the instant {@code t}: {@code #(start <= t) - #(end <= t)}. */
    private int occupied(long t) {
        return upper(starts, t) - upper(ends, t);
    }

    private boolean usable(Instant t) {
        return working(t) && occupied(t.toEpochMilli()) < slots;
    }

    /** Number of values {@code <= t} in a sorted array. */
    private static int upper(long[] a, long t) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The first load start / end strictly after {@code t} (Long.MAX_VALUE when none). */
    private long nextLoadEdge(long t) {
        int i = upper(starts, t);
        int j = upper(ends, t);
        long a = i < starts.length ? starts[i] : Long.MAX_VALUE;
        long b = j < ends.length ? ends[j] : Long.MAX_VALUE;
        return Math.min(a, b);
    }

    /** The last load start / end at or before {@code t} (Long.MIN_VALUE when none). */
    private long previousLoadEdge(long t) {
        int i = upper(starts, t);
        int j = upper(ends, t);
        long a = i > 0 ? starts[i - 1] : Long.MIN_VALUE;
        long b = j > 0 ? ends[j - 1] : Long.MIN_VALUE;
        return Math.max(a, b);
    }

    /** Local window edges (and midnight) of {@code t}'s day, in minutes of the day. */
    private List<Integer> edges(ZonedDateTime z) {
        List<Integer> out = new ArrayList<>();
        out.add(0);
        out.add(1440);
        for (int[] w : shifts.getOrDefault(z.getDayOfWeek().getValue() % 7, List.of())) {
            out.add(Math.max(0, Math.min(1440, w[0])));
            out.add(Math.max(0, Math.min(1440, w[1])));
        }
        return out;
    }

    /** The first instant after {@code t} at which the calendar (not the load) may change. */
    private Instant nextCalendarEdge(Instant t) {
        ZonedDateTime z = t.atZone(zone);
        long second = z.toLocalTime().toSecondOfDay();
        Instant best = null;
        for (int e : edges(z)) {
            if (e * 60L > second) {
                Instant at = z.toLocalDate().atStartOfDay().plusMinutes(e).toInstant(z.getOffset());
                if (at.isAfter(t) && (best == null || at.isBefore(best))) {
                    best = at;
                }
            }
        }
        ZoneOffsetTransition tr = zone.getRules().nextTransition(t);
        if (tr != null && tr.getInstant().isAfter(t) && (best == null || tr.getInstant().isBefore(best))) {
            best = tr.getInstant();
        }
        return best == null ? t.plus(1, ChronoUnit.MINUTES) : best;
    }

    /** The last instant at or before {@code t} at which the calendar (not the load) may have changed. */
    private Instant previousCalendarEdge(Instant t) {
        ZonedDateTime z = t.atZone(zone);
        long second = z.toLocalTime().toSecondOfDay();
        Instant best = null;
        for (int e : edges(z)) {
            if (e * 60L <= second) {
                Instant at = z.toLocalDate().atStartOfDay().plusMinutes(e).toInstant(z.getOffset());
                if (!at.isAfter(t) && (best == null || at.isAfter(best))) {
                    best = at;
                }
            }
        }
        ZoneOffsetTransition tr = zone.getRules().previousTransition(t.plusNanos(1));
        if (tr != null && !tr.getInstant().isAfter(t) && (best == null || tr.getInstant().isAfter(best))) {
            best = tr.getInstant();
        }
        return best == null ? t : best;
    }

    private static Instant ceilMinute(Instant i) {
        Instant m = i.truncatedTo(ChronoUnit.MINUTES);
        return m.equals(i) ? i : m.plus(1, ChronoUnit.MINUTES);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    /** The first {@code minutes} usable minutes from {@code from}; null when none fit within two years. */
    public Slot forward(Instant from, long minutes) {
        Instant t = from.truncatedTo(ChronoUnit.MINUTES);
        if (minutes <= 0) {
            return new Slot(t, t);
        }
        // The minute walk examined the minutes from .. from + SEARCH_LIMIT.
        Instant cap = t.plus(SEARCH_LIMIT + 1, ChronoUnit.MINUTES);
        Instant start = null;
        Instant end = null;
        long remaining = minutes;
        while (remaining > 0) {
            if (!t.isBefore(cap)) {
                return null;
            }
            long load = nextLoadEdge(t.toEpochMilli());
            Instant next = nextCalendarEdge(t);
            if (load != Long.MAX_VALUE) {
                next = min(next, Instant.ofEpochMilli(load));
            }
            // Every minute in [t, segment end) reads the same: the next change hits the first minute at or after it.
            Instant segEnd = min(ceilMinute(next), cap);
            long length = ChronoUnit.MINUTES.between(t, segEnd);
            if (usable(t)) {
                if (start == null) {
                    start = t;
                }
                long use = Math.min(length, remaining);
                remaining -= use;
                end = t.plus(use, ChronoUnit.MINUTES);
            }
            t = segEnd;
        }
        return new Slot(start, end);
    }

    /** The last {@code minutes} usable minutes before {@code end}; null when none fit within two years. */
    public Slot backward(Instant end, long minutes) {
        Instant t = end.truncatedTo(ChronoUnit.MINUTES);
        if (minutes <= 0) {
            return new Slot(t, t);
        }
        // The minute walk examined the minutes end - 1 .. end - 1 - SEARCH_LIMIT.
        Instant floor = t.minus(SEARCH_LIMIT + 1, ChronoUnit.MINUTES);
        Instant m = t.minus(1, ChronoUnit.MINUTES);
        Instant start = null;
        Instant stop = null;
        long remaining = minutes;
        while (remaining > 0) {
            if (m.isBefore(floor)) {
                return null;
            }
            long load = previousLoadEdge(m.toEpochMilli());
            Instant previous = previousCalendarEdge(m);
            if (load != Long.MIN_VALUE) {
                previous = max(previous, Instant.ofEpochMilli(load));
            }
            // Every minute in [segment start, m] reads the same.
            Instant lo = max(ceilMinute(previous), floor);
            long length = ChronoUnit.MINUTES.between(lo, m) + 1;
            if (usable(m)) {
                if (stop == null) {
                    stop = m.plus(1, ChronoUnit.MINUTES);
                }
                long use = Math.min(length, remaining);
                remaining -= use;
                start = m.minus(use - 1, ChronoUnit.MINUTES);
            }
            m = lo.minus(1, ChronoUnit.MINUTES);
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
        LocalDate last = to.minusMillis(1).atZone(zone).toLocalDate();
        for (LocalDate d = from.atZone(zone).toLocalDate(); !d.isAfter(last); d = d.plusDays(1)) {
            if (closed.contains(d)) {
                continue;
            }
            List<int[]> windows = shifts.isEmpty() ? List.of(new int[] {0, 1440})
                    : shifts.getOrDefault(d.getDayOfWeek().getValue() % 7, List.of());
            for (int[] w : windows) {
                sum += CapacityCalculator.overlapMinutes(d.atStartOfDay().plusMinutes(w[0]).atZone(zone).toInstant(),
                        d.atStartOfDay().plusMinutes(w[1]).atZone(zone).toInstant(), from, to);
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
