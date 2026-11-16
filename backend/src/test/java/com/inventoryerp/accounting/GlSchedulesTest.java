package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.BankMatchingServiceAccess;
import com.inventoryerp.accounting.application.GlSchedules;
import com.inventoryerp.accounting.application.GlSchedules.Slice;
import com.inventoryerp.fixedasset.application.DepreciationCalculator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-S073: pure schedule arithmetic (recurrence, deferral plans, inclusive tax, rule matching, impaired depreciation). */
class GlSchedulesTest {

    @Test
    void monthlyOccurrencesKeepTheAnchorDay() {
        LocalDate start = LocalDate.of(2026, 1, 31);
        assertThat(GlSchedules.occurrence(start, "monthly", 1, 1)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(GlSchedules.occurrence(start, "monthly", 1, 2)).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(GlSchedules.nextAfter(start, "monthly", 1, LocalDate.of(2026, 2, 28)))
                .isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(GlSchedules.nextAfter(start, "quarterly", 1, start)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(GlSchedules.nextAfter(start, "weekly", 2, start)).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(GlSchedules.nextAfter(start, "yearly", 1, LocalDate.of(2027, 6, 1)))
                .isEqualTo(LocalDate.of(2028, 1, 31));
        assertThat(GlSchedules.nextAfter(start, "daily", 1, LocalDate.of(2025, 12, 1))).isEqualTo(start);
        assertThat(GlSchedules.nextAfter(LocalDate.of(2020, 1, 1), "daily", 1, LocalDate.of(2026, 11, 10)))
                .isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void monthlyPlanSumsExactlyWithTheRemainderLast() {
        List<Slice> plan = GlSchedules.deferralPlan(new BigDecimal("1000"), LocalDate.of(2026, 1, 15), 3, "monthly");
        assertThat(plan).hasSize(3);
        assertThat(plan.get(0).amount()).isEqualByComparingTo("333.33");
        assertThat(plan.get(2).amount()).isEqualByComparingTo("333.34");
        assertThat(plan.get(0).periodEnd()).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(plan.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1000");
    }

    @Test
    void dailyPlanWeighsCalendarMonthsByDays() {
        // 2026-01-16 .. 2026-03-15 = 59 days: January 16 days, February 28, March 15.
        List<Slice> plan = GlSchedules.deferralPlan(new BigDecimal("590"), LocalDate.of(2026, 1, 16), 2, "daily");
        assertThat(plan).extracting(Slice::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("160"), new BigDecimal("280"), new BigDecimal("150"));
        assertThat(plan.get(1).periodStart()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(plan.get(2).periodEnd()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void inclusiveTaxSplit() {
        BigDecimal[] split = GlSchedules.splitInclusive(new BigDecimal("118"), new BigDecimal("18"));
        assertThat(split[0]).isEqualByComparingTo("100");
        assertThat(split[1]).isEqualByComparingTo("18");
        assertThat(GlSchedules.splitInclusive(new BigDecimal("50"), null)[1]).isEqualByComparingTo("0");
    }

    @Test
    void ruleConditions() {
        Map<String, Object> rule = new java.util.HashMap<>(Map.of("direction", "outbound", "matchField", "any",
                "matchType", "contains", "pattern", "charge"));
        rule.put("minAmount", new BigDecimal("1"));
        rule.put("maxAmount", new BigDecimal("100"));
        Map<String, Object> line = new java.util.HashMap<>(Map.of("description", "Monthly CHARGE", "amount",
                new BigDecimal("-25")));
        assertThat(BankMatchingServiceAccess.matches(rule, line)).isTrue();
        line.put("amount", new BigDecimal("-250"));
        assertThat(BankMatchingServiceAccess.matches(rule, line)).as("above max").isFalse();
        line.put("amount", new BigDecimal("25"));
        assertThat(BankMatchingServiceAccess.matches(rule, line)).as("wrong direction").isFalse();
        rule.put("direction", "any");
        rule.put("matchType", "regex");
        rule.put("pattern", "^monthly\\s+ch");
        assertThat(BankMatchingServiceAccess.matches(rule, line)).isTrue();
        rule.put("matchField", "reference");
        assertThat(BankMatchingServiceAccess.matches(rule, line)).as("no reference").isFalse();
    }

    @Test
    void impairedStraightLineSpreadsTheNewCarryingAmountOverTheRemainingLife() {
        var r = DepreciationCalculator.calculateImpaired("straight_line", BigDecimal.ZERO, 12, new BigDecimal("600"),
                new BigDecimal("200"), new BigDecimal("600"), 10);
        assertThat(r.depreciationAmount()).isEqualByComparingTo("60");
        assertThat(r.bookValue()).isEqualByComparingTo("540");
        assertThat(r.accumulatedDepreciation()).isEqualByComparingTo("260");
        var floor = DepreciationCalculator.calculateImpaired("straight_line", new BigDecimal("50"), 12,
                new BigDecimal("70"), BigDecimal.ZERO, new BigDecimal("600"), 2);
        assertThat(floor.depreciationAmount()).as("never below residual").isEqualByComparingTo("20");
    }
}
