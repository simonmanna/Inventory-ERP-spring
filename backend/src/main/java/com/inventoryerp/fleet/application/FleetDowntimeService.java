package com.inventoryerp.fleet.application;

import static com.inventoryerp.fleet.application.FleetServiceScheduleService.filter;
import static com.inventoryerp.fleet.application.MaintainableUnits.trim;

import com.inventoryerp.fleet.application.MaintenanceCommands.DowntimeEnd;
import com.inventoryerp.fleet.application.MaintenanceCommands.DowntimeInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.DowntimePatch;
import com.inventoryerp.fleet.application.MaintenanceCommands.DowntimeQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.ReliabilityQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.WorkOrderInput;
import com.inventoryerp.fleet.domain.FleetDowntime;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Downtime of maintainable units (fleet 1.6): breakdowns (unplanned) and planned service time with reason codes,
 * linked to their work order; recording one moves the unit to {@code breakdown} / {@code in_workshop}, ending it puts
 * the unit back into service when no work is still in progress. {@link #reliability} turns the intervals into
 * availability %, MTBF and MTTR per unit / class / fleet ({@link AvailabilityCalculator}).
 */
@Service
public class FleetDowntimeService {

    private final FleetDb db;
    private final MaintainableUnits units;
    private final FleetWorkOrderService workOrders;
    private final AuditService audit;

    public FleetDowntimeService(FleetDb db, MaintainableUnits units, FleetWorkOrderService workOrders,
            AuditService audit) {
        this.db = db;
        this.units = units;
        this.workOrders = workOrders;
        this.audit = audit;
    }

    /** Downtime log, newest first, with unit and work order; {@code durationHours} (open: up to now). */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(DowntimeQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        filter(where, params, "d.\"vehicleId\"", q.vehicleId());
        filter(where, params, "d.kind", q.kind());
        filter(where, params, "d.\"reasonCode\"", q.reasonCode());
        if (q.open() != null) {
            where.append(q.open() ? " AND d.\"endedAt\" IS NULL" : " AND d.\"endedAt\" IS NOT NULL");
        }
        if (q.from() != null) {
            where.append(" AND COALESCE(d.\"endedAt\", now()::timestamp(3)) >= ?");
            params.add(SqlRows.ts(start(q.from())));
        }
        if (q.to() != null) {
            where.append(" AND d.\"startedAt\" < ?");
            params.add(SqlRows.ts(start(q.to().plusDays(1))));
        }
        String from = " FROM \"FleetDowntime\" d JOIN \"FleetVehicle\" v ON v.id = d.\"vehicleId\""
                + " LEFT JOIN \"FleetWorkOrder\" w ON w.id = d.\"workOrderId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        Instant now = Instant.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT d.*, v.code AS \"v_code\", v.name AS"
                + " \"v_name\", v.\"registrationPlate\" AS \"v_plate\", w.code AS \"w_code\", w.status AS \"w_status\""
                + from + where + " ORDER BY d.\"startedAt\" DESC, d.id LIMIT ? OFFSET ?").params(pageParams))) {
            Map<String, Object> d = SqlRows.as(FleetDowntime.class, r);
            d.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "name", r.get("v_name"),
                    "registrationPlate", r.get("v_plate")));
            d.put("workOrder", r.get("w_code") == null ? null : Obj.of("id", r.get("workOrderId"), "code",
                    r.get("w_code"), "status", r.get("w_status")));
            Instant end = d.get("endedAt") == null ? now : (Instant) d.get("endedAt");
            d.put("durationHours", AvailabilityCalculator.hours(Duration.between((Instant) d.get("startedAt"), end)
                    .toSeconds()));
            rows.add(d);
        }
        return PageResult.of(rows, page, size, total);
    }

    /**
     * Record downtime (one open downtime per unit). Unplanned → unit {@code breakdown}, planned → {@code in_workshop}
     * (a unit already in workshop stays there). {@code createWorkOrder} raises the breakdown / corrective work order
     * linked to it.
     */
    @Transactional
    public Map<String, Object> start(DowntimeInput in) {
        Map<String, Object> v = units.require(in.vehicleId(), true, true);
        String org = FleetDb.org();
        Map<String, Object> open = SqlRows.one(db.jdbc().sql("SELECT id, \"startedAt\" FROM \"FleetDowntime\" WHERE"
                + " \"organizationId\" = ? AND \"vehicleId\" = ? AND \"endedAt\" IS NULL").params(org,
                in.vehicleId()));
        if (open != null) {
            throw ApiException.badRequest("Vehicle " + v.get("code") + " is already down since "
                    + open.get("startedAt") + "; end that downtime first");
        }
        Instant now = Instant.now();
        Instant at = in.startedAt() == null ? now : in.startedAt();
        if (at.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("The downtime cannot start in the future");
        }
        String kind = in.kind() == null ? "unplanned" : in.kind();
        Map<String, Object> d = db.insert(FleetDowntime.class, Obj.of(
                "vehicleId", in.vehicleId(),
                "kind", kind,
                "reasonCode", in.reasonCode(),
                "description", trim(in.description()),
                "startedAt", at,
                "source", "manual",
                "odometer", in.odometer() != null ? in.odometer() : v.get("currentOdometer"),
                "hours", in.hours() != null ? in.hours() : v.get("currentHours"),
                "reportedBy", TenantContext.userId()));
        String id = (String) d.get("id");
        String reason = reasonLabel(in.reasonCode()) + (trim(in.description()) == null ? "" : " · "
                + in.description().trim());
        if (!VehicleStatusPolicy.IN_WORKSHOP.equals(v.get("status"))) {
            units.hold(in.vehicleId(), "unplanned".equals(kind) ? VehicleStatusPolicy.BREAKDOWN
                    : VehicleStatusPolicy.IN_WORKSHOP, reason, id);
        }
        audit.recordInTx(AuditInput.of("FleetDowntime", id, "create", null, d));
        if (Boolean.TRUE.equals(in.createWorkOrder())) {
            workOrders.createFor(new WorkOrderInput(in.vehicleId(), "unplanned".equals(kind) ? "breakdown"
                    : "corrective", "unplanned".equals(kind) ? "urgent" : "normal", reason.length() > 200
                    ? reason.substring(0, 200) : reason, trim(in.description()), null, null, null, null, id, false),
                    "downtime");
        }
        return get(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> d = db.require(FleetDowntime.class, id, false, "Downtime not found");
        Map<String, Object> out = new LinkedHashMap<>(d);
        out.put("workOrder", d.get("workOrderId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, code, status"
                + " FROM \"FleetWorkOrder\" WHERE id = ? AND \"organizationId\" = ?").params(d.get("workOrderId"),
                FleetDb.org())));
        return out;
    }

    /** End a downtime; the unit returns to service unless work on it is still in progress. */
    @Transactional
    public Map<String, Object> end(String id, DowntimeEnd in) {
        Map<String, Object> d = db.require(FleetDowntime.class, id, true, "Downtime not found");
        if (d.get("endedAt") != null) {
            throw ApiException.badRequest("This downtime has already ended");
        }
        Instant now = Instant.now();
        Instant at = in == null || in.endedAt() == null ? now : in.endedAt();
        assertPeriod((Instant) d.get("startedAt"), at, now);
        Map<String, Object> data = Obj.of("endedAt", at, "endedBy", TenantContext.userId());
        if (in != null && trim(in.notes()) != null) {
            data.put("description", (d.get("description") == null ? "" : d.get("description") + " · ")
                    + in.notes().trim());
        }
        Map<String, Object> after = db.update(FleetDowntime.class, id, data);
        units.release((String) d.get("vehicleId"), null, "Downtime ended", id);
        audit.recordInTx(AuditInput.of("FleetDowntime", id, "update", d, after));
        return get(id);
    }

    /** Correct a downtime record (reason, kind, times). */
    @Transactional
    public Map<String, Object> update(String id, DowntimePatch in) {
        Map<String, Object> d = db.require(FleetDowntime.class, id, true, "Downtime not found");
        Map<String, Object> data = new LinkedHashMap<>();
        FleetServiceScheduleService.put(data, "kind", in.kind());
        FleetServiceScheduleService.put(data, "reasonCode", in.reasonCode());
        FleetServiceScheduleService.put(data, "description", trim(in.description()));
        FleetServiceScheduleService.put(data, "startedAt", in.startedAt());
        if (in.endedAt() != null) {
            if (d.get("endedAt") == null) {
                throw ApiException.badRequest("End the downtime with the end action");
            }
            data.put("endedAt", in.endedAt());
        }
        if (data.isEmpty()) {
            return get(id);
        }
        Instant start = data.containsKey("startedAt") ? (Instant) data.get("startedAt") : (Instant) d.get("startedAt");
        Instant end = data.containsKey("endedAt") ? (Instant) data.get("endedAt") : (Instant) d.get("endedAt");
        Instant now = Instant.now();
        if (start.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("The downtime cannot start in the future");
        }
        if (end != null) {
            assertPeriod(start, end, now);
        }
        Map<String, Object> after = db.update(FleetDowntime.class, id, data);
        audit.recordInTx(AuditInput.of("FleetDowntime", id, "update", d, after));
        return get(id);
    }

    // ------------------------------------------------------------------ reliability

    /**
     * Availability %, MTBF and MTTR over {@code [from, to]} (business dates, default the last 30 days) per unit
     * ({@code groupBy = vehicle}, default) or per class ({@code class}), plus the fleet total.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> reliability(ReliabilityQuery q) {
        LocalDate today = MaintainableUnits.today();
        LocalDate toDay = q.to() == null ? today : q.to();
        LocalDate fromDay = q.from() == null ? toDay.minusDays(29) : q.from();
        if (fromDay.isAfter(toDay)) {
            throw ApiException.badRequest("from must be on or before to");
        }
        Instant from = start(fromDay);
        Instant to = start(toDay.plusDays(1));
        Instant now = Instant.now();
        String org = FleetDb.org();
        StringBuilder where = new StringBuilder(" WHERE v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL"
                + " AND (v.\"disposedAt\" IS NULL OR v.\"disposedAt\" >= ?)");
        List<Object> params = new ArrayList<>(List.of(org, SqlRows.ts(from)));
        filter(where, params, "v.id", q.vehicleId());
        filter(where, params, "v.\"categoryId\"", q.categoryId());
        filter(where, params, "v.\"branchId\"", q.branchId());
        List<Map<String, Object>> vehicles = SqlRows.list(db.jdbc().sql("SELECT v.id, v.code, v.name,"
                + " v.\"registrationPlate\", v.\"categoryId\", v.\"disposedAt\", c.name AS \"categoryName\""
                + " FROM \"FleetVehicle\" v JOIN \"FleetVehicleCategory\" c ON c.id = v.\"categoryId\"" + where
                + " ORDER BY c.name, v.code").params(params));
        Map<String, List<AvailabilityCalculator.Interval>> byVehicle = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT \"vehicleId\", kind, \"startedAt\","
                + " \"endedAt\" FROM \"FleetDowntime\" WHERE \"organizationId\" = ? AND \"startedAt\" < ? AND"
                + " (\"endedAt\" IS NULL OR \"endedAt\" > ?)").params(org, SqlRows.ts(to), SqlRows.ts(from)))) {
            byVehicle.computeIfAbsent((String) r.get("vehicleId"), k -> new ArrayList<>()).add(
                    new AvailabilityCalculator.Interval((Instant) r.get("startedAt"), (Instant) r.get("endedAt"),
                            "unplanned".equals(r.get("kind"))));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, List<AvailabilityCalculator.Result>> byClass = new LinkedHashMap<>();
        Map<String, String> classNames = new LinkedHashMap<>();
        List<AvailabilityCalculator.Result> all = new ArrayList<>();
        for (Map<String, Object> v : vehicles) {
            Instant end = v.get("disposedAt") != null && ((Instant) v.get("disposedAt")).isBefore(to)
                    ? (Instant) v.get("disposedAt") : to;
            AvailabilityCalculator.Result r = AvailabilityCalculator.compute(from, end, now,
                    byVehicle.getOrDefault((String) v.get("id"), List.of()));
            all.add(r);
            byClass.computeIfAbsent((String) v.get("categoryId"), k -> new ArrayList<>()).add(r);
            classNames.put((String) v.get("categoryId"), (String) v.get("categoryName"));
            Map<String, Object> row = figures(r);
            row.put("vehicle", Obj.of("id", v.get("id"), "code", v.get("code"), "name", v.get("name"),
                    "registrationPlate", v.get("registrationPlate"), "categoryName", v.get("categoryName")));
            rows.add(row);
        }
        if ("class".equals(q.groupBy())) {
            rows = new ArrayList<>();
            for (Map.Entry<String, List<AvailabilityCalculator.Result>> e : byClass.entrySet()) {
                Map<String, Object> row = figures(AvailabilityCalculator.aggregate(e.getValue()));
                row.put("category", Obj.of("id", e.getKey(), "name", classNames.get(e.getKey())));
                row.put("units", e.getValue().size());
                rows.add(row);
            }
        }
        Map<String, Object> total = figures(AvailabilityCalculator.aggregate(all));
        total.put("units", vehicles.size());
        return Obj.of("from", fromDay, "to", toDay, "groupBy", "class".equals(q.groupBy()) ? "class" : "vehicle",
                "rows", rows, "total", total);
    }

    private static Map<String, Object> figures(AvailabilityCalculator.Result r) {
        return Obj.of("periodHours", r.periodHours(), "downtimeHours", r.downtimeHours(), "unplannedHours",
                r.unplannedHours(), "plannedHours", r.plannedHours(), "uptimeHours", r.uptimeHours(), "failures",
                r.failures(), "availabilityPct", r.availabilityPct(), "mtbfHours", r.mtbfHours(), "mttrHours",
                r.mttrHours());
    }

    // ------------------------------------------------------------------ helpers

    private static void assertPeriod(Instant start, Instant end, Instant now) {
        if (end.isBefore(start)) {
            throw ApiException.badRequest("The downtime cannot end before it started");
        }
        if (end.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("The downtime cannot end in the future");
        }
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(BusinessZone.get()).toInstant();
    }

    static String reasonLabel(String code) {
        return code == null ? "" : Character.toUpperCase(code.charAt(0)) + code.substring(1).replace('_', ' ');
    }
}
