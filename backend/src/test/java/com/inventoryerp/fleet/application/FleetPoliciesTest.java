package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.fleet.application.MeterReadingPolicy.Neighbour;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The pure fleet rules: vehicle status machine, monotonic meter rule, compliance expiry state (ADR-S009 §4). */
class FleetPoliciesTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("vehicle status machine")
    class Status {

        @Test
        @DisplayName("active, in workshop, breakdown and idle move freely between each other")
        void freeMoves() {
            for (String from : VehicleStatusPolicy.STATUSES) {
                for (String to : VehicleStatusPolicy.STATUSES) {
                    if (from.equals(to) || VehicleStatusPolicy.DISPOSED.equals(from)) {
                        continue;
                    }
                    assertThatCode(() -> VehicleStatusPolicy.assertTransition("T1", from, to, "reason"))
                            .as(from + " → " + to).doesNotThrowAnyException();
                }
            }
        }

        @Test
        @DisplayName("disposed is terminal; the same status twice and unknown statuses are refused")
        void refused() {
            assertThatThrownBy(() -> VehicleStatusPolicy.assertTransition("T1", "disposed", "active", "x"))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("Vehicle T1 is disposed; its status can no longer change");
            assertThatThrownBy(() -> VehicleStatusPolicy.assertTransition("T1", "in_workshop", "in_workshop", null))
                    .hasMessage("Vehicle T1 is already in workshop");
            assertThatThrownBy(() -> VehicleStatusPolicy.assertTransition("T1", "active", "parked", null))
                    .hasMessage("Unknown vehicle status 'parked'");
        }

        @Test
        @DisplayName("a breakdown or a disposal needs a reason; other moves do not")
        void reasons() {
            assertThatThrownBy(() -> VehicleStatusPolicy.assertTransition("T1", "active", "breakdown", " "))
                    .hasMessage("A reason is required to mark a vehicle as breakdown");
            assertThatThrownBy(() -> VehicleStatusPolicy.assertTransition("T1", "idle", "disposed", null))
                    .hasMessage("A reason is required to mark a vehicle as disposed");
            assertThatCode(() -> VehicleStatusPolicy.assertTransition("T1", "active", "idle", null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("only an active vehicle is operational")
        void operational() {
            assertThat(VehicleStatusPolicy.isOperational("active")).isTrue();
            assertThat(VehicleStatusPolicy.isOperational("in_workshop")).isFalse();
            assertThat(VehicleStatusPolicy.isOperational("breakdown")).isFalse();
            assertThat(VehicleStatusPolicy.isOperational("idle")).isFalse();
            assertThat(VehicleStatusPolicy.isOperational("disposed")).isFalse();
            assertThat(VehicleStatusPolicy.label("in_workshop")).isEqualTo("in workshop");
        }
    }

    @Nested
    @DisplayName("monotonic meter rule")
    class Meter {

        @Test
        @DisplayName("a reading between its neighbours (or equal to one) is accepted")
        void accepted() {
            Neighbour prev = new Neighbour(d("1000"), d("1000"), T0);
            Neighbour next = new Neighbour(d("1200"), d("1200"), T0.plus(Duration.ofDays(2)));
            assertThatCode(() -> MeterReadingPolicy.assertMonotonic("odometer", d("1100"), d("1100"), prev, next))
                    .doesNotThrowAnyException();
            assertThatCode(() -> MeterReadingPolicy.assertMonotonic("odometer", d("1000"), d("1000"), prev, next))
                    .doesNotThrowAnyException();
            assertThatCode(() -> MeterReadingPolicy.assertMonotonic("odometer", d("1200"), d("1200"), prev, next))
                    .doesNotThrowAnyException();
            assertThatCode(() -> MeterReadingPolicy.assertMonotonic("hours", d("5"), d("5"), null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("lower than the previous or higher than a later reading is refused, with the values in the message")
        void refused() {
            Neighbour prev = new Neighbour(d("1500.0"), d("1500.0"), T0);
            assertThatThrownBy(() -> MeterReadingPolicy.assertMonotonic("odometer", d("1200"), d("1200"), prev, null))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("Odometer reading 1200 km is lower than the previous reading of 1500 km on 2026-10-01."
                            + " Request a meter correction instead.");
            Neighbour next = new Neighbour(d("80.5"), d("80.5"), T0);
            assertThatThrownBy(() -> MeterReadingPolicy.assertMonotonic("hours", d("90"), d("90"), null, next))
                    .hasMessage("Hour meter reading 90 h is higher than the later reading of 80.5 h on 2026-10-01.");
        }

        @Test
        @DisplayName("the rule runs on lifetime values: a replaced meter's low raw reading is fine")
        void lifetime() {
            // Old meter ended at 250000 km; the new one started at 0 (offset 250000).
            Neighbour prev = new Neighbour(d("0"), d("250000"), T0);
            assertThatCode(() -> MeterReadingPolicy.assertMonotonic("odometer", d("120"), d("250120"), prev, null))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> MeterReadingPolicy.assertMonotonic("odometer", d("120"), d("120"), prev, null))
                    .hasMessageStartingWith("Odometer reading 120 km is lower than the previous reading of 0 km");
        }

        @Test
        @DisplayName("future readings, readings before a replacement and untracked meters")
        void timeAndTracking() {
            Instant now = T0;
            assertThatCode(() -> MeterReadingPolicy.assertNotFuture(now.plus(Duration.ofMinutes(4)), now))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> MeterReadingPolicy.assertNotFuture(now.plus(Duration.ofMinutes(6)), now))
                    .hasMessage("Reading time cannot be in the future");
            assertThatThrownBy(() -> MeterReadingPolicy.assertAfterReplacement("hours", T0.minusSeconds(60), T0))
                    .hasMessage("Hour meter reading predates the meter replacement on 2026-10-01");
            assertThatCode(() -> MeterReadingPolicy.assertAfterReplacement("odometer", T0, null))
                    .doesNotThrowAnyException();
            assertThat(MeterReadingPolicy.tracks("both", "hours")).isTrue();
            assertThat(MeterReadingPolicy.tracks("both", "odometer")).isTrue();
            assertThat(MeterReadingPolicy.tracks("odometer", "hours")).isFalse();
            assertThat(MeterReadingPolicy.tracks("hours", "hours")).isTrue();
            assertThat(MeterReadingPolicy.tracks("none", "odometer")).isFalse();
            assertThat(MeterReadingPolicy.unit("hours")).isEqualTo("h");
            assertThat(MeterReadingPolicy.unit("odometer")).isEqualTo("km");
        }
    }

    @Nested
    @DisplayName("compliance expiry state")
    class Compliance {

        private final LocalDate today = LocalDate.of(2026, 10, 28);

        @Test
        @DisplayName("expired after the expiry date, expiring within the lead days (inclusive), valid before")
        void states() {
            assertThat(ComplianceState.of(today.minusDays(1), 30, today)).isEqualTo(ComplianceState.EXPIRED);
            assertThat(ComplianceState.of(today, 30, today)).isEqualTo(ComplianceState.EXPIRING);
            assertThat(ComplianceState.of(today.plusDays(30), 30, today)).isEqualTo(ComplianceState.EXPIRING);
            assertThat(ComplianceState.of(today.plusDays(31), 30, today)).isEqualTo(ComplianceState.VALID);
            assertThat(ComplianceState.of(today.plusDays(1), 0, today)).isEqualTo(ComplianceState.VALID);
            assertThat(ComplianceState.of(today, 0, today)).isEqualTo(ComplianceState.EXPIRING);
            assertThat(ComplianceState.of(null, 30, today)).isEqualTo(ComplianceState.NO_EXPIRY);
        }

        @Test
        @DisplayName("days to expiry are negative once expired")
        void days() {
            assertThat(ComplianceState.daysToExpiry(today.plusDays(10), today)).isEqualTo(10L);
            assertThat(ComplianceState.daysToExpiry(today.minusDays(3), today)).isEqualTo(-3L);
            assertThat(ComplianceState.daysToExpiry(null, today)).isNull();
        }
    }

    @Test
    @DisplayName("plate / VIN normalisation and class codes")
    void normalisation() {
        assertThat(FleetVehicleService.normalizePlate("  uaz  123x ")).isEqualTo("UAZ 123X");
        assertThat(FleetVehicleService.normalizePlate(" ")).isNull();
        assertThat(FleetVehicleService.normalizeVin("jt1-abc 123")).isEqualTo("JT1ABC123");
        assertThat(FleetCategoryService.normalizeCode(" water bowser ")).isEqualTo("WATER_BOWSER");
        assertThat(FleetCategoryService.DEFAULTS).hasSize(11);
    }
}
