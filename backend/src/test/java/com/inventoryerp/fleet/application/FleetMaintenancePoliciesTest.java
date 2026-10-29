package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.fleet.application.AvailabilityCalculator.Interval;
import com.inventoryerp.fleet.application.AvailabilityCalculator.Result;
import com.inventoryerp.fleet.application.ServiceDuePolicy.Baseline;
import com.inventoryerp.fleet.application.ServiceDuePolicy.Current;
import com.inventoryerp.fleet.application.ServiceDuePolicy.DueStatus;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Fleet 1.6 calculators and policies: next-due, availability / MTBF / MTTR, defect block, state machines. */
class FleetMaintenancePoliciesTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    @Nested
    @DisplayName("next-due: km / hours / days, whichever first")
    class Due {

        ServiceDuePolicy.Interval every(String km, String hours, Integer days, String leadKm, String leadHours,
                Integer leadDays) {
            return new ServiceDuePolicy.Interval(km == null ? null : d(km), hours == null ? null : d(hours), days,
                    leadKm == null ? null : d(leadKm), leadHours == null ? null : d(leadHours), leadDays);
        }

        @Test
        void kilometresOkDueOverdue() {
            ServiceDuePolicy.Interval i = every("10000", null, null, "500", null, null);
            Baseline b = new Baseline(TODAY.minusDays(10), d("50000"), null);
            DueStatus ok = ServiceDuePolicy.evaluate(i, b, new Current(TODAY, d("59000"), null));
            assertThat(ok.state()).isEqualTo("ok");
            assertThat(ok.dimension("km").nextDue()).isEqualByComparingTo("60000");
            assertThat(ok.dimension("km").remaining()).isEqualByComparingTo("1000");
            assertThat(ServiceDuePolicy.evaluate(i, b, new Current(TODAY, d("59500"), null)).state()).isEqualTo("due");
            assertThat(ServiceDuePolicy.evaluate(i, b, new Current(TODAY, d("60000"), null)).state()).isEqualTo("due");
            DueStatus over = ServiceDuePolicy.evaluate(i, b, new Current(TODAY, d("60000.1"), null));
            assertThat(over.state()).isEqualTo("overdue");
            assertThat(over.trigger()).isEqualTo("km");
        }

        @Test
        void whicheverComesFirst() {
            // 250 h or 90 days: hours are fine, but the calendar interval has passed.
            ServiceDuePolicy.Interval i = every(null, "250", 90, null, "10", 7);
            Baseline b = new Baseline(TODAY.minusDays(95), null, d("1000"));
            DueStatus s = ServiceDuePolicy.evaluate(i, b, new Current(TODAY, null, d("1100")));
            assertThat(s.state()).isEqualTo("overdue");
            assertThat(s.trigger()).isEqualTo("days");
            assertThat(s.dimension("days").nextDueOn()).isEqualTo(TODAY.minusDays(5));
            assertThat(s.dimension("days").remaining()).isEqualByComparingTo("-5");
            assertThat(s.dimension("hours").state()).isEqualTo("ok");
            // Both due: the dimension closest to its due point (relative to the interval) is the trigger.
            DueStatus both = ServiceDuePolicy.evaluate(i, new Baseline(TODAY.minusDays(85), null, d("1000")),
                    new Current(TODAY, null, d("1245")));
            assertThat(both.state()).isEqualTo("due");
            assertThat(both.trigger()).isEqualTo("hours");
        }

        @Test
        void unknownMetersNeverTriggerAndMissingBaselineCountsFromZero() {
            ServiceDuePolicy.Interval i = every("5000", null, null, null, null, null);
            DueStatus noReading = ServiceDuePolicy.evaluate(i, new Baseline(TODAY, null, null),
                    new Current(TODAY, null, null));
            assertThat(noReading.state()).isEqualTo("ok");
            assertThat(noReading.trigger()).isNull();
            assertThat(noReading.dimension("km").remaining()).isNull();
            DueStatus fromZero = ServiceDuePolicy.evaluate(i, new Baseline(TODAY, null, null),
                    new Current(TODAY, d("5200"), null));
            assertThat(fromZero.state()).isEqualTo("overdue");
            assertThat(fromZero.dimension("km").nextDue()).isEqualByComparingTo("5000");
        }

        @Test
        void calendarLeadWindow() {
            ServiceDuePolicy.Interval i = every(null, null, 30, null, null, 5);
            assertThat(ServiceDuePolicy.evaluate(i, new Baseline(TODAY.minusDays(24), null, null),
                    new Current(TODAY, null, null)).state()).isEqualTo("ok");
            assertThat(ServiceDuePolicy.evaluate(i, new Baseline(TODAY.minusDays(25), null, null),
                    new Current(TODAY, null, null)).state()).isEqualTo("due");
            assertThat(ServiceDuePolicy.evaluate(i, new Baseline(TODAY.minusDays(31), null, null),
                    new Current(TODAY, null, null)).state()).isEqualTo("overdue");
            assertThat(ServiceDuePolicy.rank("overdue")).isGreaterThan(ServiceDuePolicy.rank("due"));
        }
    }

    @Nested
    @DisplayName("availability %, MTBF, MTTR")
    class Availability {

        final Instant from = Instant.parse("2026-10-01T00:00:00Z");
        final Instant to = Instant.parse("2026-10-11T00:00:00Z"); // 240 h

        Instant h(int hours) {
            return from.plusSeconds(hours * 3600L);
        }

        @Test
        void noDowntime() {
            Result r = AvailabilityCalculator.compute(from, to, to.plusSeconds(1), List.of());
            assertThat(r.periodHours()).isEqualByComparingTo("240");
            assertThat(r.availabilityPct()).isEqualByComparingTo("100");
            assertThat(r.failures()).isZero();
            assertThat(r.mtbfHours()).isNull();
            assertThat(r.mttrHours()).isNull();
        }

        @Test
        void breakdownsAndPlannedService() {
            // Two breakdowns (6 h + 18 h) and a planned service of 12 h that overlaps the second breakdown by 6 h.
            List<Interval> down = List.of(
                    new Interval(h(10), h(16), true),
                    new Interval(h(100), h(118), true),
                    new Interval(h(112), h(124), false));
            Result r = AvailabilityCalculator.compute(from, to, to.plusSeconds(1), down);
            assertThat(r.downtimeHours()).isEqualByComparingTo("30"); // union: 6 + 24
            assertThat(r.unplannedHours()).isEqualByComparingTo("24");
            assertThat(r.plannedHours()).isEqualByComparingTo("6");
            assertThat(r.failures()).isEqualTo(2);
            assertThat(r.availabilityPct()).isEqualByComparingTo("87.50"); // 210 / 240
            assertThat(r.mtbfHours()).isEqualByComparingTo("105.00"); // 210 h up / 2
            assertThat(r.mttrHours()).isEqualByComparingTo("12.00"); // 24 h / 2
        }

        @Test
        void clippingOpenIntervalsAndElapsedPeriod() {
            // Started before the period (not a failure in it) and still open; the period is only half elapsed.
            Instant now = h(120);
            List<Interval> down = List.of(new Interval(from.minusSeconds(3600 * 5), h(4), true),
                    new Interval(h(110), null, true));
            Result r = AvailabilityCalculator.compute(from, to, now, down);
            assertThat(r.periodHours()).isEqualByComparingTo("120");
            assertThat(r.downtimeHours()).isEqualByComparingTo("14"); // 4 + 10 (open, up to now)
            assertThat(r.failures()).isEqualTo(1);
            assertThat(r.availabilityPct()).isEqualByComparingTo("88.33");
            assertThat(AvailabilityCalculator.compute(to, to, now, down).availabilityPct()).isNull();
        }

        @Test
        void aggregateRecomputesRatios() {
            Result a = AvailabilityCalculator.compute(from, to, to, List.of(new Interval(h(0), h(24), true)));
            Result b = AvailabilityCalculator.compute(from, to, to, List.of());
            Result total = AvailabilityCalculator.aggregate(List.of(a, b));
            assertThat(total.periodHours()).isEqualByComparingTo("480");
            assertThat(total.availabilityPct()).isEqualByComparingTo("95.00");
            assertThat(total.failures()).isEqualTo(1);
            assertThat(total.mtbfHours()).isEqualByComparingTo("456.00");
            assertThat(total.mttrHours()).isEqualByComparingTo("24.00");
        }
    }

    @Nested
    @DisplayName("defects, inspections and work-order states")
    class Rules {

        @Test
        void criticalOpenDefectBlocksDispatch() {
            assertThat(MaintenancePolicies.blocksDispatch("critical", "open")).isTrue();
            assertThat(MaintenancePolicies.blocksDispatch("critical", "in_repair")).isTrue();
            assertThat(MaintenancePolicies.blocksDispatch("critical", "resolved")).isFalse();
            assertThat(MaintenancePolicies.blocksDispatch("critical", "dismissed")).isFalse();
            assertThat(MaintenancePolicies.blocksDispatch("major", "open")).isFalse();
            assertThat(MaintenancePolicies.blocksDispatch("minor", "open")).isFalse();
            assertThat(MaintenancePolicies.priorityFor("critical")).isEqualTo("urgent");
            assertThat(MaintenancePolicies.priorityFor("major")).isEqualTo("high");
            assertThat(MaintenancePolicies.priorityFor("minor")).isEqualTo("normal");
        }

        @Test
        void defectLifecycle() {
            MaintenancePolicies.assertDefectTransition("DEF-1", "open", "in_repair");
            MaintenancePolicies.assertDefectTransition("DEF-1", "in_repair", "resolved");
            MaintenancePolicies.assertDefectTransition("DEF-1", "in_repair", "open");
            MaintenancePolicies.assertDefectTransition("DEF-1", "open", "dismissed");
            assertThatThrownBy(() -> MaintenancePolicies.assertDefectTransition("DEF-1", "resolved", "in_repair"))
                    .isInstanceOf(ApiException.class).hasMessage("Defect DEF-1 is resolved; it cannot be in repair");
        }

        @Test
        void inspectionResult() {
            assertThat(MaintenancePolicies.inspectionResult(List.of("pass", "na", "pass"))).isEqualTo("pass");
            assertThat(MaintenancePolicies.inspectionResult(List.of("pass", "fail"))).isEqualTo("fail");
        }

        @Test
        void workOrderLifecycle() {
            MaintenancePolicies.assertWorkOrderTransition("WO-1", "draft", "open");
            MaintenancePolicies.assertWorkOrderTransition("WO-1", "open", "in_progress");
            MaintenancePolicies.assertWorkOrderTransition("WO-1", "in_progress", "completed");
            MaintenancePolicies.assertWorkOrderTransition("WO-1", "completed", "closed");
            MaintenancePolicies.assertWorkOrderTransition("WO-1", "open", "cancelled");
            assertThatThrownBy(() -> MaintenancePolicies.assertWorkOrderTransition("WO-1", "open", "completed"))
                    .hasMessage("Work order WO-1 is open; it cannot be completed");
            assertThatThrownBy(() -> MaintenancePolicies.assertWorkOrderTransition("WO-1", "completed", "cancelled"))
                    .hasMessage("Work order WO-1 is completed; it cannot be cancelled");
            assertThatThrownBy(() -> MaintenancePolicies.assertWorkOrderTransition("WO-1", "closed", "open"))
                    .isInstanceOf(ApiException.class);
            assertThat(MaintenancePolicies.acceptsParts("in_progress")).isTrue();
            assertThat(MaintenancePolicies.acceptsParts("completed")).isFalse();
            assertThat(MaintenancePolicies.acceptsCosts("completed")).isTrue();
            assertThat(MaintenancePolicies.acceptsCosts("closed")).isFalse();
            assertThat(MaintenancePolicies.unplanned("preventive")).isFalse();
            assertThat(MaintenancePolicies.unplanned("breakdown")).isTrue();
        }
    }
}
