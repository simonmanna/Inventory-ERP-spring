package com.inventoryerp.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.FiscalPeriodCoverageService.Last;
import com.inventoryerp.accounting.application.FiscalPeriodCoverageService.Plan;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Finance readiness audit B1: which fiscal year the coverage job creates after the last period. */
class FiscalPeriodCoveragePlanTest {

    private static LocalDate d(String day) {
        return LocalDate.parse(day);
    }

    @Test
    @DisplayName("after a period of a fiscal year: a year from the next day, with that year's periodicity")
    void followsTheLastFiscalYear() {
        assertThat(FiscalPeriodCoverageService.plan(new Last(d("2026-10-01"), d("2026-12-31"), "fy", "quarterly"), 1))
                .isEqualTo(new Plan(d("2027-01-01"), d("2027-12-31"), "quarterly"));
        assertThat(FiscalPeriodCoverageService.plan(new Last(d("2026-06-01"), d("2026-06-30"), "fy", "monthly"), 1))
                .isEqualTo(new Plan(d("2026-07-01"), d("2027-06-30"), "monthly"));
        assertThat(FiscalPeriodCoverageService.plan(new Last(d("2026-12-01"), d("2026-12-31"), "fy", null), 1))
                .isEqualTo(new Plan(d("2027-01-01"), d("2027-12-31"), "monthly"));
    }

    @Test
    @DisplayName("after a period of no fiscal year: the fiscal year holding the next day, periodicity from the length")
    void withoutAFiscalYear() {
        // the onboarding period: one calendar year
        assertThat(FiscalPeriodCoverageService.plan(new Last(d("2026-01-01"), d("2026-12-31"), null, null), 1))
                .isEqualTo(new Plan(d("2027-01-01"), d("2027-12-31"), "yearly"));
        // a July–June business whose monthly periods stop in September: the year that holds October
        assertThat(FiscalPeriodCoverageService.plan(new Last(d("2026-09-01"), d("2026-09-30"), null, null), 7))
                .isEqualTo(new Plan(d("2026-07-01"), d("2027-06-30"), "monthly"));
        // a quarter ending the July–June year
        assertThat(FiscalPeriodCoverageService.plan(new Last(d("2027-04-01"), d("2027-06-30"), null, null), 7))
                .isEqualTo(new Plan(d("2027-07-01"), d("2028-06-30"), "quarterly"));
    }

    @Test
    @DisplayName("periodicity from a period's length")
    void periodicityOfLength() {
        assertThat(FiscalPeriodCoverageService.periodicityOf(d("2026-02-01"), d("2026-02-28"))).isEqualTo("monthly");
        assertThat(FiscalPeriodCoverageService.periodicityOf(d("2026-01-01"), d("2026-01-31"))).isEqualTo("monthly");
        assertThat(FiscalPeriodCoverageService.periodicityOf(d("2026-07-01"), d("2026-09-30"))).isEqualTo("quarterly");
        assertThat(FiscalPeriodCoverageService.periodicityOf(d("2026-01-01"), d("2026-06-30"))).isEqualTo("yearly");
    }
}
