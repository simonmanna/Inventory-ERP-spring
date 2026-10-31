package com.inventoryerp.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FiscalYearPeriodsTest {

    @Test
    void monthlyQuarterlyYearlySlotsCoverTheYearWithoutGaps() {
        LocalDate start = LocalDate.of(2026, 7, 1);
        LocalDate end = LocalDate.of(2027, 6, 30);
        List<LocalDate[]> months = FiscalYearService.slots(start, end, "monthly");
        assertThat(months).hasSize(12);
        assertThat(months.get(0)).containsExactly(start, LocalDate.of(2026, 7, 31));
        assertThat(months.get(7)).containsExactly(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28));
        assertThat(months.get(11)[1]).isEqualTo(end);
        for (int i = 1; i < months.size(); i++) {
            assertThat(months.get(i)[0]).isEqualTo(months.get(i - 1)[1].plusDays(1));
        }
        assertThat(FiscalYearService.slots(start, end, "quarterly")).hasSize(4);
        assertThat(FiscalYearService.slots(start, end, "yearly")).hasSize(1);
        // A short last period is cut at the year end.
        assertThat(FiscalYearService.slots(start, LocalDate.of(2026, 11, 15), "quarterly").get(1)[1])
                .isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void periodNames() {
        assertThat(FiscalYearService.periodName(LocalDate.of(2027, 2, 1), "monthly", "FY2027")).isEqualTo("Feb 2027");
        assertThat(FiscalYearService.periodName(LocalDate.of(2027, 7, 1), "quarterly", "FY2027")).isEqualTo("Q3 2027");
        assertThat(FiscalYearService.periodName(LocalDate.of(2027, 1, 1), "yearly", "FY2027")).isEqualTo("FY2027");
    }
}
