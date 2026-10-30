package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetCostCalculator.CostBuckets;
import com.inventoryerp.fleet.application.FleetCostCalculator.Rates;
import com.inventoryerp.fleet.application.FleetCostCalculator.Usage;
import com.inventoryerp.fleet.application.FleetReportCommands.CostQuery;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fleet cost aggregation (fleet 1.9): the total cost of ownership of every unit over a period and its unit rates,
 * read from the module's own records and the linked fixed asset. Sources (all organization-scoped):
 *
 * <ul>
 *   <li><b>Depreciation</b> — posted {@code AssetDepreciation} lines of the vehicle's linked fixed asset (fixedasset
 *       domain, read-only), each monthly amount pro rata by the days of its month in the period.</li>
 *   <li><b>Fuel</b> — posted {@code FleetFuelLog}s by fuelling time: the amount of station purchases, the stock
 *       issue value of tank issues.</li>
 *   <li><b>Maintenance</b> — posted {@code FleetWorkOrderLine}s (parts net of returns, labour, external bills) by
 *       work / bill date, else the posting day.</li>
 *   <li><b>Tyres</b> — the purchase (or stock issue) cost of a tyre on its first fitment to the unit, and retread
 *       costs on the unit the tyre last ran on.</li>
 *   <li><b>Insurance / compliance</b> — {@code FleetComplianceDocument.costAmount} (premium / fee) spread evenly over
 *       the document's validity (insurance documents vs registration, road licence, inspection, permits).</li>
 *   <li><b>Incidents</b> — repair costs of incidents not repaired through a fleet work order, less insurance
 *       recoveries received (settled claims) in the period.</li>
 *   <li><b>Driver allowances</b> — approved / paid / exported {@code FleetDriverClaim}s of the unit by claim date.</li>
 *   <li><b>Fines</b> — traffic fines charged to the company (fines charged to drivers are recovered from pay).</li>
 *   <li><b>Use</b> — km and engine hours from the valid lifetime meter readings (else the trips' distance), tonnes
 *       and trips from the unit's delivered / closed own-fleet trips.</li>
 * </ul>
 */
@Service
public class FleetCostingService {

    /** Org setting: allocation basis of own-fleet trip cost ({@code km}, {@code tonne}, {@code trip}). */
    static final String SETTING_BASIS = "fleet.tripCostBasis";

    private static final int MAX_DAYS = 732;

    private final FleetDb db;
    private final SettingResolverService settings;

    public FleetCostingService(FleetDb db, SettingResolverService settings) {
        this.db = db;
        this.settings = settings;
    }

    /** A business-day period {@code [from, to]} (inclusive) and its UTC bounds. */
    public record Period(LocalDate from, LocalDate to) {

        public Instant start() {
            return from.atStartOfDay(BusinessZone.get()).toInstant();
        }

        public Instant end() {
            return to.plusDays(1).atStartOfDay(BusinessZone.get()).toInstant();
        }

        public long days() {
            return java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    /** Costs, use and the cost pool (total without trip-linked allowances) of one unit over a period. */
    public record VehicleCost(Map<String, Object> vehicle, CostBuckets costs, BigDecimal tripAllowances, Usage usage) {

        /** Cost spread over the unit's trips (allowances claimed for a trip are added to that trip directly). */
        public BigDecimal pool() {
            return costs.total().subtract(tripAllowances);
        }

        public Rates rates() {
            return Rates.of(pool(), usage);
        }
    }

    /** Default period: the month to date. Refused when {@code from} is after {@code to} or longer than two years. */
    public static Period period(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(BusinessZone.get());
        LocalDate t = to == null ? today : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        if (f.isAfter(t)) {
            throw ApiException.badRequest("from must be on or before to");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(f, t) >= MAX_DAYS) {
            throw ApiException.badRequest("A report period can cover at most two years");
        }
        return new Period(f, t);
    }

    /** Allocation basis of own-fleet trip cost (org setting {@code fleet.tripCostBasis}, default km). */
    public String allocationBasis() {
        Object v = settings.resolve(SETTING_BASIS, SettingResolverService.SettingContext.EMPTY);
        String s = v == null ? "km" : String.valueOf(v);
        return List.of("km", "tonne", "trip").contains(s) ? s : "km";
    }

    // ------------------------------------------------------------------ report

    /**
     * Vehicle cost / TCO report: per unit ({@code groupBy} vehicle, default), class, branch or month — every cost
     * bucket, the total, km / hours / tonnes / trips and cost per km / hour / tonne / trip.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> report(CostQuery q) {
        Period p = period(q.from(), q.to());
        String groupBy = q.groupBy() == null || q.groupBy().isBlank() ? "vehicle" : q.groupBy();
        if (!List.of("vehicle", "category", "branch", "month").contains(groupBy)) {
            throw ApiException.badRequest("Group by one of: vehicle, category, branch, month");
        }
        Map<String, Agg> groups = new LinkedHashMap<>();
        Agg total = new Agg("total", "Total");
        if ("month".equals(groupBy)) {
            YearMonth m = YearMonth.from(p.from());
            YearMonth last = YearMonth.from(p.to());
            while (!m.isAfter(last)) {
                LocalDate f = m.atDay(1).isBefore(p.from()) ? p.from() : m.atDay(1);
                LocalDate t = m.atEndOfMonth().isAfter(p.to()) ? p.to() : m.atEndOfMonth();
                Agg a = new Agg(m.toString(), m.toString());
                for (VehicleCost vc : vehicleCosts(new Period(f, t), q.vehicleId(), q.categoryId(), q.branchId(),
                        null)) {
                    a.add(vc);
                    total.add(vc);
                }
                groups.put(a.key, a);
                m = m.plusMonths(1);
            }
        } else {
            for (VehicleCost vc : vehicleCosts(p, q.vehicleId(), q.categoryId(), q.branchId(), null)) {
                Map<String, Object> v = vc.vehicle();
                String key = switch (groupBy) {
                    case "category" -> (String) v.get("categoryId");
                    case "branch" -> v.get("branchId") == null ? "-" : (String) v.get("branchId");
                    default -> (String) v.get("id");
                };
                String label = switch (groupBy) {
                    case "category" -> (String) v.get("categoryName");
                    case "branch" -> v.get("branchName") == null ? "(no branch)" : (String) v.get("branchName");
                    default -> v.get("code") + (v.get("registrationPlate") == null ? ""
                            : " · " + v.get("registrationPlate"));
                };
                Agg a = groups.computeIfAbsent(key, k -> new Agg(k, label));
                if ("vehicle".equals(groupBy)) {
                    a.vehicle = v;
                }
                a.add(vc);
                total.add(vc);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Agg a : groups.values()) {
            rows.add(a.row());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", p.from());
        out.put("to", p.to());
        out.put("groupBy", groupBy);
        out.put("allocationBasis", allocationBasis());
        out.put("rows", rows);
        out.put("totals", total.row());
        return out;
    }

    /**
     * One unit's cost of ownership over the period (vehicle detail): the totals plus a row per month
     * ({@code groupBy} month, default) or the single period row ({@code vehicle}). 404 for an unknown unit.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> vehicleSummary(String vehicleId, LocalDate from, LocalDate to, String groupBy) {
        db.require(com.inventoryerp.fleet.domain.FleetVehicle.class, vehicleId, false, "Vehicle not found");
        LocalDate t = to == null ? LocalDate.now(BusinessZone.get()) : to;
        LocalDate f = from == null ? t.withDayOfMonth(1).minusMonths(11) : from;
        Map<String, Object> out = report(new CostQuery(f, t, groupBy == null ? "month" : groupBy, vehicleId, null,
                null));
        out.put("vehicleId", vehicleId);
        return out;
    }

    /** Running sum of vehicle costs for one report row. */
    private static final class Agg {
        final String key;
        final String label;
        Map<String, Object> vehicle;
        CostBuckets costs = CostBuckets.ZERO;
        BigDecimal tripAllowances = BigDecimal.ZERO;
        Usage usage = Usage.NONE;
        int vehicles;

        Agg(String key, String label) {
            this.key = key;
            this.label = label;
        }

        void add(VehicleCost vc) {
            costs = costs.plus(vc.costs());
            tripAllowances = tripAllowances.add(vc.tripAllowances());
            usage = usage.plus(vc.usage());
            vehicles++;
        }

        Map<String, Object> row() {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", key);
            r.put("label", label);
            if (vehicle != null) {
                r.put("vehicleId", vehicle.get("id"));
                r.put("vehicleCode", vehicle.get("code"));
                r.put("registrationPlate", vehicle.get("registrationPlate"));
                r.put("categoryName", vehicle.get("categoryName"));
                r.put("branchName", vehicle.get("branchName"));
                r.put("ownership", vehicle.get("ownership"));
            }
            r.put("vehicles", vehicles);
            r.putAll(costs.toMap());
            r.put("running", costs.running());
            r.put("km", usage.km());
            r.put("hours", usage.hours());
            r.put("tonnes", usage.tonnes());
            r.put("trips", usage.trips());
            // Unit rates on the full cost of ownership (what a unit costs per km / hour / tonne / trip).
            r.putAll(Rates.of(costs.total(), usage).toMap());
            return r;
        }
    }

    // ------------------------------------------------------------------ aggregation

    /**
     * Cost and use of every live unit matching the filters over {@code p} ({@code onlyIds} restricts to those unit
     * ids when not null). Units without any cost or use are included (zero rows), ordered by code.
     */
    @Transactional(readOnly = true)
    public List<VehicleCost> vehicleCosts(Period p, String vehicleId, String categoryId, String branchId,
            Collection<String> onlyIds) {
        String org = FleetDb.org();
        StringBuilder where = new StringBuilder(" WHERE v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org));
        FleetIncidentService.eq(where, params, "v.id", vehicleId);
        FleetIncidentService.eq(where, params, "v.\"categoryId\"", categoryId);
        FleetIncidentService.eq(where, params, "v.\"branchId\"", branchId);
        if (onlyIds != null) {
            if (onlyIds.isEmpty()) {
                return List.of();
            }
            where.append(" AND v.id = ANY(?::text[])");
            params.add(onlyIds.toArray(String[]::new));
        }
        List<Map<String, Object>> vehicles = SqlRows.list(db.jdbc().sql("""
                SELECT v.id, v.code, v.name, v."registrationPlate", v."categoryId", c.name AS "categoryName",
                       c.kind AS "categoryKind", v."branchId", b.name AS "branchName", v."assetId", v."costCenterId",
                       v.ownership, v.status
                FROM "FleetVehicle" v JOIN "FleetVehicleCategory" c ON c.id = v."categoryId"
                LEFT JOIN "Branch" b ON b.id = v."branchId" AND b."organizationId" = v."organizationId"
                """ + where + " ORDER BY v.code").params(params));
        if (vehicles.isEmpty()) {
            return List.of();
        }
        Object start = SqlRows.ts(p.start());
        Object end = SqlRows.ts(p.end());
        Object fromDay = java.sql.Date.valueOf(p.from());
        Object toDay = java.sql.Date.valueOf(p.to());
        String zone = BusinessZone.get().getId();

        Map<String, BigDecimal> fuel = sums("""
                SELECT "vehicleId" AS k, COALESCE(sum(CASE WHEN source = 'tank' THEN COALESCE("stockValue", 0)
                       ELSE COALESCE(amount, 0) END), 0) AS v
                FROM "FleetFuelLog" WHERE "organizationId" = ? AND status = 'posted' AND "deletedAt" IS NULL
                  AND "fuelledAt" >= ? AND "fuelledAt" < ? GROUP BY "vehicleId\"""", org, start, end);
        Map<String, BigDecimal> maintenance = sums("""
                SELECT "vehicleId" AS k, COALESCE(sum(amount), 0) AS v FROM "FleetWorkOrderLine"
                WHERE "organizationId" = ? AND status = 'posted'
                  AND COALESCE("workDate", "billDate", ("createdAt" AT TIME ZONE 'UTC' AT TIME ZONE ?)::date)
                      BETWEEN ? AND ?
                GROUP BY "vehicleId\"""", org, zone, fromDay, toDay);
        Map<String, BigDecimal> tyres = sums("""
                SELECT e."vehicleId" AS k, COALESCE(sum(COALESCE(t."stockIssueValue", t."purchaseCost", 0)), 0) AS v
                FROM "FleetTyreEvent" e JOIN "FleetTyre" t ON t.id = e."tyreId"
                WHERE e."organizationId" = ? AND e."eventType" = 'fitted' AND e."vehicleId" IS NOT NULL
                  AND e."eventAt" >= ? AND e."eventAt" < ?
                  AND NOT EXISTS (SELECT 1 FROM "FleetTyreEvent" f WHERE f."tyreId" = e."tyreId"
                      AND f."eventType" = 'fitted' AND (f."eventAt" < e."eventAt"
                          OR (f."eventAt" = e."eventAt" AND f.id < e.id)))
                GROUP BY e."vehicleId\"""", org, start, end);
        add(tyres, sums("""
                SELECT x.k, COALESCE(sum(x.cost), 0) AS v FROM (
                  SELECT (SELECT f."vehicleId" FROM "FleetTyreEvent" f WHERE f."tyreId" = e."tyreId"
                            AND f."vehicleId" IS NOT NULL AND f."eventAt" <= e."eventAt"
                          ORDER BY f."eventAt" DESC, f.id DESC LIMIT 1) AS k, e.cost
                  FROM "FleetTyreEvent" e WHERE e."organizationId" = ? AND e."eventType" = 'retreaded'
                    AND e.cost IS NOT NULL AND e."eventAt" >= ? AND e."eventAt" < ?) x
                WHERE x.k IS NOT NULL GROUP BY x.k""", org, start, end));
        Map<String, BigDecimal> insurance = new HashMap<>();
        Map<String, BigDecimal> compliance = new HashMap<>();
        for (Map<String, Object> d : SqlRows.list(db.jdbc().sql("""
                SELECT "vehicleId", "docType", "costAmount", "issuedOn", "expiresOn",
                       ("createdAt" AT TIME ZONE 'UTC' AT TIME ZONE ?)::date AS "createdOn"
                FROM "FleetComplianceDocument" WHERE "organizationId" = ? AND "deletedAt" IS NULL
                  AND "costAmount" > 0 AND COALESCE("issuedOn", ("createdAt" AT TIME ZONE 'UTC' AT TIME ZONE ?)::date) <= ?
                  AND ("expiresOn" IS NULL OR "expiresOn" >= ?)""").params(zone, org, zone, toDay, fromDay))) {
            BigDecimal share = FleetCostCalculator.amortised(SqlRows.dec(d, "costAmount"),
                    (LocalDate) SqlRows.value(d.get("issuedOn")), (LocalDate) SqlRows.value(d.get("expiresOn")),
                    (LocalDate) SqlRows.value(d.get("createdOn")), p.from(), p.to());
            (("insurance".equals(d.get("docType"))) ? insurance : compliance).merge((String) d.get("vehicleId"),
                    share, BigDecimal::add);
        }
        Map<String, BigDecimal> incidents = sums("""
                SELECT "vehicleId" AS k, COALESCE(sum("repairCost"), 0) AS v FROM "FleetIncident"
                WHERE "organizationId" = ? AND "deletedAt" IS NULL AND status <> 'cancelled' AND "workOrderId" IS NULL
                  AND "repairCost" > 0 AND "occurredAt" >= ? AND "occurredAt" < ? GROUP BY "vehicleId\"""",
                org, start, end);
        for (Map.Entry<String, BigDecimal> r : sums("""
                SELECT i."vehicleId" AS k, COALESCE(sum(c."amountReceived"), 0) AS v FROM "FleetInsuranceClaim" c
                JOIN "FleetIncident" i ON i.id = c."incidentId"
                WHERE c."organizationId" = ? AND c.status = 'settled' AND c."amountReceived" > 0
                  AND c."settledOn" BETWEEN ? AND ? GROUP BY i."vehicleId\"""", org, fromDay, toDay).entrySet()) {
            incidents.merge(r.getKey(), r.getValue().negate(), BigDecimal::add);
        }
        Map<String, BigDecimal> allowances = new HashMap<>();
        Map<String, BigDecimal> tripAllowances = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT "vehicleId" AS k, COALESCE(sum("totalAmount"), 0) AS v,
                       COALESCE(sum("totalAmount") FILTER (WHERE "tripId" IS NOT NULL), 0) AS t
                FROM "FleetDriverClaim" WHERE "organizationId" = ? AND "vehicleId" IS NOT NULL
                  AND status IN ('approved', 'paid', 'exported') AND "claimDate" BETWEEN ? AND ?
                GROUP BY "vehicleId\"""").params(org, fromDay, toDay))) {
            allowances.put((String) r.get("k"), SqlRows.dec(r, "v"));
            tripAllowances.put((String) r.get("k"), SqlRows.dec(r, "t"));
        }
        Map<String, BigDecimal> fines = sums("""
                SELECT "vehicleId" AS k, COALESCE(sum(amount), 0) AS v FROM "FleetFine"
                WHERE "organizationId" = ? AND "deletedAt" IS NULL AND "chargedTo" = 'company'
                  AND status IN ('charged', 'settled') AND "offenceAt" >= ? AND "offenceAt" < ? GROUP BY "vehicleId\"""",
                org, start, end);
        Map<String, BigDecimal> depreciation = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT v.id AS k, d.period, d."depreciationAmount" FROM "FleetVehicle" v
                JOIN "AssetDepreciation" d ON d."assetId" = v."assetId" AND d."organizationId" = v."organizationId"
                WHERE v."organizationId" = ? AND v."assetId" IS NOT NULL AND v."deletedAt" IS NULL
                  AND d."isPosted" = true AND d.period >= ? AND d.period <= ?""").params(org,
                YearMonth.from(p.from()).toString(), YearMonth.from(p.to()).toString()))) {
            depreciation.merge((String) r.get("k"), FleetCostCalculator.depreciationShare((String) r.get("period"),
                    SqlRows.dec(r, "depreciationAmount"), p.from(), p.to()), BigDecimal::add);
        }
        Map<String, BigDecimal[]> meters = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT "vehicleId", "meterType",
                       max("lifetimeValue") FILTER (WHERE "readingAt" < ?) AS "lastBefore",
                       min("lifetimeValue") FILTER (WHERE "readingAt" >= ?) AS "firstIn",
                       max("lifetimeValue") FILTER (WHERE "readingAt" >= ?) AS "lastIn"
                FROM "FleetMeterReading" WHERE "organizationId" = ? AND status = 'valid' AND "readingAt" < ?
                GROUP BY "vehicleId", "meterType\"""").params(start, start, start, org, end))) {
            BigDecimal use = FleetCostCalculator.meterUse((BigDecimal) r.get("lastBefore"), (BigDecimal) r.get("firstIn"),
                    (BigDecimal) r.get("lastIn"));
            BigDecimal[] m = meters.computeIfAbsent((String) r.get("vehicleId"), k -> new BigDecimal[2]);
            m["odometer".equals(r.get("meterType")) ? 0 : 1] = use;
        }
        Map<String, Map<String, Object>> trips = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT "vehicleId" AS k, count(*) AS n, sum(COALESCE("deliveredTonnes", "loadedTonnes")) AS t,
                       sum("distanceKm") AS km
                FROM "FleetLoad" WHERE "organizationId" = ? AND carrier = 'own' AND "vehicleId" IS NOT NULL
                  AND status IN ('delivered', 'closed') AND "deliveredAt" >= ? AND "deliveredAt" < ?
                GROUP BY "vehicleId\"""").params(org, start, end))) {
            trips.put((String) r.get("k"), r);
        }
        List<VehicleCost> out = new ArrayList<>();
        for (Map<String, Object> v : vehicles) {
            String id = (String) v.get("id");
            CostBuckets c = new CostBuckets(get(depreciation, id), get(fuel, id), get(maintenance, id),
                    get(tyres, id), get(insurance, id), get(compliance, id), get(incidents, id), get(allowances, id),
                    get(fines, id));
            BigDecimal[] m = meters.getOrDefault(id, new BigDecimal[2]);
            Map<String, Object> t = trips.get(id);
            BigDecimal tripKm = t == null ? null : (BigDecimal) t.get("km");
            BigDecimal km = m[0] != null ? m[0] : tripKm;
            Usage u = new Usage(km, m[1], t == null ? null : (BigDecimal) t.get("t"),
                    t == null ? 0 : ((Number) t.get("n")).intValue());
            out.add(new VehicleCost(v, c, get(tripAllowances, id), u));
        }
        return out;
    }

    private Map<String, BigDecimal> sums(String sql, Object... params) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(sql).params(params))) {
            if (r.get("k") != null) {
                out.merge((String) r.get("k"), SqlRows.dec(r, "v"), BigDecimal::add);
            }
        }
        return out;
    }

    private static void add(Map<String, BigDecimal> into, Map<String, BigDecimal> more) {
        more.forEach((k, v) -> into.merge(k, v, BigDecimal::add));
    }

    private static BigDecimal get(Map<String, BigDecimal> m, String id) {
        BigDecimal v = m.get(id);
        return v == null ? BigDecimal.ZERO : v;
    }
}
