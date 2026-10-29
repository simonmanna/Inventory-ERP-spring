package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.DriverCommands.AssignmentEnd;
import com.inventoryerp.fleet.application.DriverCommands.AssignmentInput;
import com.inventoryerp.fleet.application.DriverCommands.HistoryQuery;
import com.inventoryerp.fleet.domain.FleetAssignment;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicle ↔ driver assignment history (fleet 1.2, ADR-S009): a vehicle has at most one active primary driver (a new
 * primary assignment ends the previous one at its start) and any number of temporary covers with an end. Assigning
 * runs the {@link DriverEligibilityPolicy} on the start day (vehicle operational, licence / permit / medical valid,
 * qualified class). The vehicle row and then the driver row are locked {@code FOR UPDATE} — the same order as
 * check-out — so concurrent assignments serialize without deadlocks.
 */
@Service
public class FleetAssignmentService {

    static final String AUDIT_ENTITY = "FleetAssignment";

    private final FleetDb db;
    private final FleetDriverService drivers;
    private final AuditService audit;

    public FleetAssignmentService(FleetDb db, FleetDriverService drivers, AuditService audit) {
        this.db = db;
        this.drivers = drivers;
        this.audit = audit;
    }

    /** Assignment history, newest first ({@code status} = active / ended; vehicle and driver filters). */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(HistoryQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE a.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        filter(where, params, "a.\"vehicleId\"", q.vehicleId());
        filter(where, params, "a.\"driverId\"", q.driverId());
        filter(where, params, "a.status", q.status());
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetAssignment\" a" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY a.status, a.\"startsAt\" DESC, a.id LIMIT ? OFFSET ?").params(params)), page, size, total);
    }

    static final String SELECT = """
            SELECT a.*, v.code AS "vehicleCode", v."registrationPlate" AS "vehicleRegistrationPlate",
                   v.name AS "vehicleName", d.code AS "driverCode",
                   trim(coalesce(e."firstName", '') || ' ' || coalesce(e."lastName", '')) AS "driverName"
            FROM "FleetAssignment" a
            JOIN "FleetVehicle" v ON v.id = a."vehicleId"
            JOIN "FleetDriver" d ON d.id = a."driverId"
            LEFT JOIN "HrEmployee" e ON e.id = d."employeeId" AND e."organizationId" = d."organizationId"
            """;

    /** Assign a driver to a vehicle (eligibility checked on the start day). */
    @Transactional
    public Map<String, Object> assign(AssignmentInput in) {
        DriverEligibilityPolicy.VehicleFacts v = drivers.vehicleFacts(in.vehicleId(), true);
        Map<String, Object> d = drivers.require(in.driverId(), true);
        Instant starts = in.startsAt() == null ? Instant.now() : in.startsAt();
        if ("temporary".equals(in.kind()) && in.endsAt() == null) {
            throw ApiException.badRequest("A temporary assignment needs an end date and time");
        }
        if (in.endsAt() != null && !in.endsAt().isAfter(starts)) {
            throw ApiException.badRequest("The assignment must end after it starts");
        }
        DriverEligibilityPolicy.assertEligible(drivers.facts(d), v, day(starts), "assigned");
        String org = FleetDb.org();
        List<Object> params = new ArrayList<>(List.of(org, in.vehicleId(), in.driverId(), SqlRows.ts(starts)));
        String endClause = "";
        if (in.endsAt() != null) {
            endClause = " AND \"startsAt\" < ?";
            params.add(SqlRows.ts(in.endsAt()));
        }
        long overlapping = db.jdbc().sql("SELECT count(*) FROM \"FleetAssignment\" WHERE \"organizationId\" = ? AND"
                + " \"vehicleId\" = ? AND \"driverId\" = ? AND status = 'active' AND (\"endsAt\" IS NULL OR \"endsAt\" > ?)"
                + endClause).params(params).query(Long.class).single();
        if (overlapping > 0) {
            throw ApiException.conflict("Driver " + d.get("code") + " is already assigned to vehicle " + v.code());
        }
        if ("primary".equals(in.kind())) {
            Map<String, Object> current = SqlRows.one(FleetAssignment.class, db.jdbc().sql("SELECT * FROM"
                    + " \"FleetAssignment\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND kind = 'primary' AND"
                    + " status = 'active' FOR UPDATE").params(org, in.vehicleId()));
            if (current != null) {
                Instant previousStart = (Instant) current.get("startsAt");
                if (!starts.isAfter(previousStart)) {
                    throw ApiException.badRequest("The new primary assignment must start after the current one ("
                            + previousStart + ")");
                }
                Instant previousEnd = (Instant) current.get("endsAt");
                Map<String, Object> ended = db.update(FleetAssignment.class, (String) current.get("id"), Obj.of(
                        "status", "ended",
                        "endsAt", previousEnd == null || previousEnd.isAfter(starts) ? starts : previousEnd,
                        "endReason", "Replaced by driver " + d.get("code"),
                        "endedBy", TenantContext.userId()));
                audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) current.get("id"), "update", current, ended));
            }
        }
        Map<String, Object> row = db.insert(FleetAssignment.class, Obj.of(
                "vehicleId", in.vehicleId(),
                "driverId", in.driverId(),
                "kind", in.kind(),
                "startsAt", starts,
                "endsAt", in.endsAt(),
                "status", "active",
                "notes", FleetVehicleService.trim(in.notes()),
                "createdBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) row.get("id"), "assign", null, row));
        return one((String) row.get("id"));
    }

    /** End an active assignment (at {@code endsAt}, default now). */
    @Transactional
    public Map<String, Object> end(String id, AssignmentEnd in) {
        Map<String, Object> a = db.require(FleetAssignment.class, id, true, "Assignment not found");
        if (!"active".equals(a.get("status"))) {
            throw ApiException.badRequest("This assignment has already ended");
        }
        Instant ends = in == null || in.endsAt() == null ? Instant.now() : in.endsAt();
        if (ends.isBefore((Instant) a.get("startsAt"))) {
            throw ApiException.badRequest("The assignment cannot end before it starts");
        }
        Map<String, Object> after = db.update(FleetAssignment.class, id, Obj.of("status", "ended", "endsAt", ends,
                "endReason", in == null ? null : FleetVehicleService.trim(in.reason()),
                "endedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", a, after));
        return one(id);
    }

    /** The driver's active assignment on the vehicle covering {@code at}, or null (linked to a check-out). */
    String covering(String vehicleId, String driverId, Instant at) {
        return db.jdbc().sql("SELECT id FROM \"FleetAssignment\" WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND"
                + " \"driverId\" = ? AND status = 'active' AND \"startsAt\" <= ? AND (\"endsAt\" IS NULL OR \"endsAt\" > ?)"
                + " ORDER BY kind, \"startsAt\" DESC LIMIT 1").params(FleetDb.org(), vehicleId, driverId,
                SqlRows.ts(at), SqlRows.ts(at)).query(String.class).optional().orElse(null);
    }

    /** Current active assignments of a vehicle (for its detail tab). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeForVehicle(String vehicleId) {
        db.require(FleetVehicle.class, vehicleId, false, "Vehicle not found");
        return SqlRows.list(db.jdbc().sql(SELECT + " WHERE a.\"organizationId\" = ? AND a.\"vehicleId\" = ? AND"
                + " a.status = 'active' ORDER BY a.kind, a.\"startsAt\" DESC").params(FleetDb.org(), vehicleId));
    }

    private Map<String, Object> one(String id) {
        return SqlRows.one(db.jdbc().sql(SELECT + " WHERE a.id = ? AND a.\"organizationId\" = ?")
                .params(id, FleetDb.org()));
    }

    static LocalDate day(Instant at) {
        return LocalDate.ofInstant(at, BusinessZone.get());
    }

    private static void filter(StringBuilder where, List<Object> params, String column, String value) {
        if (FleetVehicleService.notBlank(value)) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }
}
