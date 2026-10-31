package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.CapacityCalculator.AggregatePlan;
import com.inventoryerp.manufacturing.application.CapacityCalculator.Fraction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Load, utilisation and the aggregate crushing plan (ADR-S017). */
class CapacityCalculatorTest {

    @Test
    @DisplayName("overlap and utilisation")
    void overlap() {
        Instant a = Instant.parse("2026-11-02T08:00:00Z");
        Instant b = Instant.parse("2026-11-02T12:00:00Z");
        assertThat(CapacityCalculator.overlapMinutes(a, b, a.plusSeconds(3600), b.plusSeconds(3600))).isEqualTo(180);
        assertThat(CapacityCalculator.overlapMinutes(a, a.plusSeconds(60), b, b.plusSeconds(60))).isZero();
        assertThat(CapacityCalculator.utilisationPct(90, 60)).isEqualByComparingTo("150.00");
        assertThat(CapacityCalculator.utilisationPct(90, 0)).isNull();
        assertThat(CapacityCalculator.quantity(90, BigDecimal.valueOf(100))).isEqualByComparingTo("150.000");
        assertThat(CapacityCalculator.quantity(90, null)).isNull();
    }

    @Test
    @DisplayName("the tightest fraction sets the feed; hours follow from the bottleneck")
    void aggregate() {
        AggregatePlan plan = CapacityCalculator.aggregate(List.of(
                new Fraction("dust", new BigDecimal("20"), new BigDecimal("100")),
                new Fraction("ten", new BigDecimal("30"), new BigDecimal("60")),
                new Fraction("none", new BigDecimal("50"), BigDecimal.ZERO)), new BigDecimal("100"));
        assertThat(plan.feedTonnes()).isEqualByComparingTo("500");
        assertThat(plan.drivingProductId()).isEqualTo("dust");
        assertThat(plan.hours()).isEqualByComparingTo("5.00");
        assertThat(CapacityCalculator.expectedOutput(plan.feedTonnes(), new BigDecimal("30")))
                .isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("no demand means no feed; no bottleneck rate means no hours")
    void empty() {
        AggregatePlan plan = CapacityCalculator.aggregate(List.of(new Fraction("a", BigDecimal.TEN, BigDecimal.ZERO)),
                null);
        assertThat(plan.feedTonnes()).isEqualByComparingTo("0");
        assertThat(plan.drivingProductId()).isNull();
        assertThat(plan.hours()).isNull();
    }
}
