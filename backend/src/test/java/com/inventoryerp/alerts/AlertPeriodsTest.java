package com.inventoryerp.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.alerts.application.AlertPeriods;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The period key is what stops a repeat. */
class AlertPeriodsTest {

    @Test
    @DisplayName("daily, weekly and monthly keys")
    void keys() {
        LocalDate day = LocalDate.of(2026, 10, 31);
        assertThat(AlertPeriods.key("daily", day)).isEqualTo("2026-10-31");
        assertThat(AlertPeriods.key("weekly", day)).isEqualTo("2026-W44");
        assertThat(AlertPeriods.key("monthly", day)).isEqualTo("2026-10");
    }

    @Test
    @DisplayName("the week key uses the ISO week year")
    void isoWeekYear() {
        assertThat(AlertPeriods.key("weekly", LocalDate.of(2027, 1, 1))).isEqualTo("2026-W53");
    }

    @Test
    @DisplayName("every day of a week shares one key")
    void sameWeek() {
        assertThat(AlertPeriods.key("weekly", LocalDate.of(2026, 10, 26)))
                .isEqualTo(AlertPeriods.key("weekly", LocalDate.of(2026, 11, 1)));
    }
}
