package com.inventoryerp.analytics.application;

import static com.inventoryerp.analytics.application.Dashboard.col;

import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.Dashboard.Chart;
import com.inventoryerp.analytics.application.Dashboard.Series;
import com.inventoryerp.analytics.application.Dashboard.Table;
import com.inventoryerp.analytics.application.Dashboard.Tile;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.CrushingReportService;
import com.inventoryerp.manufacturing.application.CrushingReportService.RunScope;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregate processing: input-to-output yield, production volume and material loss of posted crushing runs. */
@Service
@Transactional(readOnly = true)
public class AggregateKpiService {

    private static final List<String> FILTERS = List.of("date", "branch", "site", "product");

    private final AnalyticsViews views;
    private final AnalyticsDocuments documents;
    private final CrushingReportService crushing;

    public AggregateKpiService(AnalyticsViews views, AnalyticsDocuments documents, CrushingReportService crushing) {
        this.views = views;
        this.documents = documents;
        this.crushing = crushing;
    }

    @SuppressWarnings("unchecked")
    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        RunScope scope = new RunScope(views.plantIds(v), f.productId());
        Map<String, Object> yields = crushing.yieldReport(v.fromText(), v.toText(), scope);
        Map<String, Object> costs = crushing.costPerTonne(v.fromText(), v.toText(), scope);
        List<Map<String, Object>> plants = (List<Map<String, Object>>) yields.get("plants");
        List<Map<String, Object>> plantCosts = (List<Map<String, Object>>) costs.get("plants");
        BigDecimal feed = KpiMath.sum(plants, "feedTonnes");
        BigDecimal output = KpiMath.sum(plants, "outputTonnes");
        BigDecimal loss = KpiMath.sum(plants, "lossTonnes");
        BigDecimal cost = KpiMath.sum(plantCosts, "totalCost");
        List<Tile> tiles = List.of(
                new Tile("inputOutputYield", KpiMath.pct(output, feed), "percent", "crushing-runs"),
                new Tile("productionVolume", output, "tonnes", "crushing-runs"),
                new Tile("feedVolume", feed, "tonnes", "crushing-runs"),
                new Tile("materialLoss", loss, "tonnes", "crushing-runs"),
                new Tile("materialLossPct", KpiMath.pct(loss, feed), "percent", null),
                new Tile("aggregateCostPerTonne", KpiMath.rate(cost, output), "rate", null));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map<String, Object> r : crushing.monthlyTrend(v.fromText(), v.toText(), scope)) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("yieldPct", KpiMath.pct(SqlRows.dec(r, "outputTonnes"), SqlRows.dec(r, "feedTonnes")));
            trend.add(row);
        }
        List<Chart> charts = List.of(
                new Chart("aggregateVolumeTrend", "bar", "period", "tonnes",
                        List.of(Series.of("outputTonnes"), Series.of("lossTonnes")), trend),
                new Chart("aggregateYieldTrend", "line", "period", "percent", List.of(Series.of("yieldPct")), trend));
        List<Table> tables = List.of(
                Dashboard.table("aggregatesByPlant", List.of(col("plantName", "text"), col("runs", "count"),
                        col("feedTonnes", "tonnes"), col("outputTonnes", "tonnes"), col("lossTonnes", "tonnes"),
                        col("expectedYieldPct", "percent"), col("actualYieldPct", "percent"),
                        col("costPerTonne", "rate")), plantRows(plants, plantCosts)),
                Dashboard.table("aggregatesByProduct", List.of(col("productName", "text"), col("role", "text"),
                        col("expectedTonnes", "tonnes"), col("actualTonnes", "tonnes"), col("varianceTonnes", "tonnes"),
                        col("actualPct", "percent")), (List<Map<String, Object>>) yields.get("products")));
        return new Dashboard("aggregates", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables,
                List.of());
    }

    private static List<Map<String, Object>> plantRows(List<Map<String, Object>> plants,
            List<Map<String, Object>> costs) {
        Map<String, Object> perTonne = new HashMap<>();
        for (Map<String, Object> c : costs) {
            perTonne.put(SqlRows.str(c, "plantId"), c.get("costPerTonne"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : plants) {
            Map<String, Object> row = new LinkedHashMap<>(p);
            row.put("costPerTonne", perTonne.get(SqlRows.str(p, "plantId")));
            out.add(row);
        }
        return out;
    }

    /** Drill-down {@code crushing-runs}: the posted runs behind the figures. */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        if (!"crushing-runs".equals(kpi)) {
            throw AnalyticsDocuments.unknown(kpi);
        }
        AnalyticsView v = views.view(f);
        StringBuilder from = new StringBuilder("FROM \"CrushingRun\" r JOIN \"CrushingPlant\" p ON p.id = r.\"plantId\""
                + " WHERE r.\"organizationId\" = ? AND r.status = 'posted' AND r.\"deletedAt\" IS NULL"
                + " AND r.\"runDate\" BETWEEN ? AND ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), v.fromDate(), v.toDate()));
        String[] plants = views.plantIds(v);
        if (plants != null) {
            from.append(" AND r.\"plantId\" = ANY(?::text[])");
            params.add(plants);
        }
        if (f.productId() != null) {
            from.append(" AND (r.\"feedProductId\" = ? OR EXISTS (SELECT 1 FROM \"CrushingRunOutput\" o WHERE"
                    + " o.\"runId\" = r.id AND o.\"productId\" = ?))");
            params.add(f.productId());
            params.add(f.productId());
        }
        return documents.page("'crushing_run' AS type, r.id, r.code AS number, r.\"runDate\" AS date, p.name AS party,"
                + " r.status, r.\"outputTonnes\" AS quantity, r.\"totalCost\" AS amount", from,
                "r.\"runDate\" DESC, r.code DESC", params, paging);
    }
}
