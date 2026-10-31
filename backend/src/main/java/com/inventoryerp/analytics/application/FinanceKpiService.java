package com.inventoryerp.analytics.application;

import static com.inventoryerp.analytics.application.Dashboard.col;

import com.inventoryerp.accounting.application.ConsolidatedReportService;
import com.inventoryerp.accounting.application.FinancialReportsService;
import com.inventoryerp.analytics.application.AnalyticsDocuments.Paging;
import com.inventoryerp.analytics.application.Dashboard.Chart;
import com.inventoryerp.analytics.application.Dashboard.Series;
import com.inventoryerp.analytics.application.Dashboard.Table;
import com.inventoryerp.analytics.application.Dashboard.Tile;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finance: profit from {@link ConsolidatedReportService}, the one ledger report that can be cut by branch, cash flow
 * from {@link FinancialReportsService} (unlimited views only, because cash has no reliable branch) and open balances
 * from {@link OpenItems}.
 */
@Service
@Transactional(readOnly = true)
public class FinanceKpiService {

    private static final List<String> FILTERS = List.of("date", "branch");
    private static final int MAX_MONTHS = 12;
    private static final int TOP_ROWS = 20;

    private final AnalyticsViews views;
    private final AnalyticsDocuments documents;
    private final ConsolidatedReportService consolidated;
    private final FinancialReportsService reports;
    private final OpenItems openItems;
    private final SalesKpiService sales;

    public FinanceKpiService(AnalyticsViews views, AnalyticsDocuments documents,
            ConsolidatedReportService consolidated, FinancialReportsService reports, OpenItems openItems,
            SalesKpiService sales) {
        this.views = views;
        this.documents = documents;
        this.consolidated = consolidated;
        this.reports = reports;
        this.openItems = openItems;
        this.sales = sales;
    }

    /** Ledger totals of a period, for every branch or the view's branches. */
    private record Profit(BigDecimal revenue, BigDecimal contra, BigDecimal cogs, BigDecimal operating,
            BigDecimal income, BigDecimal expense) {

        BigDecimal grossProfit() {
            return revenue.subtract(contra).subtract(cogs);
        }

        BigDecimal netProfit() {
            return income.subtract(expense);
        }
    }

    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        Profit profit = profit(v.fromText(), v.ledgerTo(), v.branchIds());
        List<Map<String, Object>> receivables = openItems.receivables(v, null);
        List<Map<String, Object>> payables = openItems.payables(v, null);
        List<Tile> tiles = new ArrayList<>();
        List<String> notes = new ArrayList<>(List.of("balancesAsOfNow"));
        if (v.limited()) {
            notes.add("cashOrgWide");
        } else {
            Map<String, Object> cash = reports.cashFlow(v.fromText(), v.toText());
            tiles.add(new Tile("netCashFlow", SqlRows.dec(cash, "netCashFlow"), "money", null));
            tiles.add(new Tile("closingCash", SqlRows.dec(cash, "closingCash"), "money", null));
        }
        tiles.add(new Tile("operatingExpenses", profit.operating(), "money", null));
        tiles.add(new Tile("financeReceivables", KpiMath.sum(receivables, "total"), "money", "receivables"));
        tiles.add(new Tile("financePayables", KpiMath.sum(payables, "total"), "money", "payables"));
        tiles.add(new Tile("grossProfit", profit.grossProfit(), "money", null));
        tiles.add(new Tile("netProfit", profit.netProfit(), "money", null));
        tiles.add(new Tile("profitMargin", KpiMath.pct(profit.netProfit(), profit.income()), "percent", null));
        List<Chart> charts = List.of(
                new Chart("profitTrend", "bar", "period", "money",
                        List.of(Series.of("income"), Series.of("expense")), months(v)),
                new Chart("receivablesAging", "bar", "bucket", "money", List.of(Series.of("amount")),
                        OpenItems.agingBuckets(receivables)),
                new Chart("payablesAging", "bar", "bucket", "money", List.of(Series.of("amount")),
                        OpenItems.agingBuckets(payables)));
        List<Dashboard.Column> balanceColumns = List.of(col("partnerName", "text"), col("total", "money"),
                col("notDue", "money"), col("d1_30", "money"), col("d31_60", "money"), col("d61_90", "money"),
                col("d90_plus", "money"));
        List<Table> tables = List.of(
                new Table("receivablesByCustomer", balanceColumns, top(receivables), "receivables", null, null),
                new Table("payablesBySupplier", balanceColumns, top(payables), "payables", null, null));
        return new Dashboard("finance", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables, notes);
    }

    private static List<Map<String, Object>> top(List<Map<String, Object>> rows) {
        return rows.subList(0, Math.min(TOP_ROWS, rows.size()));
    }

    @SuppressWarnings("unchecked")
    private Profit profit(String from, String to, String[] branchIds) {
        Map<String, Object> pl = consolidated.consolidated(from, to, "pl");
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal contra = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        BigDecimal operating = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (Map<String, Object> row : (List<Map<String, Object>>) pl.get("rows")) {
            BigDecimal amount = amount(row, branchIds);
            switch (String.valueOf(row.get("reportSection"))) {
                case "revenue" -> revenue = revenue.add(amount);
                case "contra_revenue" -> contra = contra.add(amount);
                case "cogs" -> cogs = cogs.add(amount);
                case "operating_expense" -> operating = operating.add(amount);
                default -> { }
            }
            if ("income".equals(row.get("type"))) {
                income = income.add(amount);
            } else {
                expense = expense.add(amount);
            }
        }
        return new Profit(revenue, contra, cogs, operating, income, expense);
    }

    /** The account's total, or its sum over the view's branches (so Unassigned is out of a limited view). */
    @SuppressWarnings("unchecked")
    private static BigDecimal amount(Map<String, Object> row, String[] branchIds) {
        if (branchIds == null) {
            return SqlRows.dec(row, "total");
        }
        Map<String, Object> byBranch = (Map<String, Object>) row.get("byBranch");
        BigDecimal sum = BigDecimal.ZERO;
        for (String b : branchIds) {
            sum = sum.add(SqlRows.dec(byBranch, b));
        }
        return sum;
    }

    /** Income, expense and net profit per calendar month of the period, the last {@link #MAX_MONTHS} at most. */
    private List<Map<String, Object>> months(AnalyticsView v) {
        YearMonth last = YearMonth.from(v.to());
        YearMonth first = YearMonth.from(v.from());
        if (first.isBefore(last.minusMonths(MAX_MONTHS - 1L))) {
            first = last.minusMonths(MAX_MONTHS - 1L);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            LocalDate start = m.atDay(1).isBefore(v.from()) ? v.from() : m.atDay(1);
            LocalDate end = m.atEndOfMonth().isAfter(v.to()) ? v.to() : m.atEndOfMonth();
            Profit p = profit(start.toString(), end + "T23:59:59.999Z", v.branchIds());
            out.add(Obj.of("period", m.toString(), "income", p.income(), "expense", p.expense(), "netProfit",
                    p.netProfit()));
        }
        return out;
    }

    /** Drill-downs {@code receivables} and {@code payables}: the open documents. */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        AnalyticsView v = views.view(new AnalyticsFilter(f.from(), f.to(), f.branchId(), null, null, null, null));
        return switch (kpi == null ? "" : kpi) {
            case "receivables" -> sales.salesDocuments(v, true, paging);
            case "payables" -> payables(v, paging);
            default -> throw AnalyticsDocuments.unknown(kpi);
        };
    }

    private PageResult<Map<String, Object>> payables(AnalyticsView v, Paging paging) {
        StringBuilder from = new StringBuilder("FROM \"Document\" d LEFT JOIN \"Partner\" pa ON pa.id = d.\"partnerId\""
                + " WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text = 'vendor_bill' AND d.status::text IN"
                + " ('posted', 'paid') AND d.\"amountResidual\" > 0");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        v.branch(from, params, "d.\"branchId\"");
        return documents.page("'vendor_bill' AS type, d.id, d.\"documentNumber\" AS number, d.\"issueDate\" AS date,"
                + " pa.name AS party, d.status::text AS status, NULL::numeric AS quantity, d.\"amountResidual\" AS"
                + " amount", from, "d.\"issueDate\" DESC", params, paging);
    }
}
