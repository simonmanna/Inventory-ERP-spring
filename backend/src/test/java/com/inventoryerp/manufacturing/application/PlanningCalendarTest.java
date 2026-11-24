package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.PlanningCalendar.Slot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Finite calendar: forward and backward placement, closures, parallel slots, run time from a rate (ADR-S017). */
class PlanningCalendarTest {

    // 2026-11-02 is a Monday. Monday to Friday 08:00 to 17:00.
    static final Map<Integer, List<int[]>> WEEK = Map.of(1, day(), 2, day(), 3, day(), 4, day(), 5, day());

    private static List<int[]> day() {
        List<int[]> w = new ArrayList<>();
        w.add(new int[] {480, 1020});
        return w;
    }

    private static PlanningCalendar calendar(Set<LocalDate> closed, List<Instant[]> busy, int slots) {
        return new PlanningCalendar(WEEK, closed, busy, slots);
    }

    @Test
    @DisplayName("backward placement ends at the anchor and stays inside the shift")
    void backwardInsideShift() {
        Slot s = calendar(Set.of(), List.of(), 1).backward(Instant.parse("2026-11-06T17:00:00Z"), 60);
        assertThat(s.start()).isEqualTo(Instant.parse("2026-11-06T16:00:00Z"));
        assertThat(s.end()).isEqualTo(Instant.parse("2026-11-06T17:00:00Z"));
    }

    @Test
    @DisplayName("backward placement jumps over the night, weekends and closures")
    void backwardOverGaps() {
        Slot s = calendar(Set.of(LocalDate.parse("2026-11-02")), List.of(), 1)
                .backward(Instant.parse("2026-11-03T09:00:00Z"), 120);
        // One hour on Tuesday; Monday is closed, Saturday and Sunday are off, so the rest is Friday's last hour.
        assertThat(s.end()).isEqualTo(Instant.parse("2026-11-03T09:00:00Z"));
        assertThat(s.start()).isEqualTo(Instant.parse("2026-10-30T16:00:00Z"));
    }

    @Test
    @DisplayName("an anchor outside working time ends at the last working minute before it")
    void backwardAnchorOutsideShift() {
        Slot s = calendar(Set.of(), List.of(), 1).backward(Instant.parse("2026-11-07T12:00:00Z"), 30);
        assertThat(s.end()).isEqualTo(Instant.parse("2026-11-06T17:00:00Z"));
        assertThat(s.start()).isEqualTo(Instant.parse("2026-11-06T16:30:00Z"));
    }

    @Test
    @DisplayName("load already placed pushes the operation away unless a parallel slot is free")
    void busyAndSlots() {
        List<Instant[]> busy = List.<Instant[]>of(new Instant[] {Instant.parse("2026-11-06T15:00:00Z"),
                Instant.parse("2026-11-06T17:00:00Z")});
        Instant anchor = Instant.parse("2026-11-06T17:00:00Z");
        assertThat(calendar(Set.of(), busy, 1).backward(anchor, 60).end())
                .isEqualTo(Instant.parse("2026-11-06T15:00:00Z"));
        assertThat(calendar(Set.of(), busy, 2).backward(anchor, 60).end()).isEqualTo(anchor);
    }

    @Test
    @DisplayName("forward placement starts at the first working minute")
    void forwardSnaps() {
        Slot s = calendar(Set.of(), List.of(), 1).forward(Instant.parse("2026-11-07T10:00:00Z"), 90);
        assertThat(s.start()).isEqualTo(Instant.parse("2026-11-09T08:00:00Z"));
        assertThat(s.end()).isEqualTo(Instant.parse("2026-11-09T09:30:00Z"));
    }

    @Test
    @DisplayName("a centre that never works gives no slot")
    void noCapacity() {
        PlanningCalendar never = new PlanningCalendar(Map.of(6, day()), Set.of(), List.<Instant[]>of(
                new Instant[] {Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2040-01-01T00:00:00Z")}), 1);
        assertThat(never.forward(Instant.parse("2026-11-02T00:00:00Z"), 10)).isNull();
    }

    @Test
    @DisplayName("working minutes: per day, closed days, and between two instants")
    void workingMinutes() {
        PlanningCalendar c = calendar(Set.of(LocalDate.parse("2026-11-03")), List.of(), 1);
        assertThat(c.workingMinutes(LocalDate.parse("2026-11-02"))).isEqualTo(540);
        assertThat(c.workingMinutes(LocalDate.parse("2026-11-03"))).isZero();
        assertThat(c.workingMinutes(LocalDate.parse("2026-11-07"))).isZero();
        assertThat(c.workingMinutesBetween(Instant.parse("2026-11-02T16:00:00Z"),
                Instant.parse("2026-11-04T09:00:00Z"))).isEqualTo(120);
        assertThat(new PlanningCalendar(Map.of(), Set.of(), List.of(), 1).workingMinutes(LocalDate.parse("2026-11-07")))
                .isEqualTo(1440);
    }

    @Test
    @DisplayName("run time: fixed minutes, or quantity at the rate, divided by efficiency")
    void operationMinutes() {
        assertThat(PlanningCalendar.operationMinutes(100, BigDecimal.TEN, null, BigDecimal.valueOf(50))).isEqualTo(200);
        assertThat(PlanningCalendar.operationMinutes(100, BigDecimal.valueOf(300), BigDecimal.valueOf(150), null))
                .isEqualTo(120);
        assertThat(PlanningCalendar.operationMinutes(100, BigDecimal.ZERO, BigDecimal.valueOf(150), null)).isEqualTo(100);
    }

    // ================================================================== Integrity fix 2026-11-24 (MP-6, MP-7)

    /** The minute-by-minute walk the calendar used before MP-6, kept as the reference the jumps must match. */
    private static final class MinuteWalk {
        private static final long LIMIT = 2L * 366 * 24 * 60;
        private final PlanningCalendar cal;
        private final List<Instant[]> busy;
        private final int slots;

        MinuteWalk(PlanningCalendar cal, List<Instant[]> busy, int slots) {
            this.cal = cal;
            this.busy = busy;
            this.slots = Math.max(1, slots);
        }

        boolean usable(Instant t) {
            if (!cal.working(t)) {
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

        Slot forward(Instant from, long minutes) {
            Instant t = from.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            Instant start = null;
            Instant last = null;
            long remaining = minutes;
            for (long step = 0; remaining > 0; step++) {
                if (step > LIMIT) {
                    return null;
                }
                if (usable(t)) {
                    start = start == null ? t : start;
                    last = t;
                    remaining--;
                }
                t = t.plusSeconds(60);
            }
            return new Slot(start, last.plusSeconds(60));
        }

        Slot backward(Instant end, long minutes) {
            Instant t = end.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            Instant start = null;
            Instant stop = null;
            long remaining = minutes;
            for (long step = 0; remaining > 0; step++) {
                if (step > LIMIT) {
                    return null;
                }
                Instant minute = t.minusSeconds(60);
                if (usable(minute)) {
                    stop = stop == null ? t : stop;
                    start = minute;
                    remaining--;
                }
                t = minute;
            }
            return new Slot(start, stop);
        }
    }

    @Test
    @DisplayName("MP-6: jumping between change points places exactly what the minute walk placed")
    void jumpsMatchMinuteWalk() {
        java.util.Random rnd = new java.util.Random(20261124L);
        List<java.time.ZoneId> zones = List.of(java.time.ZoneOffset.UTC, java.time.ZoneId.of("Africa/Kampala"),
                java.time.ZoneId.of("Europe/London"), java.time.ZoneId.of("Asia/Kolkata"));
        Instant base = Instant.parse("2026-10-20T00:00:00Z"); // spans the London DST change on 25 October
        int compared = 0;
        for (int c = 0; c < 60; c++) {
            Map<Integer, List<int[]>> shifts = new java.util.HashMap<>();
            if (c % 5 != 0) {
                for (int d = 0; d < 7; d++) {
                    if (rnd.nextInt(7) < 5) {
                        List<int[]> w = new ArrayList<>();
                        int a = rnd.nextInt(600);
                        w.add(new int[] {a, a + 30 + rnd.nextInt(500)});
                        if (rnd.nextBoolean()) {
                            int b = 1100 + rnd.nextInt(200);
                            w.add(new int[] {b, b + 10 + rnd.nextInt(140)});
                        }
                        shifts.put(d, w);
                    }
                }
            }
            Set<LocalDate> closed = new java.util.HashSet<>();
            for (int i = 0; i < rnd.nextInt(4); i++) {
                closed.add(LocalDate.parse("2026-10-20").plusDays(rnd.nextInt(20)));
            }
            List<Instant[]> busy = new ArrayList<>();
            for (int i = 0; i < rnd.nextInt(12); i++) {
                // Odd seconds on purpose: load from other schedulers is not always minute-aligned.
                Instant s = base.plusSeconds(rnd.nextInt(20 * 24 * 3600));
                busy.add(new Instant[] {s, s.plusSeconds(60 + rnd.nextInt(2 * 24 * 3600))});
            }
            int slots = 1 + rnd.nextInt(2);
            java.time.ZoneId zone = zones.get(c % zones.size());
            PlanningCalendar cal = new PlanningCalendar(shifts, closed, busy, slots, zone);
            MinuteWalk ref = new MinuteWalk(cal, busy, slots);
            for (int k = 0; k < 4; k++) {
                Instant at = base.plusSeconds(rnd.nextInt(18 * 24 * 3600));
                long minutes = 1 + rnd.nextInt(1500);
                assertThat(cal.forward(at, minutes)).as("forward %s %s %s", zone, at, minutes)
                        .isEqualTo(ref.forward(at, minutes));
                assertThat(cal.backward(at, minutes)).as("backward %s %s %s", zone, at, minutes)
                        .isEqualTo(ref.backward(at, minutes));
                compared++;
            }
        }
        assertThat(compared).isEqualTo(240);
    }

    @Test
    @DisplayName("MP-7: windows and closures are read in the organization's zone, not UTC")
    void windowsInOrgZone() {
        java.time.ZoneId kampala = java.time.ZoneId.of("Africa/Kampala");
        PlanningCalendar c = new PlanningCalendar(WEEK, Set.of(LocalDate.parse("2026-11-03")), List.of(), 1, kampala);
        // Monday 08:00 in Kampala is 05:00 UTC.
        Slot s = c.forward(Instant.parse("2026-11-02T00:00:00Z"), 60);
        assertThat(s.start()).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));
        assertThat(s.end()).isEqualTo(Instant.parse("2026-11-02T06:00:00Z"));
        // Closed Tuesday (local): Monday's last hour, then Wednesday 08:00 local.
        Slot over = c.forward(Instant.parse("2026-11-02T13:00:00Z"), 120);
        assertThat(over.start()).isEqualTo(Instant.parse("2026-11-02T13:00:00Z"));
        assertThat(over.end()).isEqualTo(Instant.parse("2026-11-04T06:00:00Z"));
        assertThat(c.workingMinutesBetween(Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z")))
                .isEqualTo(540);
    }

    @Test
    @DisplayName("MP-6: a centre loaded for years is searched quickly")
    void heavyLoadIsFast() {
        List<Instant[]> busy = new ArrayList<>();
        Instant t = Instant.parse("2026-11-02T08:00:00Z");
        for (int i = 0; i < 5000; i++) {
            busy.add(new Instant[] {t, t.plusSeconds(3600)});
            t = t.plusSeconds(3600);
        }
        long started = System.nanoTime();
        Slot s = calendar(Set.of(), busy, 1).forward(Instant.parse("2026-11-02T08:00:00Z"), 600);
        assertThat(s).isNotNull();
        assertThat(s.start()).isAfterOrEqualTo(t);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2000);
    }
}
