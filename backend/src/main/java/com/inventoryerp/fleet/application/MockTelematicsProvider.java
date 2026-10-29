package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Deterministic telematics provider for tests and demos (type {@value #TYPE}). Everything is a pure function of the
 * unit id and time on a 5-minute grid:
 *
 * <ul>
 *   <li>Positions: one fix every 5 minutes; the unit drives north / south at {@value #SPEED_KMH} km/h along a
 *       meridian from a base point derived from the unit id (around Kampala), turning every 100 fixes — every
 *       segment is exactly {@value #STEP_KM} km, so a trip's distance is predictable.</li>
 *   <li>Meters: odometer = base + {@value #SPEED_KMH} km per hour since 2026-01-01, engine hours = base + 1 per hour,
 *       taken at the last 5-minute mark of the clock.</li>
 *   <li>Events per unit and hour: ignition on at :05 and off at :50; speeding (92 km/h in an 80 zone) at :20, idling
 *       at :55 and a geofence exit at :30 in some hours (by a hash of unit and hour).</li>
 * </ul>
 * {@link #events} covers the units this provider was asked about (positions / meters) — the sync job asks for
 * positions first. {@link #useClock} pins "now" for tests.
 */
@Component
public class MockTelematicsProvider implements TelematicsProvider {

    public static final String TYPE = "mock";
    static final long GRID_SECONDS = 300;
    static final int SPEED_KMH = 30;
    static final double STEP_KM = 2.5;
    static final double KM_PER_DEGREE = 2 * Math.PI * GeoDistance.EARTH_RADIUS_KM / 360;
    static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final int MAX_FIXES = 2_000;

    private final Set<String> units = ConcurrentHashMap.newKeySet();
    private volatile Clock clock = Clock.systemUTC();

    @Override
    public String type() {
        return TYPE;
    }

    /** Pin the provider's clock (tests, demo); {@code null} restores the system clock. */
    public void useClock(Clock c) {
        this.clock = c == null ? Clock.systemUTC() : c;
    }

    Instant now() {
        return clock.instant();
    }

    @Override
    public List<Position> positions(String unit, Instant from, Instant to) {
        units.add(unit);
        List<Position> out = new ArrayList<>();
        long k = Math.floorDiv(from.getEpochSecond() + GRID_SECONDS - 1, GRID_SECONDS);
        while (out.size() < MAX_FIXES) {
            Instant at = Instant.ofEpochSecond(k * GRID_SECONDS);
            if (!at.isBefore(to)) {
                break;
            }
            out.add(position(unit, k));
            k++;
        }
        return out;
    }

    /** The fix of {@code unit} at grid step {@code k}. */
    static Position position(String unit, long k) {
        int h = Math.floorMod(unit.hashCode(), 10_000);
        double baseLat = 0.30 + (h % 100) / 1000.0;
        double baseLon = 32.55 + (h / 100) / 1000.0;
        long phase = Math.floorMod(k, 200);
        long offset = phase <= 100 ? phase : 200 - phase;
        double lat = baseLat + offset * STEP_KM / KM_PER_DEGREE;
        return new Position(unit, Instant.ofEpochSecond(k * GRID_SECONDS), lat, baseLon,
                BigDecimal.valueOf(SPEED_KMH).setScale(1, RoundingMode.HALF_UP), unit + ":p:" + k);
    }

    @Override
    public Optional<MeterSnapshot> meters(String unit) {
        units.add(unit);
        Instant at = Instant.ofEpochSecond(Math.floorDiv(now().getEpochSecond(), GRID_SECONDS) * GRID_SECONDS);
        double hours = Duration.between(EPOCH, at).toSeconds() / 3600.0;
        int h = Math.floorMod(unit.hashCode(), 5_000);
        BigDecimal odometer = BigDecimal.valueOf(10_000 + h + hours * SPEED_KMH).setScale(1, RoundingMode.HALF_UP);
        BigDecimal engine = BigDecimal.valueOf(2_000 + h / 10 + hours).setScale(1, RoundingMode.HALF_UP);
        return Optional.of(new MeterSnapshot(odometer, engine, at));
    }

    @Override
    public List<TelematicsEvent> events(Instant since) {
        Instant now = now();
        List<TelematicsEvent> out = new ArrayList<>();
        long firstHour = Math.floorDiv(since.getEpochSecond(), 3600);
        long lastHour = Math.floorDiv(now.getEpochSecond(), 3600);
        for (String unit : units.stream().sorted().toList()) {
            for (long hr = firstHour; hr <= lastHour && hr - firstHour < 48; hr++) {
                int hash = Math.floorMod((unit + ":" + hr).hashCode(), 60);
                add(out, unit, hr, 5, "ignition_on", Map.of(), since, now);
                if (hash % 3 == 0) {
                    add(out, unit, hr, 20, "speeding", Map.of("speedKmh", 92, "limitKmh", 80), since, now);
                }
                if (hash % 5 == 2) {
                    add(out, unit, hr, 30, "geofence_exit", Map.of("geofence", "Quarry yard"), since, now);
                }
                add(out, unit, hr, 50, "ignition_off", Map.of(), since, now);
                if (hash % 4 == 1) {
                    add(out, unit, hr, 55, "idling", Map.of("minutes", 12), since, now);
                }
            }
        }
        out.sort(Comparator.comparing(TelematicsEvent::at).thenComparing(TelematicsEvent::externalId));
        return out;
    }

    private static void add(List<TelematicsEvent> out, String unit, long hour, int minute, String type,
            Map<String, Object> extra, Instant since, Instant now) {
        Instant at = Instant.ofEpochSecond(hour * 3600 + minute * 60L);
        if (!at.isAfter(since) || at.isAfter(now)) {
            return;
        }
        Position p = position(unit, Math.floorDiv(at.getEpochSecond(), GRID_SECONDS));
        Map<String, Object> data = new LinkedHashMap<>(extra);
        data.put("latitude", p.lat());
        data.put("longitude", p.lon());
        out.add(new TelematicsEvent(unit, type, at, data, unit + ":" + type + ":" + at.getEpochSecond()));
    }
}
