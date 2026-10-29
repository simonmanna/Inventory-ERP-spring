package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fleet 1.7 / 1.8 unit rules: tyre status machine, cost per km / wear calculator, wheel layouts from the axle
 * configuration, incident / claim / fine / deduction state machines, GPS distance and the mock provider's
 * determinism.
 */
class FleetSafetyPoliciesTest {

    // ------------------------------------------------------------------ tyres

    @Test
    @DisplayName("tyre status machine: fit from stock / removed / retreaded, scrapped is terminal")
    void tyreStatusMachine() {
        assertThat(TyrePolicy.canMove("in_stock", "fitted")).isTrue();
        assertThat(TyrePolicy.canMove("fitted", "fitted")).isTrue();
        assertThat(TyrePolicy.canMove("fitted", "retreaded")).isFalse();
        assertThat(TyrePolicy.canMove("removed", "retreaded")).isTrue();
        assertThat(TyrePolicy.canMove("retreaded", "fitted")).isTrue();
        assertThat(TyrePolicy.canMove("in_stock", "removed")).isFalse();
        assertThat(TyrePolicy.FITTABLE).containsExactly("in_stock", "removed", "retreaded");
        assertThatThrownBy(() -> TyrePolicy.assertTransition("T1", "scrapped", "fitted"))
                .isInstanceOf(ApiException.class).hasMessage("Tyre T1 is scrapped; no further changes are possible");
        assertThatThrownBy(() -> TyrePolicy.assertTransition("T1", "in_stock", "retreaded"))
                .hasMessage("Tyre T1 is in stock and cannot be retreaded");
    }

    @Test
    @DisplayName("cost per km: (purchase + retreads) / km at 4 decimals HALF_UP; null before any km")
    void costPerKm() {
        assertThat(TyrePolicy.costPer(new BigDecimal("1200000"), new BigDecimal("80000")))
                .isEqualByComparingTo("15.0000");
        assertThat(TyrePolicy.costPer(new BigDecimal("1000"), new BigDecimal("3"))).isEqualByComparingTo("333.3333");
        assertThat(TyrePolicy.costPer(new BigDecimal("2"), new BigDecimal("3"))).isEqualByComparingTo("0.6667");
        assertThat(TyrePolicy.costPer(new BigDecimal("1000"), BigDecimal.ZERO)).isNull();
        assertThat(TyrePolicy.costPer(null, new BigDecimal("10"))).isNull();
        assertThat(TyrePolicy.stint(new BigDecimal("1000"), new BigDecimal("1250.5"))).isEqualByComparingTo("250.5");
        assertThat(TyrePolicy.stint(new BigDecimal("1000"), new BigDecimal("900"))).isEqualByComparingTo("0");
        assertThat(TyrePolicy.stint(null, new BigDecimal("900"))).isEqualByComparingTo("0");

        TyrePolicy.TyreStats s = TyrePolicy.stats(new BigDecimal("900000"), new BigDecimal("300000"),
                new BigDecimal("40000"), new BigDecimal("1200"), new BigDecimal("16"), new BigDecimal("10"),
                new BigDecimal("3"));
        assertThat(s.totalCost()).isEqualByComparingTo("1200000.00");
        assertThat(s.costPerKm()).isEqualByComparingTo("30.0000");
        assertThat(s.costPerHour()).isEqualByComparingTo("1000.0000");
        assertThat(s.treadUsedMm()).isEqualByComparingTo("6");
        assertThat(s.wearPer1000Km()).isEqualByComparingTo("0.150");
        // 13 mm usable at 6 mm per 40,000 km → 86,667 km.
        assertThat(s.projectedLifeKm()).isEqualByComparingTo("86667");
        assertThat(s.remainingTreadPct()).isEqualByComparingTo("53.8");

        TyrePolicy.TyreStats fresh = TyrePolicy.stats(new BigDecimal("500"), null, BigDecimal.ZERO, null,
                new BigDecimal("16"), new BigDecimal("16"), new BigDecimal("3"));
        assertThat(fresh.costPerKm()).isNull();
        assertThat(fresh.wearPer1000Km()).isNull();
        assertThat(fresh.remainingTreadPct()).isEqualByComparingTo("100.0");
        TyrePolicy.TyreStats worn = TyrePolicy.stats(null, null, new BigDecimal("1000"), null, new BigDecimal("16"),
                new BigDecimal("2"), new BigDecimal("3"));
        assertThat(worn.remainingTreadPct()).isEqualByComparingTo("0");
        assertThat(TyrePolicy.stats(null, null, null, null, null, null, null).treadUsedMm()).isNull();
    }

    @Test
    @DisplayName("wheel layout: 6x4 tipper, 8x4 twin steer, 4x4 singles, light vehicle, trailer, plant, defaults")
    void layouts() {
        TyrePositionLayout.Layout tipper = TyrePositionLayout.forVehicle("6x4", "road_vehicle", "TIPPER");
        assertThat(tipper.axles()).hasSize(3);
        assertThat(tipper.axles().get(0).steer()).isTrue();
        assertThat(tipper.axles().get(0).dual()).isFalse();
        assertThat(tipper.axles().get(1).dual()).isTrue();
        assertThat(tipper.codes()).containsExactly("A1L", "A1R", "A2LO", "A2LI", "A2RI", "A2RO", "A3LO", "A3LI",
                "A3RI", "A3RO", "SP1");
        assertThat(tipper.contains("A2LI")).isTrue();
        assertThat(tipper.contains("A4LO")).isFalse();

        TyrePositionLayout.Layout eight = TyrePositionLayout.forVehicle("8X4", "road_vehicle", "TIPPER");
        assertThat(eight.axles()).hasSize(4);
        assertThat(eight.axles().get(1).steer()).isTrue();
        assertThat(eight.codes()).hasSize(2 + 2 + 4 + 4 + 1);

        assertThat(TyrePositionLayout.forVehicle("4x4", "road_vehicle", "PICKUP").codes())
                .containsExactly("A1L", "A1R", "A2L", "A2R", "SP1");
        assertThat(TyrePositionLayout.forVehicle("4x2", "road_vehicle", "LIGHT_VEHICLE").codes()).hasSize(5);
        assertThat(TyrePositionLayout.forVehicle(null, "road_vehicle", null).codes())
                .containsExactly("A1L", "A1R", "A2LO", "A2LI", "A2RI", "A2RO", "SP1");
        assertThat(TyrePositionLayout.forVehicle("tri-axle", "trailer", "TRAILER").codes()).hasSize(13);
        assertThat(TyrePositionLayout.forVehicle("2 axle", "trailer", "TRAILER").axles()).hasSize(2);
        assertThat(TyrePositionLayout.forVehicle("tandem", "trailer", null).axles()).hasSize(2);
        assertThat(TyrePositionLayout.forVehicle("", "trailer", null).axles()).hasSize(3);
        TyrePositionLayout.Layout loader = TyrePositionLayout.forVehicle("4x4", "mobile_plant", "WHEEL_LOADER");
        assertThat(loader.codes()).containsExactly("A1L", "A1R", "A2L", "A2R");
        assertThat(loader.spares()).isEmpty();
    }

    // ------------------------------------------------------------------ incidents, claims, fines

    @Test
    @DisplayName("incident workflow: forward moves, reopen needs a reason, cancelled is terminal")
    void incidentWorkflow() {
        assertThat(IncidentPolicy.incidentCan("reported", "closed")).isTrue();
        assertThat(IncidentPolicy.incidentCan("closed", "investigating")).isTrue();
        assertThat(IncidentPolicy.incidentCan("closed", "repairing")).isFalse();
        IncidentPolicy.assertIncident("INC-1", "reported", "investigating", null);
        assertThatThrownBy(() -> IncidentPolicy.assertIncident("INC-1", "closed", "investigating", " "))
                .hasMessage("A reason is required to reopen an incident");
        assertThatThrownBy(() -> IncidentPolicy.assertIncident("INC-1", "reported", "cancelled", null))
                .hasMessage("A reason is required to cancel an incident");
        assertThatThrownBy(() -> IncidentPolicy.assertIncident("INC-1", "cancelled", "reported", "x"))
                .hasMessage("Incident INC-1 is cancelled");
        assertThatThrownBy(() -> IncidentPolicy.assertIncident("INC-1", "investigating", "reported", "x"))
                .hasMessage("Incident INC-1 cannot move from investigating to reported");
    }

    @Test
    @DisplayName("claim workflow: amounts at approve / settle, approved ≤ claimed, received ≤ approved")
    void claimWorkflow() {
        BigDecimal claimed = new BigDecimal("5000");
        IncidentPolicy.assertClaim("draft", "submitted", claimed, null, null, null);
        IncidentPolicy.assertClaim("submitted", "approved", claimed, new BigDecimal("4000"), null, null);
        IncidentPolicy.assertClaim("approved", "settled", claimed, new BigDecimal("4000"), new BigDecimal("4000"), null);
        assertThatThrownBy(() -> IncidentPolicy.assertClaim("submitted", "approved", claimed, null, null, null))
                .hasMessage("The approved amount is required to approve a claim");
        assertThatThrownBy(() -> IncidentPolicy.assertClaim("submitted", "approved", claimed, new BigDecimal("6000"),
                null, null)).hasMessage("The approved amount cannot exceed the amount claimed");
        assertThatThrownBy(() -> IncidentPolicy.assertClaim("approved", "settled", claimed, new BigDecimal("4000"),
                new BigDecimal("4500"), null)).hasMessage("The amount received cannot exceed the approved amount");
        assertThatThrownBy(() -> IncidentPolicy.assertClaim("approved", "settled", claimed, new BigDecimal("4000"),
                BigDecimal.ZERO, null)).hasMessage("The amount received is required to settle a claim");
        assertThatThrownBy(() -> IncidentPolicy.assertClaim("submitted", "rejected", claimed, null, null, ""))
                .hasMessage("A reason is required to mark a claim rejected");
        assertThatThrownBy(() -> IncidentPolicy.assertClaim("draft", "settled", claimed, null, null, null))
                .hasMessage("A draft claim cannot be marked settled");
        assertThat(IncidentPolicy.claimCan("settled", "withdrawn")).isFalse();
    }

    @Test
    @DisplayName("fine and deduction workflows")
    void fineWorkflow() {
        IncidentPolicy.assertFine("FIN-1", "open", "disputed", "Wrong plate");
        IncidentPolicy.assertFine("FIN-1", "disputed", "open", null);
        assertThatThrownBy(() -> IncidentPolicy.assertFine("FIN-1", "open", "cancelled", null))
                .hasMessage("A reason is required to mark a fine cancelled");
        assertThatThrownBy(() -> IncidentPolicy.assertFine("FIN-1", "charged", "cancelled", "x"))
                .hasMessage("Fine FIN-1 is charged and cannot be marked cancelled");
        IncidentPolicy.assertDeduction("pending", "exported");
        IncidentPolicy.assertDeduction("exported", "settled");
        assertThatThrownBy(() -> IncidentPolicy.assertDeduction("settled", "cancelled"))
                .hasMessage("A settled deduction cannot be marked cancelled");
        assertThat(IncidentPolicy.deductionCan("cancelled", "pending")).isFalse();
    }

    // ------------------------------------------------------------------ telematics

    @Test
    @DisplayName("distance: haversine sum, glitch segments skipped, fewer than two fixes = 0")
    void distance() {
        // One degree of latitude ≈ 111.195 km.
        assertThat(GeoDistance.haversineKm(0, 32.5, 1, 32.5)).isBetween(111.19, 111.20);
        assertThat(GeoDistance.haversineKm(0.3, 32.5, 0.3, 32.5)).isZero();
        Instant t0 = Instant.parse("2026-10-29T08:00:00Z");
        List<GeoDistance.Fix> fixes = new ArrayList<>();
        for (int i = 0; i <= 4; i++) {
            fixes.add(new GeoDistance.Fix(t0.plusSeconds(600L * i), 0.30 + i * 0.01, 32.5));
        }
        BigDecimal km = GeoDistance.pathKm(fixes);
        assertThat(km.scale()).isEqualTo(3);
        assertThat(km).isBetween(new BigDecimal("4.440"), new BigDecimal("4.460"));
        // A 2,000 km jump in 10 minutes is a GPS glitch: ignored, the path continues from the last good fix.
        List<GeoDistance.Fix> glitch = new ArrayList<>(fixes);
        glitch.add(2, new GeoDistance.Fix(t0.plusSeconds(900), 18.0, 32.5));
        assertThat(GeoDistance.pathKm(glitch)).isEqualByComparingTo(km);
        assertThat(GeoDistance.pathKm(List.of(fixes.getFirst()))).isEqualByComparingTo("0");
        assertThat(GeoDistance.pathKm(List.of())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("mock provider: 5-minute grid, 2.5 km per fix, monotonic meters, deterministic events")
    void mockProvider() {
        MockTelematicsProvider mock = new MockTelematicsProvider();
        Instant now = Instant.parse("2026-10-29T10:02:00Z");
        mock.useClock(Clock.fixed(now, ZoneOffset.UTC));
        Instant from = Instant.parse("2026-10-29T09:00:00Z");
        List<TelematicsProvider.Position> p = mock.positions("UNIT-1", from, from.plus(Duration.ofHours(1)));
        assertThat(p).hasSize(12);
        assertThat(p.getFirst().at()).isEqualTo(from);
        assertThat(p.get(1).at()).isEqualTo(from.plusSeconds(300));
        assertThat(p).isEqualTo(mock.positions("UNIT-1", from, from.plus(Duration.ofHours(1))));
        double step = GeoDistance.haversineKm(p.get(0).lat(), p.get(0).lon(), p.get(1).lat(), p.get(1).lon());
        assertThat(step).isBetween(2.4999, 2.5001);
        assertThat(p.getFirst().externalId()).startsWith("UNIT-1:p:");

        TelematicsProvider.MeterSnapshot m1 = mock.meters("UNIT-1").orElseThrow();
        assertThat(m1.at()).isEqualTo(Instant.parse("2026-10-29T10:00:00Z"));
        mock.useClock(Clock.fixed(now.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        TelematicsProvider.MeterSnapshot m2 = mock.meters("UNIT-1").orElseThrow();
        assertThat(m2.odometerKm().subtract(m1.odometerKm())).isEqualByComparingTo("60.0");
        assertThat(m2.engineHours().subtract(m1.engineHours())).isEqualByComparingTo("2.0");

        List<TelematicsProvider.TelematicsEvent> events = mock.events(from);
        assertThat(events).isNotEmpty();
        assertThat(events).extracting(TelematicsProvider.TelematicsEvent::type).contains("ignition_on", "ignition_off");
        assertThat(events).allMatch(e -> e.at().isAfter(from) && !e.at().isAfter(now.plus(Duration.ofHours(2))));
        assertThat(mock.events(from)).isEqualTo(events);
        assertThat(new MockTelematicsProvider().events(from)).as("no unit asked about yet").isEmpty();
        mock.useClock(null);
        assertThat(mock.now()).isCloseTo(Instant.now(), within());
    }

    private static org.assertj.core.data.TemporalUnitOffset within() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(5, java.time.temporal.ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("alert severity: speeding 30 km/h over the limit is critical")
    void severity() {
        assertThat(FleetTelematicsService.severity("speeding", java.util.Map.of("speedKmh", 92, "limitKmh", 80)))
                .isEqualTo("warning");
        assertThat(FleetTelematicsService.severity("speeding", java.util.Map.of("speedKmh", 115, "limitKmh", 80)))
                .isEqualTo("critical");
        assertThat(FleetTelematicsService.severity("geofence_exit", java.util.Map.of())).isEqualTo("warning");
        assertThat(FleetTelematicsService.severity("idling", java.util.Map.of())).isEqualTo("info");
    }
}
