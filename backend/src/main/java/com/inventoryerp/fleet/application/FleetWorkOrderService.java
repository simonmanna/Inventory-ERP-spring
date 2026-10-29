package com.inventoryerp.fleet.application;

import static com.inventoryerp.fleet.application.FleetServiceScheduleService.clear;
import static com.inventoryerp.fleet.application.FleetServiceScheduleService.filter;
import static com.inventoryerp.fleet.application.FleetServiceScheduleService.put;
import static com.inventoryerp.fleet.application.MaintainableUnits.like;
import static com.inventoryerp.fleet.application.MaintainableUnits.notBlank;
import static com.inventoryerp.fleet.application.MaintainableUnits.strings;
import static com.inventoryerp.fleet.application.MaintainableUnits.trim;

import com.inventoryerp.fleet.application.MaintenanceCommands.CompleteInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.TaskInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.TaskPatch;
import com.inventoryerp.fleet.application.MaintenanceCommands.WorkOrderInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.WorkOrderPatch;
import com.inventoryerp.fleet.application.MaintenanceCommands.WorkOrderQuery;
import com.inventoryerp.fleet.application.ServiceDuePolicy.DueStatus;
import com.inventoryerp.fleet.domain.FleetDefect;
import com.inventoryerp.fleet.domain.FleetDowntime;
import com.inventoryerp.fleet.domain.FleetServiceSchedule;
import com.inventoryerp.fleet.domain.FleetWorkOrder;
import com.inventoryerp.fleet.domain.FleetWorkOrderLine;
import com.inventoryerp.fleet.domain.FleetWorkOrderTask;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
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
 * Fleet maintenance work orders (1.6, ADR-S009 §5): preventive (from due schedules), corrective, breakdown and
 * inspection-defect work on a maintainable unit. Lifecycle {@code draft → open → in_progress → completed → closed}
 * ({@code cancelled} before completion). Starting the work holds the unit {@code in_workshop} and opens its downtime;
 * completing it records the completion meters, ends the downtime, resolves the linked defects, re-bases the schedule
 * and puts the unit back into service. Cost lines are posted by {@link FleetWorkOrderCostService}.
 */
@Service
public class FleetWorkOrderService {

    static final String AUDIT_ENTITY = "FleetWorkOrder";

    private static final Map<String, String> SORTS = Map.of(
            "code", "w.code",
            "reportedAt", "w.\"reportedAt\"",
            "dueOn", "w.\"dueOn\"",
            "priority", "CASE w.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 WHEN 'normal' THEN 2 ELSE 3 END",
            "status", "w.status",
            "totalCost", "w.\"totalCost\"");

    private final FleetDb db;
    private final MaintainableUnits units;
    private final FleetMeterService meters;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public FleetWorkOrderService(FleetDb db, MaintainableUnits units, FleetMeterService meters,
            SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.units = units;
        this.meters = meters;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ reads

    /** Paged work orders with the unit's code / plate. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(WorkOrderQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE w.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (notBlank(q.search())) {
            where.append(" AND (w.code ILIKE ? OR w.title ILIKE ? OR v.code ILIKE ? OR v.\"registrationPlate\" ILIKE ?)");
            String like = like(q.search());
            for (int i = 0; i < 4; i++) {
                params.add(like);
            }
        }
        filter(where, params, "w.\"vehicleId\"", q.vehicleId());
        if ("live".equals(q.status())) {
            where.append(" AND w.status IN ('draft', 'open', 'in_progress')");
        } else {
            filter(where, params, "w.status", q.status());
        }
        filter(where, params, "w.type", q.type());
        filter(where, params, "w.priority", q.priority());
        if (q.from() != null) {
            where.append(" AND w.\"reportedAt\" >= ?");
            params.add(SqlRows.ts(q.from().atStartOfDay(BusinessZone.get()).toInstant()));
        }
        if (q.to() != null) {
            where.append(" AND w.\"reportedAt\" < ?");
            params.add(SqlRows.ts(q.to().plusDays(1).atStartOfDay(BusinessZone.get()).toInstant()));
        }
        String from = " FROM \"FleetWorkOrder\" w JOIN \"FleetVehicle\" v ON v.id = w.\"vehicleId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        String sort = SORTS.getOrDefault(q.sortBy() == null ? "reportedAt" : q.sortBy(), "w.\"reportedAt\"");
        String dir = q.sortOrder() == null ? ("reportedAt".equals(q.sortBy()) || q.sortBy() == null ? "DESC" : "ASC")
                : "desc".equalsIgnoreCase(q.sortOrder()) ? "DESC" : "ASC";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT w.*, v.code AS \"v_code\", v.name AS"
                + " \"v_name\", v.\"registrationPlate\" AS \"v_plate\", v.status AS \"v_status\"" + from + where
                + " ORDER BY " + sort + " " + dir + " NULLS LAST, w.code DESC LIMIT ? OFFSET ?").params(pageParams))) {
            Map<String, Object> w = SqlRows.as(FleetWorkOrder.class, r);
            w.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "name", r.get("v_name"),
                    "registrationPlate", r.get("v_plate"), "status", r.get("v_status")));
            rows.add(w);
        }
        return PageResult.of(rows, page, size, total);
    }

    /** Detail: header, unit, schedule, tasks, cost lines, cost summary, linked defects and downtime. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return detail(require(id, false));
    }

    Map<String, Object> require(String id, boolean lock) {
        return db.require(FleetWorkOrder.class, id, lock, "Work order not found");
    }

    private Map<String, Object> detail(Map<String, Object> w) {
        String org = FleetDb.org();
        String id = (String) w.get("id");
        Map<String, Object> out = new LinkedHashMap<>(w);
        out.put("vehicle", SqlRows.one(db.jdbc().sql("""
                SELECT v.id, v.code, v.name, v."registrationPlate", v.status, v."meterType", v."costCenterId",
                       c.name AS "categoryName", c.kind AS "categoryKind", cc.code AS "costCenterCode",
                       cc.name AS "costCenterName"
                FROM "FleetVehicle" v JOIN "FleetVehicleCategory" c ON c.id = v."categoryId"
                LEFT JOIN "CostCenter" cc ON cc.id = v."costCenterId" AND cc."organizationId" = v."organizationId"
                WHERE v.id = ? AND v."organizationId" = ?""").params(w.get("vehicleId"), org)));
        out.put("schedule", w.get("scheduleId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, name,"
                + " \"intervalKm\", \"intervalHours\", \"intervalDays\" FROM \"FleetServiceSchedule\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(w.get("scheduleId"), org)));
        out.put("assignedEmployee", employee((String) w.get("assignedEmployeeId")));
        out.put("tasks", db.list(FleetWorkOrderTask.class, " AND \"workOrderId\" = ? ORDER BY \"sortOrder\", id", id));
        out.put("lines", lines(id));
        out.put("defects", db.list(FleetDefect.class, " AND \"workOrderId\" = ? ORDER BY \"reportedAt\"", id));
        out.put("downtime", db.list(FleetDowntime.class, " AND \"workOrderId\" = ? ORDER BY \"startedAt\"", id));
        out.put("costSummary", Obj.of("parts", w.get("partsCost"), "labour", w.get("labourCost"), "external",
                w.get("externalCost"), "total", w.get("totalCost")));
        out.put("allowed", allowed((String) w.get("status")));
        return out;
    }

    /** Cost lines with product / employee / supplier / location names and the vendor bill's number and status. */
    List<Map<String, Object>> lines(String workOrderId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT l.*, p.code AS "p_code", p.name AS "p_name", loc.name AS "loc_name",
                       e."employeeCode" AS "e_code", e."firstName" AS "e_first", e."lastName" AS "e_last",
                       pa.name AS "pa_name", d."documentNumber" AS "d_number", d.status::text AS "d_status"
                FROM "FleetWorkOrderLine" l
                LEFT JOIN "Product" p ON p.id = l."productId" AND p."organizationId" = l."organizationId"
                LEFT JOIN "InventoryLocation" loc ON loc.id = l."locationId" AND loc."organizationId" = l."organizationId"
                LEFT JOIN "HrEmployee" e ON e.id = l."employeeId" AND e."organizationId" = l."organizationId"
                LEFT JOIN "Partner" pa ON pa.id = l."partnerId" AND pa."organizationId" = l."organizationId"
                LEFT JOIN "Document" d ON d.id = l."vendorBillId" AND d."organizationId" = l."organizationId"
                WHERE l."organizationId" = ? AND l."workOrderId" = ? ORDER BY l."createdAt", l.id""")
                .params(FleetDb.org(), workOrderId))) {
            Map<String, Object> l = SqlRows.as(FleetWorkOrderLine.class, r);
            l.put("productCode", r.get("p_code"));
            l.put("productName", r.get("p_name"));
            l.put("locationName", r.get("loc_name"));
            l.put("employeeName", r.get("e_first") == null ? null
                    : (r.get("e_first") + (r.get("e_last") == null ? "" : " " + r.get("e_last"))).trim());
            l.put("employeeCode", r.get("e_code"));
            l.put("partnerName", r.get("pa_name"));
            l.put("vendorBill", r.get("d_number") == null ? null : Obj.of("id", r.get("vendorBillId"),
                    "documentNumber", r.get("d_number"), "status", r.get("d_status")));
            out.add(l);
        }
        return out;
    }

    private static List<String> allowed(String status) {
        List<String> out = new ArrayList<>();
        for (String to : List.of(MaintenancePolicies.OPEN, MaintenancePolicies.IN_PROGRESS,
                MaintenancePolicies.COMPLETED, MaintenancePolicies.CLOSED, MaintenancePolicies.CANCELLED)) {
            if (MaintenancePolicies.canTransition(status, to)) {
                out.add(to);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ create / edit

    /** New work order (manual), opened at once unless {@code draft}. */
    @Transactional
    public Map<String, Object> create(WorkOrderInput in) {
        return detail(insert(in, "manual", null));
    }

    /** New work order raised by another fleet flow (downtime, defect). */
    @Transactional
    public Map<String, Object> createFor(WorkOrderInput in, String source) {
        return detail(insert(in, source, null));
    }

    /** Preventive work order of a due schedule (the schedule row is locked by the caller). */
    @Transactional
    public Map<String, Object> createFromSchedule(Map<String, Object> s, Map<String, Object> v, DueStatus due,
            String source) {
        LocalDate dueOn = MaintainableUnits.today();
        ServiceDuePolicy.Dimension days = due.dimension("days");
        if (days != null && days.nextDueOn() != null && days.nextDueOn().isAfter(dueOn)) {
            dueOn = days.nextDueOn();
        }
        String reason = due.trigger() == null ? "schedule" : switch (due.trigger()) {
            case "km" -> "kilometres";
            case "hours" -> "engine hours";
            default -> "calendar";
        };
        WorkOrderInput in = new WorkOrderInput((String) v.get("id"), "preventive",
                ServiceDuePolicy.OVERDUE.equals(due.state()) ? "high" : "normal", (String) s.get("name"),
                "Preventive service " + (ServiceDuePolicy.OVERDUE.equals(due.state()) ? "overdue" : "due") + " by "
                        + reason + " (schedule " + s.get("name") + ")",
                dueOn, null, strings(s.get("tasks")), null, null, false);
        Map<String, Object> w = insert(in, source, (String) s.get("id"));
        db.update(FleetServiceSchedule.class, (String) s.get("id"), Obj.of("openWorkOrderId", w.get("id")));
        return w;
    }

    private Map<String, Object> insert(WorkOrderInput in, String source, String scheduleId) {
        Map<String, Object> v = units.require(in.vehicleId(), true, true);
        assertEmployee(in.assignedEmployeeId());
        Map<String, Object> downtime = null;
        if (notBlank(in.downtimeId())) {
            downtime = db.require(FleetDowntime.class, in.downtimeId(), true, "Downtime not found");
            if (!in.vehicleId().equals(downtime.get("vehicleId"))) {
                throw ApiException.badRequest("The downtime belongs to another vehicle");
            }
            if (downtime.get("workOrderId") != null) {
                throw ApiException.badRequest("The downtime is already linked to a work order");
            }
        }
        boolean draft = Boolean.TRUE.equals(in.draft());
        Instant now = Instant.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", nextCode());
        data.put("vehicleId", in.vehicleId());
        data.put("type", in.type());
        data.put("priority", in.priority() == null ? "normal" : in.priority());
        data.put("status", draft ? MaintenancePolicies.DRAFT : MaintenancePolicies.OPEN);
        data.put("title", in.title().trim());
        data.put("description", trim(in.description()));
        data.put("scheduleId", scheduleId);
        data.put("source", source);
        data.put("dueOn", in.dueOn());
        data.put("reportedAt", now);
        data.put("openedAt", draft ? null : now);
        data.put("assignedEmployeeId", trim(in.assignedEmployeeId()));
        data.put("costCenterId", v.get("costCenterId"));
        data.put("branchId", v.get("branchId"));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> w = db.insert(FleetWorkOrder.class, data);
        String id = (String) w.get("id");
        int i = 0;
        for (String task : FleetServiceScheduleService.tasks(in.tasks())) {
            db.insert(FleetWorkOrderTask.class, Obj.of("workOrderId", id, "sortOrder", ++i, "description", task));
        }
        if (downtime != null) {
            db.update(FleetDowntime.class, (String) downtime.get("id"), Obj.of("workOrderId", id));
        }
        if (in.defectIds() != null) {
            attachDefects(w, in.defectIds());
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, w));
        events.publish(DomainEvents.FLEET_WORK_ORDER_CREATED, Obj.of("organizationId", FleetDb.org(),
                "workOrderId", id, "code", w.get("code"), "vehicleId", in.vehicleId(), "type", in.type(),
                "source", source));
        return w;
    }

    /** Link open defects of the same unit to a live work order (they move to {@code in_repair}). */
    void attachDefects(Map<String, Object> w, List<String> defectIds) {
        for (String defectId : defectIds) {
            Map<String, Object> d = db.require(FleetDefect.class, defectId, true, "Defect not found");
            if (!w.get("vehicleId").equals(d.get("vehicleId"))) {
                throw ApiException.badRequest("Defect " + d.get("code") + " belongs to another vehicle");
            }
            MaintenancePolicies.assertDefectTransition((String) d.get("code"), (String) d.get("status"),
                    "in_repair");
            db.update(FleetDefect.class, defectId, Obj.of("status", "in_repair", "workOrderId", w.get("id")));
        }
    }

    /** Edit the header of a live work order. */
    @Transactional
    public Map<String, Object> update(String id, WorkOrderPatch in) {
        Map<String, Object> before = require(id, true);
        assertEditable(before);
        List<String> clear = clear(in.clear(), MaintenanceCommands.WORK_ORDER_CLEARABLE);
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "title", trim(in.title()));
        put(data, "description", trim(in.description()));
        put(data, "priority", in.priority());
        put(data, "dueOn", in.dueOn());
        if (notBlank(in.assignedEmployeeId())) {
            assertEmployee(in.assignedEmployeeId());
            data.put("assignedEmployeeId", in.assignedEmployeeId());
        }
        clear.forEach(f -> data.put(f, null));
        if (data.isEmpty()) {
            return detail(before);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetWorkOrder.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return detail(after);
    }

    // ------------------------------------------------------------------ lifecycle

    /** {@code draft → open}. */
    @Transactional
    public Map<String, Object> open(String id) {
        Map<String, Object> w = require(id, true);
        MaintenancePolicies.assertWorkOrderTransition(code(w), (String) w.get("status"), MaintenancePolicies.OPEN);
        return detail(transition(w, MaintenancePolicies.OPEN, Obj.of("openedAt", Instant.now())));
    }

    /** {@code open → in_progress}: the unit goes in workshop; its downtime starts (or the open one is linked). */
    @Transactional
    public Map<String, Object> start(String id) {
        Map<String, Object> w = require(id, true);
        MaintenancePolicies.assertWorkOrderTransition(code(w), (String) w.get("status"),
                MaintenancePolicies.IN_PROGRESS);
        String vehicleId = (String) w.get("vehicleId");
        Map<String, Object> v = units.require(vehicleId, true, true);
        String vehicleStatus = (String) v.get("status");
        Instant now = Instant.now();
        Map<String, Object> data = Obj.of("startedAt", now);
        if (!VehicleStatusPolicy.IN_WORKSHOP.equals(vehicleStatus)) {
            data.put("previousVehicleStatus", vehicleStatus);
            units.hold(vehicleId, VehicleStatusPolicy.IN_WORKSHOP, "Work order " + code(w), id);
        }
        Map<String, Object> open = SqlRows.one(FleetDowntime.class, db.jdbc().sql("SELECT * FROM \"FleetDowntime\""
                + " WHERE \"organizationId\" = ? AND \"vehicleId\" = ? AND \"endedAt\" IS NULL FOR UPDATE")
                .params(FleetDb.org(), vehicleId));
        if (open == null) {
            String type = (String) w.get("type");
            db.insert(FleetDowntime.class, Obj.of(
                    "vehicleId", vehicleId,
                    "workOrderId", id,
                    "kind", MaintenancePolicies.unplanned(type) ? "unplanned" : "planned",
                    "reasonCode", switch (type) {
                        case "preventive" -> "preventive_service";
                        case "inspection_defect" -> "inspection_defect";
                        default -> "other";
                    },
                    "description", "Work order " + code(w) + " · " + w.get("title"),
                    "startedAt", now,
                    "source", "work_order",
                    "odometer", v.get("currentOdometer"),
                    "hours", v.get("currentHours"),
                    "reportedBy", TenantContext.userId()));
        } else if (open.get("workOrderId") == null) {
            db.update(FleetDowntime.class, (String) open.get("id"), Obj.of("workOrderId", id));
        }
        return detail(transition(w, MaintenancePolicies.IN_PROGRESS, data));
    }

    /**
     * {@code in_progress → completed}: every task done or skipped; completion meters recorded (source work_order);
     * the downtime ends, linked defects are resolved, the schedule is re-based and the unit goes back into service.
     */
    @Transactional
    public Map<String, Object> complete(String id, CompleteInput in) {
        Map<String, Object> w = require(id, true);
        String code = code(w);
        MaintenancePolicies.assertWorkOrderTransition(code, (String) w.get("status"), MaintenancePolicies.COMPLETED);
        long pending = db.jdbc().sql("SELECT count(*) FROM \"FleetWorkOrderTask\" WHERE \"organizationId\" = ? AND"
                + " \"workOrderId\" = ? AND status = 'pending'").params(FleetDb.org(), id).query(Long.class).single();
        if (pending > 0) {
            throw ApiException.badRequest(pending + " task(s) of work order " + code
                    + " are still pending; mark them done or skipped");
        }
        Instant now = Instant.now();
        Instant at = in == null || in.completedAt() == null ? now : in.completedAt();
        if (at.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("The completion time cannot be in the future");
        }
        Instant started = (Instant) w.get("startedAt");
        if (started != null && at.isBefore(started)) {
            throw ApiException.badRequest("The completion time cannot be before the work started");
        }
        String vehicleId = (String) w.get("vehicleId");
        if (in != null && in.odometer() != null) {
            meters.recordAutomated(vehicleId, "odometer", in.odometer(), at, "work_order", id, "Work order " + code);
        }
        if (in != null && in.hours() != null) {
            meters.recordAutomated(vehicleId, "hours", in.hours(), at, "work_order", id, "Work order " + code);
        }
        Map<String, Object> v = units.require(vehicleId, false, false);
        BigDecimal km = MaintainableUnits.lifetimeKm(v);
        BigDecimal hours = MaintainableUnits.lifetimeHours(v);
        String resolution = in == null ? null : trim(in.resolution());
        Map<String, Object> after = transition(w, MaintenancePolicies.COMPLETED, Obj.of("completedAt", at,
                "resolution", resolution, "completionOdometer", km, "completionHours", hours));
        endDowntime(id, at, false);
        String org = FleetDb.org();
        db.jdbc().sql("UPDATE \"FleetDefect\" SET status = 'resolved', \"resolvedAt\" = ?, \"resolvedBy\" = ?,"
                        + " resolution = ?, \"updatedAt\" = " + FleetDb.NOW + " WHERE \"organizationId\" = ? AND"
                        + " \"workOrderId\" = ? AND status = 'in_repair'")
                .params(SqlRows.ts(at), TenantContext.userId(), resolution != null ? resolution
                        : "Repaired on work order " + code, org, id).update();
        if (w.get("scheduleId") != null) {
            Map<String, Object> s = Obj.of("lastDoneOn", at.atZone(BusinessZone.get()).toLocalDate(),
                    "lastWorkOrderId", id, "openWorkOrderId", null);
            if (km != null) {
                s.put("lastDoneKm", km);
            }
            if (hours != null) {
                s.put("lastDoneHours", hours);
            }
            db.update(FleetServiceSchedule.class, (String) w.get("scheduleId"), s);
        }
        units.release(vehicleId, (String) w.get("previousVehicleStatus"), "Work order " + code + " completed", id);
        events.publish(DomainEvents.FLEET_WORK_ORDER_COMPLETED, Obj.of("organizationId", org, "workOrderId", id,
                "code", code, "vehicleId", vehicleId, "type", w.get("type"), "totalCost", w.get("totalCost")));
        return detail(after);
    }

    /** {@code completed → closed}: final; no further cost lines. */
    @Transactional
    public Map<String, Object> close(String id) {
        Map<String, Object> w = require(id, true);
        MaintenancePolicies.assertWorkOrderTransition(code(w), (String) w.get("status"), MaintenancePolicies.CLOSED);
        return detail(transition(w, MaintenancePolicies.CLOSED, Obj.of("closedAt", Instant.now())));
    }

    /**
     * Cancel a work order that has no posted cost (reverse / return them first). Linked defects re-open, the schedule
     * is freed, the work order's own downtime ends and the unit is released.
     */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Map<String, Object> w = require(id, true);
        String code = code(w);
        String status = (String) w.get("status");
        MaintenancePolicies.assertWorkOrderTransition(code, status, MaintenancePolicies.CANCELLED);
        String org = FleetDb.org();
        long posted = db.jdbc().sql("SELECT count(*) FROM \"FleetWorkOrderLine\" WHERE \"organizationId\" = ? AND"
                + " \"workOrderId\" = ? AND status = 'posted'").params(org, id).query(Long.class).single();
        if (posted > 0) {
            throw ApiException.badRequest("Work order " + code + " has " + posted
                    + " posted cost line(s); reverse or return them before cancelling");
        }
        Instant now = Instant.now();
        Map<String, Object> after = transition(w, MaintenancePolicies.CANCELLED, Obj.of("cancelledAt", now,
                "cancelReason", reason.trim()));
        db.jdbc().sql("UPDATE \"FleetDefect\" SET status = 'open', \"workOrderId\" = NULL, \"updatedAt\" = "
                + FleetDb.NOW + " WHERE \"organizationId\" = ? AND \"workOrderId\" = ? AND status = 'in_repair'")
                .params(org, id).update();
        db.jdbc().sql("UPDATE \"FleetServiceSchedule\" SET \"openWorkOrderId\" = NULL, \"updatedAt\" = " + FleetDb.NOW
                + " WHERE \"organizationId\" = ? AND \"openWorkOrderId\" = ?").params(org, id).update();
        if (MaintenancePolicies.IN_PROGRESS.equals(status)) {
            endDowntime(id, now, true);
            units.release((String) w.get("vehicleId"), (String) w.get("previousVehicleStatus"),
                    "Work order " + code + " cancelled", id);
        }
        return detail(after);
    }

    /** Ends the open downtime linked to the work order; on cancel only the one it opened itself. */
    private void endDowntime(String workOrderId, Instant at, boolean ownOnly) {
        String org = FleetDb.org();
        db.jdbc().sql("UPDATE \"FleetDowntime\" SET \"endedAt\" = GREATEST(\"startedAt\", ?), \"endedBy\" = ?,"
                        + " \"updatedAt\" = " + FleetDb.NOW + " WHERE \"organizationId\" = ? AND \"workOrderId\" = ?"
                        + " AND \"endedAt\" IS NULL" + (ownOnly ? " AND source = 'work_order'" : ""))
                .params(SqlRows.ts(at), TenantContext.userId(), org, workOrderId).update();
        if (ownOnly) {
            db.jdbc().sql("UPDATE \"FleetDowntime\" SET \"workOrderId\" = NULL, \"updatedAt\" = " + FleetDb.NOW
                    + " WHERE \"organizationId\" = ? AND \"workOrderId\" = ? AND \"endedAt\" IS NULL")
                    .params(org, workOrderId).update();
        }
    }

    private Map<String, Object> transition(Map<String, Object> w, String to, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>(extra);
        data.put("status", to);
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetWorkOrder.class, (String) w.get("id"), data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) w.get("id"), "update", Obj.of("status",
                w.get("status")), Obj.of("status", to)));
        return after;
    }

    // ------------------------------------------------------------------ tasks

    @Transactional
    public Map<String, Object> addTask(String id, TaskInput in) {
        Map<String, Object> w = require(id, true);
        assertEditable(w);
        int next = db.jdbc().sql("SELECT COALESCE(MAX(\"sortOrder\"), 0) + 1 FROM \"FleetWorkOrderTask\" WHERE"
                + " \"organizationId\" = ? AND \"workOrderId\" = ?").params(FleetDb.org(), id).query(Integer.class)
                .single();
        db.insert(FleetWorkOrderTask.class, Obj.of("workOrderId", id, "sortOrder", next, "description",
                in.description().trim()));
        return detail(w);
    }

    @Transactional
    public Map<String, Object> updateTask(String id, String taskId, TaskPatch in) {
        Map<String, Object> w = require(id, true);
        assertEditable(w);
        Map<String, Object> t = task(id, taskId);
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "description", trim(in.description()));
        put(data, "notes", trim(in.notes()));
        if (in.status() != null && !in.status().equals(t.get("status"))) {
            data.put("status", in.status());
            boolean done = !"pending".equals(in.status());
            data.put("doneBy", done ? TenantContext.userId() : null);
            data.put("doneAt", done ? Instant.now() : null);
        }
        if (!data.isEmpty()) {
            db.update(FleetWorkOrderTask.class, taskId, data);
        }
        return detail(w);
    }

    @Transactional
    public Map<String, Object> deleteTask(String id, String taskId) {
        Map<String, Object> w = require(id, true);
        assertEditable(w);
        task(id, taskId);
        db.jdbc().sql("DELETE FROM \"FleetWorkOrderTask\" WHERE id = ? AND \"organizationId\" = ?")
                .params(taskId, FleetDb.org()).update();
        return detail(w);
    }

    private Map<String, Object> task(String workOrderId, String taskId) {
        Map<String, Object> t = db.byId(FleetWorkOrderTask.class, taskId, true);
        if (t == null || !workOrderId.equals(t.get("workOrderId"))) {
            throw ApiException.notFound("Task not found");
        }
        return t;
    }

    // ------------------------------------------------------------------ for the cost service

    /** Recompute the cost totals from the posted lines. */
    void refreshTotals(String workOrderId) {
        db.jdbc().sql("""
                UPDATE "FleetWorkOrder" w SET
                  "partsCost" = t.parts, "labourCost" = t.labour, "externalCost" = t.ext,
                  "totalCost" = t.parts + t.labour + t.ext, "updatedAt" = """ + FleetDb.NOW + """
                 FROM (SELECT COALESCE(SUM(amount) FILTER (WHERE "lineType" IN ('part', 'part_return')), 0) AS parts,
                              COALESCE(SUM(amount) FILTER (WHERE "lineType" = 'labour'), 0) AS labour,
                              COALESCE(SUM(amount) FILTER (WHERE "lineType" = 'external'), 0) AS ext
                       FROM "FleetWorkOrderLine" WHERE "organizationId" = ? AND "workOrderId" = ?
                         AND status = 'posted') t
                WHERE w.id = ? AND w."organizationId" = ?""")
                .params(FleetDb.org(), workOrderId, workOrderId, FleetDb.org()).update();
    }

    Map<String, Object> detailOf(String id) {
        return detail(require(id, false));
    }

    // ------------------------------------------------------------------ helpers

    private static void assertEditable(Map<String, Object> w) {
        if (!MaintenancePolicies.editable((String) w.get("status"))) {
            throw ApiException.badRequest("Work order " + w.get("code") + " is "
                    + MaintenancePolicies.label((String) w.get("status")) + " and can no longer be edited");
        }
    }

    void assertEmployee(String employeeId) {
        if (notBlank(employeeId) && !db.exists("HrEmployee", employeeId, true)) {
            throw ApiException.badRequest("Employee not found");
        }
    }

    Map<String, Object> employee(String employeeId) {
        return employeeId == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, \"employeeCode\", \"firstName\","
                + " \"lastName\", \"hourlyRate\" FROM \"HrEmployee\" WHERE id = ? AND \"organizationId\" = ?")
                .params(employeeId, FleetDb.org()));
    }

    private String nextCode() {
        for (int i = 0; i < 20; i++) {
            String code = sequence.next("fleet_work_order", "WO-", 5);
            if (db.jdbc().sql("SELECT count(*) FROM \"FleetWorkOrder\" WHERE \"organizationId\" = ? AND code = ?")
                    .params(FleetDb.org(), code).query(Long.class).single() == 0) {
                return code;
            }
        }
        throw ApiException.conflict("Could not allocate a work order number");
    }

    static String code(Map<String, Object> w) {
        return (String) w.get("code");
    }
}
