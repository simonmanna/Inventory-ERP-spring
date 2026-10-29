package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.fleet.application.AllowanceCalculator.Rule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fleet 1.2 — allowance rule selection (effective dates, class / zone specificity) and amounts. */
class AllowanceCalculatorTest {

    static final LocalDate D = LocalDate.of(2026, 10, 15);

    static Rule rule(String code, String basis, String amount, LocalDate from, LocalDate to, String cat, String zone) {
        return new Rule(code, code, basis, new BigDecimal(amount), from, to, cat, zone, true);
    }

    final Rule general = rule("DAY", "per_day", "20000", LocalDate.of(2026, 1, 1), null, null, null);
    final Rule old = rule("DAY-2025", "per_day", "15000", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), null,
            null);
    final Rule tipper = rule("DAY-TIP", "per_day", "25000", LocalDate.of(2026, 1, 1), null, "tipper", null);
    final Rule north = rule("DAY-N", "per_day", "22000", LocalDate.of(2026, 1, 1), null, null, "North");
    final Rule tipperNorth = rule("DAY-TN", "per_day", "30000", LocalDate.of(2026, 1, 1), null, "tipper", "north");
    final Rule night = rule("NIGHT", "per_night", "35000", LocalDate.of(2026, 1, 1), null, null, null);
    final List<Rule> all = List.of(general, old, tipper, north, tipperNorth, night);

    @Test
    @DisplayName("the most specific applicable rule wins: class + zone > class > zone > general")
    void specificity() {
        assertThat(AllowanceCalculator.select(all, "per_day", D, "tipper", " NORTH ").code()).isEqualTo("DAY-TN");
        assertThat(AllowanceCalculator.select(all, "per_day", D, "tipper", null).code()).isEqualTo("DAY-TIP");
        assertThat(AllowanceCalculator.select(all, "per_day", D, "excavator", "north").code()).isEqualTo("DAY-N");
        assertThat(AllowanceCalculator.select(all, "per_day", D, null, "south").code()).isEqualTo("DAY");
        assertThat(AllowanceCalculator.select(all, "per_night", D, "tipper", "north").code()).isEqualTo("NIGHT");
    }

    @Test
    @DisplayName("effective dates are inclusive; inactive and other-basis rules never apply")
    void effectiveDates() {
        assertThat(AllowanceCalculator.select(all, "per_day", LocalDate.of(2025, 12, 31), null, null).code())
                .isEqualTo("DAY-2025");
        assertThat(AllowanceCalculator.select(all, "per_day", LocalDate.of(2024, 6, 1), null, null)).isNull();
        assertThat(AllowanceCalculator.select(all, "per_trip", D, null, null)).isNull();
        Rule off = new Rule("x", "OFF", "per_trip", BigDecimal.TEN, LocalDate.of(2026, 1, 1), null, null, null, false);
        assertThat(AllowanceCalculator.select(List.of(off), "per_trip", D, null, null)).isNull();
        Rule newer = rule("DAY-OCT", "per_day", "21000", LocalDate.of(2026, 10, 1), null, null, null);
        assertThat(AllowanceCalculator.select(List.of(general, newer), "per_day", D, null, null).code())
                .isEqualTo("DAY-OCT");
    }

    @Test
    @DisplayName("amount = rate × quantity, HALF_UP to 2 decimals; total sums lines")
    void amounts() {
        assertThat(AllowanceCalculator.amount(new BigDecimal("20000"), new BigDecimal("2.5")))
                .isEqualByComparingTo("50000.00");
        assertThat(AllowanceCalculator.amount(new BigDecimal("33.335"), BigDecimal.ONE)).isEqualByComparingTo("33.34");
        assertThat(AllowanceCalculator.amount(new BigDecimal("10.005"), new BigDecimal("3")))
                .isEqualByComparingTo("30.02");
        assertThat(AllowanceCalculator.total(List.of(new BigDecimal("1.10"), new BigDecimal("2.205"))))
                .isEqualByComparingTo("3.31");
    }

    @Test
    @DisplayName("overlap: same basis + scope + intersecting periods; different zone / class or disjoint periods do not")
    void overlaps() {
        Rule later = rule("DAY-27", "per_day", "26000", LocalDate.of(2027, 1, 1), null, null, null);
        assertThat(AllowanceCalculator.overlaps(general, later)).isTrue();
        assertThat(AllowanceCalculator.overlaps(old, general)).isFalse();
        assertThat(AllowanceCalculator.overlaps(general, tipper)).isFalse();
        assertThat(AllowanceCalculator.overlaps(north, rule("N2", "per_day", "1", D, D, null, "north "))).isTrue();
        assertThat(AllowanceCalculator.overlaps(general, night)).isFalse();
    }
}
