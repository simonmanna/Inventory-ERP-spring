package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetCostCalculator.Allocation;
import com.inventoryerp.fleet.application.FleetCostCalculator.Profit;
import com.inventoryerp.fleet.application.FleetCostCalculator.Rates;
import com.inventoryerp.fleet.application.FleetCostingService.Period;
import com.inventoryerp.fleet.application.FleetCostingService.VehicleCost;
import com.inventoryerp.fleet.application.FleetReportCommands.CostQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.DeliveryQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.ProfitQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.RenewalQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.UnitQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.ReliabilityQuery;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fleet 1.9 reports and dashboard KPIs over the fleet records: delivery profitability, deliveries completed / on time,
 * tonnes delivered, transport cost per tonne, utilisation and availability, fuel, upcoming renewals and services. Cost
 * figures come from {@link FleetCostingService}; formulas from {@link FleetCostCalculator}. Every period is in
 * business days (the organization's time zone); trips count on their delivery (POD) time.
 */
@Service
public class FleetReportService {

    private static final String NONE = "(none)";

    private final FleetDb db;
    private final FleetCostingService costing;
    private final FleetDowntimeService downtime;
    private final FleetServiceScheduleService schedules;

    public FleetReportService(FleetDb db, FleetCostingService costing, FleetDowntimeService downtime,
            FleetServiceScheduleService schedules) {
        this.db = db;
        this.costing = costing;
        this.downtime = downtime;
        this.schedules = schedules;
    }

    // ------------------------------------------------------------------ trip facts

    /** Everything the delivery reports need about one finished trip (delivered / closed in the period). */
    record TripFact(String id, String code, String carrier, String vehicleId, String vehicleLabel, String driverId,
            String driverLabel, String transporterId, String transporterLabel, String orderId, String orderCode,
            String partnerId, String partnerLabel, String siteId, String siteLabel, LocalDate day, boolean onTime,
            BigDecimal tonnes, BigDecimal km, BigDecimal revenue, BigDecimal charge, BigDecimal productCost,
            boolean productCostEstimated, BigDecimal tripCost, String costBasis) {}

    /** Finished trips of the period with revenue, charge, product cost and trip cost (see the class comment). */
    List<TripFact> tripFacts(Period p, String partnerId, String siteId, String vehicleId, String driverId,
            String carrier, String transporterId, String deliveryOrderId) {
        String org = FleetDb.org();
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND t.status IN ('delivered',"
                + " 'closed') AND t.\"deliveredAt\" >= ? AND t.\"deliveredAt\" < ?");
        List<Object> params = new ArrayList<>(List.of(org, SqlRows.ts(p.start()), SqlRows.ts(p.end())));
        FleetIncidentService.eq(where, params, "o.\"partnerId\"", partnerId);
        FleetIncidentService.eq(where, params, "o.\"siteId\"", siteId);
        FleetIncidentService.eq(where, params, "t.\"vehicleId\"", vehicleId);
        FleetIncidentService.eq(where, params, "t.\"driverId\"", driverId);
        FleetIncidentService.eq(where, params, "t.carrier", carrier);
        FleetIncidentService.eq(where, params, "t.\"transporterPartnerId\"", transporterId);
        FleetIncidentService.eq(where, params, "t.\"deliveryOrderId\"", deliveryOrderId);
        List<Map<String, Object>> trips = SqlRows.list(db.jdbc().sql("""
                SELECT t.id, t.code, t.carrier, t."vehicleId", t."driverId", t."transporterPartnerId",
                       t."deliveryOrderId", t."deliveredAt", t."distanceKm", t."freightAmount", t."freightBasis",
                       t."freightRate", COALESCE(t."deliveredTonnes", t."loadedTonnes", t."plannedTonnes") AS tonnes,
                       o.code AS "orderCode", o."partnerId", o."siteId", o."requestedDate", o."windowTo",
                       pa.name AS "partnerName", s.code AS "siteCode", s.name AS "siteName",
                       v.code AS "vehicleCode", v."registrationPlate", d.code AS "driverCode",
                       COALESCE(NULLIF(trim(coalesce(e."firstName", '') || ' ' || coalesce(e."lastName", '')), ''), d.code)
                           AS "driverName",
                       tp.name AS "transporterName"
                FROM "FleetLoad" t JOIN "FleetDeliveryOrder" o ON o.id = t."deliveryOrderId"
                JOIN "Partner" pa ON pa.id = o."partnerId"
                LEFT JOIN "FleetCustomerSite" s ON s.id = o."siteId"
                LEFT JOIN "FleetVehicle" v ON v.id = t."vehicleId"
                LEFT JOIN "FleetDriver" d ON d.id = t."driverId"
                LEFT JOIN "HrEmployee" e ON e.id = d."employeeId" AND e."organizationId" = d."organizationId"
                LEFT JOIN "Partner" tp ON tp.id = t."transporterPartnerId"
                """ + where + " ORDER BY t.\"deliveredAt\", t.code").params(params));
        if (trips.isEmpty()) {
            return List.of();
        }
        String[] tripIds = trips.stream().map(t -> (String) t.get("id")).toArray(String[]::new);
        String[] orderIds = trips.stream().map(t -> (String) t.get("deliveryOrderId")).distinct()
                .toArray(String[]::new);
        Map<String, BigDecimal[]> goods = new HashMap<>();
        Set<String> estimated = new HashSet<>();
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("""
                SELECT l."loadId", l."dispatchedQty", l."returnedQty", l."billableQty", l."deliveredQty",
                       l."stockValue", l."uomFactor", ol."unitPrice", ol."discountPercent", pr."costPrice"
                FROM "FleetLoadLine" l JOIN "FleetDeliveryOrderLine" ol ON ol.id = l."deliveryOrderLineId"
                JOIN "Product" pr ON pr.id = l."productId"
                WHERE l."organizationId" = ? AND l."loadId" = ANY(?::text[])""").params(org, tripIds))) {
            BigDecimal billable = l.get("billableQty") != null ? (BigDecimal) l.get("billableQty")
                    : SqlRows.dec(l, "deliveredQty");
            BigDecimal revenue = FleetCostCalculator.lineRevenue(billable, (BigDecimal) l.get("unitPrice"),
                    (BigDecimal) l.get("discountPercent"));
            BigDecimal cost;
            if (l.get("stockValue") != null) {
                cost = FleetCostCalculator.deliveredCost((BigDecimal) l.get("stockValue"),
                        (BigDecimal) l.get("dispatchedQty"), (BigDecimal) l.get("returnedQty"));
            } else {
                // Goods issued by the source invoice (stock mode invoiced): estimated at the product's cost price.
                BigDecimal kept = SqlRows.dec(l, "dispatchedQty").subtract(SqlRows.dec(l, "returnedQty"));
                cost = FleetCostCalculator.money(kept.max(BigDecimal.ZERO).multiply(SqlRows.dec(l, "uomFactor"))
                        .multiply(SqlRows.dec(l, "costPrice")));
                if (cost.signum() > 0) {
                    estimated.add((String) l.get("loadId"));
                }
            }
            BigDecimal[] g = goods.computeIfAbsent((String) l.get("loadId"),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            g[0] = g[0].add(revenue);
            g[1] = g[1].add(cost);
        }
        Map<String, BigDecimal> charges = new HashMap<>();
        for (Map<String, Object> c : SqlRows.list(db.jdbc().sql("SELECT \"deliveryOrderId\" AS k, sum(amount) AS v"
                + " FROM \"FleetDeliveryCharge\" WHERE \"organizationId\" = ? AND \"deliveryOrderId\" = ANY(?::text[])"
                + " GROUP BY \"deliveryOrderId\"").params(org, orderIds))) {
            charges.put((String) c.get("k"), SqlRows.dec(c, "v"));
        }
        Map<String, Map<String, Object>> orderTrips = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT \"deliveryOrderId\" AS k, count(*) AS n,"
                + " sum(COALESCE(\"deliveredTonnes\", \"loadedTonnes\", \"plannedTonnes\")) AS t FROM \"FleetLoad\""
                + " WHERE \"organizationId\" = ? AND \"deliveryOrderId\" = ANY(?::text[]) AND status IN ('delivered',"
                + " 'closed') GROUP BY \"deliveryOrderId\"").params(org, orderIds))) {
            orderTrips.put((String) r.get("k"), r);
        }
        Map<String, BigDecimal> billed = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT \"tripId\" AS k, sum(amount) AS v FROM"
                + " \"FleetFreightBillLine\" WHERE \"organizationId\" = ? AND status = 'active' AND \"tripId\" ="
                + " ANY(?::text[]) GROUP BY \"tripId\"").params(org, tripIds))) {
            billed.put((String) r.get("k"), SqlRows.dec(r, "v"));
        }
        Map<String, BigDecimal> allowances = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT \"tripId\" AS k, sum(\"totalAmount\") AS v"
                + " FROM \"FleetDriverClaim\" WHERE \"organizationId\" = ? AND \"tripId\" = ANY(?::text[]) AND status"
                + " IN ('approved', 'paid', 'exported') GROUP BY \"tripId\"").params(org, tripIds))) {
            allowances.put((String) r.get("k"), SqlRows.dec(r, "v"));
        }
        Set<String> ownVehicles = new HashSet<>();
        for (Map<String, Object> t : trips) {
            if ("own".equals(t.get("carrier")) && t.get("vehicleId") != null) {
                ownVehicles.add((String) t.get("vehicleId"));
            }
        }
        Map<String, Rates> rates = new HashMap<>();
        for (VehicleCost vc : costing.vehicleCosts(p, null, null, null, ownVehicles)) {
            rates.put((String) vc.vehicle().get("id"), vc.rates());
        }
        String basis = costing.allocationBasis();
        ZoneId zone = BusinessZone.get();
        List<TripFact> out = new ArrayList<>();
        for (Map<String, Object> t : trips) {
            String id = (String) t.get("id");
            String orderId = (String) t.get("deliveryOrderId");
            BigDecimal tonnes = (BigDecimal) t.get("tonnes");
            BigDecimal km = (BigDecimal) t.get("distanceKm");
            BigDecimal[] g = goods.getOrDefault(id, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            Map<String, Object> ot = orderTrips.get(orderId);
            BigDecimal charge = FleetCostCalculator.share(charges.get(orderId), tonnes,
                    ot == null ? null : (BigDecimal) ot.get("t"), ot == null ? 1 : ((Number) ot.get("n")).intValue());
            BigDecimal tripCost;
            String costBasis;
            if ("transporter".equals(t.get("carrier"))) {
                BigDecimal freight = billed.containsKey(id) ? billed.get(id) : t.get("freightAmount") != null
                        ? (BigDecimal) t.get("freightAmount")
                        : FreightCalculator.amount((String) t.get("freightBasis"), (BigDecimal) t.get("freightRate"),
                                tonnes, km);
                tripCost = FleetCostCalculator.money(FleetCostCalculator.nz(freight));
                costBasis = billed.containsKey(id) ? "freight_bill" : "freight";
            } else {
                Rates r = rates.get((String) t.get("vehicleId"));
                Allocation a = r == null ? new Allocation(BigDecimal.ZERO.setScale(2), "none")
                        : FleetCostCalculator.allocate(basis, r, km, tonnes);
                tripCost = a.amount().add(FleetCostCalculator.money(allowances.getOrDefault(id, BigDecimal.ZERO)));
                costBasis = a.basis();
            }
            Instant delivered = (Instant) t.get("deliveredAt");
            LocalDateTime local = LocalDateTime.ofInstant(delivered, zone);
            out.add(new TripFact(id, (String) t.get("code"), (String) t.get("carrier"), (String) t.get("vehicleId"),
                    t.get("vehicleCode") == null ? null : t.get("vehicleCode") + (t.get("registrationPlate") == null
                            ? "" : " · " + t.get("registrationPlate")),
                    (String) t.get("driverId"), t.get("driverId") == null ? null : (String) t.get("driverName"),
                    (String) t.get("transporterPartnerId"), (String) t.get("transporterName"), orderId,
                    (String) t.get("orderCode"), (String) t.get("partnerId"), (String) t.get("partnerName"),
                    (String) t.get("siteId"), t.get("siteId") == null ? null
                            : t.get("siteCode") + " · " + t.get("siteName"), local.toLocalDate(),
                    FleetCostCalculator.onTime((LocalDate) t.get("requestedDate"), (String) t.get("windowTo"), local),
                    tonnes, km, FleetCostCalculator.money(g[0]), charge, FleetCostCalculator.money(g[1]),
                    estimated.contains(id), tripCost, costBasis));
        }
        return out;
    }

    /** Grouping key + label of a trip fact. */
    private static String[] key(TripFact f, String groupBy) {
        return switch (groupBy) {
            case "delivery_order" -> new String[] {f.orderId(), f.orderCode()};
            case "customer" -> new String[] {f.partnerId(), f.partnerLabel()};
            case "site" -> new String[] {nz(f.siteId()), f.siteLabel() == null ? "(no site)" : f.siteLabel()};
            case "day" -> new String[] {f.day().toString(), f.day().toString()};
            case "month" -> new String[] {YearMonth.from(f.day()).toString(), YearMonth.from(f.day()).toString()};
            case "vehicle" -> "own".equals(f.carrier())
                    ? new String[] {nz(f.vehicleId()), f.vehicleLabel() == null ? NONE : f.vehicleLabel()}
                    : new String[] {"transporter:" + nz(f.transporterId()), "(transporter) " + nz(f.transporterLabel())};
            case "driver" -> "own".equals(f.carrier())
                    ? new String[] {nz(f.driverId()), f.driverLabel() == null ? "(no driver)" : f.driverLabel()}
                    : new String[] {"transporter:" + nz(f.transporterId()), "(transporter) " + nz(f.transporterLabel())};
            case "transporter" -> "own".equals(f.carrier()) ? new String[] {"own", "Own fleet"}
                    : new String[] {nz(f.transporterId()), nz(f.transporterLabel())};
            case "carrier" -> "own".equals(f.carrier()) ? new String[] {"own", "Own fleet"}
                    : new String[] {"transporter", "Third-party transporters"};
            default -> new String[] {f.id(), f.code()};
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static <T> Map<String, List<T>> group(List<T> items, Function<T, String[]> key, Map<String, String> labels) {
        Map<String, List<T>> out = new LinkedHashMap<>();
        for (T it : items) {
            String[] k = key.apply(it);
            labels.putIfAbsent(k[0], k[1]);
            out.computeIfAbsent(k[0], x -> new ArrayList<>()).add(it);
        }
        return out;
    }

    private static Map<String, Object> envelope(Period p, String groupBy, List<Map<String, Object>> rows,
            Map<String, Object> totals) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", p.from());
        out.put("to", p.to());
        out.put("groupBy", groupBy);
        out.put("rows", rows);
        out.put("totals", totals);
        return out;
    }

    private static String groupBy(String given, String fallback, List<String> allowed) {
        String g = given == null || given.isBlank() ? fallback : given;
        if (!allowed.contains(g)) {
            throw ApiException.badRequest("Group by one of: " + String.join(", ", allowed));
        }
        return g;
    }

    private static BigDecimal sum(List<TripFact> fs, Function<TripFact, BigDecimal> f) {
        BigDecimal s = BigDecimal.ZERO;
        for (TripFact t : fs) {
            BigDecimal v = f.apply(t);
            if (v != null) {
                s = s.add(v);
            }
        }
        return s;
    }

    private static BigDecimal qty(BigDecimal v) {
        return v == null ? null : v.setScale(3, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ delivery profitability

    /**
     * Delivery profitability per trip (default), delivery order, customer, site, day or month: goods revenue
     * (billable quantity × order price net of discount), transport charge (the delivery order's charges shared by
     * tonnes over its finished trips), product cost (stock issue value net of returns; estimated at cost price when
     * the source invoice issued the goods), trip cost and margin.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> profitability(ProfitQuery q) {
        Period p = FleetCostingService.period(q.from(), q.to());
        String g = groupBy(q.groupBy(), "trip", List.of("trip", "delivery_order", "customer", "site", "day", "month"));
        List<TripFact> facts = tripFacts(p, q.partnerId(), q.siteId(), q.vehicleId(), null, q.carrier(),
                q.transporterPartnerId(), q.deliveryOrderId());
        Map<String, String> labels = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<TripFact>> e : group(facts, f -> key(f, g), labels).entrySet()) {
            Map<String, Object> r = profitRow(e.getKey(), labels.get(e.getKey()), e.getValue());
            if ("trip".equals(g)) {
                TripFact f = e.getValue().getFirst();
                r.put("tripId", f.id());
                r.put("day", f.day());
                r.put("carrier", f.carrier());
                r.put("vehicle", f.vehicleLabel());
                r.put("transporter", f.transporterLabel());
                r.put("deliveryOrderId", f.orderId());
                r.put("deliveryOrder", f.orderCode());
                r.put("customer", f.partnerLabel());
                r.put("site", f.siteLabel());
                r.put("costBasis", f.costBasis());
                r.put("productCostEstimated", f.productCostEstimated());
            }
            rows.add(r);
        }
        Map<String, Object> out = envelope(p, g, rows, profitRow("total", "Total", facts));
        out.put("allocationBasis", costing.allocationBasis());
        return out;
    }

    private static Map<String, Object> profitRow(String key, String label, List<TripFact> fs) {
        Profit pr = FleetCostCalculator.profit(sum(fs, TripFact::revenue), sum(fs, TripFact::charge),
                sum(fs, TripFact::productCost), sum(fs, TripFact::tripCost));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        r.put("label", label);
        r.put("trips", fs.size());
        r.put("tonnes", qty(sum(fs, TripFact::tonnes)));
        r.putAll(pr.toMap());
        return r;
    }

    // ------------------------------------------------------------------ deliveries, tonnes, transport cost

    /**
     * Deliveries completed and on time per day (default), month, customer, site, vehicle, driver, transporter or
     * carrier: finished trips, on time / late (POD by the requested day and window end), on-time %, tonnes, delivery
     * orders, and trips returned in the period.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> deliveries(DeliveryQuery q) {
        Period p = FleetCostingService.period(q.from(), q.to());
        String g = groupBy(q.groupBy(), "day", List.of("day", "month", "customer", "site", "vehicle", "driver",
                "transporter", "carrier"));
        List<TripFact> facts = tripFacts(p, q.partnerId(), q.siteId(), q.vehicleId(), q.driverId(), q.carrier(),
                q.transporterPartnerId(), null);
        Map<String, String> labels = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<TripFact>> e : group(facts, f -> key(f, g), labels).entrySet()) {
            rows.add(deliveryRow(e.getKey(), labels.get(e.getKey()), e.getValue()));
        }
        Map<String, Object> totals = deliveryRow("total", "Total", facts);
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND t.status = 'returned' AND"
                + " t.\"returnedAt\" >= ? AND t.\"returnedAt\" < ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), SqlRows.ts(p.start()), SqlRows.ts(p.end())));
        FleetIncidentService.eq(where, params, "o.\"partnerId\"", q.partnerId());
        FleetIncidentService.eq(where, params, "o.\"siteId\"", q.siteId());
        FleetIncidentService.eq(where, params, "t.\"vehicleId\"", q.vehicleId());
        FleetIncidentService.eq(where, params, "t.\"driverId\"", q.driverId());
        totals.put("returnedTrips", db.jdbc().sql("SELECT count(*) FROM \"FleetLoad\" t JOIN \"FleetDeliveryOrder\" o"
                + " ON o.id = t.\"deliveryOrderId\"" + where).params(params).query(Long.class).single());
        return envelope(p, g, rows, totals);
    }

    private static Map<String, Object> deliveryRow(String key, String label, List<TripFact> fs) {
        long onTime = fs.stream().filter(TripFact::onTime).count();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        r.put("label", label);
        r.put("trips", fs.size());
        r.put("orders", fs.stream().map(TripFact::orderId).distinct().count());
        r.put("onTime", onTime);
        r.put("late", fs.size() - onTime);
        r.put("onTimePct", FleetCostCalculator.pct(BigDecimal.valueOf(onTime), BigDecimal.valueOf(fs.size()), 1));
        r.put("tonnes", qty(sum(fs, TripFact::tonnes)));
        r.put("km", sum(fs, TripFact::km).setScale(1, RoundingMode.HALF_UP));
        return r;
    }

    /**
     * Tonnes delivered per vehicle (default), driver, customer, site, transporter, product or month: delivered tonnes
     * of finished trips (product: delivered quantity × tonnes per unit of the trip line).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> tonnes(DeliveryQuery q) {
        Period p = FleetCostingService.period(q.from(), q.to());
        String g = groupBy(q.groupBy(), "vehicle", List.of("vehicle", "driver", "customer", "site", "transporter",
                "product", "month", "day"));
        if (!"product".equals(g)) {
            List<TripFact> facts = tripFacts(p, q.partnerId(), q.siteId(), q.vehicleId(), q.driverId(), q.carrier(),
                    q.transporterPartnerId(), null);
            Map<String, String> labels = new LinkedHashMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map.Entry<String, List<TripFact>> e : group(facts, f -> key(f, g), labels).entrySet()) {
                rows.add(tonnesRow(e.getKey(), labels.get(e.getKey()), e.getValue()));
            }
            rows.sort(Comparator.comparing((Map<String, Object> r) -> (BigDecimal) r.get("tonnes")).reversed());
            return envelope(p, g, rows, tonnesRow("total", "Total", facts));
        }
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND t.status IN ('delivered',"
                + " 'closed') AND t.\"deliveredAt\" >= ? AND t.\"deliveredAt\" < ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), SqlRows.ts(p.start()), SqlRows.ts(p.end())));
        FleetIncidentService.eq(where, params, "o.\"partnerId\"", q.partnerId());
        FleetIncidentService.eq(where, params, "o.\"siteId\"", q.siteId());
        FleetIncidentService.eq(where, params, "t.\"vehicleId\"", q.vehicleId());
        FleetIncidentService.eq(where, params, "t.\"driverId\"", q.driverId());
        FleetIncidentService.eq(where, params, "t.carrier", q.carrier());
        FleetIncidentService.eq(where, params, "t.\"transporterPartnerId\"", q.transporterPartnerId());
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal tonnes = BigDecimal.ZERO;
        BigDecimal quantity = BigDecimal.ZERO;
        long trips = db.jdbc().sql("SELECT count(*) FROM \"FleetLoad\" t JOIN \"FleetDeliveryOrder\" o ON o.id ="
                + " t.\"deliveryOrderId\"" + where).params(params).query(Long.class).single();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT l."productId" AS key, pr.name AS label, count(DISTINCT t.id) AS trips,
                       COALESCE(sum(l."deliveredQty"), 0) AS quantity,
                       COALESCE(sum(l."deliveredQty" * l."tonnesPerUnit"), 0) AS tonnes
                FROM "FleetLoadLine" l JOIN "FleetLoad" t ON t.id = l."loadId"
                JOIN "FleetDeliveryOrder" o ON o.id = t."deliveryOrderId" JOIN "Product" pr ON pr.id = l."productId"
                """ + where + " GROUP BY l.\"productId\", pr.name ORDER BY tonnes DESC, pr.name").params(params))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", r.get("key"));
            row.put("label", r.get("label"));
            row.put("trips", ((Number) r.get("trips")).longValue());
            row.put("quantity", qty(SqlRows.dec(r, "quantity")));
            row.put("tonnes", qty(SqlRows.dec(r, "tonnes")));
            tonnes = tonnes.add(SqlRows.dec(r, "tonnes"));
            quantity = quantity.add(SqlRows.dec(r, "quantity"));
            rows.add(row);
        }
        return envelope(p, g, rows, Obj.of("key", "total", "label", "Total", "trips", trips, "quantity",
                qty(quantity), "tonnes", qty(tonnes)));
    }

    private static Map<String, Object> tonnesRow(String key, String label, List<TripFact> fs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        r.put("label", label);
        r.put("trips", fs.size());
        BigDecimal t = sum(fs, TripFact::tonnes);
        r.put("tonnes", qty(t));
        r.put("tonnesPerTrip", fs.isEmpty() ? null : t.divide(BigDecimal.valueOf(fs.size()), 3, RoundingMode.HALF_UP));
        return r;
    }

    /**
     * Transport cost per tonne per carrier (default), vehicle, transporter, customer, site or month: trip cost (own
     * fleet allocated + driver allowances, transporter freight), tonnes, km, cost per tonne / km, the transport
     * charges billed to customers and the recovery % (charges / cost).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> transportCost(DeliveryQuery q) {
        Period p = FleetCostingService.period(q.from(), q.to());
        String g = groupBy(q.groupBy(), "carrier", List.of("carrier", "vehicle", "transporter", "customer", "site",
                "month", "driver"));
        List<TripFact> facts = tripFacts(p, q.partnerId(), q.siteId(), q.vehicleId(), q.driverId(), q.carrier(),
                q.transporterPartnerId(), null);
        Map<String, String> labels = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<TripFact>> e : group(facts, f -> key(f, g), labels).entrySet()) {
            rows.add(costRow(e.getKey(), labels.get(e.getKey()), e.getValue()));
        }
        Map<String, Object> out = envelope(p, g, rows, costRow("total", "Total", facts));
        out.put("allocationBasis", costing.allocationBasis());
        return out;
    }

    private static Map<String, Object> costRow(String key, String label, List<TripFact> fs) {
        BigDecimal cost = FleetCostCalculator.money(sum(fs, TripFact::tripCost));
        BigDecimal t = sum(fs, TripFact::tonnes);
        BigDecimal km = sum(fs, TripFact::km);
        BigDecimal charge = FleetCostCalculator.money(sum(fs, TripFact::charge));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        r.put("label", label);
        r.put("trips", fs.size());
        r.put("tonnes", qty(t));
        r.put("km", km.setScale(1, RoundingMode.HALF_UP));
        r.put("transportCost", cost);
        r.put("costPerTonne", FleetCostCalculator.rate(cost, t));
        r.put("costPerKm", FleetCostCalculator.rate(cost, km));
        r.put("transportCharge", charge);
        r.put("recoveryPct", FleetCostCalculator.pct(charge, cost, 1));
        return r;
    }

    // ------------------------------------------------------------------ utilisation and fuel

    /**
     * Utilisation and availability per unit (default) or class: days in the period, active days (a trip dispatched or
     * a check-out that day), utilisation % (active / period days), availability % and downtime (maintenance
     * reliability), trips, km, engine hours and tonnes; plus downtime hours by reason.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> utilisation(UnitQuery q) {
        Period p = FleetCostingService.period(q.from(), q.to());
        String g = groupBy(q.groupBy(), "vehicle", List.of("vehicle", "category"));
        String org = FleetDb.org();
        String zone = BusinessZone.get().getId();
        Object start = SqlRows.ts(p.start());
        Object end = SqlRows.ts(p.end());
        Map<String, Long> active = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT x.vid AS k, count(DISTINCT x.d) AS n FROM (
                  SELECT "vehicleId" AS vid, ("dispatchedAt" AT TIME ZONE 'UTC' AT TIME ZONE ?)::date AS d
                  FROM "FleetLoad" WHERE "organizationId" = ? AND "vehicleId" IS NOT NULL
                    AND "dispatchedAt" >= ? AND "dispatchedAt" < ?
                  UNION ALL
                  SELECT "vehicleId", ("checkedOutAt" AT TIME ZONE 'UTC' AT TIME ZONE ?)::date FROM "FleetCheckout"
                  WHERE "organizationId" = ? AND "checkedOutAt" >= ? AND "checkedOutAt" < ?) x
                GROUP BY x.vid""").params(zone, org, start, end, zone, org, start, end))) {
            active.put((String) r.get("k"), ((Number) r.get("n")).longValue());
        }
        Map<String, Map<String, Object>> reliability = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rel = (List<Map<String, Object>>) downtime.reliability(new ReliabilityQuery(
                p.from(), p.to(), "vehicle", q.vehicleId(), q.categoryId(), q.branchId())).get("rows");
        for (Map<String, Object> r : rel) {
            @SuppressWarnings("unchecked")
            Map<String, Object> v = (Map<String, Object>) r.get("vehicle");
            reliability.put((String) v.get("id"), r);
        }
        long days = p.days();
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<String, Object> total = utilRow("total", "Total");
        for (VehicleCost vc : costing.vehicleCosts(p, q.vehicleId(), q.categoryId(), q.branchId(), null)) {
            Map<String, Object> v = vc.vehicle();
            String id = (String) v.get("id");
            String k = "category".equals(g) ? (String) v.get("categoryId") : id;
            String label = "category".equals(g) ? (String) v.get("categoryName")
                    : v.get("code") + (v.get("registrationPlate") == null ? "" : " · " + v.get("registrationPlate"));
            Map<String, Object> row = groups.computeIfAbsent(k, x -> utilRow(x, label));
            if (!"category".equals(g)) {
                row.put("vehicleId", id);
                row.put("categoryName", v.get("categoryName"));
                row.put("status", v.get("status"));
            }
            Map<String, Object> rr = reliability.get(id);
            for (Map<String, Object> target : List.of(row, total)) {
                accumulate(target, vc, active.getOrDefault(id, 0L), days, rr);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : groups.values()) {
            rows.add(finishUtil(r));
        }
        List<Map<String, Object>> reasons = SqlRows.list(db.jdbc().sql("""
                SELECT d."reasonCode" AS reason, count(*) AS events,
                       round(COALESCE(sum(EXTRACT(EPOCH FROM (LEAST(COALESCE(d."endedAt", ?), ?)
                           - GREATEST(d."startedAt", ?))) / 3600), 0)::numeric, 1) AS hours
                FROM "FleetDowntime" d JOIN "FleetVehicle" v ON v.id = d."vehicleId"
                WHERE d."organizationId" = ? AND d."startedAt" < ? AND (d."endedAt" IS NULL OR d."endedAt" > ?)
                  AND v."deletedAt" IS NULL""" + (FleetVehicleService.notBlank(q.vehicleId()) ? " AND v.id = ?" : "")
                + (FleetVehicleService.notBlank(q.categoryId()) ? " AND v.\"categoryId\" = ?" : "")
                + (FleetVehicleService.notBlank(q.branchId()) ? " AND v.\"branchId\" = ?" : "")
                + " GROUP BY d.\"reasonCode\" ORDER BY hours DESC").params(reasonParams(q, start, end, org)));
        Map<String, Object> out = envelope(p, g, rows, finishUtil(total));
        out.put("downtimeByReason", reasons);
        return out;
    }

    private static List<Object> reasonParams(UnitQuery q, Object start, Object end, String org) {
        Object now = SqlRows.ts(Instant.now());
        List<Object> params = new ArrayList<>(List.of(now, end, start, org, end, start));
        for (String v : new String[] {q.vehicleId(), q.categoryId(), q.branchId()}) {
            if (FleetVehicleService.notBlank(v)) {
                params.add(v);
            }
        }
        return params;
    }

    private static Map<String, Object> utilRow(String key, String label) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        r.put("label", label);
        r.put("units", 0);
        r.put("periodDays", 0L);
        r.put("activeDays", 0L);
        r.put("periodHours", BigDecimal.ZERO);
        r.put("downtimeHours", BigDecimal.ZERO);
        r.put("trips", 0);
        r.put("km", null);
        r.put("hours", null);
        r.put("tonnes", null);
        return r;
    }

    private static void accumulate(Map<String, Object> r, VehicleCost vc, long activeDays, long days,
            Map<String, Object> rel) {
        r.put("units", (Integer) r.get("units") + 1);
        r.put("periodDays", (Long) r.get("periodDays") + days);
        r.put("activeDays", (Long) r.get("activeDays") + activeDays);
        if (rel != null) {
            r.put("periodHours", ((BigDecimal) r.get("periodHours")).add((BigDecimal) rel.get("periodHours")));
            r.put("downtimeHours", ((BigDecimal) r.get("downtimeHours")).add((BigDecimal) rel.get("downtimeHours")));
        }
        r.put("trips", (Integer) r.get("trips") + vc.usage().trips());
        r.put("km", add((BigDecimal) r.get("km"), vc.usage().km()));
        r.put("hours", add((BigDecimal) r.get("hours"), vc.usage().hours()));
        r.put("tonnes", add((BigDecimal) r.get("tonnes"), vc.usage().tonnes()));
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.add(b);
    }

    private static Map<String, Object> finishUtil(Map<String, Object> r) {
        r.put("utilisationPct", FleetCostCalculator.pct(BigDecimal.valueOf((Long) r.get("activeDays")),
                BigDecimal.valueOf((Long) r.get("periodDays")), 1));
        BigDecimal ph = (BigDecimal) r.get("periodHours");
        BigDecimal dh = (BigDecimal) r.get("downtimeHours");
        r.put("availabilityPct", FleetCostCalculator.pct(ph.subtract(dh), ph, 1));
        r.put("tonnes", qty((BigDecimal) r.get("tonnes")));
        return r;
    }

    /**
     * Fuel per unit (default), class or month: logs, litres (station / tank), cost, km and hours of the period, L/100
     * km and L/h over the measured full-to-full segments, cost per km and open anomalies.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> fuel(UnitQuery q) {
        Period p = FleetCostingService.period(q.from(), q.to());
        String g = groupBy(q.groupBy(), "vehicle", List.of("vehicle", "category", "month"));
        String org = FleetDb.org();
        String zone = BusinessZone.get().getId();
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ? AND l.status = 'posted' AND"
                + " l.\"deletedAt\" IS NULL AND l.\"fuelledAt\" >= ? AND l.\"fuelledAt\" < ?");
        List<Object> params = new ArrayList<>(List.of(zone, org, SqlRows.ts(p.start()), SqlRows.ts(p.end())));
        FleetIncidentService.eq(where, params, "v.id", q.vehicleId());
        FleetIncidentService.eq(where, params, "v.\"categoryId\"", q.categoryId());
        FleetIncidentService.eq(where, params, "v.\"branchId\"", q.branchId());
        String keyCol = switch (g) {
            case "category" -> "v.\"categoryId\"";
            case "month" -> "to_char((l.\"fuelledAt\" AT TIME ZONE 'UTC' AT TIME ZONE ?), 'YYYY-MM')";
            default -> "v.id";
        };
        String labelCol = switch (g) {
            case "category" -> "max(c.name)";
            case "month" -> "min(to_char((l.\"fuelledAt\" AT TIME ZONE 'UTC' AT TIME ZONE ?), 'YYYY-MM'))";
            default -> "max(v.code || COALESCE(' · ' || v.\"registrationPlate\", ''))";
        };
        if (!"month".equals(g)) {
            params.removeFirst();
        } else {
            params.addFirst(zone);
        }
        List<Map<String, Object>> raw = SqlRows.list(db.jdbc().sql("SELECT " + keyCol + " AS key, " + labelCol
                + " AS label, count(*) AS logs, COALESCE(sum(l.litres), 0) AS litres,"
                + " COALESCE(sum(l.litres) FILTER (WHERE l.source = 'station'), 0) AS \"stationLitres\","
                + " COALESCE(sum(l.litres) FILTER (WHERE l.source = 'tank'), 0) AS \"tankLitres\","
                + " COALESCE(sum(CASE WHEN l.source = 'tank' THEN COALESCE(l.\"stockValue\", 0) ELSE"
                + " COALESCE(l.amount, 0) END), 0) AS cost,"
                + " COALESCE(sum(l.\"segmentLitres\") FILTER (WHERE l.\"lPer100Km\" IS NOT NULL), 0) AS \"kmLitres\","
                + " COALESCE(sum(l.\"distanceKm\") FILTER (WHERE l.\"lPer100Km\" IS NOT NULL), 0) AS \"segmentKm\","
                + " COALESCE(sum(l.\"segmentLitres\") FILTER (WHERE l.\"lPerHour\" IS NOT NULL), 0) AS \"hourLitres\","
                + " COALESCE(sum(l.\"hoursRun\") FILTER (WHERE l.\"lPerHour\" IS NOT NULL), 0) AS \"segmentHours\","
                + " COALESCE(sum((SELECT count(*) FROM \"FleetFuelAnomaly\" a WHERE a.\"fuelLogId\" = l.id AND"
                + " a.status = 'open')), 0) AS \"openAnomalies\""
                + " FROM \"FleetFuelLog\" l JOIN \"FleetVehicle\" v ON v.id = l.\"vehicleId\""
                + " JOIN \"FleetVehicleCategory\" c ON c.id = v.\"categoryId\"" + where
                + " GROUP BY 1 ORDER BY 2").params(params));
        Map<String, VehicleCost> usage = new HashMap<>();
        if ("vehicle".equals(g)) {
            for (VehicleCost vc : costing.vehicleCosts(p, q.vehicleId(), q.categoryId(), q.branchId(), null)) {
                usage.put((String) vc.vehicle().get("id"), vc);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] t = new BigDecimal[9];
        java.util.Arrays.fill(t, BigDecimal.ZERO);
        long logs = 0;
        for (Map<String, Object> r : raw) {
            VehicleCost vc = usage.get((String) r.get("key"));
            Map<String, Object> row = fuelRow((String) r.get("key"), (String) r.get("label"), r,
                    vc == null ? null : vc.usage().km(), vc == null ? null : vc.usage().hours());
            rows.add(row);
            logs += ((Number) r.get("logs")).longValue();
            String[] cols = {"litres", "stationLitres", "tankLitres", "cost", "kmLitres", "segmentKm", "hourLitres",
                    "segmentHours", "openAnomalies"};
            for (int i = 0; i < cols.length; i++) {
                t[i] = t[i].add(SqlRows.dec(r, cols[i]));
            }
        }
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("logs", logs);
        String[] cols = {"litres", "stationLitres", "tankLitres", "cost", "kmLitres", "segmentKm", "hourLitres",
                "segmentHours", "openAnomalies"};
        for (int i = 0; i < cols.length; i++) {
            tr.put(cols[i], t[i]);
        }
        return envelope(p, g, rows, fuelRow("total", "Total", tr, null, null));
    }

    private static Map<String, Object> fuelRow(String key, String label, Map<String, Object> r, BigDecimal km,
            BigDecimal hours) {
        BigDecimal cost = FleetCostCalculator.money(SqlRows.dec(r, "cost"));
        BigDecimal segKm = SqlRows.dec(r, "segmentKm");
        BigDecimal segH = SqlRows.dec(r, "segmentHours");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("logs", ((Number) r.get("logs")).longValue());
        row.put("litres", qty(SqlRows.dec(r, "litres")));
        row.put("stationLitres", qty(SqlRows.dec(r, "stationLitres")));
        row.put("tankLitres", qty(SqlRows.dec(r, "tankLitres")));
        row.put("cost", cost);
        row.put("km", km);
        row.put("hours", hours);
        row.put("lPer100Km", segKm.signum() > 0 ? SqlRows.dec(r, "kmLitres").multiply(new BigDecimal("100"))
                .divide(segKm, 2, RoundingMode.HALF_UP) : null);
        row.put("lPerHour", segH.signum() > 0 ? SqlRows.dec(r, "hourLitres").divide(segH, 2, RoundingMode.HALF_UP)
                : null);
        row.put("costPerKm", FleetCostCalculator.rate(cost, km));
        row.put("costPerLitre", FleetCostCalculator.rate(cost, SqlRows.dec(r, "litres")));
        row.put("openAnomalies", SqlRows.dec(r, "openAnomalies").longValue());
        return row;
    }

    // ------------------------------------------------------------------ renewals and services

    /**
     * Upcoming renewals and services: vehicle compliance documents, driver licences / medicals / permits and stored
     * weighbridge tares that expired or expire within {@code days} (default 30) of {@code asOf}, and services due or
     * overdue — one row each, soonest first. {@code kind} narrows to compliance, driver, tare or service.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> renewals(RenewalQuery q) {
        LocalDate asOf = q.asOf() == null ? LocalDate.now(BusinessZone.get()) : q.asOf();
        int days = q.days() == null ? 30 : q.days();
        if (days < 0 || days > 365) {
            throw ApiException.badRequest("days must be between 0 and 365");
        }
        String kind = q.kind() == null || q.kind().isBlank() ? "all" : q.kind();
        if (!List.of("all", "compliance", "driver", "tare", "service").contains(kind)) {
            throw ApiException.badRequest("kind must be one of: compliance, driver, tare, service");
        }
        String org = FleetDb.org();
        LocalDate until = asOf.plusDays(days);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (List.of("all", "compliance").contains(kind)) {
            for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                    SELECT d.id, d."docType", d."documentNumber", d."expiresOn", v.id AS "vehicleId", v.code,
                           v."registrationPlate"
                    FROM "FleetComplianceDocument" d JOIN "FleetVehicle" v ON v.id = d."vehicleId"
                    WHERE d."organizationId" = ? AND d.status = 'active' AND d."deletedAt" IS NULL
                      AND v."deletedAt" IS NULL AND v.status <> 'disposed' AND d."expiresOn" <= ?""")
                    .params(org, java.sql.Date.valueOf(until)))) {
                rows.add(renewal("compliance", (String) r.get("id"), (String) r.get("docType"),
                        label(r.get("code"), r.get("registrationPlate")), (String) r.get("vehicleId"), null,
                        (String) r.get("documentNumber"), (LocalDate) r.get("expiresOn"), asOf,
                        "/fleet/vehicles/" + r.get("vehicleId")));
            }
        }
        if (List.of("all", "driver").contains(kind)) {
            for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                    SELECT d.id, d.code, d."licenceNumber", d."licenceExpiresOn", d."medicalExpiresOn",
                           d."permitNumber", d."permitExpiresOn", e."firstName", e."lastName"
                    FROM "FleetDriver" d LEFT JOIN "HrEmployee" e ON e.id = d."employeeId"
                      AND e."organizationId" = d."organizationId"
                    WHERE d."organizationId" = ? AND d."deletedAt" IS NULL AND d.status <> 'inactive'
                      AND (d."licenceExpiresOn" <= ? OR d."medicalExpiresOn" <= ? OR d."permitExpiresOn" <= ?)""")
                    .params(org, java.sql.Date.valueOf(until), java.sql.Date.valueOf(until),
                            java.sql.Date.valueOf(until)))) {
                String who = r.get("code") + " · " + (nz((String) r.get("firstName")) + " "
                        + nz((String) r.get("lastName"))).trim();
                for (String[] doc : new String[][] {{"licence", "licenceExpiresOn", "licenceNumber"},
                        {"medical", "medicalExpiresOn", null}, {"permit", "permitExpiresOn", "permitNumber"}}) {
                    LocalDate exp = (LocalDate) r.get(doc[1]);
                    if (exp != null && !exp.isAfter(until)) {
                        rows.add(renewal("driver", r.get("id") + ":" + doc[0], doc[0], who, null,
                                (String) r.get("id"), doc[2] == null ? null : (String) r.get(doc[2]), exp, asOf,
                                "/fleet/drivers/" + r.get("id")));
                    }
                }
            }
        }
        if (List.of("all", "tare").contains(kind)) {
            for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                    SELECT t.id, t."registrationPlate", t."tareKg", t."expiresAt", t."vehicleId", v.code
                    FROM "FleetVehicleTare" t LEFT JOIN "FleetVehicle" v ON v.id = t."vehicleId"
                    WHERE t."organizationId" = ? AND t.status = 'active' AND t."expiresAt" < ?""")
                    .params(org, SqlRows.ts(until.plusDays(1).atStartOfDay(BusinessZone.get()).toInstant())))) {
                LocalDate exp = LocalDate.ofInstant((Instant) r.get("expiresAt"), BusinessZone.get());
                rows.add(renewal("tare", (String) r.get("id"), "stored_tare", label(r.get("code"),
                        r.get("registrationPlate")), (String) r.get("vehicleId"), null,
                        SqlRows.dec(r, "tareKg").stripTrailingZeros().toPlainString() + " kg", exp, asOf,
                        "/fleet/weighbridge/tares"));
            }
        }
        if (List.of("all", "service").contains(kind)) {
            for (Map<String, Object> s : schedules.dueForOrganization(org, asOf)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> v = (Map<String, Object>) s.get("vehicle");
                LocalDate dueOn = null;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> dims = (List<Map<String, Object>>) s.get("due");
                for (Map<String, Object> d : dims) {
                    if (d.get("nextDueOn") instanceof LocalDate ld) {
                        dueOn = ld;
                    }
                }
                Map<String, Object> row = renewal("service", (String) s.get("id"), "service",
                        label(v.get("code"), v.get("registrationPlate")), (String) v.get("id"), null,
                        (String) s.get("name"), dueOn, asOf, "/fleet/maintenance");
                row.put("state", s.get("dueState"));
                row.put("trigger", s.get("dueTrigger"));
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (LocalDate) r.get("dueOn"),
                Comparator.nullsFirst(Comparator.naturalOrder())));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("expired", "overdue", "expiring", "due")) {
            counts.put(s, rows.stream().filter(r -> s.equals(r.get("state"))).count());
        }
        return Obj.of("asOf", asOf, "days", days, "kind", kind, "rows", rows, "counts", counts);
    }

    private static String label(Object code, Object plate) {
        return code == null ? String.valueOf(plate) : code + (plate == null ? "" : " · " + plate);
    }

    private static Map<String, Object> renewal(String kind, String key, String item, String subject, String vehicleId,
            String driverId, String reference, LocalDate dueOn, LocalDate asOf, String link) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        r.put("kind", kind);
        r.put("item", item);
        r.put("label", subject);
        r.put("vehicleId", vehicleId);
        r.put("driverId", driverId);
        r.put("reference", reference);
        r.put("dueOn", dueOn);
        Long left = dueOn == null ? null : ChronoUnit.DAYS.between(asOf, dueOn);
        r.put("daysLeft", left);
        r.put("state", left == null ? "due" : left < 0 ? "expired" : "expiring");
        r.put("link", link);
        return r;
    }

    // ------------------------------------------------------------------ dashboard

    /**
     * Fleet dashboard KPIs, month to date: deliveries, on-time %, tonnes, transport cost per tonne, fleet
     * availability and utilisation, fuel cost and litres, total cost of ownership, renewals and services needing
     * attention in 30 days, freight bills to match and transporter trips not billed yet.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> kpis(LocalDate from, LocalDate to) {
        Period p = FleetCostingService.period(from, to);
        List<TripFact> facts = tripFacts(p, null, null, null, null, null, null, null);
        Map<String, Object> d = deliveryRow("total", "Total", facts);
        Map<String, Object> c = costRow("total", "Total", facts);
        Map<String, Object> pr = profitRow("total", "Total", facts);
        Map<String, Object> u = utilisation(new UnitQuery(p.from(), p.to(), "vehicle", null, null, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> ut = (Map<String, Object>) u.get("totals");
        Map<String, Object> f = fuel(new UnitQuery(p.from(), p.to(), "vehicle", null, null, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> ft = (Map<String, Object>) f.get("totals");
        @SuppressWarnings("unchecked")
        Map<String, Object> tco = (Map<String, Object>) costing.report(new CostQuery(p.from(), p.to(), "vehicle", null,
                null, null)).get("totals");
        @SuppressWarnings("unchecked")
        Map<String, Object> rc = (Map<String, Object>) renewals(new RenewalQuery(null, 30, "all")).get("counts");
        String org = FleetDb.org();
        long toMatch = db.jdbc().sql("SELECT count(*) FROM \"FleetFreightBill\" WHERE \"organizationId\" = ? AND status"
                + " IN ('draft', 'disputed')").param(org).query(Long.class).single();
        long unbilled = db.jdbc().sql("SELECT count(*) FROM \"FleetLoad\" t WHERE t.\"organizationId\" = ? AND"
                + " t.carrier = 'transporter' AND t.status = 'closed' AND COALESCE(t.\"freightAmount\", 0) > 0 AND NOT"
                + " EXISTS (SELECT 1 FROM \"FleetFreightBillLine\" l WHERE l.\"tripId\" = t.id AND l.status ="
                + " 'active')").param(org).query(Long.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", p.from());
        out.put("to", p.to());
        out.put("deliveries", d.get("trips"));
        out.put("onTimePct", d.get("onTimePct"));
        out.put("tonnes", d.get("tonnes"));
        out.put("transportCost", c.get("transportCost"));
        out.put("transportCostPerTonne", c.get("costPerTonne"));
        out.put("deliveryMargin", pr.get("margin"));
        out.put("deliveryMarginPct", pr.get("marginPct"));
        out.put("availabilityPct", ut.get("availabilityPct"));
        out.put("utilisationPct", ut.get("utilisationPct"));
        out.put("fuelCost", ft.get("cost"));
        out.put("fuelLitres", ft.get("litres"));
        out.put("totalCostOfOwnership", tco.get("total"));
        out.put("costPerKm", tco.get("perKm"));
        out.put("renewalsExpired", rc.get("expired"));
        out.put("renewalsExpiring", rc.get("expiring"));
        out.put("servicesOverdue", rc.get("overdue"));
        out.put("servicesDue", rc.get("due"));
        out.put("freightBillsToMatch", toMatch);
        out.put("unbilledTransporterTrips", unbilled);
        return out;
    }
}
