package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.MrpPlannedOrder;
import com.inventoryerp.manufacturing.domain.MrpRun;
import com.inventoryerp.manufacturing.domain.RoutingOperation;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rough-cut capacity of an MRP run (PR-5, ADR-S027): the routing load of the run's open make and subcontract planned
 * orders laid over each work centre's capacity and its already scheduled work orders
 * ({@link PlanningCapacityService#capacity}). A planned order loads each in-house operation of its BOM's routing with
 * {@code setupMins + durationMins × quantity ÷ BOM output quantity}, spread evenly over the calendar days from its
 * release date up to (not including) its due date, at least one day. Subcontracted operations load nobody.
 */
@Service
public class MrpCapacityService {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_DAYS = 186;

    private final MfgDb db;
    private final PlanningCapacityService capacity;

    public MrpCapacityService(MfgDb db, PlanningCapacityService capacity) {
        this.db = db;
        this.capacity = capacity;
    }

    /** One day's planned minutes at one work centre. */
    record DayLoad(String workCenterId, LocalDate day, BigDecimal minutes) {}

    /** Load vs capacity per work centre for the run, in {@code bucket} (day or week, default week) periods. */
    @Transactional(readOnly = true)
    public Map<String, Object> runCapacity(String runId, String bucket) {
        if (db.byId(MrpRun.class, runId) == null) {
            throw ApiException.notFound("MRP run not found");
        }
        List<DayLoad> loads = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate first = today;
        LocalDate last = today;
        for (Map<String, Object> po : db.children(MrpPlannedOrder.class, "runId", runId, "\"releaseDate\" ASC")) {
            if (!"planned".equals(po.get("status")) || "buy".equals(po.get("kind")) || po.get("bomId") == null) {
                continue;
            }
            LocalDate release = day((Instant) po.get("releaseDate"));
            LocalDate due = day((Instant) po.get("dueDate"));
            release = release.isBefore(today) ? today : release;
            LocalDate end = due.isAfter(release) ? due.minusDays(1) : release;
            for (Map.Entry<String, BigDecimal> op : operationMinutes(str(po, "bomId"), dec(po, "quantity"))
                    .entrySet()) {
                loads.addAll(spread(op.getKey(), op.getValue(), release, end));
            }
            last = end.isAfter(last) ? end : last;
        }
        if (ChronoUnit.DAYS.between(first, last) > MAX_DAYS) {
            last = first.plusDays(MAX_DAYS);
        }
        String size = bucket == null || bucket.isBlank() ? "week" : bucket;
        Map<String, Object> base = capacity.capacity(first.toString(), last.toString(), size, null);
        return overlay(base, loads, runId);
    }

    /** Minutes per work centre that {@code quantity} of the BOM's output needs on its routing. */
    Map<String, BigDecimal> operationMinutes(String bomId, BigDecimal quantity) {
        Map<String, Object> bom = db.byId(Bom.class, bomId);
        Map<String, BigDecimal> out = new HashMap<>();
        if (bom == null || bom.get("routingId") == null) {
            return out;
        }
        BigDecimal batches = dec(bom, "outputQuantity").signum() > 0
                ? quantity.divide(dec(bom, "outputQuantity"), 6, RoundingMode.HALF_UP) : quantity;
        for (Map<String, Object> op : db.children(RoutingOperation.class, "routingId", str(bom, "routingId"),
                "sequence ASC")) {
            if (Boolean.TRUE.equals(op.get("isSubcontracted")) || op.get("workCenterId") == null) {
                continue;
            }
            BigDecimal minutes = BigDecimal.valueOf(((Number) op.get("setupMins")).intValue())
                    .add(BigDecimal.valueOf(((Number) op.get("durationMins")).intValue()).multiply(batches));
            out.merge(str(op, "workCenterId"), minutes, BigDecimal::add);
        }
        return out;
    }

    /** {@code minutes} spread evenly over the days {@code from}..{@code to} (inclusive). */
    static List<DayLoad> spread(String workCenterId, BigDecimal minutes, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        BigDecimal perDay = minutes.divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP);
        List<DayLoad> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(new DayLoad(workCenterId, d, perDay));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> overlay(Map<String, Object> base, List<DayLoad> loads, String runId) {
        List<Map<String, Object>> centres = (List<Map<String, Object>>) base.get("workCenters");
        Map<String, Object> worst = null;
        BigDecimal worstPct = null;
        for (Map<String, Object> c : centres) {
            String id = (String) c.get("workCenterId");
            BigDecimal plannedTotal = BigDecimal.ZERO;
            for (Map<String, Object> b : (List<Map<String, Object>>) c.get("buckets")) {
                LocalDate from = LocalDate.parse((String) b.get("from"));
                LocalDate to = LocalDate.parse((String) b.get("to"));
                BigDecimal planned = BigDecimal.ZERO;
                for (DayLoad l : loads) {
                    if (l.workCenterId().equals(id) && !l.day().isBefore(from) && !l.day().isAfter(to)) {
                        planned = planned.add(l.minutes());
                    }
                }
                plannedTotal = plannedTotal.add(planned);
                BigDecimal plannedHours = planned.divide(SIXTY, 2, RoundingMode.HALF_UP);
                BigDecimal total = ((BigDecimal) b.get("loadHours")).add(plannedHours);
                b.put("plannedLoadHours", plannedHours);
                b.put("totalLoadHours", total);
                b.put("totalUtilisationPct", pct(total, (BigDecimal) b.get("capacityHours")));
                b.put("overloaded", total.compareTo((BigDecimal) b.get("capacityHours")) > 0);
            }
            BigDecimal plannedHours = plannedTotal.divide(SIXTY, 2, RoundingMode.HALF_UP);
            BigDecimal total = ((BigDecimal) c.get("loadHours")).add(plannedHours);
            BigDecimal u = pct(total, (BigDecimal) c.get("capacityHours"));
            c.put("plannedLoadHours", plannedHours);
            c.put("totalLoadHours", total);
            c.put("totalUtilisationPct", u);
            c.put("isBottleneck", false);
            if (u != null && u.signum() > 0 && (worstPct == null || u.compareTo(worstPct) > 0)) {
                worst = c;
                worstPct = u;
            }
        }
        if (worst != null) {
            worst.put("isBottleneck", true);
        }
        base.put("runId", runId);
        base.put("bottleneck", worst == null ? null : Obj.of("workCenterId", worst.get("workCenterId"), "name",
                worst.get("name"), "utilisationPct", worstPct));
        base.remove("plants");
        return base;
    }

    private static BigDecimal pct(BigDecimal load, BigDecimal capacity) {
        if (capacity == null || capacity.signum() <= 0) {
            return null;
        }
        return load.multiply(HUNDRED).divide(capacity, 2, RoundingMode.HALF_UP);
    }

    private static LocalDate day(Instant i) {
        return i.atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
