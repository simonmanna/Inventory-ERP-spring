package com.inventoryerp.manufacturing.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Production-shift arithmetic of the shop floor (ADR-S015), pure: shift windows in the organization's time zone
 * (a shift whose end minute is not after its start ends the next day and belongs to the day it starts), the shift an
 * instant falls in, overlap checks and interval clipping.
 */
public final class ShiftClock {

    static final int DAY = 1440;

    private ShiftClock() {}

    /** A shift definition: minutes after local midnight; {@code breakMinutes} are not planned production time. */
    public record ShiftDef(String id, String code, int startMinute, int endMinute, int breakMinutes) {}

    /** One occurrence of a shift: its production date and its window as instants. */
    public record Window(String shiftId, LocalDate shiftDate, Instant startsAt, Instant endsAt) {}

    /** Length of the shift in minutes (wall clock, ignoring DST). */
    public static int lengthMinutes(ShiftDef s) {
        return s.endMinute() > s.startMinute() ? s.endMinute() - s.startMinute() : DAY - s.startMinute() + s.endMinute();
    }

    /** The shift's window on {@code date} in {@code zone}. */
    public static Window window(ShiftDef s, LocalDate date, ZoneId zone) {
        Instant start = date.atStartOfDay().plusMinutes(s.startMinute()).atZone(zone).toInstant();
        LocalDate endDay = s.endMinute() > s.startMinute() ? date : date.plusDays(1);
        Instant end = endDay.atStartOfDay().plusMinutes(s.endMinute()).atZone(zone).toInstant();
        return new Window(s.id(), date, start, end);
    }

    /** The shift occurrence containing {@code at} (first match in list order), if any. */
    public static Optional<Window> resolve(List<ShiftDef> shifts, Instant at, ZoneId zone) {
        LocalDate today = at.atZone(zone).toLocalDate();
        for (ShiftDef s : shifts) {
            for (LocalDate d : List.of(today.minusDays(1), today)) {
                Window w = window(s, d, zone);
                if (!at.isBefore(w.startsAt()) && at.isBefore(w.endsAt())) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    /** True when local minute {@code m} (0..1439) is inside the shift. */
    static boolean covers(ShiftDef s, int m) {
        return s.endMinute() > s.startMinute() ? m >= s.startMinute() && m < s.endMinute()
                : m >= s.startMinute() || m < s.endMinute();
    }

    /** The codes of the first two shifts that overlap ("A/B"), or null when none do. */
    public static String overlap(List<ShiftDef> shifts) {
        for (int i = 0; i < shifts.size(); i++) {
            for (int j = i + 1; j < shifts.size(); j++) {
                for (int m = 0; m < DAY; m++) {
                    if (covers(shifts.get(i), m) && covers(shifts.get(j), m)) {
                        return shifts.get(i).code() + "/" + shifts.get(j).code();
                    }
                }
            }
        }
        return null;
    }

    /** Whole minutes of {@code [from, to)} inside {@code [windowStart, windowEnd)}; an open interval ends at to. */
    public static long clippedMinutes(Instant from, Instant to, Instant windowStart, Instant windowEnd) {
        Instant s = from.isAfter(windowStart) ? from : windowStart;
        Instant e = to.isBefore(windowEnd) ? to : windowEnd;
        return e.isAfter(s) ? ChronoUnit.MINUTES.between(s, e) : 0;
    }

    /** A stop interval ({@code to} = the end of an open stop's clip, e.g. now). */
    public record Stop(Instant from, Instant to, boolean planned) {}

    /**
     * {@code {planned, unplanned}} stop minutes inside {@code [windowStart, windowEnd)}: a minute counts when its
     * midpoint is inside a stop; overlapping stops count once and a planned stop wins over an unplanned one.
     */
    public static long[] stopMinutes(List<Stop> stops, Instant windowStart, Instant windowEnd) {
        long n = Math.max(0, Math.min(ChronoUnit.MINUTES.between(windowStart, windowEnd), 3L * DAY));
        long planned = 0;
        long unplanned = 0;
        for (long i = 0; i < n; i++) {
            Instant mid = windowStart.plusSeconds(i * 60 + 30);
            boolean p = false;
            boolean u = false;
            for (Stop s : stops) {
                if (!mid.isBefore(s.from()) && mid.isBefore(s.to())) {
                    if (s.planned()) {
                        p = true;
                    } else {
                        u = true;
                    }
                }
            }
            if (p) {
                planned++;
            } else if (u) {
                unplanned++;
            }
        }
        return new long[] {planned, unplanned};
    }
}
