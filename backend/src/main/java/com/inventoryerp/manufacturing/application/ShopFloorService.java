package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CalendarInput;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.MaterialInput;
import com.inventoryerp.manufacturing.application.MfgCommands.ReworkInput;
import com.inventoryerp.manufacturing.application.MfgCommands.TimeLogInput;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import com.inventoryerp.manufacturing.domain.WorkCenterClosure;
import com.inventoryerp.manufacturing.domain.WorkCenterShift;
import com.inventoryerp.manufacturing.domain.WorkOrder;
import com.inventoryerp.manufacturing.domain.WorkOrderTimeLog;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shop floor (ADR-S006 package C): work-centre calendars, finite forward scheduling of an order's work orders,
 * operator time logs and rework orders. The business clock is UTC.
 */
@Service
public class ShopFloorService {

    private static final long SEARCH_LIMIT_MINUTES = 366L * 24 * 60;

    private final MfgDb db;
    private final ProductionService production;
    private final WorkOrderService workOrders;

    public ShopFloorService(MfgDb db, ProductionService production, WorkOrderService workOrders) {
        this.db = db;
        this.production = production;
        this.workOrders = workOrders;
    }

    // ================================================================== calendars

    @Transactional(readOnly = true)
    public Map<String, Object> calendar(String workCenterId) {
        Map<String, Object> wc = requireCenter(workCenterId);
        return Obj.of("workCenter", wc,
                "shifts", db.list(WorkCenterShift.class, " AND \"workCenterId\" = ? ORDER BY weekday, \"startMinute\"",
                        workCenterId),
                "closures", db.list(WorkCenterClosure.class, " AND (\"workCenterId\" = ? OR \"workCenterId\" IS NULL)"
                        + " ORDER BY date", workCenterId));
    }

    /** Replace the weekly shifts (and, when given, the centre's own closures). */
    @Transactional
    public Map<String, Object> saveCalendar(String workCenterId, CalendarInput dto) {
        requireCenter(workCenterId);
        if (dto.shifts() != null) {
            db.jdbc().sql("DELETE FROM \"WorkCenterShift\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ?")
                    .params(MfgDb.org(), workCenterId).update();
            for (MfgCommands.ShiftInput s : dto.shifts()) {
                if (s.endMinute() <= s.startMinute()) {
                    throw ApiException.badRequest("Shift end must be after its start.");
                }
                db.insert(WorkCenterShift.class, Obj.of("workCenterId", workCenterId, "weekday",
                        s.weekday().shortValue(), "startMinute", s.startMinute(), "endMinute", s.endMinute()));
            }
        }
        if (dto.closures() != null) {
            db.jdbc().sql("DELETE FROM \"WorkCenterClosure\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ?")
                    .params(MfgDb.org(), workCenterId).update();
            for (MfgCommands.ClosureInput c : dto.closures()) {
                db.insert(WorkCenterClosure.class, Obj.of("workCenterId", workCenterId, "date",
                        LocalDate.parse(c.date().substring(0, 10)), "reason", c.reason()));
            }
        }
        return calendar(workCenterId);
    }

    // ================================================================== finite scheduling

    /**
     * Place the order's work orders forward from {@code startAt} (default now), in operation sequence, through each
     * centre's working windows and around the load already scheduled there (up to its parallel slots). Duration =
     * planned minutes ÷ the centre's efficiency.
     */
    @Transactional
    public List<Map<String, Object>> schedule(String orderId, String startAt) {
        Map<String, Object> order = db.byId(ProductionOrder.class, orderId);
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        List<Map<String, Object>> wos = db.list(WorkOrder.class, " AND \"productionOrderId\" = ?"
                + " AND status NOT IN ('completed', 'cancelled') ORDER BY \"operationSequence\" ASC", orderId);
        if (wos.isEmpty()) {
            throw ApiException.badRequest("No open work orders to schedule. Generate them first.");
        }
        Instant cursor = startAt != null && !startAt.isEmpty() ? EntityPatch.toInstant(startAt) : Instant.now();
        cursor = cursor.truncatedTo(ChronoUnit.MINUTES);
        for (Map<String, Object> wo : wos) {
            String wcId = str(wo, "workCenterId");
            int planned = wo.get("plannedDurationMins") instanceof Number n ? n.intValue() : 0;
            Instant[] slot;
            if (wcId == null) {
                slot = new Instant[] {cursor, cursor.plus(planned, ChronoUnit.MINUTES)};
            } else {
                Map<String, Object> wc = db.byId(WorkCenter.class, wcId);
                BigDecimal eff = wc == null ? BigDecimal.valueOf(100) : dec(wc, "efficiencyPct");
                long minutes = BigDecimal.valueOf(planned).multiply(BigDecimal.valueOf(100))
                        .divide(eff.signum() > 0 ? eff : BigDecimal.valueOf(100), 0, RoundingMode.CEILING).longValue();
                slot = place(wcId, wc, orderId, cursor, minutes);
            }
            db.update(WorkOrder.class, str(wo, "id"), Obj.of("plannedStartAt", slot[0], "plannedEndAt", slot[1],
                    "updatedBy", TenantContext.userId()));
            cursor = slot[1];
        }
        return workOrders.list(orderId);
    }

    private Instant[] place(String wcId, Map<String, Object> wc, String orderId, Instant from, long minutes) {
        Map<Integer, List<int[]>> shifts = new LinkedHashMap<>();
        for (Map<String, Object> s : db.list(WorkCenterShift.class, " AND \"workCenterId\" = ?", wcId)) {
            shifts.computeIfAbsent(((Number) s.get("weekday")).intValue(), k -> new ArrayList<>())
                    .add(new int[] {((Number) s.get("startMinute")).intValue(), ((Number) s.get("endMinute")).intValue()});
        }
        Set<LocalDate> closed = new HashSet<>();
        for (Map<String, Object> c : db.list(WorkCenterClosure.class, " AND (\"workCenterId\" = ? OR \"workCenterId\""
                + " IS NULL) AND date >= ?", wcId, java.sql.Date.valueOf(from.atZone(ZoneOffset.UTC).toLocalDate()))) {
            closed.add((LocalDate) c.get("date"));
        }
        List<Instant[]> busy = new ArrayList<>();
        for (Map<String, Object> b : SqlRows.list(db.jdbc().sql("SELECT \"plannedStartAt\", \"plannedEndAt\" FROM"
                + " \"WorkOrder\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND \"productionOrderId\" <> ?"
                + " AND status NOT IN ('completed', 'cancelled') AND \"plannedStartAt\" IS NOT NULL"
                + " AND \"plannedEndAt\" > ?").params(MfgDb.org(), wcId, orderId, MfgDb.param(from)))) {
            busy.add(new Instant[] {(Instant) b.get("plannedStartAt"), (Instant) b.get("plannedEndAt")});
        }
        int slots = wc != null && wc.get("parallelSlots") instanceof Number n ? n.intValue() : 1;
        if (minutes <= 0) {
            return new Instant[] {from, from};
        }
        Instant t = from;
        Instant start = null;
        long remaining = minutes;
        long steps = 0;
        while (remaining > 0) {
            if (++steps > SEARCH_LIMIT_MINUTES * 2) {
                throw ApiException.badRequest("No capacity at work centre " + (wc == null ? wcId : wc.get("code"))
                        + " within a year.");
            }
            if (working(t, shifts, closed) && concurrent(t, busy) < slots) {
                if (start == null) {
                    start = t;
                }
                remaining--;
            }
            t = t.plus(1, ChronoUnit.MINUTES);
        }
        return new Instant[] {start, t};
    }

    private static boolean working(Instant t, Map<Integer, List<int[]>> shifts, Set<LocalDate> closed) {
        ZonedDateTime z = t.atZone(ZoneOffset.UTC);
        if (closed.contains(z.toLocalDate())) {
            return false;
        }
        if (shifts.isEmpty()) {
            return true;
        }
        int minute = z.getHour() * 60 + z.getMinute();
        for (int[] w : shifts.getOrDefault(z.getDayOfWeek().getValue() % 7, List.of())) {
            if (minute >= w[0] && minute < w[1]) {
                return true;
            }
        }
        return false;
    }

    private static int concurrent(Instant t, List<Instant[]> busy) {
        int n = 0;
        for (Instant[] b : busy) {
            if (!t.isBefore(b[0]) && t.isBefore(b[1])) {
                n++;
            }
        }
        return n;
    }

    /** Scheduled work orders in a window (Gantt data), optionally for one centre. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> scheduleView(String from, String to, String workCenterId) {
        Instant f = from != null && !from.isEmpty() ? EntityPatch.toInstant(from) : Instant.now().minus(1, ChronoUnit.DAYS);
        Instant t = to != null && !to.isEmpty() ? EntityPatch.toInstant(to) : f.plus(14, ChronoUnit.DAYS);
        List<Object> params = new ArrayList<>(List.of(MfgDb.org(), MfgDb.param(t), MfgDb.param(f)));
        String wc = "";
        if (workCenterId != null) {
            wc = " AND w.\"workCenterId\" = ?";
            params.add(workCenterId);
        }
        return SqlRows.list(db.jdbc().sql("SELECT w.id, w.name, w.status, w.\"workCenterId\", c.name AS \"workCenterName\","
                + " w.\"plannedStartAt\", w.\"plannedEndAt\", w.\"plannedDurationMins\", w.\"productionOrderId\","
                + " o.\"orderCode\", o.\"outputProductId\", o.status AS \"orderStatus\" FROM \"WorkOrder\" w"
                + " JOIN \"ProductionOrder\" o ON o.id = w.\"productionOrderId\" LEFT JOIN \"WorkCenter\" c"
                + " ON c.id = w.\"workCenterId\" WHERE w.\"organizationId\" = ? AND w.\"plannedStartAt\" < ?"
                + " AND w.\"plannedEndAt\" >= ?" + wc + " ORDER BY w.\"plannedStartAt\" ASC").params(params));
    }

    // ================================================================== time logs

    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeLogs(String workOrderId) {
        requireWorkOrder(workOrderId);
        return db.list(WorkOrderTimeLog.class, " AND \"workOrderId\" = ? ORDER BY \"startedAt\" ASC", workOrderId);
    }

    /** A manual (or setup) time entry; the work order's labour minutes become the sum of its closed logs. */
    @Transactional
    public Map<String, Object> addTimeLog(String workOrderId, TimeLogInput dto) {
        Map<String, Object> wo = requireWorkOrder(workOrderId);
        if ("completed".equals(wo.get("status")) || "cancelled".equals(wo.get("status"))) {
            throw ApiException.badRequest("Work order already " + wo.get("status") + ".");
        }
        Instant started = dto.startedAt() != null ? EntityPatch.toInstant(dto.startedAt())
                : Instant.now().minus(dto.minutes(), ChronoUnit.MINUTES);
        Map<String, Object> log = db.insert(WorkOrderTimeLog.class, Obj.of("workOrderId", workOrderId, "resourceId",
                dto.resourceId() != null ? dto.resourceId() : wo.get("resourceId"), "userId", TenantContext.userId(),
                "kind", dto.kind() != null ? dto.kind() : "manual", "startedAt", started, "endedAt",
                started.plus(dto.minutes(), ChronoUnit.MINUTES), "minutes", dto.minutes(), "notes", dto.notes()));
        int total = db.jdbc().sql("SELECT coalesce(sum(minutes), 0)::int FROM \"WorkOrderTimeLog\" WHERE"
                + " \"organizationId\" = ? AND \"workOrderId\" = ? AND \"endedAt\" IS NOT NULL")
                .params(MfgDb.org(), workOrderId).query(Integer.class).single();
        db.update(WorkOrder.class, workOrderId, Obj.of("labourMins", total, "updatedBy", TenantContext.userId()));
        return log;
    }

    private Map<String, Object> requireWorkOrder(String id) {
        Map<String, Object> wo = db.byId(WorkOrder.class, id);
        if (wo == null) {
            throw ApiException.notFound("Work order not found");
        }
        return wo;
    }

    // ================================================================== rework

    /**
     * Turn the rework units of a {@code qc_hold} order into a linked rework order: it consumes them from quarantine
     * (plus optional extra materials) and produces the same product, through QC again. The parent order closes.
     */
    @Transactional
    public Map<String, Object> rework(String orderId, ReworkInput dto) {
        Map<String, Object> parent = db.byId(ProductionOrder.class, orderId);
        if (parent == null) {
            throw ApiException.notFound("Production order not found");
        }
        if (!"qc_hold".equals(parent.get("status"))) {
            throw ApiException.badRequest("Only an order on QC hold can be reworked (status " + parent.get("status")
                    + ").");
        }
        BigDecimal qty = dto != null && dto.quantity() != null ? dto.quantity()
                : db.jdbc().sql("SELECT \"reworkQty\" FROM \"ProductionQcCheck\" WHERE \"organizationId\" = ?"
                        + " AND \"orderId\" = ? ORDER BY \"createdAt\" DESC LIMIT 1").params(MfgDb.org(), orderId)
                        .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("Nothing to rework: record a QC check with reworkQty first.");
        }
        String quarantine = production.quarantineLocation();
        List<MaterialInput> materials = new ArrayList<>();
        materials.add(new MaterialInput(str(parent, "outputProductId"), str(parent, "outputVariantId"), qty, null,
                str(parent, "batchNumber"), null));
        if (dto != null && dto.materials() != null) {
            materials.addAll(dto.materials());
        }
        Map<String, Object> order = production.createOrder(new CreateOrder(null, str(parent, "outputProductId"),
                str(parent, "outputVariantId"), quarantine, parent.get("outputLocationId") != null
                        ? str(parent, "outputLocationId") : str(parent, "locationId"), null, qty, null,
                str(parent, "batchNumber"), null, null, dto != null && dto.notes() != null ? dto.notes()
                        : "Rework of " + parent.get("orderCode"), materials, null));
        Map<String, Object> updated = db.update(ProductionOrder.class, str(order, "id"), Obj.of("parentOrderId",
                orderId, "orderKind", "rework", "qcRequired", true));
        db.update(ProductionOrder.class, orderId, Obj.of("status", "completed", "completedAt", Instant.now(),
                "updatedBy", TenantContext.userId()));
        return production.withLines(updated, true, true);
    }

    private Map<String, Object> requireCenter(String id) {
        Map<String, Object> wc = db.byId(WorkCenter.class, id);
        if (wc == null) {
            throw ApiException.notFound("Work centre not found");
        }
        return wc;
    }
}
