package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.procurement.application.ProcurementDashboardService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ProcurementDashboardWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Test
    void periodsAndPreviousPeriods() {
        assertThat(ProcurementDashboardService.window("this_month", TODAY))
                .containsExactly(LocalDate.of(2026, 11, 1), TODAY);
        assertThat(ProcurementDashboardService.window("this_quarter", TODAY))
                .containsExactly(LocalDate.of(2026, 10, 1), TODAY);
        assertThat(ProcurementDashboardService.window("this_year", TODAY))
                .containsExactly(LocalDate.of(2026, 1, 1), TODAY);
        assertThat(ProcurementDashboardService.window("last_12_months", TODAY))
                .containsExactly(LocalDate.of(2025, 11, 28), TODAY);
        assertThat(ProcurementDashboardService.window("last_year", TODAY))
                .containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        LocalDate[] month = ProcurementDashboardService.window("this_month", TODAY);
        assertThat(ProcurementDashboardService.previous(month))
                .containsExactly(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 31));
        assertThatThrownBy(() -> ProcurementDashboardService.window("forever", TODAY))
                .hasMessageContaining("period must be one of");
    }
}
