package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetCommands.MeterCorrectionInput;
import com.inventoryerp.fleet.application.FleetCommands.MeterReadingInput;
import com.inventoryerp.fleet.application.MeterReadingPolicy.Neighbour;
import com.inventoryerp.fleet.domain.FleetMeterCorrection;
import com.inventoryerp.fleet.domain.FleetMeterReading;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.kernel.approvals.ApprovalAdminService;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Odometer (km) and hour-meter readings with the monotonic rule ({@link MeterReadingPolicy}) and the approval-gated
 * correction workflow (ADR-S009 §4). Public API for later sub-modules: {@link #recordAutomated} (trips, fuel logs,
 * check-in, work orders, telematics — idempotent per source document).
 *
 * <p>A correction (adjust a wrong reading, or record a meter replacement) is a rollback, so it goes through the
 * kernel approval engine as entity type {@value #APPROVAL_ENTITY}. When the organization has no workflow for it yet,
 * a default one is created (one step, approver permission {@code fleet_vehicle:approve_meter}; the engine refuses
 * self-approval). The decision is applied by {@link FleetApprovalSubscriber} (or {@link #sync}); an administrator who
 * deactivates the workflow makes corrections apply immediately, as the engine auto-approves without a workflow.
 */
@Service
public class FleetMeterService {

    public static final String APPROVAL_ENTITY = "fleet_meter_correction";
    static final String AUDIT_ENTITY = "FleetMeterReading";

    private final FleetDb db;
    private final ApprovalsService approvals;
    private final ApprovalAdminService approvalAdmin;
    private final AuditService audit;
    private final EventBus events;

    public FleetMeterService(FleetDb db, ApprovalsService approvals, ApprovalAdminService approvalAdmin,
            AuditService audit, EventBus events) {
        this.db = db;
        this.approvals = approvals;
        this.approvalAdmin = approvalAdmin;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ readings

    /** Readings of a vehicle, newest first ({@code includeSuperseded} adds the corrected originals). */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String vehicleId, String meterType, boolean includeSuperseded,
            Integer page, Integer pageSize) {
        vehicle(vehicleId, false);
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 20 : Math.max(1, Math.min(200, pageSize));
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ? AND r.\"vehicleId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), vehicleId));
        if (meterType != null && !meterType.isBlank()) {
            where.append(" AND r.\"meterType\" = ?");
            params.add(meterType);
        }
        if (!includeSuperseded) {
            where.append(" AND r.status = 'valid'");
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetMeterReading\" r" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((p - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT r.*, u.\"firstName\" AS"
                + " \"createdByFirstName\", u.\"lastName\" AS \"createdByLastName\" FROM \"FleetMeterReading\" r"
                + " LEFT JOIN \"User\" u ON u.id = r.\"createdBy\"" + where
                + " ORDER BY r.\"readingAt\" DESC, r.\"lifetimeValue\" DESC, r.id LIMIT ? OFFSET ?").params(params));
        return PageResult.of(rows, p, size, total);
    }

    /** Record a manual reading (screen / mobile): monotonic rule, audited. */
    @Transactional
    public Map<String, Object> record(String vehicleId, MeterReadingInput in) {
        Map<String, Object> reading = insertReading(vehicleId, in.meterType(), in.value(),
                in.readingAt() == null ? Instant.now() : in.readingAt(), "manual", null,
                FleetVehicleService.trim(in.notes()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) reading.get("id"), "create", null, reading));
        return reading;
    }

    /**
     * Record a reading captured by another fleet process ({@code source} = check_out, check_in, trip, fuel,
     * work_order, inspection, telematics, import) for its document {@code sourceId}. Idempotent: a second call for
     * the same source document returns the first reading. Same monotonic rule as a manual reading.
     */
    @Transactional
    public Map<String, Object> recordAutomated(String vehicleId, String meterType, BigDecimal value, Instant readingAt,
            String source, String sourceId, String notes) {
        if (sourceId != null) {
            Map<String, Object> existing = SqlRows.one(FleetMeterReading.class, db.jdbc().sql("SELECT * FROM"
                    + " \"FleetMeterReading\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND \"meterType\" = ?"
                    + " AND source = ? AND \"sourceId\" = ? ORDER BY \"createdAt\" LIMIT 1")
                    .params(FleetDb.org(), vehicleId, meterType, source, sourceId));
            if (existing != null) {
                return existing;
            }
        }
        return insertReading(vehicleId, meterType, value, readingAt == null ? Instant.now() : readingAt, source,
                sourceId, notes);
    }

    /**
     * Why the meter rule would refuse a reading of {@code value} at {@code at} (null = it would be accepted). Never
     * throws for a rule violation, so a caller can record the reading or keep its document and flag it instead (fuel
     * logs: anomaly {@code meter_not_increasing}). Same checks as {@link #recordAutomated}.
     */
    public String readingRejection(String vehicleId, String meterType, BigDecimal value, Instant at) {
        Map<String, Object> v = vehicle(vehicleId, false);
        try {
            if (VehicleStatusPolicy.DISPOSED.equals(v.get("status"))) {
                return "Vehicle " + v.get("code") + " is disposed; no further meter readings";
            }
            assertTracks(v, meterType);
            MeterReadingPolicy.assertNotFuture(at, Instant.now());
            MeterReadingPolicy.assertAfterReplacement(meterType, at, replacedAt(vehicleId, meterType));
            BigDecimal lifetime = value.add(SqlRows.dec(v, offsetColumn(meterType)));
            MeterReadingPolicy.assertMonotonic(meterType, value, lifetime, previous(vehicleId, meterType, at, null),
                    next(vehicleId, meterType, at, null));
            return null;
        } catch (ApiException e) {
            return e.getMessage();
        }
    }

    /** Opening reading of a vehicle being registered (same transaction as the vehicle insert). */
    public void recordOpening(String vehicleId, String meterType, BigDecimal value, Instant at) {
        insertReading(vehicleId, meterType, value, at, "opening", null, "Opening reading");
    }

    public boolean hasReadings(String vehicleId, String meterType) {
        return db.jdbc().sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"organizationId\" = ? AND"
                + " \"vehicleId\" = ? AND \"meterType\" = ?").params(FleetDb.org(), vehicleId, meterType)
                .query(Long.class).single() > 0;
    }

    /** Anything beyond the registration data: non-opening readings or corrections. */
    public boolean hasOperatingHistory(String vehicleId) {
        String org = FleetDb.org();
        return db.jdbc().sql("SELECT (SELECT count(*) FROM \"FleetMeterReading\" WHERE \"organizationId\" = ? AND"
                + " \"vehicleId\" = ? AND source <> 'opening') + (SELECT count(*) FROM \"FleetMeterCorrection\" WHERE"
                + " \"organizationId\" = ? AND \"vehicleId\" = ?)").params(org, vehicleId, org, vehicleId)
                .query(Long.class).single() > 0;
    }

    private Map<String, Object> insertReading(String vehicleId, String meterType, BigDecimal value, Instant at,
            String source, String sourceId, String notes) {
        Map<String, Object> v = vehicle(vehicleId, true);
        String code = (String) v.get("code");
        if (VehicleStatusPolicy.DISPOSED.equals(v.get("status")) && !"opening".equals(source)) {
            throw ApiException.badRequest("Vehicle " + code + " is disposed; no further meter readings");
        }
        assertTracks(v, meterType);
        MeterReadingPolicy.assertNotFuture(at, Instant.now());
        MeterReadingPolicy.assertAfterReplacement(meterType, at, replacedAt(vehicleId, meterType));
        BigDecimal lifetime = value.add(SqlRows.dec(v, offsetColumn(meterType)));
        MeterReadingPolicy.assertMonotonic(meterType, value, lifetime, previous(vehicleId, meterType, at, null),
                next(vehicleId, meterType, at, null));
        Map<String, Object> reading = db.insert(FleetMeterReading.class, Obj.of(
                "vehicleId", vehicleId,
                "meterType", meterType,
                "readingAt", at,
                "value", value,
                "lifetimeValue", lifetime,
                "source", source,
                "sourceId", sourceId,
                "status", "valid",
                "notes", notes,
                "createdBy", TenantContext.userId()));
        Instant currentAt = (Instant) v.get(atColumn(meterType));
        if (currentAt == null || !at.isBefore(currentAt)) {
            db.update(FleetVehicle.class, vehicleId, Obj.of(currentColumn(meterType), value, atColumn(meterType), at));
        }
        return reading;
    }

    // ------------------------------------------------------------------ corrections

    /** Corrections of a vehicle, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> corrections(String vehicleId) {
        vehicle(vehicleId, false);
        return SqlRows.list(db.jdbc().sql("""
                SELECT c.*, u."firstName" AS "requestedByFirstName", u."lastName" AS "requestedByLastName",
                       d."firstName" AS "decidedByFirstName", d."lastName" AS "decidedByLastName"
                FROM "FleetMeterCorrection" c LEFT JOIN "User" u ON u.id = c."requestedBy"
                LEFT JOIN "User" d ON d.id = c."decidedBy"
                WHERE c."organizationId" = ? AND c."vehicleId" = ? ORDER BY c."requestedAt" DESC, c.id DESC""")
                .params(FleetDb.org(), vehicleId));
    }

    /** One correction (404 when unknown). */
    @Transactional(readOnly = true)
    public Map<String, Object> correction(String id) {
        return db.require(FleetMeterCorrection.class, id, false, "Meter correction not found");
    }

    /** Request a correction: validated now, then gated by the approval engine (applied at once without workflow). */
    @Transactional
    public Map<String, Object> requestCorrection(String vehicleId, MeterCorrectionInput in) {
        Map<String, Object> v = vehicle(vehicleId, true);
        String code = (String) v.get("code");
        String meterType = in.meterType();
        assertTracks(v, meterType);
        if (VehicleStatusPolicy.DISPOSED.equals(v.get("status"))) {
            throw ApiException.badRequest("Vehicle " + code + " is disposed; its meters can no longer be corrected");
        }
        long pending = db.jdbc().sql("SELECT count(*) FROM \"FleetMeterCorrection\" WHERE \"organizationId\" = ? AND"
                + " \"vehicleId\" = ? AND \"meterType\" = ? AND status = 'pending'")
                .params(FleetDb.org(), vehicleId, meterType).query(Long.class).single();
        if (pending > 0) {
            throw ApiException.conflict("A " + MeterReadingPolicy.meterLabel(meterType).toLowerCase(java.util.Locale.ROOT)
                    + " correction is already pending for vehicle " + code);
        }
        BigDecimal corrected = in.correctedValue();
        BigDecimal previousValue;
        Instant at;
        if ("adjust".equals(in.kind())) {
            if (in.readingId() == null || in.readingId().isBlank()) {
                throw ApiException.badRequest("readingId is required to adjust a reading");
            }
            Map<String, Object> r = reading(in.readingId(), vehicleId, meterType, false);
            if (!"valid".equals(r.get("status"))) {
                throw ApiException.badRequest("This reading was already corrected");
            }
            previousValue = SqlRows.dec(r, "value");
            if (previousValue.compareTo(corrected) == 0) {
                throw ApiException.badRequest("The corrected value equals the recorded value");
            }
            checkAdjust(r, corrected);
            at = (Instant) r.get("readingAt");
        } else {
            if (in.readingId() != null && !in.readingId().isBlank()) {
                throw ApiException.badRequest("A meter replacement does not reference a reading");
            }
            at = in.readingAt() == null ? Instant.now() : in.readingAt();
            MeterReadingPolicy.assertNotFuture(at, Instant.now());
            checkReplace(vehicleId, meterType, at);
            previousValue = v.get(currentColumn(meterType)) == null ? null : SqlRows.dec(v, currentColumn(meterType));
        }
        Map<String, Object> c = db.insert(FleetMeterCorrection.class, Obj.of(
                "vehicleId", vehicleId,
                "meterType", meterType,
                "kind", in.kind(),
                "readingId", "adjust".equals(in.kind()) ? in.readingId() : null,
                "previousValue", previousValue,
                "correctedValue", corrected,
                "readingAt", at,
                "reason", in.reason().trim(),
                "status", "pending",
                "requestedBy", TenantContext.userId(),
                "requestedAt", Instant.now()));
        String id = (String) c.get("id");
        audit.recordInTx(AuditInput.of("FleetMeterCorrection", id, "create", null, c));
        ensureWorkflow();
        Map<String, Object> snapshot = Obj.of("amount", 0, "vehicle", code, "meterType", meterType, "kind",
                in.kind(), "previousValue", previousValue == null ? null : MeterReadingPolicy.plain(previousValue),
                "correctedValue", MeterReadingPolicy.plain(corrected), "reason", in.reason().trim());
        Map<String, Object> gate = approvals.checkOrRequestApproval(APPROVAL_ENTITY, id, snapshot);
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            return db.update(FleetMeterCorrection.class, id, Obj.of("approvalRequestId", gate.get("requestId")));
        }
        // No active workflow (an administrator switched it off): the engine auto-approves, so apply now.
        return decide(id, true, null);
    }

    /**
     * Apply the approval engine's decision on the correction's request: approved → applied (or {@code failed} when
     * the meter moved on meanwhile and the correction no longer fits), rejected → rejected. Idempotent.
     */
    @Transactional
    public Map<String, Object> applyDecision(String correctionId, boolean approved, String deciderId) {
        return decide(correctionId, approved, deciderId);
    }

    /**
     * Re-read the linked approval request and apply its outcome now (normally done automatically when the request
     * is decided). Refused while the request is still pending.
     */
    @Transactional
    public Map<String, Object> sync(String correctionId) {
        Map<String, Object> c = db.require(FleetMeterCorrection.class, correctionId, false, "Meter correction not found");
        if (!"pending".equals(c.get("status"))) {
            return c;
        }
        String requestId = (String) c.get("approvalRequestId");
        if (requestId == null) {
            return decide(correctionId, true, null);
        }
        String status = db.jdbc().sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(requestId, FleetDb.org()).query(String.class).optional()
                .orElse("pending");
        if ("pending".equals(status)) {
            throw ApiException.badRequest("The meter correction is waiting for approval (request " + requestId + ")");
        }
        return decide(correctionId, "approved".equals(status), lastApprover(requestId));
    }

    /** The approver of the latest decision on an approval request (the person who decided it). */
    public String lastApprover(String requestId) {
        return db.jdbc().sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
    }

    private Map<String, Object> decide(String correctionId, boolean approved, String deciderId) {
        Map<String, Object> c = db.require(FleetMeterCorrection.class, correctionId, true, "Meter correction not found");
        if (!"pending".equals(c.get("status"))) {
            return c;
        }
        String vehicleId = (String) c.get("vehicleId");
        Map<String, Object> v = vehicle(vehicleId, true);
        Instant now = Instant.now();
        if (!approved) {
            Map<String, Object> out = db.update(FleetMeterCorrection.class, correctionId, Obj.of("status", "rejected",
                    "decidedBy", deciderId, "decidedAt", now));
            audit.recordInTx(AuditInput.of("FleetMeterCorrection", correctionId, "reject", c, out));
            return out;
        }
        String meterType = (String) c.get("meterType");
        BigDecimal corrected = SqlRows.dec(c, "correctedValue");
        String reason = (String) c.get("reason");
        Map<String, Object> applied;
        try {
            if ("adjust".equals(c.get("kind"))) {
                Map<String, Object> r = reading((String) c.get("readingId"), vehicleId, meterType, true);
                if (!"valid".equals(r.get("status"))) {
                    throw ApiException.badRequest("The reading was corrected in the meantime");
                }
                BigDecimal lifetime = checkAdjust(r, corrected);
                db.update(FleetMeterReading.class, (String) r.get("id"), Obj.of("status", "superseded",
                        "correctionId", correctionId));
                applied = db.insert(FleetMeterReading.class, Obj.of(
                        "vehicleId", vehicleId, "meterType", meterType, "readingAt", r.get("readingAt"),
                        "value", corrected, "lifetimeValue", lifetime, "source", "correction",
                        "sourceId", correctionId, "status", "valid", "notes", reason, "createdBy", deciderId));
                refreshCurrent(vehicleId, meterType);
            } else {
                Instant at = (Instant) c.get("readingAt");
                Map<String, Object> latest = checkReplace(vehicleId, meterType, at);
                BigDecimal lastLifetime = latest == null ? SqlRows.dec(v, currentColumn(meterType))
                        .add(SqlRows.dec(v, offsetColumn(meterType))) : SqlRows.dec(latest, "lifetimeValue");
                applied = db.insert(FleetMeterReading.class, Obj.of(
                        "vehicleId", vehicleId, "meterType", meterType, "readingAt", at, "value", corrected,
                        "lifetimeValue", lastLifetime, "source", "replacement", "sourceId", correctionId,
                        "status", "valid", "notes", reason, "createdBy", deciderId));
                db.update(FleetVehicle.class, vehicleId, Obj.of(currentColumn(meterType), corrected,
                        offsetColumn(meterType), lastLifetime.subtract(corrected), atColumn(meterType), at));
            }
        } catch (ApiException e) {
            Map<String, Object> out = db.update(FleetMeterCorrection.class, correctionId, Obj.of("status", "failed",
                    "failureReason", e.getMessage(), "decidedBy", deciderId, "decidedAt", now));
            audit.recordInTx(AuditInput.of("FleetMeterCorrection", correctionId, "cancel", c, out));
            return out;
        }
        Map<String, Object> out = db.update(FleetMeterCorrection.class, correctionId, Obj.of("status", "applied",
                "decidedBy", deciderId, "decidedAt", now, "appliedReadingId", applied.get("id")));
        audit.recordInTx(AuditInput.of("FleetMeterCorrection", correctionId, "approve", c, out));
        events.publish(DomainEvents.FLEET_METER_CORRECTED, Obj.of("organizationId", FleetDb.org(),
                "vehicleId", vehicleId, "correctionId", correctionId, "meterType", meterType, "kind", c.get("kind"),
                "previousValue", c.get("previousValue") == null ? null
                        : MeterReadingPolicy.plain(SqlRows.dec(c, "previousValue")),
                "correctedValue", MeterReadingPolicy.plain(corrected)));
        return out;
    }

    /** The new lifetime value of an adjusted reading, checked against its neighbours (the reading itself excluded). */
    private BigDecimal checkAdjust(Map<String, Object> r, BigDecimal corrected) {
        String vehicleId = (String) r.get("vehicleId");
        String meterType = (String) r.get("meterType");
        Instant at = (Instant) r.get("readingAt");
        BigDecimal offset = SqlRows.dec(r, "lifetimeValue").subtract(SqlRows.dec(r, "value"));
        BigDecimal lifetime = corrected.add(offset);
        String id = (String) r.get("id");
        MeterReadingPolicy.assertMonotonic(meterType, corrected, lifetime, previous(vehicleId, meterType, at, id),
                next(vehicleId, meterType, at, id));
        return lifetime;
    }

    /** A replacement must come after every valid reading; returns the latest one (or null). */
    private Map<String, Object> checkReplace(String vehicleId, String meterType, Instant at) {
        Map<String, Object> latest = SqlRows.one(FleetMeterReading.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetMeterReading\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND \"meterType\" = ?"
                + " AND status = 'valid' ORDER BY \"readingAt\" DESC, \"lifetimeValue\" DESC LIMIT 1")
                .params(FleetDb.org(), vehicleId, meterType));
        if (latest != null && at.isBefore((Instant) latest.get("readingAt"))) {
            throw ApiException.badRequest("The replacement time must be after the last reading on "
                    + MeterReadingPolicy.day((Instant) latest.get("readingAt")));
        }
        return latest;
    }

    /** Creates the default meter-correction workflow when the organization has none (active or not). */
    private void ensureWorkflow() {
        long n = db.jdbc().sql("SELECT count(*) FROM \"ApprovalWorkflow\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = ?").params(FleetDb.org(), APPROVAL_ENTITY).query(Long.class).single();
        if (n == 0) {
            approvalAdmin.createWorkflow("Fleet meter correction", APPROVAL_ENTITY, null, true, true,
                    List.of(new ApprovalAdminService.Step(1, "Fleet manager",
                            List.of(Permissions.FLEET_VEHICLE__APPROVE_METER), 1, null, null)));
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> vehicle(String id, boolean lock) {
        return db.require(FleetVehicle.class, id, lock, "Vehicle not found");
    }

    private Map<String, Object> reading(String id, String vehicleId, String meterType, boolean lock) {
        Map<String, Object> r = db.byId(FleetMeterReading.class, id, lock);
        if (r == null || !vehicleId.equals(r.get("vehicleId")) || !meterType.equals(r.get("meterType"))) {
            throw ApiException.notFound("Meter reading not found");
        }
        return r;
    }

    private static void assertTracks(Map<String, Object> v, String meterType) {
        if (!MeterReadingPolicy.tracks((String) v.get("meterType"), meterType)) {
            throw ApiException.badRequest("Vehicle " + v.get("code") + " does not track "
                    + ("hours".equals(meterType) ? "engine hours" : "an odometer"));
        }
    }

    /** Recompute the vehicle's current raw value from its latest valid reading (after an adjustment). */
    private void refreshCurrent(String vehicleId, String meterType) {
        Map<String, Object> latest = SqlRows.one(db.jdbc().sql("SELECT value, \"readingAt\" FROM \"FleetMeterReading\""
                + " WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND \"meterType\" = ? AND status = 'valid'"
                + " ORDER BY \"readingAt\" DESC, \"lifetimeValue\" DESC LIMIT 1")
                .params(FleetDb.org(), vehicleId, meterType));
        db.update(FleetVehicle.class, vehicleId, Obj.of(currentColumn(meterType),
                latest == null ? null : latest.get("value"), atColumn(meterType),
                latest == null ? null : latest.get("readingAt")));
    }

    private Instant replacedAt(String vehicleId, String meterType) {
        return db.jdbc().sql("SELECT max(\"readingAt\") FROM \"FleetMeterReading\" WHERE \"organizationId\" = ? AND"
                + " \"vehicleId\" = ? AND \"meterType\" = ? AND source = 'replacement' AND status = 'valid'")
                .params(FleetDb.org(), vehicleId, meterType).query(java.sql.Timestamp.class).optional()
                .map(ts -> (Instant) SqlRows.value(ts)).orElse(null);
    }

    /** Highest lifetime value among valid readings at or before {@code at}. */
    private Neighbour previous(String vehicleId, String meterType, Instant at, String excludeId) {
        return neighbour(vehicleId, meterType, "\"readingAt\" <= ?", at, excludeId, "DESC");
    }

    /** Lowest lifetime value among valid readings after {@code at}. */
    private Neighbour next(String vehicleId, String meterType, Instant at, String excludeId) {
        return neighbour(vehicleId, meterType, "\"readingAt\" > ?", at, excludeId, "ASC");
    }

    private Neighbour neighbour(String vehicleId, String meterType, String timeClause, Instant at, String excludeId,
            String order) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), vehicleId, meterType, SqlRows.ts(at)));
        if (excludeId != null) {
            params.add(excludeId);
        }
        Map<String, Object> row = SqlRows.one(db.jdbc().sql("SELECT value, \"lifetimeValue\", \"readingAt\" FROM"
                + " \"FleetMeterReading\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND \"meterType\" = ? AND"
                + " status = 'valid' AND " + timeClause + (excludeId == null ? "" : " AND id <> ?")
                + " ORDER BY \"lifetimeValue\" " + order + " LIMIT 1").params(params));
        return row == null ? null : new Neighbour(SqlRows.dec(row, "value"), SqlRows.dec(row, "lifetimeValue"),
                (Instant) row.get("readingAt"));
    }

    static String currentColumn(String meterType) {
        return "hours".equals(meterType) ? "currentHours" : "currentOdometer";
    }

    static String offsetColumn(String meterType) {
        return "hours".equals(meterType) ? "hoursOffset" : "odometerOffset";
    }

    static String atColumn(String meterType) {
        return "hours".equals(meterType) ? "hoursAt" : "odometerAt";
    }
}
