package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Telematics provider SPI (fleet 1.8, ADR-S009 §7). One Spring bean per vendor, selected by {@link #type()} (devices
 * store their provider type; new devices default to {@code app.fleet.telematics.provider}). Implementations: the
 * deterministic {@link MockTelematicsProvider} (tests / demo); vendor adapters later (base URL / API key from
 * {@code FLEET_TELEMATICS_URL} / {@code FLEET_TELEMATICS_API_KEY}, never logged).
 *
 * <p>The sync job pulls positions per unit, the current meters per unit and the events of all units since a cursor;
 * re-pulling a window is safe because positions and events carry the provider's {@code externalId}.
 */
public interface TelematicsProvider {

    String type();

    /** GPS fixes of a unit within [{@code from}, {@code to}), oldest first. */
    List<Position> positions(String externalUnitId, Instant from, Instant to);

    /** Current odometer (km) / engine hours of a unit, when the unit reports them. */
    Optional<MeterSnapshot> meters(String externalUnitId);

    /** Events (ignition on / off, speeding, idling, geofence, harsh driving) of every unit after {@code since}. */
    List<TelematicsEvent> events(Instant since);

    record Position(String externalUnitId, Instant at, double lat, double lon, BigDecimal speedKmh,
            String externalId) {}

    record MeterSnapshot(BigDecimal odometerKm, BigDecimal engineHours, Instant at) {}

    record TelematicsEvent(String externalUnitId, String type, Instant at, Map<String, Object> data,
            String externalId) {}
}
