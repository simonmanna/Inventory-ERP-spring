package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.lossprevention.application.LpInsightsService;
import com.inventoryerp.lossprevention.application.LpInsightsService.DayKey;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Which summary days an accepted event makes dirty (LP6a, LP6b). The business zone is UTC here. */
class LpInsightsKeysTest {

    final LocalDate day = LocalDate.of(2026, 3, 1);

    @Test
    void aMetricEventMarksItsBranchAndDay() {
        assertThat(LpInsightsService.keysFor("sale.completed", "b1", day)).containsExactly(new DayKey("b1", day));
        assertThat(LpInsightsService.keysFor("refund.issued", null, day)).containsExactly(new DayKey("", day));
    }

    @Test
    void aKitchenStageAlsoMarksTheDayBeforeForTicketsCreatedBeforeMidnight() {
        assertThat(LpInsightsService.keysFor("kitchen.stage", "b1", day))
                .containsExactlyInAnyOrder(new DayKey("b1", day), new DayKey("b1", day.minusDays(1)));
    }

    @Test
    void otherEventsLeaveTheSummariesAlone() {
        assertThat(LpInsightsService.keysFor("override.approved", "b1", day)).isEmpty();
        assertThat(LpInsightsService.keysFor("vision.handling", "b1", day)).isEmpty();
        assertThat(LpInsightsService.keysFor("vision.activity", "b1", day)).isEmpty();
        assertThat(LpInsightsService.keysFor("sale.completed", "b1", null)).isEmpty();
    }
    @Test
    void cameraCountsMarkTheirDay() {
        for (String type : new String[] {"vision.line_crossed", "vision.dwell", "vision.gap"}) {
            assertThat(LpInsightsService.keysFor(type, "b1", day, Instant.parse("2026-03-01T00:10:00Z")))
                    .containsExactly(new DayKey("b1", day));
        }
    }

    @Test
    void anOccupancyReportedInTheFirstHourAlsoMarksTheDayBefore() {
        assertThat(LpInsightsService.keysFor("vision.occupancy", "b1", day, Instant.parse("2026-03-01T00:59:59Z")))
                .containsExactlyInAnyOrder(new DayKey("b1", day), new DayKey("b1", day.minusDays(1)));
        assertThat(LpInsightsService.keysFor("vision.occupancy", "b1", day, Instant.parse("2026-03-01T01:00:00Z")))
                .containsExactly(new DayKey("b1", day));
        assertThat(LpInsightsService.keysFor("vision.occupancy", null, day, null)).containsExactly(new DayKey("",
                day));
    }
}
