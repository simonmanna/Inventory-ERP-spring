package com.inventoryerp.rental.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pure rental arithmetic, with the JavaScript semantics of the Nest services. */
class RentalMathTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Map<String, Object> band(String id, int min, Integer max) {
        Map<String, Object> b = new HashMap<>();
        b.put("id", id);
        b.put("minUnits", min);
        b.put("maxUnits", max);
        return b;
    }

    @Test
    @DisplayName("rate bands: first band (priority order) containing the unit count, open-ended max, no match")
    void pickBand() {
        // Already ordered like the query: priority ASC, minUnits DESC.
        List<Map<String, Object>> bands = List.of(band("weekly", 7, null), band("three-plus", 3, 6),
                band("single", 1, 2));
        assertThat(RentalMath.pickBand(bands, 1).get("id")).isEqualTo("single");
        assertThat(RentalMath.pickBand(bands, 2).get("id")).isEqualTo("single");
        assertThat(RentalMath.pickBand(bands, 3).get("id")).isEqualTo("three-plus");
        assertThat(RentalMath.pickBand(bands, 6).get("id")).isEqualTo("three-plus");
        assertThat(RentalMath.pickBand(bands, 40).get("id")).isEqualTo("weekly");
        assertThat(RentalMath.pickBand(bands, 0)).isNull();
        assertThat(RentalMath.pickBand(bands, 2.5)).isNull();
        assertThat(RentalMath.pickBand(List.of(), 1)).isNull();
    }

    @Test
    @DisplayName("calcUnits: ceil per period, at least one unit, month = 30 days, unknown period = day")
    void calcUnits() {
        assertThat(RentalMath.calcUnits(T0, T0.plus(Duration.ofHours(25)), "day")).isEqualTo(2);
        assertThat(RentalMath.calcUnits(T0, T0.plus(Duration.ofDays(2)), "day")).isEqualTo(2);
        assertThat(RentalMath.calcUnits(T0, T0.plusMillis(1), "day")).isEqualTo(1);
        assertThat(RentalMath.calcUnits(T0, T0, "day")).isEqualTo(1);
        assertThat(RentalMath.calcUnits(T0, T0.plus(Duration.ofMinutes(61)), "hour")).isEqualTo(2);
        assertThat(RentalMath.calcUnits(T0, T0.plus(Duration.ofDays(8)), "week")).isEqualTo(2);
        assertThat(RentalMath.calcUnits(T0, T0.plus(Duration.ofDays(30)), "month")).isEqualTo(1);
        assertThat(RentalMath.calcUnits(T0, T0.plus(Duration.ofDays(31)), "month")).isEqualTo(2);
        assertThat(RentalMath.calcUnits(T0, T0.plus(Duration.ofDays(3)), "fortnight")).isEqualTo(3);
    }

    @Test
    @DisplayName("late days: started days past due, never negative; late fee = days × fee × quantity")
    void lateFees() {
        assertThat(RentalMath.lateDays(T0, T0.plus(Duration.ofDays(1)))).isZero();
        assertThat(RentalMath.lateDays(T0, T0)).isZero();
        assertThat(RentalMath.lateDays(T0.plusSeconds(1), T0)).isEqualTo(1);
        assertThat(RentalMath.lateDays(T0.plus(Duration.ofHours(49)), T0)).isEqualTo(3);
        assertThat(RentalMath.lateFee(3, 50, 2)).isEqualTo(300);
        assertThat(RentalMath.lateFee(0, 50, 2)).isZero();
        assertThat(RentalMath.lateFee(4, 0, 1)).isZero();
    }

    @Test
    @DisplayName("deposit requirement: fixed amount, percent of line totals rounded up, 10 % default")
    void computeDeposit() {
        List<Map<String, Object>> lines = List.of(Map.of("lineTotal", "150"), Map.of("lineTotal", "55.5"));
        assertThat(RentalMath.computeDeposit(lines, null)).isEqualTo(21);
        assertThat(RentalMath.computeDeposit(lines, Map.of("mode", "percent", "percent", 20))).isEqualTo(42);
        assertThat(RentalMath.computeDeposit(lines, Map.of("mode", "fixed", "fixedAmount", 75))).isEqualTo(75);
        // fixed mode without a (truthy) fixed amount falls back to the percentage
        assertThat(RentalMath.computeDeposit(lines, Map.of("mode", "fixed", "fixedAmount", 0))).isEqualTo(21);
        Map<String, Object> noPercent = new LinkedHashMap<>();
        noPercent.put("mode", "replacement");
        assertThat(RentalMath.computeDeposit(lines, noPercent)).isEqualTo(21);
        assertThat(RentalMath.computeDeposit(List.of(), null)).isZero();
    }

    @Test
    @DisplayName("deposit status: held, partially applied, refunded / forfeited only when exclusive, else applied")
    void depositStatus() {
        assertThat(RentalMath.depositStatus(0, 0, 0, 0)).isEqualTo("held");
        assertThat(RentalMath.depositStatus(100, 0, 0, 0)).isEqualTo("partially_applied");
        assertThat(RentalMath.depositStatus(100, 30, 0, 0)).isEqualTo("partially_applied");
        assertThat(RentalMath.depositStatus(100, 0, 100, 0)).isEqualTo("refunded");
        assertThat(RentalMath.depositStatus(100, 0, 0, 100)).isEqualTo("forfeited");
        assertThat(RentalMath.depositStatus(100, 40, 60, 0)).isEqualTo("applied");
        assertThat(RentalMath.depositStatus(100, 0, 50, 50)).isEqualTo("applied");
        assertThat(RentalMath.depositStatus(100, 100, 0, 0)).isEqualTo("applied");
    }

    @Test
    @DisplayName("score: capped penalties, lost items uncapped, unpaid balance per started 100k, bands")
    void score() {
        assertThat(RentalMath.score(0, 0, 0, 0, 0)).isEqualTo(100);
        assertThat(RentalMath.score(1, 0, 0, 0, 0)).isEqualTo(95);
        assertThat(RentalMath.score(10, 0, 0, 0, 0)).isEqualTo(70);
        assertThat(RentalMath.score(0, 1, 0, 0, 0)).isEqualTo(85);
        assertThat(RentalMath.score(0, 5, 0, 0, 0)).isEqualTo(70);
        assertThat(RentalMath.score(0, 0, 2, 0, 0)).isEqualTo(60);
        assertThat(RentalMath.score(0, 0, 0, 9, 0)).isEqualTo(90);
        assertThat(RentalMath.score(0, 0, 0, 0, 1)).isEqualTo(98);
        assertThat(RentalMath.score(0, 0, 0, 0, 250_000)).isEqualTo(94);
        assertThat(RentalMath.score(0, 0, 0, 0, 10_000_000)).isEqualTo(90);
        assertThat(RentalMath.score(10, 5, 6, 9, 1_000_000)).isZero();
        assertThat(RentalMath.band(100)).isEqualTo("good");
        assertThat(RentalMath.band(80)).isEqualTo("good");
        assertThat(RentalMath.band(79)).isEqualTo("caution");
        assertThat(RentalMath.band(50)).isEqualTo("caution");
        assertThat(RentalMath.band(49)).isEqualTo("blocked");
    }
}
