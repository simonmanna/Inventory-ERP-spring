package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.ShiftClock.ShiftDef;
import com.inventoryerp.manufacturing.application.ShiftClock.Stop;
import com.inventoryerp.manufacturing.application.ShiftClock.Window;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Shift windows, resolution, overlap and stop minutes (ADR-S015). */
class ShiftClockTest {

    static final ShiftDef DAY = new ShiftDef("d", "DAY", 360, 840, 30);
    static final ShiftDef EVENING = new ShiftDef("e", "EVENING", 840, 1320, 30);
    static final ShiftDef NIGHT = new ShiftDef("n", "NIGHT", 1320, 360, 30);
    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    @Test
    @DisplayName("windows in the organization's zone; a night shift belongs to the day it starts")
    void windows() {
        LocalDate d = LocalDate.of(2026, 10, 30);
        Window day = ShiftClock.window(DAY, d, KAMPALA);
        assertThat(day.startsAt()).isEqualTo(Instant.parse("2026-10-30T03:00:00Z"));
        assertThat(day.endsAt()).isEqualTo(Instant.parse("2026-10-30T11:00:00Z"));
        Window night = ShiftClock.window(NIGHT, d, KAMPALA);
        assertThat(night.startsAt()).isEqualTo(Instant.parse("2026-10-30T19:00:00Z"));
        assertThat(night.endsAt()).isEqualTo(Instant.parse("2026-10-31T03:00:00Z"));
        assertThat(ShiftClock.lengthMinutes(NIGHT)).isEqualTo(480);
        assertThat(ShiftClock.lengthMinutes(new ShiftDef("a", "ALL", 0, 1440, 0))).isEqualTo(1440);
        assertThat(ShiftClock.window(new ShiftDef("a", "ALL", 0, 1440, 0), d, ZoneOffset.UTC).endsAt())
                .isEqualTo(Instant.parse("2026-10-31T00:00:00Z"));
    }

    @Test
    @DisplayName("resolve: 02:00 local belongs to the previous day's night shift; gaps resolve to nothing")
    void resolve() {
        List<ShiftDef> all = List.of(DAY, EVENING, NIGHT);
        Window w = ShiftClock.resolve(all, Instant.parse("2026-10-30T23:00:00Z"), KAMPALA).orElseThrow(); // 02:00
        assertThat(w.shiftId()).isEqualTo("n");
        assertThat(w.shiftDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        Window early = ShiftClock.resolve(all, Instant.parse("2026-10-31T01:30:00Z"), KAMPALA).orElseThrow(); // 04:30
        assertThat(early.shiftDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        Window eve = ShiftClock.resolve(all, Instant.parse("2026-10-30T11:00:00Z"), KAMPALA).orElseThrow(); // 14:00
        assertThat(eve.shiftId()).isEqualTo("e");
        assertThat(ShiftClock.resolve(List.of(DAY), Instant.parse("2026-10-30T12:00:00Z"), KAMPALA)).isEmpty();
    }

    @Test
    @DisplayName("overlap: the standard pattern is clean; a 05:00-13:00 shift overlaps DAY and NIGHT")
    void overlap() {
        assertThat(ShiftClock.overlap(List.of(DAY, EVENING, NIGHT))).isNull();
        assertThat(ShiftClock.overlap(List.of(DAY, new ShiftDef("x", "EARLY", 300, 780, 0)))).isEqualTo("DAY/EARLY");
        assertThat(ShiftClock.overlap(List.of(NIGHT, new ShiftDef("x", "DAWN", 0, 60, 0)))).isEqualTo("NIGHT/DAWN");
    }

    @Test
    @DisplayName("stop minutes: clipped to the window, overlaps counted once, planned wins")
    void stops() {
        Instant ws = Instant.parse("2026-10-30T06:00:00Z");
        Instant we = Instant.parse("2026-10-30T14:00:00Z");
        long[] m = ShiftClock.stopMinutes(List.of(
                new Stop(Instant.parse("2026-10-30T05:30:00Z"), Instant.parse("2026-10-30T06:20:00Z"), false), // 20
                new Stop(Instant.parse("2026-10-30T06:10:00Z"), Instant.parse("2026-10-30T06:30:00Z"), false), // +10
                new Stop(Instant.parse("2026-10-30T10:00:00Z"), Instant.parse("2026-10-30T10:30:00Z"), true), // 30 p
                new Stop(Instant.parse("2026-10-30T10:20:00Z"), Instant.parse("2026-10-30T10:40:00Z"), false), // +10
                new Stop(Instant.parse("2026-10-30T13:50:00Z"), Instant.parse("2026-10-30T15:00:00Z"), false)), // 10
                ws, we);
        assertThat(m[0]).isEqualTo(30);
        assertThat(m[1]).isEqualTo(50);
        assertThat(ShiftClock.clippedMinutes(Instant.parse("2026-10-30T05:00:00Z"), Instant.parse("2026-10-30T07:00:00Z"),
                ws, we)).isEqualTo(60);
        assertThat(ShiftClock.clippedMinutes(Instant.parse("2026-10-30T15:00:00Z"), Instant.parse("2026-10-30T16:00:00Z"),
                ws, we)).isZero();
        assertThat(ShiftClock.stopMinutes(List.of(), we, ws)[1]).isZero();
    }
}
