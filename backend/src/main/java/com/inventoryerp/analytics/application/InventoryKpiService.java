package com.inventoryerp.analytics.application;

import static com.inventoryerp.analytics.application.Dashboard.col;

import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.Dashboard.Chart;
import com.inventoryerp.analytics.application.Dashboard.Series;
import com.inventoryerp.analytics.application.Dashboard.Table;
import com.inventoryerp.analytics.application.Dashboard.Tile;
import com.inventoryerp.inventory.application.InventoryQueryService;
import com.inventoryerp.inventory.application.InventoryReportsService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory: stock availability, value, aging and discrepancies. Value and availability are read here because the
 * valuation report takes one location at a time; aging and ledger drift come from {@link InventoryReportsService} and
 * {@link InventoryQueryService}, called once per location for a narrowed view (capped at {@link #MAX_LOCATIONS}).
 */
@Service
@Transactional(readOnly = true)
public class InventoryKpiService {

    private static final List<String> FILTERS = List.of("date", "branch", "site", "product");
    /** Stock health and reconciliation run once per location; beyond this the screen says the figure is partial. */
    private static final int MAX_LOCATIONS = 25;
    /** {@code quantity × average cost, else cost price}, as the valuation report values stock. */
    private static final String VALUE = "si.quantity * COALESCE(NULLIF(si.\"runningAverageCost\", 0),"
            + " COALESCE(p.\"costPrice\", 0))";
    private static final List<String> AGE_BUCKETS = List.of("d0_30", "d31_60", "d61_90", "d91_180", "d180_plus");

    private final AnalyticsViews views;
    private final AnalyticsDocuments documents;
    private final InventoryReportsService reports;
    private final InventoryQueryService queries;
    private final JdbcClient jdbc;

    public InventoryKpiService(AnalyticsViews views, AnalyticsDocuments documents, InventoryReportsService reports,
            InventoryQueryService queries, JdbcClient jdbc) {
        this.views = views;
        this.documents = documents;
        this.reports = reports;
        this.queries = queries;
        this.jdbc = jdbc;
    }

    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        String[] locations = views.stockLocationIds(v);
        Map<String, Object> availability = availability(locations, f.productId());
        long tracked = ((Number) availability.get("tracked")).longValue();
        long inStock = ((Number) availability.get("inStock")).longValue();
        long low = ((Number) availability.get("low")).longValue();
        List<Map<String, Object>> byLocation = byLocation(locations, f.productId());
        BigDecimal value = KpiMath.sum(byLocation, "value");
        Health health = health(locations, f.productId());
        Map<String, Object> counts = countVariances(v, locations);
        Map<String, Object> surveys = surveyVariances(v, locations);
        long countLines = ((Number) counts.get("lines")).longValue();
        long surveyCount = ((Number) surveys.get("surveys")).longValue();
        List<Tile> tiles = List.of(
                new Tile("stockValue", value, "money", null),
                new Tile("stockAvailability", KpiMath.pct(BigDecimal.valueOf(inStock), BigDecimal.valueOf(tracked)),
                        "percent", null),
                new Tile("belowMinimum", low, "count", "below-minimum"),
                new Tile("agedOver90", health.agedOver90(), "money", null),
                new Tile("slowStock", health.slowValue(), "money", null),
                new Tile("deadStock", health.deadValue(), "money", null),
                new Tile("stockDiscrepancies", health.drifted() + countLines + surveyCount, "count",
                        "count-variances"),
                new Tile("discrepancyValue", SqlRows.dec(counts, "value").add(SqlRows.dec(surveys, "value")), "money",
                        "stockpile-surveys"));
        List<Chart> charts = List.of(new Chart("stockAgingValue", "bar", "bucket", "money",
                List.of(Series.of("value")), health.buckets()));
        List<Table> tables = List.of(
                Dashboard.table("stockByLocation", List.of(col("locationName", "text"), col("branchName", "text"),
                        col("products", "count"), col("value", "money")), byLocation),
                Dashboard.table("stockDiscrepancySources", List.of(col("source", "text"), col("entries", "count"),
                        col("value", "money")), List.of(
                                Obj.of("source", "ledgerDrift", "entries", health.drifted(), "value", null),
                                Obj.of("source", "countVariance", "entries", countLines, "value", counts.get("value")),
                                Obj.of("source", "surveyVariance", "entries", surveyCount, "value",
                                        surveys.get("value")))));
        List<String> notes = new ArrayList<>(List.of("stockAsOfNow"));
        if (health.partial()) {
            notes.add("stockAgingPartial");
        }
        return new Dashboard("inventory", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables, notes);
    }

    private record Health(BigDecimal agedOver90, BigDecimal slowValue, BigDecimal deadValue, long drifted,
            List<Map<String, Object>> buckets, boolean partial) {}

    /** Aging value per bucket: a row's value spread over its bucket quantities. */
    @SuppressWarnings("unchecked")
    private Health health(String[] locations, String productId) {
        List<String> targets = locations == null ? Collections.singletonList(null)
                : Arrays.asList(locations).subList(0, Math.min(locations.length, MAX_LOCATIONS));
        Map<String, BigDecimal> buckets = new LinkedHashMap<>();
        AGE_BUCKETS.forEach(b -> buckets.put(b, BigDecimal.ZERO));
        BigDecimal slow = BigDecimal.ZERO;
        BigDecimal dead = BigDecimal.ZERO;
        long drifted = 0;
        boolean partial = locations != null && locations.length > MAX_LOCATIONS;
        for (String location : targets) {
            Map<String, String> q = new HashMap<>();
            if (location != null) {
                q.put("locationId", location);
            }
            if (productId != null) {
                q.put("productId", productId);
            }
            Map<String, Object> stock = reports.stockHealth(q);
            Map<String, Object> summary = (Map<String, Object>) stock.get("summary");
            slow = slow.add(SqlRows.dec(summary, "slowValue"));
            dead = dead.add(SqlRows.dec(summary, "deadValue"));
            partial = partial || Boolean.TRUE.equals(summary.get("truncated"));
            for (Map<String, Object> row : (List<Map<String, Object>>) stock.get("rows")) {
                spread(row, buckets);
            }
            Map<String, Object> drift = queries.getStockReconciliation(location, null, false);
            drifted += ((Number) ((Map<String, Object>) drift.get("summary")).get("driftedCells")).longValue();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        buckets.forEach((b, value) -> rows.add(Obj.of("bucket", b, "value", value.setScale(2, RoundingMode.HALF_UP))));
        BigDecimal aged = buckets.get("d91_180").add(buckets.get("d180_plus")).setScale(2, RoundingMode.HALF_UP);
        return new Health(aged, slow, dead, drifted, rows, partial);
    }

    @SuppressWarnings("unchecked")
    private static void spread(Map<String, Object> row, Map<String, BigDecimal> buckets) {
        BigDecimal onHand = SqlRows.dec(row, "onHand");
        if (onHand.signum() <= 0) {
            return;
        }
        BigDecimal perUnit = SqlRows.dec(row, "value").divide(onHand, 10, RoundingMode.HALF_UP);
        Map<String, Object> aging = (Map<String, Object>) row.get("aging");
        for (String b : AGE_BUCKETS) {
            buckets.merge(b, SqlRows.dec(aging, b).multiply(perUnit), BigDecimal::add);
        }
    }

    private Map<String, Object> availability(String[] locations, String productId) {
        StringBuilder stock = new StringBuilder(" WHERE si.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        locationClause(stock, params, "si.\"locationId\"", locations);
        params.add(TenantContext.organizationId());
        StringBuilder products = new StringBuilder(" WHERE p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL AND"
                + " p.\"isActive\" AND p.\"trackInventory\"");
        AnalyticsView.equal(products, params, "p.id", productId);
        return SqlRows.one(jdbc.sql("""
                SELECT count(*) AS tracked,
                       count(*) FILTER (WHERE COALESCE(s.qty, 0) > 0) AS "inStock",
                       count(*) FILTER (WHERE COALESCE(s.qty, 0) > 0 AND p."minQuantity" > 0
                           AND s.qty <= p."minQuantity") AS low
                FROM "Product" p LEFT JOIN (SELECT si."productId", SUM(si.quantity) AS qty FROM "StockItem" si
                """ + stock + " GROUP BY si.\"productId\") s ON s.\"productId\" = p.id" + products).params(params));
    }

    private List<Map<String, Object>> byLocation(String[] locations, String productId) {
        StringBuilder where = new StringBuilder(" WHERE si.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        locationClause(where, params, "si.\"locationId\"", locations);
        AnalyticsView.equal(where, params, "si.\"productId\"", productId);
        return SqlRows.list(jdbc.sql("SELECT l.id AS \"locationId\", l.name AS \"locationName\", b.name AS"
                + " \"branchName\", count(DISTINCT si.\"productId\") FILTER (WHERE si.quantity <> 0) AS products,"
                + " COALESCE(SUM(" + VALUE + "), 0) AS value FROM \"StockItem\" si JOIN \"Product\" p ON p.id ="
                + " si.\"productId\" JOIN \"InventoryLocation\" l ON l.id = si.\"locationId\" LEFT JOIN \"Branch\" b ON"
                + " b.id = l.\"branchId\"" + where + " GROUP BY l.id, l.name, b.name ORDER BY value DESC")
                .params(params));
    }

    /** Non-zero count lines of the sessions submitted in the period, valued at cost price. */
    private Map<String, Object> countVariances(AnalyticsView v, String[] locations) {
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ? AND s.status::text = 'submitted' AND"
                + " s.\"deletedAt\" IS NULL AND s.\"submittedAt\" >= ? AND s.\"submittedAt\" < ? AND cl.variance <> 0");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), v.start(), v.end()));
        locationClause(where, params, "s.\"locationId\"", locations);
        AnalyticsView.equal(where, params, "cl.\"productId\"", v.filter().productId());
        return SqlRows.one(jdbc.sql("SELECT count(*) AS lines, COALESCE(SUM(ABS(cl.variance) *"
                + " COALESCE(p.\"costPrice\", 0)), 0) AS value FROM \"InventoryCountLine\" cl JOIN"
                + " \"InventoryCountSession\" s ON s.id = cl.\"sessionId\" LEFT JOIN \"Product\" p ON p.id ="
                + " cl.\"productId\"" + where).params(params));
    }

    /** Posted stockpile surveys of the period: absolute variance value. */
    private Map<String, Object> surveyVariances(AnalyticsView v, String[] locations) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = surveyWhere(v, locations, params);
        return SqlRows.one(jdbc.sql("SELECT count(*) FILTER (WHERE s.\"varianceTonnes\" <> 0) AS surveys,"
                + " COALESCE(SUM(ABS(s.\"varianceValue\")), 0) AS value FROM \"CrushingStockpileSurvey\" s JOIN"
                + " \"QuarryStockpile\" k ON k.id = s.\"stockpileId\"" + where).params(params));
    }

    private static StringBuilder surveyWhere(AnalyticsView v, String[] locations, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ? AND s.status = 'posted' AND"
                + " s.\"deletedAt\" IS NULL AND s.\"surveyDate\" BETWEEN ? AND ?");
        params.addAll(List.of(TenantContext.organizationId(), v.fromDate(), v.toDate()));
        locationClause(where, params, "k.\"locationId\"", locations);
        AnalyticsView.equal(where, params, "s.\"productId\"", v.filter().productId());
        return where;
    }

    private static void locationClause(StringBuilder where, List<Object> params, String column,
            String[] locations) {
        if (locations != null) {
            where.append(" AND ").append(column).append(" = ANY(?::text[])");
            params.add(locations);
        }
    }

    /** Drill-downs: {@code below-minimum}, {@code count-variances}, {@code stockpile-surveys}. */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        AnalyticsView v = views.view(f);
        String[] locations = views.stockLocationIds(v);
        return switch (kpi == null ? "" : kpi) {
            case "below-minimum" -> belowMinimum(f, locations, paging);
            case "count-variances" -> countDocuments(v, locations, paging);
            case "stockpile-surveys" -> surveyDocuments(v, locations, paging);
            default -> throw AnalyticsDocuments.unknown(kpi);
        };
    }

    private PageResult<Map<String, Object>> belowMinimum(AnalyticsFilter f, String[] locations,
            Paging paging) {
        StringBuilder stock = new StringBuilder(" WHERE si.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        locationClause(stock, params, "si.\"locationId\"", locations);
        StringBuilder from = new StringBuilder("FROM \"Product\" p JOIN (SELECT si.\"productId\","
                + " SUM(si.quantity) AS qty, SUM(" + VALUE + ") AS value FROM \"StockItem\" si JOIN \"Product\" p"
                + " ON p.id = si.\"productId\""
                + stock + " GROUP BY si.\"productId\") s ON s.\"productId\" = p.id WHERE p.\"organizationId\" = ? AND"
                + " p.\"deletedAt\" IS NULL AND p.\"isActive\" AND p.\"minQuantity\" > 0 AND s.qty > 0 AND s.qty <="
                + " p.\"minQuantity\"");
        params.add(TenantContext.organizationId());
        AnalyticsView.equal(from, params, "p.id", f.productId());
        return documents.page("'product' AS type, p.id, p.code AS number, NULL::timestamp AS date, p.name AS party,"
                + " 'low' AS status, s.qty AS quantity, s.value AS amount", from, "p.name", params, paging);
    }

    private PageResult<Map<String, Object>> countDocuments(AnalyticsView v, String[] locations,
            Paging paging) {
        String product = v.filter().productId() == null ? "" : " AND cl.\"productId\" = ?";
        StringBuilder from = new StringBuilder("FROM \"InventoryCountSession\" s JOIN \"InventoryLocation\" l ON l.id ="
                + " s.\"locationId\" WHERE s.\"organizationId\" = ? AND s.status::text = 'submitted' AND"
                + " s.\"deletedAt\" IS NULL AND s.\"submittedAt\" >= ? AND s.\"submittedAt\" < ? AND EXISTS (SELECT 1"
                + " FROM"
                + " \"InventoryCountLine\" cl WHERE cl.\"sessionId\" = s.id AND cl.variance <> 0" + product + ")");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), v.start(), v.end()));
        if (v.filter().productId() != null) {
            params.add(v.filter().productId());
        }
        locationClause(from, params, "s.\"locationId\"", locations);
        return documents.page("'inventory_count' AS type, s.id, s.\"countCode\" AS number, s.\"submittedAt\" AS date,"
                + " l.name AS party, s.status::text AS status, NULL::numeric AS quantity, NULL::numeric AS amount",
                from, "s.\"submittedAt\" DESC", params, paging);
    }

    private PageResult<Map<String, Object>> surveyDocuments(AnalyticsView v, String[] locations,
            Paging paging) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = surveyWhere(v, locations, params);
        where.append(" AND s.\"varianceTonnes\" <> 0");
        return documents.page("'stockpile_survey' AS type, k.id, s.code AS number, s.\"surveyDate\" AS date, k.name AS"
                + " party, s.status, s.\"varianceTonnes\" AS quantity, s.\"varianceValue\" AS amount",
                "FROM \"CrushingStockpileSurvey\" s JOIN \"QuarryStockpile\" k ON k.id = s.\"stockpileId\"" + where,
                "s.\"surveyDate\" DESC", params, paging);
    }
}
