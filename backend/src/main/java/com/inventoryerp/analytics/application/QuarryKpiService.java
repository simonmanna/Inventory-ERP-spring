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
import com.inventoryerp.quarry.application.QuarryReportService;
import com.inventoryerp.quarry.application.QuarryReportService.ReportScope;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quarry operations: extraction volume, cost per tonne and target achievement, from {@link QuarryReportService}. */
@Service
@Transactional(readOnly = true)
public class QuarryKpiService {

    private static final List<String> FILTERS = List.of("date", "branch", "site", "product");

    private final AnalyticsViews views;
    private final AnalyticsDocuments documents;
    private final QuarryReportService reports;

    public QuarryKpiService(AnalyticsViews views, AnalyticsDocuments documents, QuarryReportService reports) {
        this.views = views;
        this.documents = documents;
        this.reports = reports;
    }

    @SuppressWarnings("unchecked")
    public Dashboard dashboard(AnalyticsFilter f) {
        AnalyticsView v = views.view(f);
        ReportScope scope = new ReportScope(views.siteIds(v), f.productId());
        Map<String, Object> bySite = reports.costPerTonne(v.from(), v.to(), scope, "site");
        Map<String, Object> byMonth = reports.costPerTonne(v.from(), v.to(), scope, "month");
        Map<String, Object> targets = reports.extractionVsTarget(v.from(), v.to(), scope);
        Map<String, Object> totals = (Map<String, Object>) bySite.get("totals");
        Map<String, Object> targetTotals = (Map<String, Object>) targets.get("totals");
        List<Tile> tiles = List.of(
                new Tile("extractionVolume", totals.get("netTonnes"), "tonnes", "extraction-logs"),
                new Tile("extractionCostPerTonne", totals.get("costPerTonne"), "rate", "extraction-logs"),
                new Tile("targetAchievement", targetTotals.get("achievedPct"), "percent", null),
                new Tile("targetTonnes", targetTotals.get("targetTonnes"), "tonnes", null),
                new Tile("extractionCost", totals.get("totalCost"), "money", "extraction-logs"),
                new Tile("extractionLoss", totals.get("lossPct"), "percent", null));
        List<Chart> charts = List.of(new Chart("extractionTrend", "bar", "period", "tonnes",
                List.of(Series.of("netTonnes")), trend((List<Map<String, Object>>) byMonth.get("rows"))));
        List<Table> tables = List.of(
                new Table("extractionBySite", List.of(col("siteName", "text"), col("logs", "count"),
                        col("grossTonnes", "tonnes"), col("lossPct", "percent"), col("netTonnes", "tonnes"),
                        col("totalCost", "money"), col("costPerTonne", "rate")),
                        (List<Map<String, Object>>) bySite.get("rows"), "extraction-logs", "siteId", "siteId"),
                Dashboard.table("extractionTargets", List.of(col("code", "text"), col("siteName", "text"),
                        col("periodStart", "date"), col("periodEnd", "date"), col("targetTonnes", "tonnes"),
                        col("actualTonnes", "tonnes"), col("achievedPct", "percent")),
                        targetRows((List<Map<String, Object>>) targets.get("rows"))));
        return new Dashboard("quarry", v.from(), v.to(), FILTERS, v.ignored(FILTERS), tiles, charts, tables,
                List.of("targetsWholePlan"));
    }

    /** One point per month; the report's rows are per site and month. */
    private static List<Map<String, Object>> trend(List<Map<String, Object>> rows) {
        TreeMap<String, BigDecimal[]> months = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            String month = YearMonth.from((LocalDate) r.get("period")).toString();
            BigDecimal[] t = months.computeIfAbsent(month, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(SqlRows.dec(r, "netTonnes"));
            t[1] = t[1].add(SqlRows.dec(r, "totalCost"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        months.forEach((m, t) -> out.add(Obj.of("period", m, "netTonnes", t[0], "totalCost", t[1], "costPerTonne",
                KpiMath.rate(t[1], t[0]))));
        return out;
    }

    private static List<Map<String, Object>> targetRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : rows) {
            BigDecimal target = SqlRows.dec(p, "targetTonnes");
            BigDecimal actual = SqlRows.dec(p, "actualTonnes");
            out.add(Obj.of("code", p.get("code"), "siteName", p.get("siteName"), "periodStart", p.get("periodStart"),
                    "periodEnd", p.get("periodEnd"), "targetTonnes", target, "actualTonnes", actual, "achievedPct",
                    KpiMath.pct(actual, target)));
        }
        return out;
    }

    /** Drill-down {@code extraction-logs}: the posted logs behind the figures. */
    public PageResult<Map<String, Object>> documents(String kpi, AnalyticsFilter f, Paging paging) {
        if (!"extraction-logs".equals(kpi)) {
            throw AnalyticsDocuments.unknown(kpi);
        }
        AnalyticsView v = views.view(f);
        StringBuilder from = new StringBuilder("FROM \"QuarryExtractionLog\" l JOIN \"QuarrySite\" s ON s.id ="
                + " l.\"siteId\" WHERE l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"deletedAt\" IS NULL"
                + " AND l.\"extractedOn\" BETWEEN ? AND ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId(), v.fromDate(), v.toDate()));
        String[] sites = views.siteIds(v);
        if (sites != null) {
            from.append(" AND l.\"siteId\" = ANY(?::text[])");
            params.add(sites);
        }
        if (f.productId() != null) {
            from.append(" AND l.\"materialId\" IN (SELECT m.id FROM \"QuarryMaterial\" m WHERE m.\"organizationId\" ="
                    + " l.\"organizationId\" AND m.\"productId\" = ?)");
            params.add(f.productId());
        }
        return documents.page("'quarry_extraction' AS type, l.id, l.code AS number, l.\"extractedOn\" AS date,"
                + " s.name AS party, l.status, l.\"netTonnes\" AS quantity, l.\"totalCost\" AS amount", from,
                "l.\"extractedOn\" DESC, l.code DESC", params, paging);
    }
}
