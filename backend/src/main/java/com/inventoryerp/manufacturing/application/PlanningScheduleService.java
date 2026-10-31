package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.PlanningCalendar.Slot;
import com.inventoryerp.manufacturing.application.PlanningCommands.BackwardInput;
import com.inventoryerp.manufacturing.application.PlanningCommands.OrderPlanningInput;
import com.inventoryerp.manufacturing.application.PlanningCommands.RescheduleInput;
import com.inventoryerp.manufacturing.application.PlanningCommands.ScheduleOpenInput;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import com.inventoryerp.manufacturing.domain.WorkCenterClosure;
import com.inventoryerp.manufacturing.domain.WorkCenterShift;
import com.inventoryerp.manufacturing.domain.WorkOrder;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backward scheduling, the Gantt board and manual rescheduling (Track C 2.5, ADR-S019). An order's open work orders are
 * placed backward from its due date, last operation first, through each centre's calendar and around the load already
 * there. Curing is a calendar lag, not machine time: a stage with minimum curing days keeps that gap between the end of
 * the previous operation and the start of its own operation (blocks cure between the press and cubing); on the first
 * operation the lag follows it. An order that cannot fit before now is placed forward from now instead, and its
 * lateness
 * is stored. Run time comes from the centre's rate when it has one: t/h of the crushing unit or the ideal rate of the
 * shop-floor profile. A per-centre advisory lock keeps two planners from double-booking a slot.
 */
@Service
public class PlanningScheduleService {

    private static final Set<String> OPEN_ORDER = Set.of("draft", "confirmed", "in_progress");

    private final MfgDb db;
    private final WorkOrderService workOrders;
    private final PlanningPegService pegs;
    private final TransactionTemplate tx;

    public PlanningScheduleService(MfgDb db, WorkOrderService workOrders, PlanningPegService pegs,
            PlatformTransactionManager txm) {
        this.db = db;
        this.workOrders = workOrders;
        this.pegs = pegs;
        this.tx = new TransactionTemplate(txm);
    }

    private record Step(Map<String, Object> wo, long minutes, int curingDays) {}

    // ================================================================== order planning record

    /** Open orders ranked for planning: highest priority, then earliest due date; unscheduled ones flagged. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> board(String status, Integer limit, Integer offset) {
        String where = status != null && !status.isBlank() ? " AND o.status = ?::\"ProductionOrderStatus\""
                : " AND o.status IN ('draft', 'confirmed', 'in_progress')";
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (status != null && !status.isBlank()) {
            params.add(status);
        }
        return SqlRows.list(db.jdbc().sql("SELECT o.id, o.\"orderCode\", o.status, o.\"outputProductId\","
                + " pr.name AS \"productName\", o.\"plannedQty\", o.\"producedQty\", o.\"scheduledFor\", g.\"dueAt\","
                + " coalesce(g.priority, 2) AS priority, g.\"priorityReason\", g.\"partnerId\", pa.name AS"
                + " \"partnerName\","
                + " coalesce(g.\"scheduleMode\", 'none') AS \"scheduleMode\", g.\"plannedStartAt\", g.\"plannedEndAt\","
                + " g.\"availableAt\", coalesce(g.\"curingDays\", 0) AS \"curingDays\","
                + " coalesce(g.\"lateMinutes\", 0) AS \"lateMinutes\" FROM \"ProductionOrder\" o"
                + " LEFT JOIN \"ProductionOrderPlanning\" g ON g.\"productionOrderId\" = o.id"
                + " LEFT JOIN \"Product\" pr ON pr.id = o.\"outputProductId\" LEFT JOIN \"Partner\" pa ON pa.id ="
                + " g.\"partnerId\" WHERE o.\"organizationId\" = ?" + where
                + " ORDER BY coalesce(g.priority, 2) DESC, g.\"dueAt\" ASC NULLS LAST, o.\"createdAt\" ASC"
                + (limit == null ? " LIMIT 500" : "") + MfgDb.page(limit, offset)).params(params));
    }

    /** Set the due date and priority of an order. */
    @Transactional
    public Map<String, Object> saveOrderPlanning(String orderId, OrderPlanningInput dto) {
        lockOrder(orderId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.dueAt() != null) {
            data.put("dueAt", EntityPatch.toInstant(dto.dueAt()));
        }
        if (dto.priority() != null) {
            data.put("priority", dto.priority());
        }
        if (dto.priorityReason() != null) {
            data.put("priorityReason", dto.priorityReason());
        }
        return pegs.upsert(orderId, data);
    }

    // ================================================================== backward scheduling

    /**
     * Place the order backward from {@code dueAt} (default its stored due date); returns the result and work orders.
     */
    @Transactional
    public Map<String, Object> scheduleBackward(String orderId, BackwardInput dto) {
        Map<String, Object> order = lockOrder(orderId);
        if (!OPEN_ORDER.contains(str(order, "status"))) {
            throw ApiException.badRequest("Order " + order.get("orderCode") + " is " + order.get("status")
                    + "; only open orders can be scheduled.");
        }
        Map<String, Object> existing = pegs.planning(orderId);
        Instant due = dto != null && dto.dueAt() != null ? EntityPatch.toInstant(dto.dueAt())
                : existing == null ? null : (Instant) existing.get("dueAt");
        if (due == null) {
            throw ApiException.badRequest("Set a due date first.");
        }
        workOrders.generateForOrder(orderId);
        List<Step> steps = steps(order);
        if (steps.isEmpty()) {
            throw ApiException.badRequest("No open work orders to schedule.");
        }
        lockCenters(steps);
        Map<String, Slot> placed = backward(steps, due);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        boolean feasible = !placed.get(str(steps.getFirst().wo(), "id")).start().isBefore(now);
        if (!feasible) {
            placed = forward(steps, now);
        }
        return save(orderId, steps, placed, due, feasible ? "backward" : "forward", feasible);
    }

    /**
     * Backward-schedule every open order with a due date, highest priority first, so urgent work gets the slots. Each
     * order runs in its own transaction: one that cannot be scheduled (no routing) is reported and the rest go on.
     */
    public Map<String, Object> scheduleOpenOrders(ScheduleOpenInput dto) {
        String where = "";
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (dto != null && dto.dueFrom() != null) {
            where += " AND g.\"dueAt\" >= ?";
            params.add(java.sql.Date.valueOf(dto.dueFrom()));
        }
        if (dto != null && dto.dueTo() != null) {
            where += " AND g.\"dueAt\" < ?";
            params.add(java.sql.Date.valueOf(LocalDate.parse(dto.dueTo()).plusDays(1)));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        String sql = "SELECT o.id, o.\"orderCode\" FROM \"ProductionOrderPlanning\" g JOIN \"ProductionOrder\" o ON"
                + " o.id = g.\"productionOrderId\" WHERE g.\"organizationId\" = ? AND g.\"dueAt\" IS NOT NULL"
                + " AND o.status IN ('draft', 'confirmed')" + where + " ORDER BY g.priority DESC, g.\"dueAt\" ASC";
        for (Map<String, Object> o : tx.execute(s -> SqlRows.list(db.jdbc().sql(sql).params(params)))) {
            try {
                Map<String, Object> r = tx.execute(s -> scheduleBackward(str(o, "id"), null));
                results.add(Obj.of("orderId", o.get("id"), "orderCode", o.get("orderCode"), "feasible",
                        r.get("feasible"), "lateMinutes", r.get("lateMinutes"), "plannedStartAt",
                                r.get("plannedStartAt"),
                        "plannedEndAt", r.get("plannedEndAt")));
            } catch (ApiException e) {
                skipped.add(Obj.of("orderId", o.get("id"), "orderCode", o.get("orderCode"), "reason", e.getMessage()));
            }
        }
        return Obj.of("scheduled", results.size(), "results", results, "skipped", skipped);
    }

    private List<Step> steps(Map<String, Object> order) {
        String orderId = str(order, "id");
        Map<String, Integer> stageCuring = new LinkedHashMap<>();
        for (Map<String, Object> s : SqlRows.list(db.jdbc().sql("SELECT \"routingOperationId\", \"minCuringDays\" FROM"
                + " \"ProductionOrderStage\" WHERE \"organizationId\" = ? AND \"orderId\" = ?"
                + " AND \"routingOperationId\" IS NOT NULL").params(MfgDb.org(), orderId))) {
            stageCuring.put(str(s, "routingOperationId"), ((Number) s.get("minCuringDays")).intValue());
        }
        BigDecimal qty = dec(order, "plannedQty").subtract(dec(order, "producedQty")).max(BigDecimal.ZERO);
        List<Step> steps = new ArrayList<>();
        for (Map<String, Object> wo : db.list(WorkOrder.class, " AND \"productionOrderId\" = ?"
                + " AND status NOT IN ('completed', 'cancelled') ORDER BY \"operationSequence\" ASC", orderId)) {
            String op = str(wo, "routingOperationId");
            int curing = op == null ? 0 : stageCuring.containsKey(op) ? stageCuring.get(op) : setupCuring(op);
            steps.add(new Step(wo, minutes(wo, qty), curing));
        }
        if (stageCuring.isEmpty() && !steps.isEmpty() && order.get("bomId") != null) {
            int mix = db.jdbc().sql("SELECT coalesce(max(\"minCuringDays\"), 0)::int FROM \"PrecastMixDesign\" WHERE"
                    + " \"organizationId\" = ? AND \"bomId\" = ?").params(MfgDb.org(), order.get("bomId"))
                    .query(Integer.class).single();
            Step last = steps.getLast();
            if (mix > last.curingDays()) {
                steps.set(steps.size() - 1, new Step(last.wo(), last.minutes(), mix));
            }
        }
        return steps;
    }

    private int setupCuring(String routingOperationId) {
        return db.jdbc().sql("SELECT coalesce(max(\"minCuringDays\"), 0)::int FROM \"RoutingStageSetup\" WHERE"
                + " \"organizationId\" = ? AND \"routingOperationId\" = ?").params(MfgDb.org(), routingOperationId)
                .query(Integer.class).single();
    }

    private long minutes(Map<String, Object> wo, BigDecimal qty) {
        int planned = wo.get("plannedDurationMins") instanceof Number n ? n.intValue() : 0;
        String wcId = str(wo, "workCenterId");
        if (wcId == null) {
            return Math.max(0, planned);
        }
        Map<String, Object> wc = db.byId(WorkCenter.class, wcId);
        return PlanningCalendar.operationMinutes(planned, qty, ratePerHour(wcId),
                wc == null ? null : dec(wc, "efficiencyPct"));
    }

    /** The centre's rate: the crushing unit's t/h (lowest when shared), else the shop-floor profile's ideal rate. */
    BigDecimal ratePerHour(String workCenterId) {
        BigDecimal tph = db.jdbc().sql("SELECT min(\"capacityTph\") FROM \"CrushingPlantUnit\" WHERE \"organizationId\""
                + " = ?"
                + " AND \"workCenterId\" = ? AND \"isActive\" = true AND \"capacityTph\" > 0")
                .params(MfgDb.org(), workCenterId).query(BigDecimal.class).optional().orElse(null);
        if (tph != null) {
            return tph;
        }
        return db.jdbc().sql("SELECT \"idealRatePerHour\" FROM \"ShopFloorWorkCenterProfile\" WHERE"
                + " \"organizationId\" = ? AND \"workCenterId\" = ?").params(MfgDb.org(), workCenterId)
                .query(BigDecimal.class).optional().orElse(null);
    }

    /** Last operation first, each ending where the next one's start and curing lag allow. */
    private Map<String, Slot> backward(List<Step> steps, Instant due) {
        Map<String, Slot> out = new LinkedHashMap<>();
        Map<String, PlanningCalendar> calendars = new LinkedHashMap<>();
        String orderId = str(steps.getFirst().wo(), "productionOrderId");
        Instant cursor = due;
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step s = steps.get(i);
            if (i == 0) {
                cursor = cursor.minus(s.curingDays(), ChronoUnit.DAYS);
            }
            Slot slot = calendar(calendars, s, orderId, null).backward(cursor, s.minutes());
            requireSlot(slot, s);
            out.put(str(s.wo(), "id"), slot);
            cursor = slot.start();
            if (i > 0) {
                cursor = cursor.minus(s.curingDays(), ChronoUnit.DAYS);
            }
        }
        return out;
    }

    /** First operation first, from {@code from}, keeping the same curing lags. */
    private Map<String, Slot> forward(List<Step> steps, Instant from) {
        Map<String, Slot> out = new LinkedHashMap<>();
        Map<String, PlanningCalendar> calendars = new LinkedHashMap<>();
        String orderId = str(steps.getFirst().wo(), "productionOrderId");
        Instant cursor = from;
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (i > 0) {
                cursor = cursor.plus(s.curingDays(), ChronoUnit.DAYS);
            }
            Slot slot = calendar(calendars, s, orderId, null).forward(cursor, s.minutes());
            requireSlot(slot, s);
            out.put(str(s.wo(), "id"), slot);
            cursor = i == 0 ? slot.end().plus(s.curingDays(), ChronoUnit.DAYS) : slot.end();
        }
        return out;
    }

    private static void requireSlot(Slot slot, Step s) {
        if (slot == null) {
            throw ApiException.badRequest("No capacity for operation " + s.wo().get("name") + " within two years.");
        }
    }

    private PlanningCalendar calendar(Map<String, PlanningCalendar> cache, Step s, String orderId, String except) {
        String wcId = str(s.wo(), "workCenterId");
        return wcId == null ? new PlanningCalendar(Map.of(), Set.of(), List.of(), 1)
                : cache.computeIfAbsent(wcId, id -> calendarOf(id, orderId, except));
    }

    /** The calendar of a centre with the load of other orders (and other work orders) on it. */
    PlanningCalendar calendarOf(String wcId, String exceptOrderId, String exceptWorkOrderId) {
        Map<String, Object> wc = db.byId(WorkCenter.class, wcId);
        List<Instant[]> busy = new ArrayList<>();
        for (Map<String, Object> b : SqlRows.list(db.jdbc().sql("SELECT id, \"productionOrderId\", \"plannedStartAt\","
                + " \"plannedEndAt\" FROM \"WorkOrder\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ?"
                + " AND status NOT IN ('completed', 'cancelled') AND \"plannedStartAt\" IS NOT NULL"
                + " AND \"plannedEndAt\" IS NOT NULL").params(MfgDb.org(), wcId))) {
            if (str(b, "productionOrderId").equals(exceptOrderId) || str(b, "id").equals(exceptWorkOrderId)) {
                continue;
            }
            busy.add(new Instant[] {(Instant) b.get("plannedStartAt"), (Instant) b.get("plannedEndAt")});
        }
        return new PlanningCalendar(shifts(wcId), closures(wcId), busy,
                wc != null && wc.get("parallelSlots") instanceof Number n ? n.intValue() : 1);
    }

    Map<Integer, List<int[]>> shifts(String wcId) {
        Map<Integer, List<int[]>> shifts = new LinkedHashMap<>();
        for (Map<String, Object> s : db.list(WorkCenterShift.class, " AND \"workCenterId\" = ?", wcId)) {
            shifts.computeIfAbsent(((Number) s.get("weekday")).intValue(), k -> new ArrayList<>()).add(
                    new int[] {((Number) s.get("startMinute")).intValue(), ((Number) s.get("endMinute")).intValue()});
        }
        return shifts;
    }

    Set<LocalDate> closures(String wcId) {
        Set<LocalDate> closed = new HashSet<>();
        for (Map<String, Object> c : db.list(WorkCenterClosure.class, " AND (\"workCenterId\" = ? OR \"workCenterId\""
                + " IS NULL)", wcId)) {
            closed.add((LocalDate) c.get("date"));
        }
        return closed;
    }

    private Map<String, Object> save(String orderId, List<Step> steps, Map<String, Slot> placed, Instant due,
            String mode,
            boolean feasible) {
        Instant start = null;
        Instant end = null;
        for (Step s : steps) {
            Slot slot = placed.get(str(s.wo(), "id"));
            db.update(WorkOrder.class, str(s.wo(), "id"), Obj.of("plannedStartAt", slot.start(), "plannedEndAt",
                    slot.end(), "updatedBy", TenantContext.userId()));
            start = start == null || slot.start().isBefore(start) ? slot.start() : start;
            end = end == null || slot.end().isAfter(end) ? slot.end() : end;
        }
        int firstLag = steps.getFirst().curingDays();
        Instant available = end.plus(steps.size() == 1 ? firstLag : 0, ChronoUnit.DAYS);
        long late = available.isAfter(due) ? Duration.between(due, available).toMinutes() : 0;
        int curing = steps.stream().mapToInt(Step::curingDays).sum();
        db.update(ProductionOrder.class, orderId, Obj.of("scheduledFor", start, "updatedBy", TenantContext.userId()));
        Map<String, Object> row = pegs.upsert(orderId, Obj.of("dueAt", due, "scheduleMode", mode, "plannedStartAt",
                start,
                "plannedEndAt", end, "availableAt", available, "curingDays", curing, "lateMinutes", late, "scheduledAt",
                Instant.now()));
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("feasible", feasible);
        out.put("workOrders", workOrders.list(orderId));
        return out;
    }

    // ================================================================== manual reschedule

    /**
     * A Gantt drag: the work order moves to the first free working minute at or after {@code startAt}, optionally on
     * another centre. Calendar and capacity are enforced; a clash with the operation before or after it, or with a
     * curing lag, comes back as a warning because the planner may be moving the whole chain one bar at a time.
     */
    @Transactional
    public Map<String, Object> reschedule(String workOrderId, RescheduleInput dto) {
        Map<String, Object> wo = lockWorkOrder(workOrderId);
        String status = str(wo, "status");
        if (!"pending".equals(status) && !"paused".equals(status)) {
            throw ApiException.badRequest("Cannot reschedule a " + status + " work order.");
        }
        Map<String, Object> order = lockOrder(str(wo, "productionOrderId"));
        String wcId = dto.workCenterId() != null && !dto.workCenterId().isBlank() ? dto.workCenterId()
                : str(wo, "workCenterId");
        if (wcId != null && db.byId(WorkCenter.class, wcId) == null) {
            throw ApiException.notFound("Work centre not found");
        }
        if (wcId != null) {
            lockCenter(wcId);
        }
        BigDecimal qty = dec(order, "plannedQty").subtract(dec(order, "producedQty")).max(BigDecimal.ZERO);
        Map<String, Object> moved = new LinkedHashMap<>(wo);
        moved.put("workCenterId", wcId);
        long minutes = minutes(moved, qty);
        Instant asked = EntityPatch.toInstant(dto.startAt());
        PlanningCalendar calendar = wcId == null ? new PlanningCalendar(Map.of(), Set.of(), List.of(), 1)
                : calendarOf(wcId, null, workOrderId);
        Slot slot = calendar.forward(asked, minutes);
        if (slot == null) {
            throw ApiException.badRequest("No capacity at this work centre within two years.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plannedStartAt", slot.start());
        data.put("plannedEndAt", slot.end());
        data.put("workCenterId", wcId);
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> updated = db.update(WorkOrder.class, workOrderId, data);

        List<String> warnings = warnings(order, updated);
        Map<String, Object> planning = refreshOrderWindow(order);
        return Obj.of("workOrder", updated, "planning", planning, "snapped",
                !slot.start().equals(asked.truncatedTo(ChronoUnit.MINUTES)), "warnings", warnings);
    }

    private List<String> warnings(Map<String, Object> order, Map<String, Object> wo) {
        List<String> out = new ArrayList<>();
        int sequence = ((Number) wo.get("operationSequence")).intValue();
        Instant start = (Instant) wo.get("plannedStartAt");
        Instant end = (Instant) wo.get("plannedEndAt");
        List<Step> steps = steps(order);
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> other = steps.get(i).wo();
            int seq = ((Number) other.get("operationSequence")).intValue();
            Instant oStart = (Instant) other.get("plannedStartAt");
            Instant oEnd = (Instant) other.get("plannedEndAt");
            if (oStart == null || oEnd == null || seq == sequence) {
                continue;
            }
            if (seq < sequence) {
                Instant earliest = oEnd.plus(curingOf(steps, sequence), ChronoUnit.DAYS);
                if (start.isBefore(earliest)) {
                    out.add("Starts before " + other.get("name") + " has finished"
                            + (curingOf(steps, sequence) > 0 ? " and cured." : "."));
                }
            } else if (end.isAfter(oStart.minus(steps.get(i).curingDays(), ChronoUnit.DAYS))) {
                out.add("Ends after " + other.get("name") + " should start.");
            }
        }
        return out;
    }

    private static int curingOf(List<Step> steps, int sequence) {
        for (Step s : steps) {
            if (((Number) s.wo().get("operationSequence")).intValue() == sequence) {
                return s.curingDays();
            }
        }
        return 0;
    }

    /** Recompute the order's window from its work orders after a manual move. */
    private Map<String, Object> refreshOrderWindow(Map<String, Object> order) {
        String orderId = str(order, "id");
        Map<String, Object> w = SqlRows.one(db.jdbc().sql("SELECT min(\"plannedStartAt\") AS start,"
                + " max(\"plannedEndAt\")"
                + " AS stop FROM \"WorkOrder\" WHERE \"organizationId\" = ? AND \"productionOrderId\" = ?"
                + " AND status NOT IN ('completed', 'cancelled')").params(MfgDb.org(), orderId));
        Map<String, Object> planning = pegs.planning(orderId);
        Instant end = w == null ? null : (Instant) w.get("stop");
        Instant due = planning == null ? null : (Instant) planning.get("dueAt");
        long late = due != null && end != null && end.isAfter(due) ? Duration.between(due, end).toMinutes() : 0;
        return pegs.upsert(orderId, Obj.of("scheduleMode", "manual", "plannedStartAt",
                w == null ? null : w.get("start"),
                "plannedEndAt", end, "availableAt", end, "lateMinutes", late, "scheduledAt", Instant.now()));
    }

    // ================================================================== Gantt

    /**
     * Gantt data: a lane per active work centre with its bars (order, due date, priority, late flag), the closed dates
     * and weekly shifts for shading, and the open work orders nobody has placed yet.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> gantt(String from, String to, String workCenterId) {
        Instant f = from != null && !from.isEmpty() ? EntityPatch.toInstant(from)
                : Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant t = to != null && !to.isEmpty() ? EntityPatch.toInstant(to) : f.plus(14, ChronoUnit.DAYS);
        if (t.isBefore(f)) {
            throw ApiException.badRequest("to must not be before from");
        }
        Map<String, Map<String, Object>> lanes = new LinkedHashMap<>();
        for (Map<String, Object> c : db.list(WorkCenter.class, " AND \"isActive\" = true ORDER BY code")) {
            String id = str(c, "id");
            if (workCenterId != null && !workCenterId.isBlank() && !workCenterId.equals(id)) {
                continue;
            }
            Map<String, Object> lane = new LinkedHashMap<>();
            lane.put("workCenterId", id);
            lane.put("code", c.get("code"));
            lane.put("name", c.get("name"));
            lane.put("parallelSlots", c.get("parallelSlots"));
            lane.put("shifts", db.list(WorkCenterShift.class, " AND \"workCenterId\" = ? ORDER BY weekday,"
                    + " \"startMinute\"",
                    id));
            lane.put("closedDates", closedBetween(id, f, t));
            lane.put("tasks", new ArrayList<Map<String, Object>>());
            lanes.put(id, lane);
        }
        List<Map<String, Object>> unassigned = new ArrayList<>();
        for (Map<String, Object> b : SqlRows.list(db.jdbc().sql(BAR_SELECT + " WHERE w.\"organizationId\" = ?"
                + " AND w.status NOT IN ('completed', 'cancelled') AND w.\"plannedStartAt\" < ?"
                + " AND w.\"plannedEndAt\" > ? ORDER BY w.\"plannedStartAt\"").params(MfgDb.org(), MfgDb.param(t),
                        MfgDb.param(f)))) {
            Map<String, Object> lane = lanes.get(str(b, "workCenterId"));
            if (lane != null) {
                tasks(lane).add(bar(b));
            } else if (b.get("workCenterId") == null) {
                unassigned.add(bar(b));
            }
        }
        List<Map<String, Object>> unscheduled = SqlRows.list(db.jdbc().sql(BAR_SELECT + " WHERE w.\"organizationId\" ="
                + " ?"
                + " AND w.status IN ('pending', 'paused') AND w.\"plannedStartAt\" IS NULL"
                + " AND o.status IN ('draft', 'confirmed', 'in_progress')"
                + " ORDER BY coalesce(g.priority, 2) DESC, g.\"dueAt\" ASC NULLS LAST LIMIT 200").param(MfgDb.org()));
        return Obj.of("from", f, "to", t, "lanes", new ArrayList<>(lanes.values()), "unassigned", unassigned,
                "unscheduled", unscheduled);
    }

    private static final String BAR_SELECT = "SELECT w.id, w.name, w.status, w.\"workCenterId\", w.\"plannedStartAt\","
            + " w.\"plannedEndAt\", w.\"plannedDurationMins\", w.\"operationSequence\", w.\"productionOrderId\","
            + " o.\"orderCode\", o.status AS \"orderStatus\", o.\"plannedQty\", pr.name AS \"productName\","
            + " g.\"dueAt\","
            + " coalesce(g.priority, 2) AS priority, coalesce(g.\"lateMinutes\", 0) AS \"lateMinutes\","
            + " pa.name AS \"partnerName\" FROM \"WorkOrder\" w JOIN \"ProductionOrder\" o ON o.id ="
            + " w.\"productionOrderId\" LEFT JOIN \"ProductionOrderPlanning\" g ON g.\"productionOrderId\" = o.id"
            + " LEFT JOIN \"Product\" pr ON pr.id = o.\"outputProductId\" LEFT JOIN \"Partner\" pa ON pa.id ="
            + " g.\"partnerId\"";

    private static Map<String, Object> bar(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        Instant end = (Instant) row.get("plannedEndAt");
        Instant due = (Instant) row.get("dueAt");
        out.put("late", end != null && due != null && end.isAfter(due));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tasks(Map<String, Object> lane) {
        return (List<Map<String, Object>>) lane.get("tasks");
    }

    private List<String> closedBetween(String wcId, Instant from, Instant to) {
        List<String> out = new ArrayList<>();
        LocalDate a = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate b = to.atZone(ZoneOffset.UTC).toLocalDate();
        for (LocalDate d : closures(wcId)) {
            if (!d.isBefore(a) && !d.isAfter(b)) {
                out.add(d.toString());
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    // ================================================================== locks

    private Map<String, Object> lockOrder(String orderId) {
        Map<String, Object> order = SqlRows.one(ProductionOrder.class, db.jdbc().sql("SELECT * FROM \"ProductionOrder\""
                + " WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(orderId, MfgDb.org()));
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        return order;
    }

    private Map<String, Object> lockWorkOrder(String id) {
        Map<String, Object> wo = SqlRows.one(WorkOrder.class, db.jdbc().sql("SELECT * FROM \"WorkOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? FOR UPDATE").params(id, MfgDb.org()));
        if (wo == null) {
            throw ApiException.notFound("Work order not found");
        }
        return wo;
    }

    private void lockCenters(List<Step> steps) {
        Set<String> ids = new java.util.TreeSet<>();
        for (Step s : steps) {
            if (s.wo().get("workCenterId") != null) {
                ids.add(str(s.wo(), "workCenterId"));
            }
        }
        ids.forEach(this::lockCenter);
    }

    private void lockCenter(String wcId) {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param("wc:" + MfgDb.org() + ":" + wcId)
                .query().listOfRows();
    }
}
