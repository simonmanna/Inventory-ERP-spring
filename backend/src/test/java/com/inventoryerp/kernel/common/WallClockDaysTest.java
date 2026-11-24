package com.inventoryerp.kernel.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WallClockDaysTest {

    @Test
    void boundsCoverTheWholeDay() {
        LocalDate d = LocalDate.of(2026, 12, 31);
        assertThat(WallClockDays.startTs(d)).hasToString("2026-12-31 00:00:00.0");
        assertThat(WallClockDays.endTs(d)).hasToString("2026-12-31 23:59:59.999");
        assertThat(WallClockDays.endInstant(d)).isEqualTo(Instant.parse("2026-12-31T23:59:59.999Z"));
    }

    @Test
    void recognisesDateOnlyValues() {
        assertThat(WallClockDays.isDateOnly("2026-01-31")).isTrue();
        assertThat(WallClockDays.isDateOnly(" 2026-01-31 ")).isTrue();
        assertThat(WallClockDays.isDateOnly("2026-01-31T00:00:00Z")).isFalse();
        assertThat(WallClockDays.isDateOnly("31/01/2026")).isFalse();
        assertThat(WallClockDays.isDateOnly(null)).isFalse();
    }
}
