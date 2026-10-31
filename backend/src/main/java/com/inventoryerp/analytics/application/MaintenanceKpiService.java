package com.inventoryerp.analytics.application;

import static com.inventoryerp.analytics.application.Dashboard.col;

import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.Dashboard.Chart;
import com.inventoryerp.analytics.application.Dashboard.Series;
import com.inventoryerp.analytics.application.Dashboard.Table;
import com.inventoryerp.analytics.application.Dashboard.Tile;
import com.inventoryerp.fleet.application.FleetDowntimeService;
import com.inventoryerp.fleet.application.MaintenanceCommands.ReliabilityQuery;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.CrushingReportService;
import com.inventoryerp.manufacturing.application.CrushingReportService.RunScope;
import com.inventoryerp.manufacturing.application.ShopFloorReportService;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance: equipment downtime, expenditure and breakdown frequency. Reliability comes from
 * {@link FleetDowntimeService} (per branch, then summed from hours), plant hours from {@link CrushingReportService},
 * OEE from {@link ShopFloorReportService}, and spend from the work orders.
 */
@Service
@Transactional(readOnly = true)
public class MaintenanceKpiService {

    private static final List<String> FILTERS = List.of("date", "branch");
    /** {@link ShopFloorReportService#oeeReport} refuses a longer range. */
    private static final long OEE_MAX_DAYS = 92;
    private static final int TOP_ROWS = 20;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String WORK_ORDERS = " FROM \"FleetWorkOrder\" w JOIN \"FleetVehicle\" u ON u.id ="
            + " w.\"vehicleId\" WHERE w.\"organizationId\" = ? AND w.status IN ('completed', 'closed') AND"
            + " w.\"completedAt\" >= ? AND w.\"completedAt\" < ?";

    private final AnalyticsViews views;
    private final AnalyticsDocuments documents;
    private final FleetDowntimeService downtime;
    private final CrushingReportService crushing;
    private final ShopFloorReportService shopFloor;
    private final JdbcClient jdbc;

    public MaintenanceKpiService(AnalyticsViews views, AnalyticsDocuments documents, FleetDowntimeService downtime,
            CrushingReportService crushing, ShopFloorReportService shopFloor, JdbcClient jdbc) {
        this.views = views;
        this.documents = documents;
        this.downtime = downtime;
        this.crushing = crushing;
        this.shopFloor = shopFloor;
        this.jdbc = jdbc;
    }

    private static final class Reliability {
        BigDecimal period = BigDecimal.ZERO;
        BigDecimal down = BigDecimal.ZERO;
        BigDecimal unplanned = BigDecimal.ZERO;
        BigDecimal uptime = BigDecimal.ZERO;
        long failures;
        long units;
        final List<Map<String, Object>> rows = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        Reliability r = reliability(v);
        Map<String, Object> plantHours = crushing.plantHours(v.fromText(), v.toText(),
                new RunScope(views.plantIds(v), null));
        List<Map<String, Object>> plants = (List<Map<String, Object>>) plantHours.get("plants");
        List<Map<String, Object>> spend = spendByMonth(v);
        BigDecimal failures = BigDecimal.valueOf(r.failures);
        Object oee = null;
        List<Map<String, Object>> centres = List.of();
        List<String> notes = new ArrayList<>();
        if (!v.limited() && ChronoUnit.DAYS.between(v.from(), v.to()) <= OEE_MAX_DAYS) {
            Map<String, Object> report = shopFloor.oeeReport(v.fromText(), v.toText(), null);
            oee = percent(((Map<String, Object>) report.get("total")).get("oee"));
            centres = centreRows((List<Map<String, Object>>) report.get("workCenters"));
        } else {
            notes.add("oeeUnavailable");
        }
        List<Tile> tiles = new ArrayList<>(List.of(
                new Tile("equipmentDowntime", r.down, "hours", "downtime"),
                new Tile("plantDowntime", KpiMath.sum(plants, "downtimeHours"), "hours", null),
                new Tile("fleetAvailability", KpiMath.pct(r.uptime, r.period), "percent", null),
                new Tile("maintenanceExpenditure", KpiMath.sum(spend, "totalCost"), "money", "work-orders"),
                new Tile("breakdownFrequency", r.failures, "count", "downtime"),
                new Tile("breakdownsPerUnit", KpiMath.rate(failures, BigDecimal.valueOf(r.units)), "qty", null),
                new Tile("mtbf", KpiMath.rate(r.uptime, failures), "hours", null),
                new Tile("mttr", KpiMath.rate(r.unplanned, failures), "hours", null)));
        tiles.add(new Tile("oee", oee, "percent", null));
        List<Chart> charts = List.of(new Chart("maintenanceSpendTrend", "bar", "period", "money",
                List.of(Series.of("partsCost"), Series.of("labourCost"), Series.of("externalCost")), spend));
        r.rows.sort(Comparator.comparing((Map<String, Object> m) -> SqlRows.dec(m, "downtimeHours")).reversed());
        List<Table> tables = List.of(
                Dashboard.table("downtimeByUnit", List.of(col("unit", "text"), col("category", "text"),
                        col("downtimeHours", "hours"), col("failures", "count"), col("availabilityPct", "percent"),
                        col("mtbfHours", "hours"), col("mttrHours", "hours")),
                        r.rows.subList(0, Math.min(TOP_ROWS, r.rows.size()))),
                Dashboard.table("downtimeByPlant", List.of(col("plantName", "text"), col("operatingHours", "hours"),
                        col("downtimeHours", "hours"), col("availabilityPct", "percent"),
                        col("throughputTph", "qty")), plants),
                Dashboard.table("oeeByWorkCenter", List.of(col("workCenterName", "text"), col("shifts", "count"),
                        col("downtimeMinutes", "count"), col("availability", "percent"), col("performance", "percent"),
                        col("quality", "percent"), col("oee", "percent")), centres));
        return new Dashboard("maintenance", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables,
                notes);
    }

    private static BigDecimal percent(Object fraction) {
        return fraction == null ? null : new BigDecimal(fraction.toString()).multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static List<Map<String, Object>> centreRows(List<Map<String, Object>> centres) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : centres) {
            out.add(Obj.of("workCenterName", c.get("workCenterName"), "shifts", c.get("shifts"), "downtimeMinutes",
                    c.get("downtimeMinutes"), "availability", percent(c.get("availability")), "performance",
                    percent(c.get("performance")), "quality", percent(c.get("quality")), "oee", percent(c.get("oee"))));
        }
        return out;
    }

    /** One report per branch of a limited view; totals are summed from hours and recomputed, never averaged. */
    @SuppressWarnings("unchecked")
    private Reliability reliability(AnalyticsView v) {
        List<String> branches = v.limited() ? Arrays.asList(v.branchIds()) : Collections.singletonList(null);
        Reliability out = new Reliability();
        for (String branch : branches) {
            Map<String, Object> report = downtime.reliability(new ReliabilityQuery(v.from(), v.to(), "vehicle", null,
                    null, branch));
            Map<String, Object> total = (Map<String, Object>) report.get("total");
            out.period = out.period.add(SqlRows.dec(total, "periodHours"));
            out.down = out.down.add(SqlRows.dec(total, "downtimeHours"));
            out.unplanned = out.unplanned.add(SqlRows.dec(total, "unplannedHours"));
            out.uptime = out.uptime.add(SqlRows.dec(total, "uptimeHours"));
            out.failures += ((Number) total.get("failures")).longValue();
            out.units += ((Number) total.get("units")).longValue();
            for (Map<String, Object> row : (List<Map<String, Object>>) report.get("rows")) {
                Map<String, Object> vehicle = (Map<String, Object>) row.get("vehicle");
                Map<String, Object> m = Obj.merge(row, Obj.of("unit", vehicle.get("code"), "category",
                        vehicle.get("categoryName")));
                m.remove("vehicle");
                out.rows.add(m);
            }
        }
        return out;
    }

    private List<Map<String, Object>> spendByMonth(AnalyticsView v) {
        StringBuilder where = new StringBuilder(WORK_ORDERS);
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), v.start(), v.end()));
        v.branch(where, params, "COALESCE(w.\"branchId\", u.\"branchId\")");
        return SqlRows.list(jdbc.sql("SELECT to_char(w.\"completedAt\", 'YYYY-MM') AS period, count(*) AS orders,"
                + " SUM(w.\"partsCost\") AS \"partsCost\", SUM(w.\"labourCost\") AS \"labourCost\","
                + " SUM(w.\"externalCost\") AS \"externalCost\", SUM(w.\"totalCost\") AS \"totalCost\"" + where
                + " GROUP BY 1 ORDER BY 1").params(params));
    }

    /** Drill-downs {@code work-orders} (completed in the period) and {@code downtime} (intervals in the period). */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        AnalyticsView v = views.view(f);
        return switch (kpi == null ? "" : kpi) {
            case "work-orders" -> workOrders(v, paging);
            case "downtime" -> downtimes(v, paging);
            default -> throw AnalyticsDocuments.unknown(kpi);
        };
    }

    private PageResult<Map<String, Object>> workOrders(AnalyticsView v, Paging paging) {
        StringBuilder from = new StringBuilder(WORK_ORDERS);
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), v.start(), v.end()));
        v.branch(from, params, "COALESCE(w.\"branchId\", u.\"branchId\")");
        return documents.page("'fleet_work_order' AS type, w.id, w.code AS number, w.\"completedAt\" AS date, u.code AS"
                + " party, w.status, NULL::numeric AS quantity, w.\"totalCost\" AS amount", from,
                "w.\"completedAt\" DESC", params, paging);
    }

    private PageResult<Map<String, Object>> downtimes(AnalyticsView v, Paging paging) {
        StringBuilder from = new StringBuilder("FROM \"FleetDowntime\" d JOIN \"FleetVehicle\" u ON u.id ="
                + " d.\"vehicleId\" WHERE d.\"organizationId\" = ? AND d.\"startedAt\" < ? AND (d.\"endedAt\" IS"
                + " NULL OR d.\"endedAt\" > ?)");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), v.end(), v.start()));
        v.branch(from, params, "u.\"branchId\"");
        return documents.page("'fleet_downtime' AS type, d.id, d.\"reasonCode\" AS number, d.\"startedAt\" AS date,"
                + " u.code AS party, d.kind AS status, NULL::numeric AS quantity, NULL::numeric AS amount", from,
                "d.\"startedAt\" DESC", params, paging);
    }
}
