package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.DriverCommands.CheckInInput;
import com.inventoryerp.fleet.application.DriverCommands.CheckOutInput;
import com.inventoryerp.fleet.application.DriverCommands.HistoryQuery;
import com.inventoryerp.fleet.domain.FleetCheckout;
import com.inventoryerp.fleet.domain.FleetCheckoutPhoto;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicle check-out / check-in (fleet 1.2, ADR-S009): the driver takes the vehicle with its odometer / hour-meter,
 * fuel level, condition, damage flags and photos, and brings it back the same way. The meters are recorded through
 * {@link FleetMeterService#recordAutomated} ({@code source = check_out / check_in}, idempotent per check-out), so the
 * monotonic meter rule applies. Check-out runs the {@link DriverEligibilityPolicy}.
 *
 * <p>Concurrency: one open check-out per vehicle and per driver. The vehicle row and then the driver row are locked
 * {@code FOR UPDATE} before the open check-outs are read, and partial unique indexes back the rule up.
 */
@Service
public class FleetCheckoutService {

    static final String AUDIT_ENTITY = "FleetCheckout";
    private static final Duration SKEW = Duration.ofMinutes(5);

    private final FleetDb db;
    private final FleetDriverService drivers;
    private final FleetAssignmentService assignments;
    private final FleetMeterService meters;
    private final FleetVehicleService vehicles;
    private final FleetAttachmentService attachments;
    private final FilesService files;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public FleetCheckoutService(FleetDb db, FleetDriverService drivers, FleetAssignmentService assignments,
            FleetMeterService meters, FleetVehicleService vehicles, FleetAttachmentService attachments,
            FilesService files, SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.drivers = drivers;
        this.assignments = assignments;
        this.meters = meters;
        this.vehicles = vehicles;
        this.attachments = attachments;
        this.files = files;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    static final String SELECT = """
            SELECT c.*, v.code AS "vehicleCode", v."registrationPlate" AS "vehicleRegistrationPlate",
                   v.name AS "vehicleName", v."meterType" AS "vehicleMeterType", d.code AS "driverCode",
                   trim(coalesce(e."firstName", '') || ' ' || coalesce(e."lastName", '')) AS "driverName"
            FROM "FleetCheckout" c
            JOIN "FleetVehicle" v ON v.id = c."vehicleId"
            JOIN "FleetDriver" d ON d.id = c."driverId"
            LEFT JOIN "HrEmployee" e ON e.id = d."employeeId" AND e."organizationId" = d."organizationId"
            """;

    /** Check-out history, open first then newest (vehicle / driver / status filters). */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(HistoryQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        for (String[] f : new String[][] {{"c.\"vehicleId\"", q.vehicleId()}, {"c.\"driverId\"", q.driverId()},
            {"c.status", q.status()}}) {
            if (FleetVehicleService.notBlank(f[1])) {
                where.append(" AND ").append(f[0]).append(" = ?");
                params.add(f[1]);
            }
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetCheckout\" c" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY c.status DESC, c.\"checkedOutAt\" DESC, c.id LIMIT ? OFFSET ?").params(params)), page,
                size, total);
    }

    /** One check-out with its photos (signed download URLs). */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> c = SqlRows.one(db.jdbc().sql(SELECT + " WHERE c.id = ? AND c.\"organizationId\" = ?")
                .params(id, FleetDb.org()));
        if (c == null) {
            throw ApiException.notFound("Check-out not found");
        }
        List<Map<String, Object>> photos = new ArrayList<>();
        for (Map<String, Object> p : SqlRows.list(db.jdbc().sql("SELECT p.id, p.phase, p.\"attachmentId\", a.\"fileId\","
                + " a.title, f.filename, f.\"contentType\" FROM \"FleetCheckoutPhoto\" p JOIN \"FleetVehicleAttachment\" a"
                + " ON a.id = p.\"attachmentId\" JOIN \"File\" f ON f.id = a.\"fileId\" AND f.\"deletedAt\" IS NULL"
                + " WHERE p.\"organizationId\" = ? AND p.\"checkoutId\" = ? ORDER BY p.\"createdAt\"")
                .params(FleetDb.org(), id))) {
            Map<String, Object> out = new LinkedHashMap<>(p);
            out.put("url", files.signDownload((String) p.get("fileId")).get("url"));
            photos.add(out);
        }
        c.put("photos", photos);
        return c;
    }

    /** The open check-out of a vehicle (or null) — the check-in screen starts from it. */
    @Transactional(readOnly = true)
    public Map<String, Object> openForVehicle(String vehicleId) {
        db.require(FleetVehicle.class, vehicleId, false, "Vehicle not found");
        String id = db.jdbc().sql("SELECT id FROM \"FleetCheckout\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ?"
                + " AND status = 'open'").params(FleetDb.org(), vehicleId).query(String.class).optional().orElse(null);
        return id == null ? null : get(id);
    }

    /** Check a vehicle out to a driver. */
    @Transactional
    public Map<String, Object> checkOut(String vehicleId, CheckOutInput in) {
        DriverEligibilityPolicy.VehicleFacts vf = drivers.vehicleFacts(vehicleId, true);
        Map<String, Object> v = db.require(FleetVehicle.class, vehicleId, false, "Vehicle not found");
        Map<String, Object> d = drivers.require(in.driverId(), true);
        String org = FleetDb.org();
        Map<String, Object> busy = SqlRows.one(db.jdbc().sql("SELECT c.code, d.code AS \"driverCode\" FROM"
                + " \"FleetCheckout\" c JOIN \"FleetDriver\" d ON d.id = c.\"driverId\" WHERE c.\"organizationId\" = ?"
                + " AND c.\"vehicleId\" = ? AND c.status = 'open'").params(org, vehicleId));
        if (busy != null) {
            throw ApiException.conflict("Vehicle " + vf.code() + " is already checked out to driver "
                    + busy.get("driverCode") + " (" + busy.get("code") + ")");
        }
        Map<String, Object> other = SqlRows.one(db.jdbc().sql("SELECT c.code, v.code AS \"vehicleCode\" FROM"
                + " \"FleetCheckout\" c JOIN \"FleetVehicle\" v ON v.id = c.\"vehicleId\" WHERE c.\"organizationId\" = ?"
                + " AND c.\"driverId\" = ? AND c.status = 'open'").params(org, in.driverId()));
        if (other != null) {
            throw ApiException.conflict("Driver " + d.get("code") + " already has vehicle " + other.get("vehicleCode")
                    + " checked out (" + other.get("code") + ")");
        }
        Instant at = in.checkedOutAt() == null ? Instant.now() : in.checkedOutAt();
        if (at.isAfter(Instant.now().plus(SKEW))) {
            throw ApiException.badRequest("The check-out time cannot be in the future");
        }
        DriverEligibilityPolicy.assertEligible(drivers.facts(d), vf, FleetAssignmentService.day(at), "checked out");
        assertMeters(v, in.odometer(), in.hours(), "check out");
        String code = sequence.next("fleet_checkout", "CHK-", 6);
        String tripId = FleetVehicleService.trim(in.tripId());
        if (tripId != null && !db.exists("FleetLoad", tripId, false)) {
            throw ApiException.badRequest("Trip not found");
        }
        Map<String, Object> row;
        try {
            row = db.insert(FleetCheckout.class, Obj.of(
                    "code", code,
                    "vehicleId", vehicleId,
                    "driverId", in.driverId(),
                    "assignmentId", assignments.covering(vehicleId, in.driverId(), at),
                    "status", "open",
                    "purpose", FleetVehicleService.trim(in.purpose()),
                    "tripId", FleetVehicleService.trim(in.tripId()),
                    "checkedOutAt", at,
                    "outOdometer", in.odometer(),
                    "outHours", in.hours(),
                    "outFuelLevelPct", in.fuelLevelPct(),
                    "outCondition", in.condition() == null ? "good" : in.condition(),
                    "outDamageFlags", flags(in.damageFlags()),
                    "outNotes", FleetVehicleService.trim(in.notes()),
                    "checkedOutBy", TenantContext.userId()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Vehicle " + vf.code() + " or driver " + d.get("code")
                    + " already has an open check-out");
        }
        String id = (String) row.get("id");
        recordMeters(vehicleId, in.odometer(), in.hours(), at, "check_out", id, "Check-out " + code);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, row));
        events.publish(DomainEvents.FLEET_VEHICLE_CHECKED_OUT, Obj.of("organizationId", org, "checkoutId", id,
                "vehicleId", vehicleId, "driverId", in.driverId(), "checkedOutAt", at.toString()));
        return get(id);
    }

    /** Check the vehicle back in; the meters must not be lower than at check-out (monotonic rule). */
    @Transactional
    public Map<String, Object> checkIn(String checkoutId, CheckInInput in) {
        Map<String, Object> peek = db.require(FleetCheckout.class, checkoutId, false, "Check-out not found");
        String vehicleId = (String) peek.get("vehicleId");
        Map<String, Object> v = db.require(FleetVehicle.class, vehicleId, true, "Vehicle not found");
        Map<String, Object> c = db.require(FleetCheckout.class, checkoutId, true, "Check-out not found");
        if (!"open".equals(c.get("status"))) {
            throw ApiException.badRequest("Check-out " + c.get("code") + " is already closed");
        }
        Instant at = in.checkedInAt() == null ? Instant.now() : in.checkedInAt();
        if (at.isAfter(Instant.now().plus(SKEW))) {
            throw ApiException.badRequest("The check-in time cannot be in the future");
        }
        if (at.isBefore((Instant) c.get("checkedOutAt"))) {
            throw ApiException.badRequest("The check-in time must be after the check-out time");
        }
        assertMeters(v, in.odometer(), in.hours(), "check in");
        Map<String, BigDecimal> used = recordMeters(vehicleId, in.odometer(), in.hours(), at, "check_in", checkoutId,
                "Check-in " + c.get("code"));
        Map<String, Object> after = db.update(FleetCheckout.class, checkoutId, Obj.of(
                "status", "closed",
                "checkedInAt", at,
                "inOdometer", in.odometer(),
                "inHours", in.hours(),
                "inFuelLevelPct", in.fuelLevelPct(),
                "inCondition", in.condition() == null ? "good" : in.condition(),
                "inDamageFlags", flags(in.damageFlags()),
                "inNotes", FleetVehicleService.trim(in.notes()),
                "checkedInBy", TenantContext.userId(),
                "distanceKm", used.get("odometer"),
                "hoursUsed", used.get("hours")));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, checkoutId, "update", c, after));
        if (Boolean.TRUE.equals(in.reportBreakdown()) && !VehicleStatusPolicy.BREAKDOWN.equals(v.get("status"))
                && !VehicleStatusPolicy.DISPOSED.equals(v.get("status"))) {
            String reason = FleetVehicleService.trim(in.notes());
            vehicles.changeStatus(vehicleId, VehicleStatusPolicy.BREAKDOWN,
                    reason == null ? "Reported at check-in " + c.get("code") : reason, "check_in", checkoutId);
        }
        events.publish(DomainEvents.FLEET_VEHICLE_CHECKED_IN, Obj.of("organizationId", FleetDb.org(),
                "checkoutId", checkoutId, "vehicleId", vehicleId, "driverId", c.get("driverId"),
                "checkedInAt", at.toString(), "distanceKm", plain(used.get("odometer")),
                "hoursUsed", plain(used.get("hours"))));
        return get(checkoutId);
    }

    /** Store a check-out / check-in photo as a vehicle attachment and link it to the check-out. */
    @Transactional
    public Map<String, Object> addPhoto(String checkoutId, String phase, String filename, String contentType,
            byte[] bytes) {
        Map<String, Object> c = db.require(FleetCheckout.class, checkoutId, false, "Check-out not found");
        String p = phase == null || phase.isBlank() ? ("open".equals(c.get("status")) ? "out" : "in") : phase;
        if (!List.of("out", "in").contains(p)) {
            throw ApiException.badRequest("phase must be one of the following values: out, in");
        }
        Map<String, Object> att = attachments.upload((String) c.get("vehicleId"), "photo",
                ("out".equals(p) ? "Check-out " : "Check-in ") + c.get("code"), null, filename, contentType, bytes);
        Map<String, Object> link = db.insert(FleetCheckoutPhoto.class, Obj.of("checkoutId", checkoutId,
                "attachmentId", att.get("id"), "phase", p));
        Map<String, Object> out = new LinkedHashMap<>(att);
        out.put("phase", p);
        out.put("photoId", link.get("id"));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** The tracked meters must be read at check-out / check-in. */
    private static void assertMeters(Map<String, Object> v, BigDecimal odometer, BigDecimal hours, String action) {
        String type = (String) v.get("meterType");
        if (MeterReadingPolicy.tracks(type, "odometer") && odometer == null) {
            throw ApiException.badRequest("Enter the odometer reading (km) to " + action + " vehicle " + v.get("code"));
        }
        if (MeterReadingPolicy.tracks(type, "hours") && hours == null) {
            throw ApiException.badRequest("Enter the engine hours to " + action + " vehicle " + v.get("code"));
        }
    }

    /**
     * Records the given meters for the check-out document; at check-in returns the lifetime distance / hours since
     * the check-out readings ({@code odometer} / {@code hours} keys, null when not read).
     */
    private Map<String, BigDecimal> recordMeters(String vehicleId, BigDecimal odometer, BigDecimal hours, Instant at,
            String source, String checkoutId, String notes) {
        Map<String, BigDecimal> used = new LinkedHashMap<>();
        for (Object[] m : new Object[][] {{"odometer", odometer}, {"hours", hours}}) {
            String meter = (String) m[0];
            BigDecimal value = (BigDecimal) m[1];
            if (value == null) {
                used.put(meter, null);
                continue;
            }
            Map<String, Object> reading = meters.recordAutomated(vehicleId, meter, value, at, source, checkoutId, notes);
            BigDecimal out = "check_in".equals(source) ? db.jdbc().sql("SELECT \"lifetimeValue\" FROM"
                    + " \"FleetMeterReading\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND \"meterType\" = ?"
                    + " AND source = 'check_out' AND \"sourceId\" = ? AND status = 'valid' LIMIT 1")
                    .params(FleetDb.org(), vehicleId, meter, checkoutId).query(BigDecimal.class).optional()
                    .orElse(null) : null;
            used.put(meter, out == null ? null : SqlRows.dec(reading, "lifetimeValue").subtract(out));
        }
        return used;
    }

    private static String[] flags(List<String> flags) {
        if (flags == null) {
            return new String[0];
        }
        for (String f : flags) {
            if (!DriverCommands.DAMAGE_FLAGS.contains(f)) {
                throw ApiException.badRequest("Unknown damage flag '" + f + "'; use one of "
                        + String.join(", ", DriverCommands.DAMAGE_FLAGS));
            }
        }
        return new LinkedHashSet<>(flags).toArray(String[]::new);
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
