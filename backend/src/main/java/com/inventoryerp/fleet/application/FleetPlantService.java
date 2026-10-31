package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetCommands.VehicleInput;
import com.inventoryerp.fleet.application.FleetCostCalculator.CostBuckets;
import com.inventoryerp.fleet.application.FleetCostCalculator.Rates;
import com.inventoryerp.fleet.application.FleetCostCalculator.Usage;
import com.inventoryerp.fleet.application.FleetCostingService.Period;
import com.inventoryerp.fleet.application.FleetCostingService.VehicleCost;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed-plant register (CMMS-1, Phase 4): crushers, conveyors, screens, block machines and fixed generators are units
 * of the fleet register with a class of kind {@value #KIND} (ADR-S009 §4, docs/spec/fleet.md "Maintainable unit"), so
 * the 1.6 engine (schedules, work orders, parts, labour, downtime, inspections, reliability) serves trucks and plant
 * alike and no parallel model exists. This service adds what is specific to plant: its classes, the register view by
 * location, cost per equipment with downtime, the link back from a fixed asset, and disposal in step with the asset.
 */
@Service
public class FleetPlantService {

    public static final String KIND = "fixed_plant";

    /** Register filters; {@code locationId} is the unit's home location. */
    public record PlantQuery(String search, String categoryId, String status, String branchId, String locationId,
            Integer page, Integer pageSize) {}

    private final FleetDb db;
    private final FleetCategoryService categories;
    private final FleetVehicleService vehicles;
    private final FleetCostingService costing;

    public FleetPlantService(FleetDb db, FleetCategoryService categories, FleetVehicleService vehicles,
            FleetCostingService costing) {
        this.db = db;
        this.categories = categories;
        this.vehicles = vehicles;
        this.costing = costing;
    }

    /** The active fixed-plant classes, seeding the missing defaults first. */
    @Transactional
    public List<Map<String, Object>> classes() {
        categories.ensureFixedPlantClasses();
        return categories.list(false).stream().filter(c -> KIND.equals(c.get("kind"))).toList();
    }

    /** Registers a unit of a fixed-plant class (400 for any other class); the rest is the fleet register. */
    @Transactional
    public Map<String, Object> register(VehicleInput in) {
        categories.ensureFixedPlantClasses();
        Map<String, Object> category = categories.requireActive(in.categoryId());
        if (!KIND.equals(category.get("kind"))) {
            throw ApiException.badRequest("Vehicle class " + category.get("code") + " is not a fixed-plant class");
        }
        return vehicles.create(in);
    }

    /** Register by location: identity, class, branch, location, lifetime hours, linked asset, open work. */
    @Transactional
    public PageResult<Map<String, Object>> list(PlantQuery q) {
        categories.ensureFixedPlantClasses();
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL"
                + " AND c.kind = '" + KIND + "'");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (MaintainableUnits.notBlank(q.search())) {
            where.append(" AND (v.code ILIKE ? OR v.name ILIKE ? OR v.make ILIKE ? OR v.model ILIKE ?)");
            String like = MaintainableUnits.like(q.search());
            for (int i = 0; i < 4; i++) {
                params.add(like);
            }
        }
        FleetIncidentService.eq(where, params, "v.\"categoryId\"", q.categoryId());
        FleetIncidentService.eq(where, params, "v.status", q.status());
        FleetIncidentService.eq(where, params, "v.\"branchId\"", q.branchId());
        FleetIncidentService.eq(where, params, "v.\"homeLocationId\"", q.locationId());
        String from = " FROM \"FleetVehicle\" v JOIN \"FleetVehicleCategory\" c ON c.id = v.\"categoryId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        String select = """
                SELECT v.*, c.name AS "categoryName", c.code AS "categoryCode",
                       b.name AS "branchName", l.code AS "locationCode", l.name AS "locationName",
                       a."assetCode" AS "assetCode", a.status::text AS "assetStatus",
                       (SELECT count(*) FROM "FleetWorkOrder" w WHERE w."organizationId" = v."organizationId"
                          AND w."vehicleId" = v.id AND w.status IN ('draft', 'open', 'in_progress')) AS "openWorkOrders",
                       (SELECT count(*) FROM "FleetDowntime" d WHERE d."organizationId" = v."organizationId"
                          AND d."vehicleId" = v.id AND d."endedAt" IS NULL) AS "openDowntime"
                """;
        String joins = """
                 LEFT JOIN "Branch" b ON b.id = v."branchId" AND b."organizationId" = v."organizationId"
                 LEFT JOIN "InventoryLocation" l ON l.id = v."homeLocationId" AND l."organizationId" = v."organizationId"
                 LEFT JOIN "Asset" a ON a.id = v."assetId" AND a."organizationId" = v."organizationId"
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(select + from + joins + where
                + " ORDER BY v.code, v.id LIMIT ? OFFSET ?").params(pageParams))) {
            Map<String, Object> out = SqlRows.as(FleetVehicle.class, r);
            out.put("lifetimeHours", MaintainableUnits.lifetimeHours(r));
            for (String key : List.of("categoryName", "categoryCode", "branchName", "locationCode", "locationName",
                    "assetCode", "assetStatus", "openWorkOrders", "openDowntime")) {
                out.put(key, r.get(key));
            }
            rows.add(out);
        }
        return PageResult.of(rows, page, size, total);
    }

    /** The fixed-plant unit linked to a fixed asset, or {@code {unit: null}} (asset detail link). */
    @Transactional(readOnly = true)
    public Map<String, Object> byAsset(String assetId) {
        Map<String, Object> unit = SqlRows.one(db.jdbc().sql("""
                SELECT v.id, v.code, v.name, v.status, c.name AS "categoryName"
                FROM "FleetVehicle" v JOIN "FleetVehicleCategory" c ON c.id = v."categoryId"
                WHERE v."organizationId" = ? AND v."assetId" = ? AND v."deletedAt" IS NULL AND c.kind = ?""")
                .params(FleetDb.org(), assetId, KIND));
        return Obj.of("unit", unit);
    }

    /**
     * Operating cost per equipment over the period: the fleet cost buckets of {@link FleetCostingService} (book
     * depreciation of the linked asset, fuel, maintenance, compliance, incidents), running hours, cost per hour, work
     * orders raised and downtime hours (open downtime counts up to now).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> costs(LocalDate from, LocalDate to, String categoryId, String branchId,
            String locationId) {
        Period p = FleetCostingService.period(from, to);
        String org = FleetDb.org();
        StringBuilder where = new StringBuilder(" WHERE v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL"
                + " AND c.kind = '" + KIND + "'");
        List<Object> params = new ArrayList<>(List.of(org));
        FleetIncidentService.eq(where, params, "v.\"categoryId\"", categoryId);
        FleetIncidentService.eq(where, params, "v.\"branchId\"", branchId);
        FleetIncidentService.eq(where, params, "v.\"homeLocationId\"", locationId);
        List<String> ids = db.jdbc().sql("SELECT v.id FROM \"FleetVehicle\" v JOIN \"FleetVehicleCategory\" c ON"
                + " c.id = v.\"categoryId\"" + where).params(params).query(String.class).list();
        List<VehicleCost> units = costing.vehicleCosts(p, null, null, null, ids);
        Map<String, BigDecimal> downtime = ids.isEmpty() ? Map.of() : downtimeHours(p, ids);
        Map<String, Long> workOrders = ids.isEmpty() ? Map.of() : workOrders(p, ids);
        List<Map<String, Object>> rows = new ArrayList<>();
        CostBuckets costs = CostBuckets.ZERO;
        Usage usage = Usage.NONE;
        BigDecimal downtimeTotal = BigDecimal.ZERO;
        long workOrderTotal = 0;
        for (VehicleCost vc : units) {
            String id = (String) vc.vehicle().get("id");
            BigDecimal hours = downtime.getOrDefault(id, BigDecimal.ZERO);
            long raised = workOrders.getOrDefault(id, 0L);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicleId", id);
            row.put("code", vc.vehicle().get("code"));
            row.put("name", vc.vehicle().get("name"));
            row.put("categoryName", vc.vehicle().get("categoryName"));
            row.put("branchName", vc.vehicle().get("branchName"));
            row.put("status", vc.vehicle().get("status"));
            row.putAll(vc.costs().toMap());
            row.put("hours", vc.usage().hours());
            row.put("perHour", vc.rates().perHour());
            row.put("workOrders", raised);
            row.put("downtimeHours", hours);
            rows.add(row);
            costs = costs.plus(vc.costs());
            usage = usage.plus(vc.usage());
            downtimeTotal = downtimeTotal.add(hours);
            workOrderTotal += raised;
        }
        Map<String, Object> totals = new LinkedHashMap<>(costs.toMap());
        totals.put("hours", usage.hours());
        totals.put("perHour", Rates.of(costs.total(), usage).perHour());
        totals.put("workOrders", workOrderTotal);
        totals.put("downtimeHours", downtimeTotal);
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "totals", totals);
    }

    private Map<String, BigDecimal> downtimeHours(Period p, List<String> ids) {
        Instant end = p.end().isAfter(Instant.now()) ? Instant.now() : p.end();
        Object start = SqlRows.ts(p.start());
        Object stop = SqlRows.ts(end);
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT "vehicleId" AS k,
                       sum(EXTRACT(EPOCH FROM (LEAST(COALESCE("endedAt", ?::timestamp), ?::timestamp)
                           - GREATEST("startedAt", ?::timestamp))) / 3600) AS v
                FROM "FleetDowntime" WHERE "organizationId" = ? AND "vehicleId" = ANY(?::text[])
                  AND "startedAt" < ?::timestamp AND COALESCE("endedAt", ?::timestamp) > ?::timestamp
                GROUP BY "vehicleId\"""").params(stop, stop, start, FleetDb.org(), ids.toArray(String[]::new), stop,
                stop, start))) {
            out.put((String) r.get("k"), SqlRows.dec(r, "v").setScale(2, RoundingMode.HALF_UP));
        }
        return out;
    }

    private Map<String, Long> workOrders(Period p, List<String> ids) {
        Map<String, Long> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT "vehicleId" AS k, count(*) AS v FROM "FleetWorkOrder"
                WHERE "organizationId" = ? AND "vehicleId" = ANY(?::text[]) AND status <> 'cancelled'
                  AND "createdAt" >= ?::timestamp AND "createdAt" < ?::timestamp GROUP BY "vehicleId\"""")
                .params(FleetDb.org(), ids.toArray(String[]::new), SqlRows.ts(p.start()), SqlRows.ts(p.end())))) {
            out.put((String) r.get("k"), ((Number) r.get("v")).longValue());
        }
        return out;
    }

    /**
     * {@code fixed_asset.disposed}: a fixed-plant unit follows its asset to {@code disposed} (idempotent). Road
     * vehicles keep their manual status, as ADR-S009 §4 has it.
     */
    @Transactional
    public void assetDisposed(String assetId) {
        Map<String, Object> unit = byAsset(assetId);
        if (unit.get("unit") instanceof Map<?, ?> u && !VehicleStatusPolicy.DISPOSED.equals(u.get("status"))) {
            vehicles.changeStatus((String) u.get("id"), VehicleStatusPolicy.DISPOSED, "Fixed asset disposed",
                    "fixed_asset", assetId);
        }
    }
}
