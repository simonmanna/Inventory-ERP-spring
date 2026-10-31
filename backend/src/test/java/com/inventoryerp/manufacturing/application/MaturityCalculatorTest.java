package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Nurse-Saul maturity and the minimum-curing-days hold (ADR-S014). */
class MaturityCalculatorTest {

    static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    static MaturityCalculator.Reading r(long hours, int temp) {
        return new MaturityCalculator.Reading(T0.plus(Duration.ofHours(hours)), BigDecimal.valueOf(temp));
    }

    @Test
    void nurseSaul() {
        // 0–24 h at 20 °C (first reading's temperature): 30 × 24 = 720; 24–48 h mean 25 °C: 35 × 24 = 840.
        assertThat(MaturityCalculator.maturity(T0, null, List.of(r(24, 20), r(48, 30))))
                .isEqualByComparingTo("1560");
        // Order does not matter; readings before the start are ignored.
        assertThat(MaturityCalculator.maturity(T0, null, List.of(r(48, 30), r(-5, 90), r(24, 20))))
                .isEqualByComparingTo("1560");
        // Below the datum contributes nothing; custom datum 0 °C.
        assertThat(MaturityCalculator.maturity(T0, BigDecimal.valueOf(-10), List.of(r(10, -20)))).isZero();
        assertThat(MaturityCalculator.maturity(T0, BigDecimal.ZERO, List.of(r(10, 15)))).isEqualByComparingTo("150");
        assertThat(MaturityCalculator.maturity(T0, null, List.of())).isZero();
    }

    @Test
    void curingHold() {
        assertThat(CuringHold.dueAt(T0, 7)).isEqualTo(T0.plus(Duration.ofDays(7)));
        assertThat(CuringHold.dueAt(T0, 0)).isNull();
        assertThat(CuringHold.dueAt(null, 7)).isNull();
        assertThat(CuringHold.passDueAt(Map.of("minCuringDays", 3), T0)).isEqualTo(T0.plus(Duration.ofDays(3)));
        assertThat(CuringHold.passDueAt(Map.of(), T0)).isNull();
        assertThat(CuringHold.daysCured(T0, T0.plus(Duration.ofHours(50)))).isEqualTo(2);
        assertThat(CuringHold.daysCured(T0, T0.minus(Duration.ofHours(1)))).isZero();
    }
}
