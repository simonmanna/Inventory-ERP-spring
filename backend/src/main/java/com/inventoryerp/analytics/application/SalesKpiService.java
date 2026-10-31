package com.inventoryerp.analytics.application;

import static com.inventoryerp.analytics.application.Dashboard.col;

import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.Dashboard.Chart;
import com.inventoryerp.analytics.application.Dashboard.Series;
import com.inventoryerp.analytics.application.Dashboard.Table;
import com.inventoryerp.analytics.application.Dashboard.Tile;
import com.inventoryerp.invoicing.application.SalesAnalysisService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.ManufacturingCostingService;
import com.inventoryerp.manufacturing.application.ManufacturingCostingService.MarginScope;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales: revenue and volume from {@link SalesAnalysisService}, gross margin from
 * {@link ManufacturingCostingService}, customer debt from {@link OpenItems}.
 */
@Service
@Transactional(readOnly = true)
public class SalesKpiService {

    private static final List<String> FILTERS = List.of("date", "branch", "product", "partner");
    private static final int TOP_ROWS = 20;

    private final AnalyticsViews views;
    private final AnalyticsDocuments documents;
    private final SalesAnalysisService sales;
    private final ManufacturingCostingService costing;
    private final OpenItems openItems;

    public SalesKpiService(AnalyticsViews views, AnalyticsDocuments documents, SalesAnalysisService sales,
            ManufacturingCostingService costing, OpenItems openItems) {
        this.views = views;
        this.documents = documents;
        this.sales = sales;
        this.costing = costing;
        this.openItems = openItems;
    }

    private Map<String, Object> analysis(AnalyticsView v, String groupBy) {
        AnalyticsFilter f = v.filter();
        return sales.analyse(new SalesAnalysisService.Filter(groupBy, v.fromText(), v.toText(), f.branchId(),
                f.productId(), f.partnerId(), null));
    }

    @SuppressWarnings("unchecked")
    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        Map<String, Object> byMonth = analysis(v, "month");
        Map<String, Object> byCustomer = analysis(v, "customer");
        MarginScope scope = new MarginScope(v.branchIds(), f.productId(), f.partnerId());
        Map<String, Object> margin = costing.margin(v.fromText(), v.toText(), "product", scope);
        Map<String, Object> marginTotals = (Map<String, Object>) margin.get("totals");
        List<Map<String, Object>> debt = openItems.receivables(v, f.partnerId());
        BigDecimal debtTotal = KpiMath.sum(debt, "total");
        BigDecimal overdue = debtTotal.subtract(KpiMath.sum(debt, "notDue"));
        List<Tile> tiles = List.of(
                new Tile("revenue", byMonth.get("totalAmount"), "money", "invoices"),
                new Tile("salesVolume", byMonth.get("totalQuantity"), "qty", "invoices"),
                new Tile("grossMargin", marginTotals.get("margin"), "money", null),
                new Tile("grossMarginPct", marginTotals.get("marginPct"), "percent", null),
                new Tile("customerDebt", debtTotal, "money", "receivables"),
                new Tile("overdueDebt", overdue, "money", "receivables"));
        List<Chart> charts = List.of(
                new Chart("revenueTrend", "bar", "label", "money", List.of(Series.of("amount")),
                        (List<Map<String, Object>>) byMonth.get("rows")),
                new Chart("customerDebtAging", "bar", "bucket", "money", List.of(Series.of("amount")),
                        OpenItems.agingBuckets(debt)));
        List<Map<String, Object>> customers = (List<Map<String, Object>>) byCustomer.get("rows");
        List<Table> tables = List.of(
                new Table("salesByCustomer", List.of(col("label", "text"), col("quantity", "qty"),
                        col("amount", "money"), col("sharePercent", "percent")),
                        customers.subList(0, Math.min(TOP_ROWS, customers.size())), "invoices", "partnerId", "key"),
                Dashboard.table("marginByProduct", List.of(col("label", "text"), col("quantity", "qty"),
                        col("revenue", "money"), col("cost", "money"), col("margin", "money"),
                        col("marginPct", "percent"), col("costBasis", "text")),
                        (List<Map<String, Object>>) margin.get("rows")),
                new Table("customerDebtByCustomer", List.of(col("partnerName", "text"), col("total", "money"),
                        col("notDue", "money"), col("d1_30", "money"), col("d31_60", "money"), col("d61_90", "money"),
                        col("d90_plus", "money")), debt.subList(0, Math.min(TOP_ROWS, debt.size())), "receivables",
                        "partnerId", "partnerId"));
        return new Dashboard("sales", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables,
                List.of("debtAsOfNow", "marginProductLines"));
    }

    /** Drill-downs {@code invoices} (posted sales of the period) and {@code receivables} (open balances). */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        AnalyticsView v = views.view(f);
        return switch (kpi == null ? "" : kpi) {
            case "invoices" -> salesDocuments(v, false, paging);
            case "receivables" -> salesDocuments(v, true, paging);
            default -> throw AnalyticsDocuments.unknown(kpi);
        };
    }

    /** Posted sales documents, or every open one whatever its date. */
    PageResult<Map<String, Object>> salesDocuments(AnalyticsView v, boolean open, Paging paging) {
        String org = TenantContext.organizationId();
        String product = v.filter().productId();
        List<Object> params = new ArrayList<>(List.of(org));
        StringBuilder docs = new StringBuilder("SELECT 'invoice' AS type, d.id, d.\"documentNumber\" AS number,"
                + " d.\"issueDate\" AS date, d.\"partnerId\", d.\"branchId\", d.status::text AS status,"
                + " d.\"totalAmount\" AS total, d.\"amountResidual\" AS residual FROM \"Document\" d WHERE"
                + " d.\"organizationId\" = ? AND"
                + " d.\"documentType\"::text = 'sales_invoice' AND d.status::text IN ('posted', 'paid')");
        documentFilters(docs, params, v, open, "d", product == null ? null : "EXISTS (SELECT 1 FROM \"DocumentLine\" dl"
                + " WHERE dl.\"documentId\" = d.id AND dl.\"productId\" = ?)");
        params.add(org);
        StringBuilder invoices = new StringBuilder("SELECT 'pos_invoice', i.id, i.\"invoiceNumber\", i.\"issueDate\","
                + " i.\"partnerId\", i.\"branchId\", i.status::text, i.\"totalAmount\", i.\"amountResidual\" FROM"
                + " \"Invoice\" i WHERE i.\"organizationId\" = ? AND i.status::text IN ('posted', 'paid')");
        documentFilters(invoices, params, v, open, "i", product == null ? null : "EXISTS (SELECT 1 FROM \"InvoiceItem\""
                + " ii WHERE ii.\"invoiceId\" = i.id AND ii.\"productId\" = ?)");
        return documents.page("x.type, x.id, x.number, x.date, COALESCE(pa.name, 'Walk-in') AS party, x.status,"
                + " NULL::numeric AS quantity, " + (open ? "x.residual" : "x.total") + " AS amount",
                "FROM (" + docs + " UNION ALL " + invoices + ") x LEFT JOIN \"Partner\" pa ON pa.id = x.\"partnerId\"",
                "x.date DESC, x.number DESC", params, paging);
    }

    private static void documentFilters(StringBuilder where, List<Object> params, AnalyticsView v, boolean open,
            String alias, String productClause) {
        if (open) {
            where.append(" AND ").append(alias).append(".\"amountResidual\" > 0");
        } else {
            where.append(" AND ").append(alias).append(".\"issueDate\" >= ? AND ").append(alias)
                    .append(".\"issueDate\" < ?");
            params.add(v.start());
            params.add(v.end());
        }
        v.branch(where, params, alias + ".\"branchId\"");
        AnalyticsView.equal(where, params, alias + ".\"partnerId\"", v.filter().partnerId());
        if (productClause != null) {
            where.append(" AND ").append(productClause);
            params.add(v.filter().productId());
        }
    }
}
