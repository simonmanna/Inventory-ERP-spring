package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan performance reports (Track C 2.5, ADR-S019): production vs plan per plan line, plan adherence (orders finished
 * by
 * their due date, work orders finished by their planned end, shift plans met) and production vs target with the
 * pro-rata
 * target to date. Read only.
 */
@Service
@Transactional(readOnly = true)
public class PlanningReportService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final MfgDb db;
    private final PlanningShiftService shifts;

    public PlanningReportService(MfgDb db, PlanningShiftService shifts) {
        this.db = db;
        this.shifts = shifts;
    }

    private record Range(LocalDate from, LocalDate to) {
        Instant start() {
            return from.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Instant end() {
            return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    private static Range range(String from, String to) {
        LocalDate t = CrushingSupport.date(to) != null ? CrushingSupport.date(to) : LocalDate.now(BusinessZone.get());
        LocalDate f = CrushingSupport.date(from) != null ? CrushingSupport.date(from) : t.withDayOfMonth(1);
        if (t.isBefore(f)) {
            throw ApiException.badRequest("to must not be before from");
        }
        return new Range(f, t);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() <= 0 ? null : part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(long part, long whole) {
        return pct(BigDecimal.valueOf(part), BigDecimal.valueOf(whole));
    }

    /** Target vs produced per plan line scheduled in the period (or of one plan), with totals per product. */
    public Map<String, Object> productionVsPlan(String from, String to, String planId) {
        Range r = range(from, to);
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        String where;
        if (planId != null && !planId.isBlank()) {
            where = " AND p.id = ?";
            params.add(planId);
        } else {
            where = " AND coalesce(l.\"scheduledDate\", p.\"dateFrom\", p.\"createdAt\") >= ?"
                    + " AND coalesce(l.\"scheduledDate\", p.\"dateFrom\", p.\"createdAt\") < ?";
            params.add(MfgDb.param(r.start()));
            params.add(MfgDb.param(r.end()));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> products = new LinkedHashMap<>();
        BigDecimal target = BigDecimal.ZERO;
        BigDecimal produced = BigDecimal.ZERO;
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT l.id AS \"lineId\", p.id AS \"planId\","
                + " p.\"planNumber\", p.status AS \"planStatus\", l.\"productId\", pr.name AS \"productName\","
                + " l.\"targetQty\", l.\"scheduledDate\", o.id AS \"orderId\", o.\"orderCode\", o.status AS"
                + " \"orderStatus\","
                + " coalesce(o.\"producedQty\", 0) AS \"producedQty\", o.\"completedAt\" FROM \"ProductionPlanLine\" l"
                + " JOIN \"ProductionPlan\" p ON p.id = l.\"planId\" LEFT JOIN \"Product\" pr ON pr.id ="
                + " l.\"productId\""
                + " LEFT JOIN \"ProductionOrder\" o ON o.id = l.\"generatedOrderId\" WHERE l.\"organizationId\" = ?"
                + " AND p.\"deletedAt\" IS NULL AND p.status <> 'cancelled'" + where
                + " ORDER BY p.\"planNumber\", l.\"lineNumber\" LIMIT 1000").params(params))) {
            Map<String, Object> row = new LinkedHashMap<>(l);
            BigDecimal t = dec(l, "targetQty");
            BigDecimal q = dec(l, "producedQty");
            row.put("varianceQty", q.subtract(t));
            row.put("attainmentPct", pct(q, t));
            rows.add(row);
            target = target.add(t);
            produced = produced.add(q);
            Map<String, Object> sum = products.computeIfAbsent(str(l, "productId"), k -> Obj.of("productId", k,
                    "productName", l.get("productName"), "targetQty", BigDecimal.ZERO, "producedQty", BigDecimal.ZERO));
            sum.put("targetQty", dec(sum, "targetQty").add(t));
            sum.put("producedQty", dec(sum, "producedQty").add(q));
        }
        for (Map<String, Object> s : products.values()) {
            s.put("varianceQty", dec(s, "producedQty").subtract(dec(s, "targetQty")));
            s.put("attainmentPct", pct(dec(s, "producedQty"), dec(s, "targetQty")));
        }
        return Obj.of("from", r.from().toString(), "to", r.to().toString(), "rows", rows, "byProduct",
                new ArrayList<>(products.values()), "totals", Obj.of("targetQty", target, "producedQty", produced,
                        "varianceQty", produced.subtract(target), "attainmentPct", pct(produced, target)));
    }

    /**
     * Plan adherence over the period: orders due (on time, late, still open), work orders with a planned end (finished
     * by it, per centre) and published shift plans (met or not).
     */
    public Map<String, Object> planAdherence(String from, String to) {
        Range r = range(from, to);
        Instant now = Instant.now();
        List<Map<String, Object>> orders = new ArrayList<>();
        long onTime = 0;
        long late = 0;
        long overdue = 0;
        long open = 0;
        for (Map<String, Object> o : SqlRows.list(db.jdbc().sql("SELECT o.id, o.\"orderCode\", o.status,"
                + " pr.name AS \"productName\", o.\"plannedQty\", o.\"producedQty\", o.\"completedAt\", g.\"dueAt\","
                + " g.priority, g.\"lateMinutes\", pa.name AS \"partnerName\" FROM \"ProductionOrderPlanning\" g JOIN"
                + " \"ProductionOrder\" o ON o.id = g.\"productionOrderId\" LEFT JOIN \"Product\" pr ON pr.id ="
                + " o.\"outputProductId\" LEFT JOIN \"Partner\" pa ON pa.id = g.\"partnerId\""
                + " WHERE g.\"organizationId\" = ? AND g.\"dueAt\" >= ? AND g.\"dueAt\" < ? AND o.status <> 'cancelled'"
                + " ORDER BY g.\"dueAt\" LIMIT 1000").params(MfgDb.org(), MfgDb.param(r.start()),
                        MfgDb.param(r.end())))) {
            Instant due = (Instant) o.get("dueAt");
            Instant done = (Instant) o.get("completedAt");
            String outcome;
            if (done != null) {
                outcome = done.isAfter(due) ? "late" : "on_time";
            } else {
                outcome = now.isAfter(due) ? "overdue" : "open";
            }
            switch (outcome) {
                case "on_time" -> onTime++;
                case "late" -> late++;
                case "overdue" -> overdue++;
                default -> open++;
            }
            Map<String, Object> row = new LinkedHashMap<>(o);
            row.put("outcome", outcome);
            row.put("minutesLate", done != null && done.isAfter(due) ? ChronoUnit.MINUTES.between(due, done) : 0L);
            orders.add(row);
        }
        List<Map<String, Object>> centres = new ArrayList<>();
        long woTotal = 0;
        long woOnTime = 0;
        for (Map<String, Object> w : SqlRows.list(db.jdbc().sql("SELECT c.id AS \"workCenterId\", c.code, c.name,"
                + " count(*) AS total, count(*) FILTER (WHERE w.\"completedAt\" IS NOT NULL AND w.\"completedAt\" <="
                + " w.\"plannedEndAt\") AS \"onTime\", count(*) FILTER (WHERE w.\"completedAt\" IS NOT NULL) AS"
                + " completed,"
                + " avg(extract(epoch FROM (w.\"startedAt\" - w.\"plannedStartAt\")) / 60) AS"
                + " \"avgStartDeviationMinutes\""
                + " FROM \"WorkOrder\" w JOIN \"WorkCenter\" c ON c.id = w.\"workCenterId\" WHERE w.\"organizationId\""
                + " = ?"
                + " AND w.status <> 'cancelled' AND w.\"plannedEndAt\" >= ? AND w.\"plannedEndAt\" < ?"
                + " GROUP BY c.id, c.code, c.name ORDER BY c.code").params(MfgDb.org(), MfgDb.param(r.start()),
                        MfgDb.param(r.end())))) {
            long total = ((Number) w.get("total")).longValue();
            long ok = ((Number) w.get("onTime")).longValue();
            woTotal += total;
            woOnTime += ok;
            Map<String, Object> row = new LinkedHashMap<>(w);
            row.put("avgStartDeviationMinutes", w.get("avgStartDeviationMinutes") == null ? null
                    : dec(w, "avgStartDeviationMinutes").setScale(1, RoundingMode.HALF_UP));
            row.put("adherencePct", pct(ok, total));
            centres.add(row);
        }
        long plans = 0;
        long met = 0;
        for (Map<String, Object> p : SqlRows.list(db.jdbc().sql("SELECT * FROM \"ProductionShiftPlan\" WHERE"
                + " \"organizationId\" = ? AND status = 'published' AND \"shiftDate\" >= ? AND \"shiftDate\" <= ?")
                .params(MfgDb.org(), java.sql.Date.valueOf(r.from()), java.sql.Date.valueOf(r.to())))) {
            plans++;
            if (shifts.actualQty(p).compareTo(dec(p, "targetQty")) >= 0) {
                met++;
            }
        }
        long closed = onTime + late + overdue;
        return Obj.of("from", r.from().toString(), "to", r.to().toString(),
                "orders", Obj.of("due", orders.size(), "onTime", onTime, "late", late, "overdue", overdue, "open", open,
                        "adherencePct", pct(onTime, closed)),
                "workOrders", Obj.of("total", woTotal, "onTime", woOnTime, "adherencePct", pct(woOnTime, woTotal)),
                "shiftPlans", Obj.of("published", plans, "met", met, "adherencePct", pct(met, plans)),
                "orderRows", orders, "workCenters", centres);
    }

    /** Actual output against each target overlapping the period, up to today, with the pro-rata target to date. */
    public Map<String, Object> productionVsTarget(String from, String to) {
        Range r = range(from, to);
        LocalDate today = LocalDate.now(BusinessZone.get());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : shifts.targets(r.from(), r.to(), null)) {
            LocalDate start = (LocalDate) t.get("periodFrom");
            LocalDate stop = (LocalDate) t.get("periodTo");
            BigDecimal actual = actual(t, start, stop);
            long days = ChronoUnit.DAYS.between(start, stop) + 1;
            long elapsed = Math.max(0, Math.min(days, ChronoUnit.DAYS.between(start, today) + 1));
            BigDecimal target = dec(t, "targetQty");
            BigDecimal toDate = target.multiply(BigDecimal.valueOf(elapsed))
                    .divide(BigDecimal.valueOf(days), 3, RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>(t);
            row.put("actualQty", actual);
            row.put("targetToDate", toDate);
            row.put("varianceToDate", actual.subtract(toDate));
            row.put("attainmentPct", pct(actual, target));
            row.put("attainmentToDatePct", pct(actual, toDate));
            rows.add(row);
        }
        return Obj.of("from", r.from().toString(), "to", r.to().toString(), "rows", rows);
    }

    private BigDecimal actual(Map<String, Object> t, LocalDate from, LocalDate to) {
        java.sql.Date a = java.sql.Date.valueOf(from);
        java.sql.Date b = java.sql.Date.valueOf(to);
        Object start = MfgDb.param(from.atStartOfDay(ZoneOffset.UTC).toInstant());
        Object end = MfgDb.param(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        return switch (str(t, "scope")) {
            case "work_center" -> db.jdbc().sql("SELECT coalesce(sum(\"qtyGood\"), 0) FROM"
                    + " \"ShopFloorProductionReport\""
                    + " WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND status = 'posted'"
                    + " AND \"reportedAt\" >= ? AND \"reportedAt\" < ?").params(MfgDb.org(), t.get("workCenterId"),
                            start,
                            end).query(BigDecimal.class).single();
            case "crushing_plant" -> db.jdbc().sql("SELECT coalesce(sum(\"outputTonnes\"), 0) FROM \"CrushingRun\""
                    + " WHERE"
                    + " \"organizationId\" = ? AND \"plantId\" = ? AND status = 'posted' AND \"deletedAt\" IS NULL"
                    + " AND \"runDate\" >= ? AND \"runDate\" <= ?").params(MfgDb.org(), t.get("plantId"), a, b)
                    .query(BigDecimal.class).single();
            default -> db.jdbc().sql("SELECT coalesce(sum(o.\"producedQty\"), 0) FROM \"ProductionOrder\" o WHERE"
                    + " o.\"organizationId\" = ? AND o.\"outputProductId\" = ? AND o.status = 'completed'"
                    + " AND o.\"completedAt\" >= ? AND o.\"completedAt\" < ? AND NOT EXISTS (SELECT 1 FROM"
                    + " \"CrushingRun\" r"
                    + " WHERE r.\"productionOrderId\" = o.id)").params(MfgDb.org(), t.get("productId"), start, end)
                    .query(BigDecimal.class).single()
                    .add(db.jdbc().sql("SELECT coalesce(sum(x.\"actualTonnes\"), 0) FROM \"CrushingRunOutput\" x JOIN"
                            + " \"CrushingRun\" r ON r.id = x.\"runId\" WHERE x.\"organizationId\" = ? AND"
                            + " x.\"productId\" = ?"
                            + " AND r.status = 'posted' AND r.\"deletedAt\" IS NULL AND r.\"runDate\" >= ?"
                            + " AND r.\"runDate\" <= ?").params(MfgDb.org(), t.get("productId"), a, b)
                            .query(BigDecimal.class).single());
        };
    }
}
