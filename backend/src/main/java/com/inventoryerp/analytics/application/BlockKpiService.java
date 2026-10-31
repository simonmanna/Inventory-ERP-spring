package com.inventoryerp.analytics.application;

import static com.inventoryerp.analytics.application.Dashboard.col;

import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.Dashboard.Chart;
import com.inventoryerp.analytics.application.Dashboard.Series;
import com.inventoryerp.analytics.application.Dashboard.Table;
import com.inventoryerp.analytics.application.Dashboard.Tile;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.PrecastReportService;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Block manufacturing: blocks produced, rejection rate and cost per acceptable block of precast orders. */
@Service
@Transactional(readOnly = true)
public class BlockKpiService {

    private static final List<String> FILTERS = List.of("date", "branch", "product");
    /** The order limit of {@link PrecastReportService#costPerBlock}. */
    private static final int ORDER_LIMIT = 500;

    private final AnalyticsViews views;
    private final AnalyticsDocuments documents;
    private final PrecastReportService precast;

    public BlockKpiService(AnalyticsViews views, AnalyticsDocuments documents, PrecastReportService precast) {
        this.views = views;
        this.documents = documents;
        this.precast = precast;
    }

    @SuppressWarnings("unchecked")
    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        Map<String, Object> costs = precast.costPerBlock(v.fromText(), v.toText(), f.productId(), v.branchIds());
        Map<String, Object> breakage = precast.breakage(v.fromText(), v.toText(), f.productId(), v.branchIds());
        List<Map<String, Object>> orders = (List<Map<String, Object>>) costs.get("rows");
        List<Map<String, Object>> products = (List<Map<String, Object>>) costs.get("byProduct");
        BigDecimal pressed = KpiMath.sum(products, "blocksPressed");
        BigDecimal good = KpiMath.sum(products, "goodBlocks");
        BigDecimal second = KpiMath.sum(products, "secondGrade");
        BigDecimal rejected = KpiMath.sum(products, "brokenAtPress").add(KpiMath.sum(products, "brokenAtCubing"))
                .add(second);
        BigDecimal value = KpiMath.sum(products, "finishedGoodsValue");
        List<Tile> tiles = List.of(
                new Tile("blocksProduced", good, "qty", "precast-orders"),
                new Tile("blocksPressed", pressed, "qty", "precast-orders"),
                new Tile("rejectionRate", KpiMath.pct(rejected, pressed), "percent", "precast-orders"),
                new Tile("secondGrade", second, "qty", null),
                new Tile("costPerAcceptableBlock", KpiMath.rate(value, good), "rate", "precast-orders"),
                new Tile("finishedGoodsValue", value, "money", null));
        List<Chart> charts = List.of(new Chart("blocksTrend", "bar", "period", "qty",
                List.of(Series.of("goodBlocks"), Series.of("rejectedBlocks")), trend(orders)));
        List<Table> tables = List.of(
                new Table("blocksByProduct", List.of(col("productName", "text"), col("orders", "count"),
                        col("blocksPressed", "qty"), col("goodBlocks", "qty"), col("secondGrade", "qty"),
                        col("rejectionRatePct", "percent"), col("costPerAcceptableBlock", "rate")), products,
                        "precast-orders", "productId", "productId"),
                Dashboard.table("blocksBreakage", List.of(col("productName", "text"), col("stage", "text"),
                        col("reasonName", "text"), col("quantity", "qty"), col("postings", "count")),
                        (List<Map<String, Object>>) breakage.get("byReason")));
        return new Dashboard("blocks", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables,
                orders.size() >= ORDER_LIMIT ? List.of("blocksByOrderDate", "blocksTruncated")
                        : List.of("blocksByOrderDate"));
    }

    /** One point per month the orders were created. */
    private static List<Map<String, Object>> trend(List<Map<String, Object>> orders) {
        TreeMap<String, BigDecimal[]> months = new TreeMap<>();
        for (Map<String, Object> o : orders) {
            Instant created = (Instant) o.get("createdAt");
            String month = YearMonth.from(created.atZone(BusinessZone.get())).toString();
            BigDecimal[] t = months.computeIfAbsent(month, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(SqlRows.dec(o, "goodBlocks"));
            t[1] = t[1].add(SqlRows.dec(o, "brokenAtPress")).add(SqlRows.dec(o, "brokenAtCubing"))
                    .add(SqlRows.dec(o, "secondGrade"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        months.forEach((m, t) -> out.add(Obj.of("period", m, "goodBlocks", t[0], "rejectedBlocks", t[1])));
        return out;
    }

    /** Drill-down {@code precast-orders}: the orders behind the figures. */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        if (!"precast-orders".equals(kpi)) {
            throw AnalyticsDocuments.unknown(kpi);
        }
        AnalyticsView v = views.view(f);
        StringBuilder from = new StringBuilder("FROM \"ProductionOrder\" o JOIN \"PrecastMixDesign\" m ON m.\"bomId\" ="
                + " o.\"bomId\" AND m.\"organizationId\" = o.\"organizationId\" LEFT JOIN \"Product\" p ON p.id ="
                + " o.\"outputProductId\" WHERE o.\"organizationId\" = ? AND o.status <> 'cancelled'"
                + " AND o.\"createdAt\" >= ? AND o.\"createdAt\" < ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(),
                Timestamp.valueOf(v.from().atStartOfDay()), Timestamp.valueOf(v.to().plusDays(1).atStartOfDay())));
        AnalyticsView.equal(from, params, "o.\"outputProductId\"", f.productId());
        if (v.limited()) {
            from.append(" AND o.\"locationId\" IN (SELECT l.id FROM \"InventoryLocation\" l WHERE l.\"organizationId\" ="
                    + " o.\"organizationId\" AND l.\"branchId\" = ANY(?::text[]))");
            params.add(v.branchIds());
        }
        return documents.page("'precast_order' AS type, o.id, o.\"orderCode\" AS number, o.\"createdAt\" AS date,"
                + " p.name AS party, o.status::text AS status, o.\"producedQty\" AS quantity, o.\"totalCost\" AS amount",
                from, "o.\"createdAt\" DESC", params, paging);
    }
}
