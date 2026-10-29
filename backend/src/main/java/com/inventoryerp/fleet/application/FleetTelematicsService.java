package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetTelematicsCommands.DeviceInput;
import com.inventoryerp.fleet.application.FleetTelematicsCommands.DevicePatch;
import com.inventoryerp.fleet.application.FleetTelematicsCommands.EventQuery;
import com.inventoryerp.fleet.application.TelematicsProvider.MeterSnapshot;
import com.inventoryerp.fleet.application.TelematicsProvider.Position;
import com.inventoryerp.fleet.application.TelematicsProvider.TelematicsEvent;
import com.inventoryerp.fleet.domain.FleetTelematicsDevice;
import com.inventoryerp.fleet.domain.FleetTelematicsEvent;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Telematics (fleet 1.8, ADR-S009 §7): provider units mapped to vehicles, the provider sync (positions, odometer /
 * engine-hour readings through {@link FleetMeterService#recordAutomated} with source {@code telematics}, ignition
 * events and alerts), the position / distance queries and alert acknowledgement.
 *
 * <p><b>Public API for dispatch (1.3)</b>: {@link #positionsBetween}, {@link #distanceKm}, {@link #lastKnownPosition}
 * (trip distance, proof-of-delivery location).
 *
 * <p>Sync is idempotent: positions and events carry the provider's external id (unique per device), a meter snapshot
 * is recorded once per device and snapshot time. Each device's steps run in their own transaction, so one failing
 * device or meter (e.g. a provider odometer below a manual reading) never blocks the others; the outcome is kept on
 * the device ({@code lastSyncStatus}, {@code lastError}).
 */
@Service
public class FleetTelematicsService {

    private static final Logger log = LoggerFactory.getLogger(FleetTelematicsService.class);

    /** First sync of a device looks back this far; later syncs continue from the cursor (capped at the max). */
    static final Duration FIRST_LOOKBACK = Duration.ofHours(1);
    static final Duration MAX_LOOKBACK = Duration.ofHours(24);
    static final int MAX_POINTS = 10_000;
    private static final int PURGE_BATCH = 5_000;

    private static final Set<String> TYPES = Set.of("ignition_on", "ignition_off", "speeding", "idling",
            "geofence_enter", "geofence_exit", "harsh_braking", "harsh_acceleration", "other");
    private static final Set<String> ALERTS = Set.of("speeding", "idling", "geofence_enter", "geofence_exit",
            "harsh_braking", "harsh_acceleration");

    /** A GPS fix of a vehicle (public API record). */
    public record PositionPoint(Instant at, BigDecimal latitude, BigDecimal longitude, BigDecimal speedKmh,
            Boolean ignition) {}

    /** Outcome of one sync run of an organization. */
    public record SyncResult(int devices, int positions, int events, int alerts, int meterReadings, int errors,
            int purged) {}

    private final FleetDb db;
    private final FleetVehicleService vehicles;
    private final FleetMeterService meters;
    private final TelematicsProviders providers;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate tx;

    public FleetTelematicsService(FleetDb db, FleetVehicleService vehicles, FleetMeterService meters,
            TelematicsProviders providers, AuditService audit, EventBus events, PlatformTransactionManager txm) {
        this.db = db;
        this.vehicles = vehicles;
        this.meters = meters;
        this.providers = providers;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
    }

    // ------------------------------------------------------------------ settings + devices

    /** Provider configuration as the settings screen shows it (never the URL or key themselves). */
    public Map<String, Object> settings() {
        return Obj.of("provider", providers.configured(), "available", providers.available(),
                "baseUrlConfigured", providers.baseUrlConfigured(), "apiKeyConfigured", providers.apiKeyConfigured(),
                "retentionDays", providers.retentionDays(), "syncIntervalMinutes", 5);
    }

    /** Devices with their vehicle, newest first ({@code includeInactive} adds the deactivated ones). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> devices(boolean includeInactive, String vehicleId) {
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (!includeInactive) {
            where.append(" AND d.\"isActive\"");
        }
        FleetIncidentService.eq(where, params, "d.\"vehicleId\"", vehicleId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT d.*, v.code AS \"v_code\","
                + " v.\"registrationPlate\" AS \"v_plate\", v.name AS \"v_name\" FROM \"FleetTelematicsDevice\" d"
                + " LEFT JOIN \"FleetVehicle\" v ON v.id = d.\"vehicleId\"" + where
                + " ORDER BY d.\"isActive\" DESC, v.code NULLS LAST, d.\"externalUnitId\"").params(params))) {
            out.add(deviceShape(r));
        }
        return out;
    }

    private static Map<String, Object> deviceShape(Map<String, Object> r) {
        Map<String, Object> d = SqlRows.as(FleetTelematicsDevice.class, r);
        d.put("vehicle", r.get("vehicleId") == null ? null : Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"),
                "registrationPlate", r.get("v_plate"), "name", r.get("v_name")));
        return d;
    }

    /** Map a provider unit to a vehicle (one active device per unit and per vehicle). */
    @Transactional
    public Map<String, Object> createDevice(DeviceInput in) {
        String provider = FleetVehicleService.notBlank(in.provider()) ? in.provider().trim() : providers.configured();
        if (providers.get(provider).isEmpty()) {
            throw ApiException.badRequest("Telematics provider " + provider + " is not available (available: "
                    + String.join(", ", providers.available()) + ")");
        }
        String unit = in.externalUnitId().trim();
        String vehicleId = FleetIncidentService.blank(in.vehicleId());
        assertMapping(null, provider, unit, vehicleId);
        Map<String, Object> row = db.insert(FleetTelematicsDevice.class, Obj.of(
                "vehicleId", vehicleId,
                "provider", provider,
                "externalUnitId", unit,
                "name", FleetVehicleService.trim(in.name()),
                "imei", FleetVehicleService.trim(in.imei()),
                "simNumber", FleetVehicleService.trim(in.simNumber()),
                "isActive", true,
                "syncOdometer", in.syncOdometer() == null || in.syncOdometer(),
                "syncHours", in.syncHours() == null || in.syncHours(),
                "notes", FleetVehicleService.trim(in.notes()),
                "createdBy", TenantContext.userId(),
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetTelematicsDevice", (String) row.get("id"), "create", null, row));
        return device((String) row.get("id"));
    }

    /** Re-map, rename, (de)activate a device. */
    @Transactional
    public Map<String, Object> updateDevice(String id, DevicePatch in) {
        Map<String, Object> before = db.require(FleetTelematicsDevice.class, id, true, "Telematics device not found");
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!FleetTelematicsCommands.DEVICE_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        FleetIncidentService.put(data, "vehicleId", FleetIncidentService.blank(in.vehicleId()));
        FleetIncidentService.put(data, "externalUnitId", FleetVehicleService.trim(in.externalUnitId()));
        FleetIncidentService.put(data, "name", FleetVehicleService.trim(in.name()));
        FleetIncidentService.put(data, "imei", FleetVehicleService.trim(in.imei()));
        FleetIncidentService.put(data, "simNumber", FleetVehicleService.trim(in.simNumber()));
        FleetIncidentService.put(data, "isActive", in.isActive());
        FleetIncidentService.put(data, "syncOdometer", in.syncOdometer());
        FleetIncidentService.put(data, "syncHours", in.syncHours());
        FleetIncidentService.put(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return device(id);
        }
        Map<String, Object> merged = new LinkedHashMap<>(before);
        merged.putAll(data);
        if (Boolean.TRUE.equals(merged.get("isActive"))) {
            assertMapping(id, (String) merged.get("provider"), (String) merged.get("externalUnitId"),
                    (String) merged.get("vehicleId"));
        }
        if (data.containsKey("vehicleId") && !java.util.Objects.equals(data.get("vehicleId"), before.get("vehicleId"))) {
            // A re-mapped unit starts a fresh history on its new vehicle.
            data.put("positionsSyncedTo", null);
            data.put("eventsSyncedTo", null);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetTelematicsDevice.class, id, data);
        audit.recordInTx(AuditInput.of("FleetTelematicsDevice", id, "update", before, after));
        return device(id);
    }

    /** Delete a device without data; one that synced positions / events is deactivated instead. */
    @Transactional
    public Map<String, Object> deleteDevice(String id) {
        Map<String, Object> d = db.require(FleetTelematicsDevice.class, id, true, "Telematics device not found");
        String org = FleetDb.org();
        long data = db.jdbc().sql("SELECT (SELECT count(*) FROM \"FleetPosition\" WHERE \"organizationId\" = ? AND"
                + " \"deviceId\" = ?) + (SELECT count(*) FROM \"FleetTelematicsEvent\" WHERE \"organizationId\" = ?"
                + " AND \"deviceId\" = ?)").params(org, id, org, id).query(Long.class).single();
        if (data > 0) {
            db.update(FleetTelematicsDevice.class, id, Obj.of("isActive", false, "updatedBy", TenantContext.userId()));
            audit.recordInTx(AuditInput.of("FleetTelematicsDevice", id, "update", d, Obj.of("isActive", false)));
            return Obj.of("id", id, "deleted", false, "deactivated", true);
        }
        db.jdbc().sql("DELETE FROM \"FleetTelematicsDevice\" WHERE id = ? AND \"organizationId\" = ?").params(id, org)
                .update();
        audit.recordInTx(AuditInput.of("FleetTelematicsDevice", id, "delete", d, null));
        return Obj.of("id", id, "deleted", true, "deactivated", false);
    }

    private Map<String, Object> device(String id) {
        return deviceShape(SqlRows.one(db.jdbc().sql("SELECT d.*, v.code AS \"v_code\", v.\"registrationPlate\" AS"
                + " \"v_plate\", v.name AS \"v_name\" FROM \"FleetTelematicsDevice\" d LEFT JOIN \"FleetVehicle\" v ON"
                + " v.id = d.\"vehicleId\" WHERE d.id = ? AND d.\"organizationId\" = ?").params(id, FleetDb.org())));
    }

    private void assertMapping(String exceptId, String provider, String unit, String vehicleId) {
        String org = FleetDb.org();
        List<Object> params = new ArrayList<>(List.of(org, provider, unit));
        if (exceptId != null) {
            params.add(exceptId);
        }
        long dup = db.jdbc().sql("SELECT count(*) FROM \"FleetTelematicsDevice\" WHERE \"organizationId\" = ? AND"
                + " provider = ? AND \"externalUnitId\" = ? AND \"isActive\"" + (exceptId == null ? "" : " AND id <> ?"))
                .params(params).query(Long.class).single();
        if (dup > 0) {
            throw ApiException.conflict("Unit " + unit + " of provider " + provider + " is already registered");
        }
        if (vehicleId == null) {
            return;
        }
        Map<String, Object> v = vehicles.require(vehicleId, false);
        if (VehicleStatusPolicy.DISPOSED.equals(v.get("status"))) {
            throw ApiException.badRequest("Vehicle " + v.get("code") + " is disposed");
        }
        List<Object> p2 = new ArrayList<>(List.of(org, vehicleId));
        if (exceptId != null) {
            p2.add(exceptId);
        }
        String other = db.jdbc().sql("SELECT \"externalUnitId\" FROM \"FleetTelematicsDevice\" WHERE \"organizationId\""
                + " = ? AND \"vehicleId\" = ? AND \"isActive\"" + (exceptId == null ? "" : " AND id <> ?") + " LIMIT 1")
                .params(p2).query(String.class).optional().orElse(null);
        if (other != null) {
            throw ApiException.conflict("Vehicle " + v.get("code") + " already has telematics unit " + other);
        }
    }

    // ------------------------------------------------------------------ sync

    /** Sync the caller's organization now (settings screen "Sync now"). */
    public SyncResult syncNow() {
        return syncOrganization(Instant.now());
    }

    /**
     * Pull every active, mapped device of the current organization up to {@code now}: positions, meter snapshot,
     * then the events of each provider since the oldest device cursor; finally purge positions past retention.
     */
    public SyncResult syncOrganization(Instant now) {
        String org = FleetDb.org();
        List<Map<String, Object>> devices = tx.execute(s -> SqlRows.list(FleetTelematicsDevice.class, db.jdbc().sql(
                "SELECT * FROM \"FleetTelematicsDevice\" WHERE \"organizationId\" = ? AND \"isActive\" AND"
                        + " \"vehicleId\" IS NOT NULL ORDER BY \"externalUnitId\"").params(org)));
        int positions = 0;
        int readings = 0;
        int errors = 0;
        Map<String, List<String>> errorsByDevice = new HashMap<>();
        Map<String, List<Map<String, Object>>> byProvider = new LinkedHashMap<>();
        for (Map<String, Object> d : devices) {
            String id = (String) d.get("id");
            List<String> problems = errorsByDevice.computeIfAbsent(id, k -> new ArrayList<>());
            Optional<TelematicsProvider> provider = providers.get((String) d.get("provider"));
            if (provider.isEmpty()) {
                problems.add("Provider " + d.get("provider") + " is not available");
                continue;
            }
            byProvider.computeIfAbsent((String) d.get("provider"), k -> new ArrayList<>()).add(d);
            try {
                Integer n = tx.execute(s -> syncPositions(d, provider.get(), now));
                positions += n == null ? 0 : n;
            } catch (RuntimeException e) {
                problems.add("Positions: " + e.getMessage());
            }
            try {
                Optional<MeterSnapshot> snap = provider.get().meters((String) d.get("externalUnitId"));
                if (snap.isPresent()) {
                    readings += syncMeters(d, snap.get(), problems);
                }
            } catch (RuntimeException e) {
                problems.add("Meters: " + e.getMessage());
            }
        }
        int[] ev = {0, 0};
        for (Map.Entry<String, List<Map<String, Object>>> e : byProvider.entrySet()) {
            try {
                int[] n = tx.execute(s -> syncEvents(e.getValue(), providers.get(e.getKey()).orElseThrow(), now));
                if (n != null) {
                    ev[0] += n[0];
                    ev[1] += n[1];
                }
            } catch (RuntimeException ex) {
                e.getValue().forEach(d -> errorsByDevice.get((String) d.get("id")).add("Events: " + ex.getMessage()));
            }
        }
        for (Map<String, Object> d : devices) {
            List<String> problems = errorsByDevice.getOrDefault((String) d.get("id"), List.of());
            errors += problems.size();
            String status = problems.isEmpty() ? "ok" : problems.size() >= 2 ? "error" : "partial";
            String message = problems.isEmpty() ? null : String.join("; ", problems);
            tx.executeWithoutResult(s -> db.update(FleetTelematicsDevice.class, (String) d.get("id"), Obj.of(
                    "lastSyncAt", now, "lastSyncStatus", status, "lastError",
                    message == null ? null : message.substring(0, Math.min(1000, message.length())))));
            if (message != null) {
                log.warn("Telematics sync of device {} ({}): {}", d.get("id"), d.get("externalUnitId"), message);
            }
        }
        Integer purged = tx.execute(s -> db.jdbc().sql("DELETE FROM \"FleetPosition\" WHERE id IN (SELECT id FROM"
                        + " \"FleetPosition\" WHERE \"organizationId\" = ? AND \"recordedAt\" < ? LIMIT " + PURGE_BATCH
                        + ")").params(org, SqlRows.ts(now.minus(Duration.ofDays(providers.retentionDays()))))
                .update());
        return new SyncResult(devices.size(), positions, ev[0], ev[1], readings, errors, purged == null ? 0 : purged);
    }

    private int syncPositions(Map<String, Object> d, TelematicsProvider provider, Instant now) {
        String deviceId = (String) d.get("id");
        String vehicleId = (String) d.get("vehicleId");
        Instant from = cursor((Instant) d.get("positionsSyncedTo"), now);
        int inserted = 0;
        Position latest = null;
        for (Position p : provider.positions((String) d.get("externalUnitId"), from, now)) {
            if (p.at().isAfter(now) || Double.isNaN(p.lat()) || Math.abs(p.lat()) > 90 || Math.abs(p.lon()) > 180) {
                continue;
            }
            inserted += db.jdbc().sql("INSERT INTO \"FleetPosition\" (id, \"organizationId\", \"vehicleId\","
                            + " \"deviceId\", \"recordedAt\", latitude, longitude, \"speedKmh\", \"externalId\","
                            + " \"receivedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, " + FleetDb.NOW + ") ON CONFLICT"
                            + " (\"deviceId\", \"externalId\") WHERE \"externalId\" IS NOT NULL DO NOTHING")
                    .params(UUID.randomUUID().toString(), FleetDb.org(), vehicleId, deviceId, SqlRows.ts(p.at()),
                            coord(p.lat()), coord(p.lon()), p.speedKmh(), p.externalId())
                    .update();
            if (latest == null || p.at().isAfter(latest.at())) {
                latest = p;
            }
        }
        Map<String, Object> data = Obj.of("positionsSyncedTo", now);
        Instant last = (Instant) d.get("lastPositionAt");
        if (latest != null && (last == null || latest.at().isAfter(last))) {
            data.put("lastPositionAt", latest.at());
            data.put("lastLatitude", coord(latest.lat()));
            data.put("lastLongitude", coord(latest.lon()));
            data.put("lastSpeedKmh", latest.speedKmh());
        }
        db.update(FleetTelematicsDevice.class, deviceId, data);
        return inserted;
    }

    /** Odometer / hours of the snapshot through the meter service (each in its own transaction). */
    private int syncMeters(Map<String, Object> d, MeterSnapshot snap, List<String> problems) {
        String deviceId = (String) d.get("id");
        String vehicleId = (String) d.get("vehicleId");
        Map<String, Object> v = tx.execute(s -> vehicles.require(vehicleId, false));
        String meterType = (String) v.get("meterType");
        Instant at = snap.at();
        int n = 0;
        if (Boolean.TRUE.equals(d.get("syncOdometer")) && snap.odometerKm() != null
                && MeterReadingPolicy.tracks(meterType, "odometer")) {
            n += reading(vehicleId, "odometer", snap.odometerKm(), at, "tm:" + deviceId + ":odo:" + at.toEpochMilli(),
                    problems);
        }
        if (Boolean.TRUE.equals(d.get("syncHours")) && snap.engineHours() != null
                && MeterReadingPolicy.tracks(meterType, "hours")) {
            n += reading(vehicleId, "hours", snap.engineHours(), at, "tm:" + deviceId + ":hrs:" + at.toEpochMilli(),
                    problems);
        }
        tx.executeWithoutResult(s -> db.update(FleetTelematicsDevice.class, deviceId, Obj.of(
                "lastOdometerKm", snap.odometerKm(), "lastEngineHours", snap.engineHours(), "lastMeterAt", at)));
        return n;
    }

    private int reading(String vehicleId, String meterType, BigDecimal value, Instant at, String sourceId,
            List<String> problems) {
        try {
            Boolean created = tx.execute(s -> {
                boolean existed = db.jdbc().sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"organizationId\" ="
                        + " ? AND \"vehicleId\" = ? AND \"meterType\" = ? AND source = 'telematics' AND \"sourceId\" = ?")
                        .params(FleetDb.org(), vehicleId, meterType, sourceId).query(Long.class).single() > 0;
                meters.recordAutomated(vehicleId, meterType, value.setScale(1, RoundingMode.HALF_UP), at,
                        "telematics", sourceId, "Telematics sync");
                return !existed;
            });
            return Boolean.TRUE.equals(created) ? 1 : 0;
        } catch (RuntimeException e) {
            problems.add(("hours".equals(meterType) ? "Engine hours: " : "Odometer: ") + e.getMessage());
            return 0;
        }
    }

    /** Events of {@code devices} (one provider) since their oldest cursor; returns {inserted, alerts}. */
    private int[] syncEvents(List<Map<String, Object>> devices, TelematicsProvider provider, Instant now) {
        Instant since = null;
        Map<String, Map<String, Object>> byUnit = new HashMap<>();
        for (Map<String, Object> d : devices) {
            Instant c = cursor((Instant) d.get("eventsSyncedTo"), now);
            since = since == null || c.isBefore(since) ? c : since;
            byUnit.put((String) d.get("externalUnitId"), d);
        }
        int inserted = 0;
        int alerts = 0;
        Map<String, TelematicsEvent> lastIgnition = new HashMap<>();
        for (TelematicsEvent e : provider.events(since)) {
            Map<String, Object> d = byUnit.get(e.externalUnitId());
            if (d == null || e.at().isAfter(now)) {
                continue;
            }
            String type = TYPES.contains(e.type()) ? e.type() : "other";
            Map<String, Object> data = new LinkedHashMap<>(e.data() == null ? Map.of() : e.data());
            if ("other".equals(type)) {
                data.put("type", e.type());
            }
            boolean alert = ALERTS.contains(type);
            String id = UUID.randomUUID().toString();
            int n = db.jdbc().sql("INSERT INTO \"FleetTelematicsEvent\" (id, \"organizationId\", \"vehicleId\","
                            + " \"deviceId\", \"eventType\", \"occurredAt\", latitude, longitude, \"speedKmh\", data,"
                            + " \"externalId\", \"isAlert\", severity, \"receivedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?::jsonb, ?, ?, ?, " + FleetDb.NOW + ") ON CONFLICT (\"deviceId\", \"externalId\")"
                            + " WHERE \"externalId\" IS NOT NULL DO NOTHING")
                    .params(id, FleetDb.org(), d.get("vehicleId"), d.get("id"), type, SqlRows.ts(e.at()),
                            number(data.get("latitude"), 6), number(data.get("longitude"), 6),
                            number(data.get("speedKmh"), 1), EntityJson.toJson(data), e.externalId(), alert,
                            severity(type, data))
                    .update();
            inserted += n;
            if (n > 0 && alert) {
                alerts++;
                events.publish(DomainEvents.FLEET_TELEMATICS_ALERT_RAISED, Obj.of("organizationId", FleetDb.org(),
                        "vehicleId", d.get("vehicleId"), "eventId", id, "eventType", type, "severity",
                        severity(type, data), "occurredAt", e.at().toString()));
            }
            if (type.startsWith("ignition_")) {
                TelematicsEvent prev = lastIgnition.get(e.externalUnitId());
                if (prev == null || e.at().isAfter(prev.at())) {
                    lastIgnition.put(e.externalUnitId(), e);
                }
            }
        }
        for (Map<String, Object> d : devices) {
            Map<String, Object> data = Obj.of("eventsSyncedTo", now);
            TelematicsEvent ign = lastIgnition.get((String) d.get("externalUnitId"));
            if (ign != null) {
                data.put("lastIgnition", "ignition_on".equals(ign.type()));
            }
            db.update(FleetTelematicsDevice.class, (String) d.get("id"), data);
        }
        return new int[] {inserted, alerts};
    }

    static String severity(String type, Map<String, Object> data) {
        return switch (type) {
            case "speeding" -> {
                BigDecimal speed = number(data.get("speedKmh"), 1);
                BigDecimal limit = number(data.get("limitKmh"), 1);
                yield speed != null && limit != null && speed.subtract(limit).compareTo(BigDecimal.valueOf(30)) >= 0
                        ? "critical" : "warning";
            }
            case "harsh_braking", "harsh_acceleration", "geofence_exit" -> "warning";
            default -> "info";
        };
    }

    private static Instant cursor(Instant syncedTo, Instant now) {
        Instant floor = now.minus(MAX_LOOKBACK);
        Instant from = syncedTo == null ? now.minus(FIRST_LOOKBACK) : syncedTo;
        return from.isBefore(floor) ? floor : from;
    }

    private static BigDecimal coord(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal number(Object v, int scale) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.toString()).setScale(scale, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ positions (public API for 1.3)

    /** Positions of a vehicle in [{@code from}, {@code to}], oldest first (at most {@value #MAX_POINTS}). */
    @Transactional(readOnly = true)
    public List<PositionPoint> positionsBetween(String vehicleId, Instant from, Instant to) {
        vehicles.require(vehicleId, false);
        List<PositionPoint> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT \"recordedAt\", latitude, longitude,"
                        + " \"speedKmh\", ignition FROM \"FleetPosition\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ?"
                        + " AND \"recordedAt\" >= ? AND \"recordedAt\" <= ? ORDER BY \"recordedAt\" LIMIT " + MAX_POINTS)
                .params(FleetDb.org(), vehicleId, SqlRows.ts(from), SqlRows.ts(to)))) {
            out.add(point(r));
        }
        return out;
    }

    /** Distance driven by a vehicle in [{@code from}, {@code to}] from its GPS positions ({@link GeoDistance}). */
    @Transactional(readOnly = true)
    public BigDecimal distanceKm(String vehicleId, Instant from, Instant to) {
        List<GeoDistance.Fix> fixes = new ArrayList<>();
        for (PositionPoint p : positionsBetween(vehicleId, from, to)) {
            fixes.add(new GeoDistance.Fix(p.at(), p.latitude().doubleValue(), p.longitude().doubleValue()));
        }
        return GeoDistance.pathKm(fixes);
    }

    /** The newest position of a vehicle, if any. */
    @Transactional(readOnly = true)
    public Optional<PositionPoint> lastKnownPosition(String vehicleId) {
        vehicles.require(vehicleId, false);
        Map<String, Object> r = SqlRows.one(db.jdbc().sql("SELECT \"recordedAt\", latitude, longitude, \"speedKmh\","
                + " ignition FROM \"FleetPosition\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ? ORDER BY"
                + " \"recordedAt\" DESC LIMIT 1").params(FleetDb.org(), vehicleId));
        return Optional.ofNullable(r == null ? null : point(r));
    }

    /** {@code {vehicleId, position}} for the screens ({@code position} null when the vehicle never reported). */
    @Transactional(readOnly = true)
    public Map<String, Object> lastPosition(String vehicleId) {
        return Obj.of("vehicleId", vehicleId, "position", lastKnownPosition(vehicleId).orElse(null));
    }

    private static PositionPoint point(Map<String, Object> r) {
        return new PositionPoint((Instant) r.get("recordedAt"), (BigDecimal) r.get("latitude"),
                (BigDecimal) r.get("longitude"), (BigDecimal) r.get("speedKmh"), (Boolean) r.get("ignition"));
    }

    /** Paged positions of a vehicle in a period (default the last 24 h), newest first. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> positions(String vehicleId, Instant from, Instant to, Integer page,
            Integer pageSize) {
        vehicles.require(vehicleId, false);
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 50 : Math.max(1, Math.min(500, pageSize));
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), vehicleId, SqlRows.ts(start), SqlRows.ts(end)));
        String where = " WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND \"recordedAt\" >= ? AND"
                + " \"recordedAt\" <= ?";
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetPosition\"" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((p - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT id, \"deviceId\", \"recordedAt\", latitude,"
                + " longitude, \"speedKmh\", ignition FROM \"FleetPosition\"" + where
                + " ORDER BY \"recordedAt\" DESC LIMIT ? OFFSET ?").params(params));
        return PageResult.of(rows, p, size, total);
    }

    /** Distance summary of a period for the screens: km, fixes, first / last fix. */
    @Transactional(readOnly = true)
    public Map<String, Object> distance(String vehicleId, Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("from must be before to");
        }
        List<PositionPoint> points = positionsBetween(vehicleId, start, end);
        List<GeoDistance.Fix> fixes = new ArrayList<>();
        points.forEach(p -> fixes.add(new GeoDistance.Fix(p.at(), p.latitude().doubleValue(),
                p.longitude().doubleValue())));
        return Obj.of("vehicleId", vehicleId, "from", start, "to", end, "distanceKm", GeoDistance.pathKm(fixes),
                "points", points.size(), "first", points.isEmpty() ? null : points.getFirst(),
                "last", points.isEmpty() ? null : points.getLast());
    }

    /** Last known position of every vehicle with an active device (live list), with open alerts per vehicle. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> latest() {
        String org = FleetDb.org();
        return SqlRows.list(db.jdbc().sql("""
                SELECT d.id AS "deviceId", d."externalUnitId", d.provider, d."lastPositionAt", d."lastLatitude",
                       d."lastLongitude", d."lastSpeedKmh", d."lastIgnition", d."lastSyncAt", d."lastSyncStatus",
                       d."lastError", d."lastOdometerKm", d."lastEngineHours", v.id AS "vehicleId", v.code,
                       v."registrationPlate", v.name, v.status,
                       (SELECT count(*) FROM "FleetTelematicsEvent" e WHERE e."organizationId" = d."organizationId"
                          AND e."vehicleId" = v.id AND e."isAlert" AND e."acknowledgedAt" IS NULL) AS "openAlerts"
                FROM "FleetTelematicsDevice" d JOIN "FleetVehicle" v ON v.id = d."vehicleId"
                WHERE d."organizationId" = ? AND d."isActive" AND v."deletedAt" IS NULL
                ORDER BY v.code""").params(org));
    }

    // ------------------------------------------------------------------ events / alerts

    /** Paged telematics events (ignition, alerts) with the vehicle, newest first. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> events(EventQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE e.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        FleetIncidentService.eq(where, params, "e.\"vehicleId\"", q.vehicleId());
        FleetIncidentService.eq(where, params, "e.\"eventType\"", q.eventType());
        if (Boolean.TRUE.equals(q.alertsOnly())) {
            where.append(" AND e.\"isAlert\"");
        }
        if (Boolean.TRUE.equals(q.open())) {
            where.append(" AND e.\"isAlert\" AND e.\"acknowledgedAt\" IS NULL");
        }
        if (q.from() != null) {
            where.append(" AND e.\"occurredAt\" >= ?");
            params.add(SqlRows.ts(q.from()));
        }
        if (q.to() != null) {
            where.append(" AND e.\"occurredAt\" <= ?");
            params.add(SqlRows.ts(q.to()));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetTelematicsEvent\" e" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT e.*, v.code AS \"v_code\","
                + " v.\"registrationPlate\" AS \"v_plate\", u.\"firstName\" AS \"u_first\", u.\"lastName\" AS \"u_last\""
                + " FROM \"FleetTelematicsEvent\" e JOIN \"FleetVehicle\" v ON v.id = e.\"vehicleId\""
                + " LEFT JOIN \"User\" u ON u.id = e.\"acknowledgedBy\"" + where
                + " ORDER BY e.\"occurredAt\" DESC, e.id LIMIT ? OFFSET ?").params(params))) {
            Map<String, Object> m = SqlRows.as(FleetTelematicsEvent.class, r);
            m.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "registrationPlate",
                    r.get("v_plate")));
            m.put("acknowledgedByName", r.get("acknowledgedBy") == null ? null
                    : ((r.get("u_first") == null ? "" : r.get("u_first")) + " "
                            + (r.get("u_last") == null ? "" : r.get("u_last"))).trim());
            rows.add(m);
        }
        return PageResult.of(rows, page, size, total);
    }

    /** Acknowledge an alert (ignition events need none). */
    @Transactional
    public Map<String, Object> acknowledge(String eventId, String note) {
        Map<String, Object> e = db.require(FleetTelematicsEvent.class, eventId, true, "Telematics event not found");
        if (!Boolean.TRUE.equals(e.get("isAlert"))) {
            throw ApiException.badRequest("Only alerts need an acknowledgement");
        }
        if (e.get("acknowledgedAt") != null) {
            throw ApiException.badRequest("This alert was already acknowledged");
        }
        Map<String, Object> after = db.update(FleetTelematicsEvent.class, eventId, Obj.of("acknowledgedAt",
                Instant.now(), "acknowledgedBy", TenantContext.userId(), "acknowledgeNote",
                FleetVehicleService.trim(note)));
        audit.recordInTx(AuditInput.of("FleetTelematicsEvent", eventId, "update", Obj.of("acknowledgedAt", null),
                Obj.of("acknowledgedAt", after.get("acknowledgedAt"), "note", FleetVehicleService.trim(note))));
        return after;
    }
}
