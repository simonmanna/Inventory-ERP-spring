package com.inventoryerp.analytics.application;

import static com.inventoryerp.analytics.application.Dashboard.col;

import com.inventoryerp.accounting.application.BranchPerformanceService;
import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.Dashboard.Chart;
import com.inventoryerp.analytics.application.Dashboard.Series;
import com.inventoryerp.analytics.application.Dashboard.Table;
import com.inventoryerp.analytics.application.Dashboard.Tile;
import com.inventoryerp.shared.web.PageResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Branch performance: revenue, expenses, stock position and profitability by location. */
@Service
@Transactional(readOnly = true)
public class BranchKpiService {

    private static final List<String> FILTERS = List.of("date", "branch");

    private final AnalyticsViews views;
    private final BranchPerformanceService branches;
    private final SalesKpiService sales;

    public BranchKpiService(AnalyticsViews views, BranchPerformanceService branches, SalesKpiService sales) {
        this.views = views;
        this.branches = branches;
        this.sales = sales;
    }

    @SuppressWarnings("unchecked")
    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        Map<String, Object> comparison = branches.comparison(v.fromText(), v.ledgerTo());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) comparison.get("rows");
        Map<String, Object> head = (Map<String, Object>) comparison.get("total");
        if (f.branchId() != null) {
            rows = rows.stream().filter(r -> f.branchId().equals(r.get("branchId"))).toList();
            head = rows.isEmpty() ? head : rows.getFirst();
        }
        List<Tile> tiles = List.of(
                new Tile("branchRevenue", head.get("income"), "money", "invoices"),
                new Tile("branchExpenses", head.get("expense"), "money", null),
                new Tile("branchProfit", head.get("netProfit"), "money", null),
                new Tile("branchMargin", head.get("marginPercent"), "percent", null),
                new Tile("stockPosition", head.get("stockValue"), "money", null),
                new Tile("openReceivables", head.get("openReceivables"), "money", null));
        List<Chart> charts = List.of(new Chart("branchComparison", "bar", "name", "money",
                List.of(Series.of("income"), Series.of("expense"), Series.of("netProfit")), rows));
        List<Table> tables = List.of(new Table("branchPerformance", List.of(col("name", "text"), col("income", "money"),
                col("expense", "money"), col("netProfit", "money"), col("marginPercent", "percent"),
                col("stockValue", "money"), col("openReceivables", "money"), col("deliveries", "count"),
                col("deliveredTonnes", "tonnes"), col("producedQty", "qty")), rows, "invoices", "branchId",
                "branchId"));
        return new Dashboard("branches", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables,
                List.of("stockAsOfNow"));
    }

    /** Drill-down {@code invoices}: the branch's posted sales documents. */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        if (!"invoices".equals(kpi)) {
            throw AnalyticsDocuments.unknown(kpi);
        }
        return sales.salesDocuments(views.view(f), false, paging);
    }
}
