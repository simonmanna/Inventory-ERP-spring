package com.inventoryerp.analytics.application;

import static com.inventoryerp.analytics.application.Dashboard.col;

import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.Dashboard.Chart;
import com.inventoryerp.analytics.application.Dashboard.Series;
import com.inventoryerp.analytics.application.Dashboard.Table;
import com.inventoryerp.analytics.application.Dashboard.Tile;
import com.inventoryerp.fleet.application.FleetReportCommands.DeliveryQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.UnitQuery;
import com.inventoryerp.fleet.application.FleetReportService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fleet and delivery: deliveries completed, fuel cost and transport cost per tonne ({@link FleetReportService}). */
@Service
@Transactional(readOnly = true)
public class FleetKpiService {

    private static final List<String> FILTERS = List.of("date", "branch", "deliverySite", "product", "partner");
    private static final List<String> GROUPS = List.of("month", "customer", "carrier");
    private static final int TOP_ROWS = 20;

    private final AnalyticsViews views;
    private final AnalyticsDocuments documents;
    private final FleetReportService fleet;

    public FleetKpiService(AnalyticsViews views, AnalyticsDocuments documents, FleetReportService fleet) {
        this.views = views;
        this.documents = documents;
        this.fleet = fleet;
    }

    private record Fuel(BigDecimal cost, BigDecimal litres, List<Map<String, Object>> byMonth) {}

    @SuppressWarnings("unchecked")
    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        DeliveryQuery query = new DeliveryQuery(v.from(), v.to(), null, f.partnerId(), f.deliverySiteId(), null, null,
                null, null);
        Map<String, Object> trips = fleet.deliveryKpis(query, v.branchIds(), f.productId(), GROUPS);
        Map<String, Object> totals = (Map<String, Object>) trips.get("totals");
        Map<String, List<Map<String, Object>>> groups =
                (Map<String, List<Map<String, Object>>>) trips.get("groups");
        Fuel fuel = fuel(v);
        List<Tile> tiles = List.of(
                new Tile("deliveriesCompleted", totals.get("trips"), "count", "deliveries"),
                new Tile("onTimeDeliveries", totals.get("onTimePct"), "percent", "deliveries"),
                new Tile("tonnesDelivered", totals.get("tonnes"), "tonnes", "deliveries"),
                new Tile("fuelCost", fuel.cost(), "money", "fuel-logs"),
                new Tile("fuelLitres", fuel.litres(), "qty", "fuel-logs"),
                new Tile("transportCost", totals.get("transportCost"), "money", null),
                new Tile("transportCostPerTonne", totals.get("costPerTonne"), "rate", null));
        List<Chart> charts = List.of(
                new Chart("deliveriesTrend", "bar", "label", "count", List.of(Series.of("trips")),
                        groups.get("month")),
                new Chart("fuelCostTrend", "bar", "label", "money", List.of(Series.of("cost")), fuel.byMonth()));
        List<Map<String, Object>> customers = groups.get("customer");
        List<Table> tables = List.of(
                new Table("deliveriesByCustomer", deliveryColumns(), customers.subList(0,
                        Math.min(TOP_ROWS, customers.size())), "deliveries", "partnerId", "key"),
                Dashboard.table("deliveriesByCarrier", deliveryColumns(), groups.get("carrier")));
        return new Dashboard("fleet", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables,
                f.partnerId() == null && f.deliverySiteId() == null && f.productId() == null ? List.of()
                        : List.of("fuelNotByCustomer"));
    }

    private static List<Dashboard.Column> deliveryColumns() {
        return List.of(col("label", "text"), col("trips", "count"), col("onTimePct", "percent"),
                col("tonnes", "tonnes"), col("km", "qty"), col("transportCost", "money"), col("costPerTonne", "rate"));
    }

    /** Fuel per month; one call per branch of a limited view, because the report takes a single branch. */
    @SuppressWarnings("unchecked")
    private Fuel fuel(AnalyticsView v) {
        List<String> branches = v.limited() ? Arrays.asList(v.branchIds()) : Collections.singletonList(null);
        TreeMap<String, BigDecimal> months = new TreeMap<>();
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal litres = BigDecimal.ZERO;
        for (String branch : branches) {
            Map<String, Object> report = fleet.fuel(new UnitQuery(v.from(), v.to(), "month", null, null, branch));
            Map<String, Object> totals = (Map<String, Object>) report.get("totals");
            cost = cost.add(SqlRows.dec(totals, "cost"));
            litres = litres.add(SqlRows.dec(totals, "litres"));
            for (Map<String, Object> r : (List<Map<String, Object>>) report.get("rows")) {
                months.merge(SqlRows.str(r, "key"), SqlRows.dec(r, "cost"), BigDecimal::add);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        months.forEach((m, c) -> rows.add(Obj.of("label", m, "cost", c)));
        return new Fuel(cost, litres, rows);
    }

    /** Drill-downs {@code deliveries} (finished trips) and {@code fuel-logs} (posted fuel logs). */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        AnalyticsView v = views.view(f);
        return switch (kpi == null ? "" : kpi) {
            case "deliveries" -> deliveries(v, paging);
            case "fuel-logs" -> fuelLogs(v, paging);
            default -> throw AnalyticsDocuments.unknown(kpi);
        };
    }

    private PageResult<Map<String, Object>> deliveries(AnalyticsView v, Paging paging) {
        AnalyticsFilter f = v.filter();
        StringBuilder from = new StringBuilder("FROM \"FleetLoad\" t JOIN \"FleetDeliveryOrder\" o ON o.id ="
                + " t.\"deliveryOrderId\" JOIN \"Partner\" pa ON pa.id = o.\"partnerId\" WHERE t.\"organizationId\" = ?"
                + " AND t.status IN ('delivered', 'closed') AND t.\"deliveredAt\" >= ? AND t.\"deliveredAt\" < ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), v.start(), v.end()));
        AnalyticsView.equal(from, params, "o.\"partnerId\"", f.partnerId());
        AnalyticsView.equal(from, params, "o.\"siteId\"", f.deliverySiteId());
        v.branch(from, params, "COALESCE(o.\"branchId\", (SELECT ol.\"branchId\" FROM \"InventoryLocation\" ol WHERE"
                + " ol.id = o.\"locationId\"))");
        if (f.productId() != null) {
            from.append(" AND EXISTS (SELECT 1 FROM \"FleetLoadLine\" pl WHERE pl.\"loadId\" = t.id AND"
                    + " pl.\"productId\" = ?)");
            params.add(f.productId());
        }
        return documents.page("'fleet_trip' AS type, t.id, t.code AS number, t.\"deliveredAt\" AS date, pa.name AS"
                + " party, t.status, COALESCE(t.\"deliveredTonnes\", t.\"loadedTonnes\", t.\"plannedTonnes\") AS"
                + " quantity, t.\"freightAmount\" AS amount", from, "t.\"deliveredAt\" DESC", params, paging);
    }

    private PageResult<Map<String, Object>> fuelLogs(AnalyticsView v, Paging paging) {
        StringBuilder from = new StringBuilder("FROM \"FleetFuelLog\" l JOIN \"FleetVehicle\" u ON u.id ="
                + " l.\"vehicleId\" WHERE l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"deletedAt\" IS NULL"
                + " AND l.\"fuelledAt\" >= ? AND l.\"fuelledAt\" < ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), v.start(), v.end()));
        v.branch(from, params, "u.\"branchId\"");
        return documents.page("'fleet_fuel' AS type, l.id, l.code AS number, l.\"fuelledAt\" AS date, u.code AS party,"
                + " l.status, l.litres AS quantity, CASE WHEN l.source = 'tank' THEN l.\"stockValue\" ELSE l.amount END"
                + " AS amount", from, "l.\"fuelledAt\" DESC", params, paging);
    }
}
