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
}
