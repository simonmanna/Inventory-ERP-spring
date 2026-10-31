package com.inventoryerp.analytics.application;

import static com.inventoryerp.analytics.application.Dashboard.col;

import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.Dashboard.Chart;
import com.inventoryerp.analytics.application.Dashboard.Series;
import com.inventoryerp.analytics.application.Dashboard.Table;
import com.inventoryerp.analytics.application.Dashboard.Tile;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.procurement.application.SupplierPerformanceService;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Procurement: spending and purchase price trends from purchase order lines (no report had them) and supplier
 * performance from {@link SupplierPerformanceService}.
 */
@Service
@Transactional(readOnly = true)
public class ProcurementKpiService {

    private static final List<String> FILTERS = List.of("date", "branch", "product", "partner");
    private static final int TREND_PRODUCTS = 5;
    private static final String LINES = " FROM \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" po ON po.id ="
            + " l.\"purchaseOrderId\" LEFT JOIN \"Product\" p ON p.id = l.\"productId\"";
    private static final String NET_VALUE = "l.subtotal * po.\"exchangeRate\"";

    private final AnalyticsViews views;
    private final AnalyticsDocuments documents;
    private final SupplierPerformanceService suppliers;
    private final JdbcClient jdbc;

    public ProcurementKpiService(AnalyticsViews views, AnalyticsDocuments documents,
            SupplierPerformanceService suppliers, JdbcClient jdbc) {
        this.views = views;
        this.documents = documents;
        this.suppliers = suppliers;
        this.jdbc = jdbc;
    }

    private static StringBuilder orderWhere(AnalyticsView v, List<Object> params) {
        StringBuilder w = new StringBuilder(" WHERE po.\"organizationId\" = ? AND po.\"deletedAt\" IS NULL AND"
                + " po.status::text NOT IN ('draft', 'cancelled') AND po.\"orderDate\" >= ? AND po.\"orderDate\" < ?");
        params.addAll(List.of(TenantContext.organizationId(), v.start(), v.end()));
        v.branch(w, params, "po.\"branchId\"");
        AnalyticsView.equal(w, params, "po.\"partnerId\"", v.filter().partnerId());
        return w;
    }

    @SuppressWarnings("unchecked")
    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        List<Object> sumParams = new ArrayList<>();
        StringBuilder sumWhere = orderWhere(v, sumParams);
        AnalyticsView.equal(sumWhere, sumParams, "l.\"productId\"", f.productId());
        List<Map<String, Object>> spend = SqlRows.list(jdbc.sql("SELECT to_char(po.\"orderDate\", 'YYYY-MM') AS period,"
                + " count(DISTINCT po.id) AS orders, COALESCE(SUM(" + NET_VALUE + "), 0) AS spend" + LINES + sumWhere
                + " GROUP BY 1 ORDER BY 1").params(sumParams));
        Map<String, Object> scorecard = suppliers.report(v.fromText(), v.toText(), f.partnerId(), v.branchIds());
        List<Map<String, Object>> supplierRows = (List<Map<String, Object>>) scorecard.get("rows");
        BigDecimal total = KpiMath.sum(spend, "spend");
        List<Map<String, Object>> priceRows = priceRows(v);
        List<Tile> tiles = List.of(
                new Tile("procurementSpend", total, "money", "purchase-orders"),
                new Tile("purchaseOrders", KpiMath.sum(spend, "orders"), "count", "purchase-orders"),
                new Tile("activeSuppliers", supplierRows.size(), "count", null),
                new Tile("supplierScore", weightedScore(supplierRows), "percent", null),
                new Tile("supplierOnTime", weighted(supplierRows, "onTimeRate", "receipts"), "percent", null));
        List<Chart> charts = new ArrayList<>(List.of(new Chart("procurementSpendTrend", "bar", "period", "money",
                List.of(Series.of("spend")), spend)));
        priceChart(priceRows, charts);
        List<Table> tables = List.of(
                new Table("supplierPerformance", List.of(col("partnerName", "text"), col("orders", "count"),
                        col("spend", "money"), col("onTimeRate", "percent"), col("fillRate", "percent"),
                        col("qualityRate", "percent"), col("priceVarianceRate", "percent"), col("score", "percent")),
                        supplierRows, "purchase-orders", "partnerId", "partnerId"),
                Dashboard.table("purchasePriceChange", List.of(col("productName", "text"), col("firstPeriod", "text"),
                        col("firstPrice", "rate"), col("lastPeriod", "text"), col("lastPrice", "rate"),
                        col("changePct", "percent")), priceChanges(priceRows)));
        return new Dashboard("procurement", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables,
                f.productId() == null ? List.of("scorecardGrossSpend")
                        : List.of("scorecardGrossSpend", "scorecardAllProducts"));
    }

    /** Spend-weighted mean of the suppliers' scores. */
    private static BigDecimal weightedScore(List<Map<String, Object>> rows) {
        return weighted(rows, "score", "spend");
    }

    private static BigDecimal weighted(List<Map<String, Object>> rows, String measure, String weight) {
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal weights = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            if (r.get(measure) != null) {
                BigDecimal w = SqlRows.dec(r, weight);
                sum = sum.add(SqlRows.dec(r, measure).multiply(w));
                weights = weights.add(w);
            }
        }
        BigDecimal mean = KpiMath.rate(sum, weights);
        return mean == null ? null : mean.setScale(1, RoundingMode.HALF_UP);
    }

    /** Monthly weighted price of the selected product, or of the top products by spend. */
    private List<Map<String, Object>> priceRows(AnalyticsView v) {
        List<Object> topParams = new ArrayList<>();
        StringBuilder topWhere = orderWhere(v, topParams);
        topWhere.append(" AND l.\"productId\" IS NOT NULL");
        AnalyticsView.equal(topWhere, topParams, "l.\"productId\"", v.filter().productId());
        topParams.add(TREND_PRODUCTS);
        String[] top = jdbc.sql("SELECT l.\"productId\"" + LINES + topWhere + " GROUP BY l.\"productId\" ORDER BY"
                + " SUM(" + NET_VALUE + ") DESC LIMIT ?").params(topParams).query(String.class).list()
                .toArray(String[]::new);
        if (top.length == 0) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        StringBuilder where = orderWhere(v, params);
        where.append(" AND l.\"productId\" = ANY(?::text[])");
        params.add(top);
        return SqlRows.list(jdbc.sql("SELECT l.\"productId\", p.name AS \"productName\", to_char(po.\"orderDate\","
                + " 'YYYY-MM') AS period, SUM(l.quantity) AS quantity, SUM(l.\"unitPrice\" * l.quantity *"
                + " po.\"exchangeRate\") AS value" + LINES + where + " GROUP BY 1, 2, 3 ORDER BY 3").params(params));
    }

    private static void priceChart(List<Map<String, Object>> rows, List<Chart> charts) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Series> series = new LinkedHashMap<>();
        TreeMap<String, Map<String, Object>> months = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            String product = SqlRows.str(r, "productId");
            series.putIfAbsent(product, new Series(product, SqlRows.str(r, "productName")));
            months.computeIfAbsent(SqlRows.str(r, "period"), m -> Obj.of("period", m)).put(product,
                    KpiMath.rate(SqlRows.dec(r, "value"), SqlRows.dec(r, "quantity")));
        }
        charts.add(new Chart("purchasePriceTrend", "line", "period", "rate", new ArrayList<>(series.values()),
                new ArrayList<>(months.values())));
    }

    /** First and last month of each product in the period and the change between them. */
    private static List<Map<String, Object>> priceChanges(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            BigDecimal price = KpiMath.rate(SqlRows.dec(r, "value"), SqlRows.dec(r, "quantity"));
            Map<String, Object> m = byProduct.computeIfAbsent(SqlRows.str(r, "productId"), k -> Obj.of("productName",
                    r.get("productName"), "firstPeriod", r.get("period"), "firstPrice", price));
            m.put("lastPeriod", r.get("period"));
            m.put("lastPrice", price);
        }
        for (Map<String, Object> m : byProduct.values()) {
            BigDecimal first = (BigDecimal) m.get("firstPrice");
            BigDecimal last = (BigDecimal) m.get("lastPrice");
            m.put("changePct", first == null || last == null ? null : KpiMath.pct(last.subtract(first), first));
        }
        return new ArrayList<>(byProduct.values());
    }

    /** Drill-down {@code purchase-orders}. */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        if (!"purchase-orders".equals(kpi)) {
            throw AnalyticsDocuments.unknown(kpi);
        }
        AnalyticsView v = views.view(f);
        List<Object> params = new ArrayList<>();
        StringBuilder where = orderWhere(v, params);
        if (f.productId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM \"PurchaseOrderLine\" l WHERE l.\"purchaseOrderId\" = po.id AND"
                    + " l.\"productId\" = ?)");
            params.add(f.productId());
        }
        return documents.page("'purchase_order' AS type, po.id, po.\"orderNumber\" AS number, po.\"orderDate\" AS date,"
                + " pa.name AS party, po.status::text AS status, NULL::numeric AS quantity,"
                + " po.\"totalAmount\" * po.\"exchangeRate\" AS amount",
                "FROM \"PurchaseOrder\" po LEFT JOIN \"Partner\" pa ON pa.id = po.\"partnerId\"" + where,
                "po.\"orderDate\" DESC", params, paging);
    }
}
