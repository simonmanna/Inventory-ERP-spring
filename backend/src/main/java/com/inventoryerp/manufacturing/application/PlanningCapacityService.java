package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.application.CapacityCalculator.AggregatePlan;
import com.inventoryerp.manufacturing.application.CapacityCalculator.Fraction;
import com.inventoryerp.manufacturing.application.DemandNetting.Demand;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Capacity planning (Track C 2.5, ADR-S017). Load against capacity per work centre by day or week, in hours and in the
 * centre's rate (t/h of a crushing unit, units per hour of the shop-floor profile), with the bottleneck flagged; per
 * crushing plant the circuit bottleneck (the stage with the lowest t/h, parallel units adding up, never above the
 * design
 * capacity); and the aggregate plan: the feed a grading profile needs to cover the demand for its sized products, the
 * crusher hours that takes and the ROM stock it uses.
 */
@Service
public class PlanningCapacityService {

    private static final int MAX_DAYS = 186;

    private final MfgDb db;
    private final PlanningScheduleService schedule;
    private final PlanningDemandService demand;
    private final MrpPlanningService mrp;

    public PlanningCapacityService(MfgDb db, PlanningScheduleService schedule, PlanningDemandService demand,
            MrpPlanningService mrp) {
        this.db = db;
        this.schedule = schedule;
        this.demand = demand;
        this.mrp = mrp;
    }

    private record Period(LocalDate from, LocalDate to) {
        Instant start() {
            return from.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Instant end() {
            return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    private static Period period(String from, String to) {
        LocalDate f = CrushingSupport.date(from) != null ? CrushingSupport.date(from)
                : LocalDate.now(BusinessZone.get());
        LocalDate t = CrushingSupport.date(to) != null ? CrushingSupport.date(to) : f.plusDays(13);
        if (t.isBefore(f)) {
            throw ApiException.badRequest("to must not be before from");
        }
        if (ChronoUnit.DAYS.between(f, t) > MAX_DAYS) {
            throw ApiException.badRequest("A capacity period can cover at most " + MAX_DAYS + " days");
        }
        return new Period(f, t);
    }

    /** Load vs capacity per work centre in {@code bucket} (day, default, or week) periods; bottleneck flagged. */
    @Transactional(readOnly = true)
    public Map<String, Object> capacity(String from, String to, String bucket, String workCenterId) {
        Period p = period(from, to);
        String size = bucket == null || bucket.isBlank() ? "day" : bucket;
        if (!List.of("day", "week").contains(size)) {
            throw ApiException.badRequest("bucket must be day or week");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : db.list(WorkCenter.class, " AND \"isActive\" = true ORDER BY code")) {
            if (workCenterId == null || workCenterId.isBlank() || workCenterId.equals(c.get("id"))) {
                rows.add(centre(c, p, size));
            }
        }
        Map<String, Object> worst = null;
        for (Map<String, Object> r : rows) {
            BigDecimal u = (BigDecimal) r.get("utilisationPct");
            if (u != null && u.signum() > 0 && (worst == null
                    || u.compareTo((BigDecimal) worst.get("utilisationPct")) > 0)) {
                worst = r;
            }
        }
        if (worst != null) {
            worst.put("isBottleneck", true);
        }
        return Obj.of("from", p.from().toString(), "to", p.to().toString(), "bucket", size, "workCenters", rows,
                "bottleneck", worst == null ? null : Obj.of("workCenterId", worst.get("workCenterId"), "name",
                        worst.get("name"), "utilisationPct", worst.get("utilisationPct")),
                "plants", plants(p));
    }

    private Map<String, Object> centre(Map<String, Object> c, Period p, String size) {
        String id = str(c, "id");
        PlanningCalendar cal = schedule.calendarOf(id, null, null);
        int slots = c.get("parallelSlots") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        long resourceMinutes = db.jdbc().sql("SELECT coalesce(sum(\"capacityMinsPerDay\"), 0)::bigint FROM \"Resource\""
                + " WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND \"isActive\" = true")
                .params(MfgDb.org(), id).query(Long.class).single();
        List<Map<String, Object>> load = SqlRows.list(db.jdbc().sql("SELECT \"plannedStartAt\", \"plannedEndAt\" FROM"
                + " \"WorkOrder\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND status IN ('pending',"
                + " 'in_progress', 'paused') AND \"plannedStartAt\" IS NOT NULL AND \"plannedEndAt\" IS NOT NULL")
                .params(MfgDb.org(), id));
        long unscheduled = db.jdbc().sql("SELECT coalesce(sum(\"plannedDurationMins\"), 0)::bigint FROM \"WorkOrder\""
                + " WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND status = 'pending'"
                + " AND \"plannedStartAt\" IS NULL").params(MfgDb.org(), id).query(Long.class).single();
        BigDecimal rate = schedule.ratePerHour(id);
        List<Map<String, Object>> buckets = new ArrayList<>();
        long totalCapacity = 0;
        long totalLoad = 0;
        LocalDate d = p.from();
        while (!d.isAfter(p.to())) {
            LocalDate end = "week".equals(size) ? min(d.plusDays(6), p.to()) : d;
            Instant a = d.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant b = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            long capacity = 0;
            for (LocalDate day = d; !day.isAfter(end); day = day.plusDays(1)) {
                long minutes = cal.workingMinutes(day);
                capacity += minutes == 0 ? 0
                        : !cal.hasShifts() && resourceMinutes > 0 ? resourceMinutes : minutes * slots;
            }
            long used = 0;
            for (Map<String, Object> w : load) {
                Instant s = (Instant) w.get("plannedStartAt");
                Instant t = (Instant) w.get("plannedEndAt");
                used += cal.workingMinutesBetween(s.isAfter(a) ? s : a, t.isBefore(b) ? t : b);
            }
            totalCapacity += capacity;
            totalLoad += used;
            buckets.add(Obj.of("from", d.toString(), "to", end.toString(), "capacityHours",
                    CapacityCalculator.hours(capacity), "loadHours", CapacityCalculator.hours(used), "utilisationPct",
                    CapacityCalculator.utilisationPct(used, capacity), "overloaded", used > capacity));
            d = end.plusDays(1);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workCenterId", id);
        row.put("code", c.get("code"));
        row.put("name", c.get("name"));
        row.put("parallelSlots", slots);
        row.put("capacityHours", CapacityCalculator.hours(totalCapacity));
        row.put("loadHours", CapacityCalculator.hours(totalLoad));
        row.put("unscheduledHours", CapacityCalculator.hours(unscheduled));
        row.put("utilisationPct", CapacityCalculator.utilisationPct(totalLoad, totalCapacity));
        row.put("ratePerHour", rate);
        row.put("capacityQty", CapacityCalculator.quantity(totalCapacity, rate));
        row.put("loadQty", CapacityCalculator.quantity(totalLoad, rate));
        row.put("isBottleneck", false);
        row.put("buckets", buckets);
        return row;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    /** The circuit's limiting stage. */
    private record Circuit(BigDecimal tph, String limit, String workCenterId) {}

    private Circuit circuit(Map<String, Object> plant) {
        Map<String, BigDecimal> perStage = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        Map<String, String> centres = new HashMap<>();
        for (Map<String, Object> u : SqlRows.list(db.jdbc().sql("SELECT u.id, u.name, u.\"stageId\", u.\"capacityTph\","
                + " u.\"workCenterId\", s.name AS \"stageName\" FROM \"CrushingPlantUnit\" u LEFT JOIN"
                + " \"CrushingPlantStage\" s ON s.id = u.\"stageId\" WHERE u.\"organizationId\" = ? AND u.\"plantId\" ="
                + " ?"
                + " AND u.\"isActive\" = true AND u.\"capacityTph\" > 0").params(MfgDb.org(), plant.get("id")))) {
            String key = u.get("stageId") != null ? str(u, "stageId") : str(u, "id");
            perStage.merge(key, dec(u, "capacityTph"), BigDecimal::add);
            labels.put(key, u.get("stageName") != null ? str(u, "stageName") : str(u, "name"));
            if (u.get("workCenterId") != null) {
                centres.put(key, str(u, "workCenterId"));
            }
        }
        String key = null;
        for (Map.Entry<String, BigDecimal> e : perStage.entrySet()) {
            if (key == null || e.getValue().compareTo(perStage.get(key)) < 0) {
                key = e.getKey();
            }
        }
        BigDecimal design = plant.get("designCapacityTph") == null ? null : dec(plant, "designCapacityTph");
        if (key == null) {
            return new Circuit(design, design == null ? null : "Design capacity", null);
        }
        if (design != null && design.compareTo(perStage.get(key)) < 0) {
            return new Circuit(design, "Design capacity", centres.get(key));
        }
        return new Circuit(perStage.get(key), labels.get(key), centres.get(key));
    }

    /** Working hours of the limiting stage's centre in the period; null when no unit is tied to a work centre. */
    private Long availableMinutes(Circuit c, Period p) {
        if (c.workCenterId() == null) {
            return null;
        }
        return schedule.calendarOf(c.workCenterId(), null, null).workingMinutesBetween(p.start(), p.end());
    }

    private List<Map<String, Object>> plants(Period p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> plant : activePlants(null)) {
            Circuit c = circuit(plant);
            Long minutes = availableMinutes(c, p);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plantId", plant.get("id"));
            row.put("code", plant.get("code"));
            row.put("name", plant.get("name"));
            row.put("designCapacityTph", plant.get("designCapacityTph"));
            row.put("bottleneckTph", c.tph());
            row.put("bottleneck", c.limit());
            row.put("availableHours", minutes == null ? null : CapacityCalculator.hours(minutes));
            row.put("capacityTonnes", minutes == null ? null : CapacityCalculator.quantity(minutes, c.tph()));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> activePlants(String plantId) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        String w = "";
        if (plantId != null && !plantId.isBlank()) {
            w = " AND id = ?";
            params.add(plantId);
        }
        return SqlRows.list(db.jdbc().sql("SELECT id, code, name, \"designCapacityTph\" FROM \"CrushingPlant\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\"" + w + " ORDER BY"
                + " code").params(params));
    }

    /**
     * Aggregate plan per active grading profile: demand of each sized product net of stock and open production, the
     * feed
     * the tightest fraction requires, the output every fraction then gives, crusher hours at the circuit bottleneck
     * against the working hours of the period, and the ROM stock against the feed.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> aggregates(String from, String to, String plantId) {
        Period p = period(from, to);
        Map<String, BigDecimal> demanded = new HashMap<>();
        for (Demand d : demand.collect(p.from(), p.to(), false, PlanningDemandService.RUN_RATE, 4, true)) {
            demanded.merge(d.productId(), d.qty(), BigDecimal::add);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> plant : activePlants(plantId)) {
            Circuit circuit = circuit(plant);
            Long minutes = availableMinutes(circuit, p);
            BigDecimal available = minutes == null ? null : CapacityCalculator.hours(minutes);
            for (Map<String, Object> profile : SqlRows.list(db.jdbc().sql("SELECT g.id, g.code, g.name,"
                    + " g.\"feedProductId\","
                    + " f.name AS \"feedProductName\" FROM \"CrushingGradingProfile\" g JOIN \"Product\" f ON"
                    + " f.id = g.\"feedProductId\" WHERE g.\"organizationId\" = ? AND g.\"plantId\" = ?"
                    + " AND g.status = 'active' AND g.\"deletedAt\" IS NULL ORDER BY g.code")
                    .params(MfgDb.org(), plant.get("id")))) {
                rows.add(profilePlan(plant, profile, demanded, circuit, available));
            }
        }
        return Obj.of("from", p.from().toString(), "to", p.to().toString(), "plans", rows);
    }

    private Map<String, Object> profilePlan(Map<String, Object> plant, Map<String, Object> profile,
            Map<String, BigDecimal> demanded, Circuit circuit, BigDecimal available) {
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("SELECT l.\"productId\", p.code, p.name,"
                + " l.\"sizeLabel\","
                + " l.role, l.\"expectedPct\" FROM \"CrushingGradingLine\" l JOIN \"Product\" p ON p.id ="
                + " l.\"productId\""
                + " WHERE l.\"organizationId\" = ? AND l.\"profileId\" = ? AND l.stocked = true ORDER BY"
                + " l.\"sortOrder\"")
                .params(MfgDb.org(), profile.get("id")));
        List<Fraction> fractions = new ArrayList<>();
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            String productId = str(l, "productId");
            BigDecimal wanted = demanded.getOrDefault(productId, BigDecimal.ZERO);
            BigDecimal onHand = mrp.onHand(productId, null);
            BigDecimal supply = onHand.subtract(mrp.reservedByOthers(productId, null))
                    .add(mrp.openProductionOutput(productId)).add(mrp.openCoProductOutput(productId));
            BigDecimal net = wanted.subtract(supply).max(BigDecimal.ZERO);
            fractions.add(new Fraction(productId, dec(l, "expectedPct"), net));
            Map<String, Object> row = new LinkedHashMap<>(l);
            row.put("demandQty", wanted);
            row.put("onHandQty", onHand);
            row.put("netDemandQty", net);
            byProduct.put(productId, row);
        }
        AggregatePlan plan = CapacityCalculator.aggregate(fractions, circuit.tph());
        for (Fraction f : fractions) {
            Map<String, Object> row = byProduct.get(f.productId());
            BigDecimal output = CapacityCalculator.expectedOutput(plan.feedTonnes(), f.expectedPct());
            row.put("expectedOutputQty", output);
            row.put("surplusQty", output.subtract(f.netDemand()).max(BigDecimal.ZERO));
        }
        BigDecimal feedStock = mrp.onHand(str(profile, "feedProductId"), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plantId", plant.get("id"));
        out.put("plantCode", plant.get("code"));
        out.put("plantName", plant.get("name"));
        out.put("profileId", profile.get("id"));
        out.put("profileCode", profile.get("code"));
        out.put("profileName", profile.get("name"));
        out.put("feedProductName", profile.get("feedProductName"));
        out.put("feedTonnes", plan.feedTonnes());
        out.put("feedOnHand", feedStock);
        out.put("feedShortfall", plan.feedTonnes().subtract(feedStock).max(BigDecimal.ZERO));
        out.put("drivingProductId", plan.drivingProductId());
        out.put("bottleneckTph", circuit.tph());
        out.put("bottleneck", circuit.limit());
        out.put("hoursRequired", plan.hours());
        out.put("hoursAvailable", available);
        out.put("feasible", plan.hours() == null || available == null ? null : plan.hours().compareTo(available) <= 0);
        out.put("fractions", new ArrayList<>(byProduct.values()));
        return out;
    }
}
