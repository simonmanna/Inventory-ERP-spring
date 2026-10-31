package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.hr.application.HrOvertimeCalculator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Overtime maths (HR-3): rule choice and weighted hours per kind of day. */
class HrOvertimeCalculatorTest {

    Map<String, Object> rule(String appliesTo) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("appliesTo", appliesTo);
        r.put("weekdayMultiplier", new BigDecimal("1.5"));
        r.put("weekendMultiplier", new BigDecimal("1.75"));
        r.put("holidayMultiplier", new BigDecimal("2"));
        r.put("weekendDays", "SUNDAY");
        return r;
    }

    @Test
    @DisplayName("a targeted rule beats the general one; no rule at all means null")
    void pick() {
        Map<String, Object> general = rule(null);
        Map<String, Object> quarry = rule("departmentId:D1");
        Map<String, Object> casual = rule("employmentType:CASUAL");
        List<Map<String, Object>> rules = List.of(general, quarry, casual);
        assertThat(HrOvertimeCalculator.pick(rules, Map.of("departmentId", "D1"))).isSameAs(quarry);
        assertThat(HrOvertimeCalculator.pick(rules, Map.of("departmentId", "D2", "employmentType", "CASUAL")))
                .isSameAs(casual);
        assertThat(HrOvertimeCalculator.pick(rules, Map.of("departmentId", "D2"))).isSameAs(general);
        assertThat(HrOvertimeCalculator.pick(List.of(quarry), Map.of("departmentId", "D2"))).isNull();
        assertThat(HrOvertimeCalculator.pick(List.of(), Map.of())).isNull();
    }

    @Test
    @DisplayName("a weekday, a Sunday and a holiday on a Sunday each take their own multiplier")
    void weighted() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        LocalDate sunday = LocalDate.of(2026, 11, 8);
        LocalDate holiday = LocalDate.of(2026, 11, 15);
        Map<LocalDate, BigDecimal> minutes = new LinkedHashMap<>();
        minutes.put(monday, new BigDecimal(60));
        minutes.put(sunday, new BigDecimal(120));
        minutes.put(holiday, new BigDecimal(30));
        BigDecimal hours = HrOvertimeCalculator.weightedHours(minutes, rule(null), holiday::equals);
        // 1 h × 1.5 + 2 h × 1.75 + 0.5 h × 2
        assertThat(hours).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("no overtime is zero hours")
    void none() {
        assertThat(HrOvertimeCalculator.weightedHours(Map.of(), rule(null), d -> false)).isEqualByComparingTo("0");
    }
}
