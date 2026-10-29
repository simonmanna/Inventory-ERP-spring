package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.fleet.application.FleetTelematicsService;
import com.inventoryerp.fleet.application.FleetTelematicsService.PositionPoint;
import com.inventoryerp.fleet.application.FleetTelematicsService.SyncResult;
import com.inventoryerp.fleet.application.MockTelematicsProvider;
import com.inventoryerp.fleet.application.TelematicsProvider;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.8 telematics with the deterministic mock provider: device ↔ vehicle mapping rules, the sync (positions,
 * odometer readings through the meter service — idempotent per snapshot, events and alerts), failure isolation per
 * device, the public position API for dispatch (positions between, distance, last known position), alert
 * acknowledgement, tenant isolation and the authorization matrix.
 */
class FleetTelematicsIT extends FleetSafetyTestSupport {

    @Autowired
    FleetTelematicsService telematics;

    @Autowired
    MockTelematicsProvider mock;

    @AfterEach
    void systemClock() {
        mock.useClock(null);
    }

    /** Grid time two hours ago (positions and snapshots are on a 5-minute grid). */
    static Instant gridAgo(Duration ago) {
        long s = Instant.now().minus(ago).getEpochSecond();
        return Instant.ofEpochSecond(s - Math.floorMod(s, 300));
    }

    <T> T asAdmin(Supplier<T> body) {
        return TenantContext.run(TenantStore.user(demoOrg, adminId), body);
    }

    SyncResult syncAt(Instant now) {
        mock.useClock(Clock.fixed(now, ZoneOffset.UTC));
        SyncResult r = asAdmin(() -> telematics.syncOrganization(now));
        detachedWrites.awaitIdle(Duration.ofSeconds(10));
        return r;
    }

    /** A unit id whose mock events include a speeding alert in (since, until]. */
    String unitWithSpeeding(Instant since, Instant until) {
        for (int i = 0; i < 200; i++) {
            String unit = "MOCK-" + sfx + "-" + i;
            MockTelematicsProvider probe = new MockTelematicsProvider();
            probe.useClock(Clock.fixed(until, ZoneOffset.UTC));
            probe.positions(unit, since, until);
            if (probe.events(since).stream().anyMatch(e -> "speeding".equals(e.type()))) {
                return unit;
            }
        }
        throw new AssertionError("no unit with a speeding alert");
    }

    @Test
    @DisplayName("devices: provider default, one active device per unit and per vehicle, remap, deactivate, delete")
    void devices() {
        JsonNode settings = ok(200, "GET", "/fleet/telematics/settings", null);
        assertThat(settings.get("provider").asString()).isEqualTo("mock");
        assertThat(settings.get("available").toString()).contains("mock");
        assertThat(settings.get("apiKeyConfigured").asBoolean()).isFalse();
        String v1 = vehicle(Map.of()).get("id").asString();
        String v2 = vehicle(Map.of()).get("id").asString();
        JsonNode d = ok(201, "POST", "/fleet/telematics/devices", Map.of("vehicleId", v1, "externalUnitId",
                "DEV-" + sfx, "name", "Teltonika FMB920"));
        assertThat(d.get("provider").asString()).isEqualTo("mock");
        assertThat(d.get("vehicle").get("id").asString()).isEqualTo(v1);
        String id = d.get("id").asString();
        assertThat(as("POST", "/fleet/telematics/devices", Map.of("externalUnitId", "DEV-" + sfx)).status())
                .isEqualTo(409);
        Res sameVehicle = as("POST", "/fleet/telematics/devices", Map.of("vehicleId", v1, "externalUnitId",
                "DEV2-" + sfx));
        assertThat(sameVehicle.status()).isEqualTo(409);
        assertThat(msg(sameVehicle)).endsWith("already has telematics unit DEV-" + sfx);
        assertThat(msg(as("POST", "/fleet/telematics/devices", Map.of("externalUnitId", "X-" + sfx, "provider",
                "acme")))).startsWith("Telematics provider acme is not available");
        JsonNode moved = ok(200, "PATCH", "/fleet/telematics/devices/" + id, Map.of("vehicleId", v2));
        assertThat(moved.get("vehicle").get("id").asString()).isEqualTo(v2);
        JsonNode unmapped = ok(200, "PATCH", "/fleet/telematics/devices/" + id, Map.of("clear", List.of("vehicleId")));
        assertThat(unmapped.get("vehicleId").isNull()).isTrue();
        assertThat(ok(200, "GET", "/fleet/telematics/devices?vehicleId=" + v2, null)).isEmpty();
        JsonNode deleted = ok(200, "DELETE", "/fleet/telematics/devices/" + id, null);
        assertThat(deleted.get("deleted").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/fleet/telematics/devices?includeInactive=true", null).toString())
                .doesNotContain(id);
    }

    @Test
    @DisplayName("sync: positions, odometer reading once per snapshot, events + alert, distance / last position API")
    void syncAndPositions() {
        Instant t = gridAgo(Duration.ofHours(2));
        Instant t2 = t.plus(Duration.ofMinutes(30));
        String unit = unitWithSpeeding(t.minus(Duration.ofHours(1)), t2);
        String vehicleId = vehicle(Map.of()).get("id").asString();
        String deviceId = ok(201, "POST", "/fleet/telematics/devices", Map.of("vehicleId", vehicleId,
                "externalUnitId", unit)).get("id").asString();

        SyncResult first = syncAt(t);
        assertThat(first.devices()).isGreaterThanOrEqualTo(1);
        assertThat(first.positions()).isGreaterThanOrEqualTo(12);
        long readings = jdbc.sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"vehicleId\" = ? AND source ="
                + " 'telematics'").param(vehicleId).query(Long.class).single();
        assertThat(readings).isEqualTo(1);
        TelematicsProvider.MeterSnapshot snap = mock.meters(unit).orElseThrow();
        JsonNode v = ok(200, "GET", "/fleet/vehicles/" + vehicleId, null);
        assertThat(dec(v.get("meters").get("odometer").get("value"))).isEqualByComparingTo(snap.odometerKm());

        // Same snapshot again: no new reading, no duplicate positions or events.
        long events = jdbc.sql("SELECT count(*) FROM \"FleetTelematicsEvent\" WHERE \"vehicleId\" = ?")
                .param(vehicleId).query(Long.class).single();
        syncAt(t);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"vehicleId\" = ? AND source ="
                + " 'telematics'").param(vehicleId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetPosition\" WHERE \"vehicleId\" = ?").param(vehicleId)
                .query(Long.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetTelematicsEvent\" WHERE \"vehicleId\" = ?").param(vehicleId)
                .query(Long.class).single()).isEqualTo(events);

        syncAt(t2);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetPosition\" WHERE \"vehicleId\" = ?").param(vehicleId)
                .query(Long.class).single()).isEqualTo(18);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"vehicleId\" = ? AND source ="
                + " 'telematics'").param(vehicleId).query(Long.class).single()).isEqualTo(2);
        JsonNode device = ok(200, "GET", "/fleet/telematics/devices?vehicleId=" + vehicleId, null).get(0);
        assertThat(device.get("lastSyncStatus").asString()).isEqualTo("ok");
        assertThat(Instant.parse(device.get("lastPositionAt").asString())).isEqualTo(t2.minus(Duration.ofMinutes(5)));

        // Public API for dispatch (1.3): 13 fixes over the hour before t, 12 segments of 2.5 km.
        Instant from = t.minus(Duration.ofHours(1));
        List<PositionPoint> points = asAdmin(() -> telematics.positionsBetween(vehicleId, from, t));
        assertThat(points).hasSize(13);
        assertThat(points.getFirst().at()).isEqualTo(from);
        BigDecimal km = asAdmin(() -> telematics.distanceKm(vehicleId, from, t));
        assertThat(km).isBetween(new BigDecimal("29.990"), new BigDecimal("30.010"));
        Optional<PositionPoint> last = asAdmin(() -> telematics.lastKnownPosition(vehicleId));
        assertThat(last).isPresent();
        assertThat(last.get().at()).isEqualTo(t2.minus(Duration.ofMinutes(5)));

        JsonNode distance = ok(200, "GET", "/fleet/vehicles/" + vehicleId + "/distance?from=" + from + "&to=" + t,
                null);
        assertThat(dec(distance.get("distanceKm"))).isEqualByComparingTo(km);
        assertThat(distance.get("points").asInt()).isEqualTo(13);
        JsonNode page = ok(200, "GET", "/fleet/vehicles/" + vehicleId + "/positions?from=" + from + "&to=" + t2
                + "&pageSize=5", null);
        assertThat(page.get("meta").get("total").asInt()).isEqualTo(18);
        assertThat(page.get("data")).hasSize(5);
        assertThat(Instant.parse(page.get("data").get(0).get("recordedAt").asString()))
                .isEqualTo(t2.minus(Duration.ofMinutes(5)));
        assertThat(Instant.parse(ok(200, "GET", "/fleet/vehicles/" + vehicleId + "/last-position", null)
                .get("position").get("at").asString())).isEqualTo(t2.minus(Duration.ofMinutes(5)));
        assertThat(ok(200, "GET", "/fleet/telematics/positions/latest", null).toString()).contains(vehicleId);
        assertThat(as("GET", "/fleet/vehicles/" + vehicleId + "/distance?from=" + t + "&to=" + from, null).status())
                .isEqualTo(400);

        // Events: ignition (no acknowledgement) and a speeding alert (acknowledged once).
        JsonNode ignition = ok(200, "GET", "/fleet/telematics/events?vehicleId=" + vehicleId
                + "&eventType=ignition_on", null);
        assertThat(ignition.get("meta").get("total").asInt()).isPositive();
        String ignitionId = ignition.get("data").get(0).get("id").asString();
        assertThat(msg(as("POST", "/fleet/telematics/events/" + ignitionId + "/acknowledge", Map.of())))
                .isEqualTo("Only alerts need an acknowledgement");
        JsonNode alerts = ok(200, "GET", "/fleet/telematics/events?vehicleId=" + vehicleId + "&open=true", null);
        assertThat(alerts.toString()).contains("speeding");
        JsonNode speeding = null;
        for (JsonNode e : alerts.get("data")) {
            if ("speeding".equals(e.get("eventType").asString())) {
                speeding = e;
            }
        }
        assertThat(speeding).isNotNull();
        assertThat(speeding.get("severity").asString()).isEqualTo("warning");
        assertThat(speeding.get("latitude").isNull()).isFalse();
        String alertId = speeding.get("id").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'fleet.telematics.alert_raised'"
                + " AND payload->>'eventId' = ?").param(alertId).query(Long.class).single()).isEqualTo(1);
        JsonNode acked = ok(201, "POST", "/fleet/telematics/events/" + alertId + "/acknowledge", Map.of("note",
                "Driver warned"));
        assertThat(acked.get("acknowledgedAt").isNull()).isFalse();
        assertThat(msg(as("POST", "/fleet/telematics/events/" + alertId + "/acknowledge", Map.of())))
                .isEqualTo("This alert was already acknowledged");
        assertThat(ok(200, "GET", "/fleet/telematics/events?vehicleId=" + vehicleId + "&alertsOnly=true", null)
                .toString()).contains("Driver warned");

        // A device with data is deactivated, not deleted.
        JsonNode del = ok(200, "DELETE", "/fleet/telematics/devices/" + deviceId, null);
        assertThat(del.get("deactivated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("failure isolation: a provider odometer below a manual reading fails that meter only")
    void failureIsolation() {
        Instant t = gridAgo(Duration.ofHours(3));
        String vehicleId = vehicle(Map.of("openingOdometer", 9_000_000)).get("id").asString();
        jdbc.sql("UPDATE \"FleetMeterReading\" SET \"readingAt\" = ? WHERE \"vehicleId\" = ?")
                .params(java.sql.Timestamp.valueOf(t.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC)
                        .toLocalDateTime()), vehicleId).update();
        String deviceId = ok(201, "POST", "/fleet/telematics/devices", Map.of("vehicleId", vehicleId,
                "externalUnitId", "LOW-" + sfx)).get("id").asString();
        SyncResult r = syncAt(t);
        assertThat(r.errors()).isPositive();
        JsonNode device = ok(200, "GET", "/fleet/telematics/devices?vehicleId=" + vehicleId, null).get(0);
        assertThat(device.get("id").asString()).isEqualTo(deviceId);
        assertThat(device.get("lastSyncStatus").asString()).isEqualTo("partial");
        assertThat(device.get("lastError").asString()).startsWith("Odometer: ").contains("lower than the previous");
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetPosition\" WHERE \"vehicleId\" = ?").param(vehicleId)
                .query(Long.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"vehicleId\" = ? AND source ="
                + " 'telematics'").param(vehicleId).query(Long.class).single()).isZero();
        // The settings screen's "Sync now" runs the same pull with the real clock.
        mock.useClock(null);
        Res now = as("POST", "/fleet/telematics/sync", null);
        assertThat(now.status()).as(now.raw().body()).isEqualTo(201);
        assertThat(now.body().get("devices").asInt()).isPositive();
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's devices, positions or alerts")
    void tenantIsolation() {
        Instant t = gridAgo(Duration.ofHours(4));
        String vehicleId = vehicle(Map.of()).get("id").asString();
        String deviceId = ok(201, "POST", "/fleet/telematics/devices", Map.of("vehicleId", vehicleId,
                "externalUnitId", "ISO-" + sfx)).get("id").asString();
        syncAt(t);
        String eventId = jdbc.sql("SELECT id FROM \"FleetTelematicsEvent\" WHERE \"vehicleId\" = ? LIMIT 1")
                .param(vehicleId).query(String.class).single();
        assertThat(call("GET", "/fleet/vehicles/" + vehicleId + "/positions", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/fleet/vehicles/" + vehicleId + "/last-position", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("PATCH", "/fleet/telematics/devices/" + deviceId, Map.of("name", "x"), otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/fleet/telematics/events/" + eventId + "/acknowledge", Map.of(), otherToken)
                .status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/telematics/devices?includeInactive=true", null, otherToken).body().toString())
                .doesNotContain(deviceId);
        assertThat(call("GET", "/fleet/telematics/positions/latest", null, otherToken).body().toString())
                .doesNotContain(vehicleId);
        assertThat(call("GET", "/fleet/telematics/events", null, otherToken).body().toString())
                .doesNotContain(eventId);
        assertThat(call("POST", "/fleet/telematics/devices", Map.of("vehicleId", vehicleId, "externalUnitId", "X"),
                otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("authorization: 401 / 403 on every telematics route; positions need fleet_trip:read")
    void authorization() {
        assertThat(assertRouteMatrix(Set.of(com.inventoryerp.fleet.web.FleetTelematicsController.class)))
                .isEqualTo(12);
        String viewer = tokenWith("fleet_vehicle:read");
        assertThat(call("GET", "/fleet/telematics/devices", null, viewer).status()).isEqualTo(200);
        Res denied = call("GET", "/fleet/telematics/positions/latest", null, viewer);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).isEqualTo("Missing required permission(s): fleet_trip:read");
    }
}
