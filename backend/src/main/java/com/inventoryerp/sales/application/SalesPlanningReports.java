package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesReportKit.area;
import static com.inventoryerp.sales.application.SalesReportKit.bar;
import static com.inventoryerp.sales.application.SalesReportKit.line;
import static com.inventoryerp.sales.application.SalesReportKit.money;
import static com.inventoryerp.sales.application.SalesReportKit.num;
import static com.inventoryerp.sales.application.SalesReportKit.pct;
import static com.inventoryerp.sales.application.SalesReportKit.table;
import static com.inventoryerp.sales.application.SalesReportSet.STD;
import static com.inventoryerp.sales.application.SalesReportSet.def;
import static com.inventoryerp.sales.application.SalesReportSet.with;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.sales.application.SalesReportFacts.Dim;
import com.inventoryerp.sales.application.SalesReportKit.Bucket;
import com.inventoryerp.sales.application.SalesReportKit.Def;
import com.inventoryerp.sales.application.SalesReportKit.Filter;
import com.inventoryerp.sales.application.SalesReportKit.Report;
import com.inventoryerp.sales.application.SalesReportKit.Sql;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Targets, forecasts and planning reports of the report center (ADR-S057). Targets are the monthly sales targets of
 * Commissions & Targets (company, branch, team, salesperson or product-category scope); actuals come from
 * {@link SalesReportFacts}.
 */
@Component
class SalesPlanningReports implements SalesReportSet {

    static final String PLAN = "forecasting";

    private final SalesDb db;
    private final SalesReportFacts facts;
    private final SalesAnalyticsService analytics;

    SalesPlanningReports(SalesDb db, SalesReportFacts facts, SalesAnalyticsService analytics) {
        this.db = db;
        this.facts = facts;
        this.analytics = analytics;
    }

    @Override
    public List<Def> definitions() {
        List<Def> d = new ArrayList<>();
        List<String> tf = List.of("period", "branch", "salesperson", "category", "dimension");
        d.add(def("target-vs-actual", SalesRevenueReports.PERF, "Sales Target vs Actual",
                "Monthly targets, achieved sales, remaining target and achievement % by scope.", tf,
                f -> targets(f, f.dimension())));
        d.add(def("target-achievement", SalesRevenueReports.TEAM, "Sales Target Achievement",
                "Individual targets vs actual sales per representative.", List.of("period", "salesperson"),
                f -> targets(f, "salesperson")));
        d.add(def("sales-forecast", PLAN, "Sales Forecast",
                "Run rate, open orders, weighted quotations and agreement balances for the coming months.",
                List.of("limit"), this::forecast));
        d.add(def("monthly-projection", PLAN, "Monthly Sales Projection",
                "Actual sales to date and seasonally adjusted projection for the rest of the year against target.",
                List.of("branch", "salesperson", "category", "asOf"), this::projection));
        d.add(def("target-planning", PLAN, "Sales Target Planning", "Planned monthly targets by scope.",
                List.of("period", "branch", "salesperson", "category"), this::targetPlan));
        d.add(def("sales-growth", PLAN, "Sales Growth Analysis", "Net sales per month across years and the year-on-year"
                + " growth.", STD, this::growth));
        d.add(def("demand-trend", PLAN, "Demand Trend Report",
                "Monthly quantity per product with its trend (growth per month) over the period.", with(STD, "limit"),
                this::demandTrend));
        d.add(def("customer-demand-forecast", PLAN, "Customer Demand Forecast",
                "Expected next-month quantity per customer and product from the last six months.",
                with(STD, "limit", "asOf"), this::customerDemand));
        d.add(def("sales-seasonality", PLAN, "Sales Seasonality", "Average sales per calendar month and the seasonal"
                + " index (100 = average month).", with(STD, "measure"), this::seasonality));
        d.add(def("budget-vs-actual", PLAN, "Sales Budget vs Actual",
                "Monthly sales budget (company targets, else the sum of scoped targets) against actual.",
                List.of("period", "branch"), this::budget));
        return d;
    }

    // ------------------------------------------------------------------ targets

    List<Map<String, Object>> targetRows(Filter f) {
        Sql s = new Sql("SELECT t.*, to_char(t.month, 'YYYY-MM') AS ym FROM \"SalesTarget\" t WHERE t.\"organizationId\" = ?"
                + " AND t.month >= ? AND t.month <= ?", org(), f.from().withDayOfMonth(1), f.to());
        s.in("t.\"branchId\"", f.branchIds()).eq("t.\"salespersonId\"", f.salespersonId())
                .eq("t.\"productCategoryId\"", f.categoryId());
        s.add(" ORDER BY t.month");
        return db.rows(s.toString(), s.params);
    }

    /** Actual measures by (month, salesperson, team, branch, category). */
    List<Map<String, Object>> actualCube(Filter f) {
        Filter all = f.withPeriod(f.from().withDayOfMonth(1), f.to());
        Filter wide = new Filter(all.from(), all.to(), List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, all.asOf());
        return facts.aggregate(wide, Dim.MONTH, Dim.SALESPERSON, Dim.TEAM, Dim.BRANCH, Dim.CATEGORY);
    }

    Report targets(Filter f, String dimension) {
        List<Map<String, Object>> targets = targetRows(f);
        List<Map<String, Object>> cube = actualCube(f);
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<String, String> spNames = facts.names(Dim.SALESPERSON, targets.stream().map(t -> str(t.get("salespersonId")))
                .toList());
        Map<String, String> brNames = facts.names(Dim.BRANCH, targets.stream().map(t -> str(t.get("branchId"))).toList());
        Map<String, String> tmNames = facts.names(Dim.TEAM, targets.stream().map(t -> str(t.get("teamId"))).toList());
        Map<String, String> catNames = facts.names(Dim.CATEGORY, targets.stream().map(t -> str(t.get("productCategoryId")))
                .toList());
        for (Map<String, Object> t : targets) {
            String ym = str(t.get("ym"));
            BigDecimal rev = BigDecimal.ZERO;
            BigDecimal qty = BigDecimal.ZERO;
            for (Map<String, Object> c : cube) {
                if (ym.equals(str(c.get("k0"))) && match(t.get("salespersonId"), c.get("k1"))
                        && match(t.get("teamId"), c.get("k2")) && match(t.get("branchId"), c.get("k3"))
                        && match(t.get("productCategoryId"), c.get("k4"))) {
                    rev = rev.add(dec(c.get("revenue")));
                    qty = qty.add(dec(c.get("baseQty")));
                }
            }
            String scope = scopeLabel(t, spNames, brNames, tmNames, catNames);
            String key = "month".equals(dimension) ? ym : "salesperson".equals(dimension)
                    ? SalesReportFacts.fmt(spNames.get(str(t.get("salespersonId")))) : scope + " · " + ym;
            String label = "month".equals(dimension) ? ym : "salesperson".equals(dimension)
                    ? (t.get("salespersonId") == null ? "(unassigned)" : spNames.getOrDefault(str(t.get("salespersonId")),
                            "?")) : scope;
            Map<String, Object> g = groups.computeIfAbsent(key, k -> Obj.of("label", label, "month",
                    "month".equals(dimension) || "salesperson".equals(dimension) ? null : ym, "target", 0, "actual", 0,
                    "targetQty", 0, "actualQty", 0));
            g.put("target", money(dec(g.get("target")).add(dec(t.get("amount")))));
            g.put("actual", money(dec(g.get("actual")).add(rev)));
            g.put("targetQty", num(dec(g.get("targetQty")).add(dec(t.get("quantity")))));
            g.put("actualQty", num(dec(g.get("actualQty")).add(qty)));
        }
        List<Map<String, Object>> rows = new ArrayList<>(groups.values());
        for (Map<String, Object> r : rows) {
            BigDecimal tg = dec(r.get("target"));
            BigDecimal ac = dec(r.get("actual"));
            r.put("remaining", money(tg.subtract(ac).max(BigDecimal.ZERO)));
            r.put("achievement", pct(ac, tg));
            r.put("qtyAchievement", pct(dec(r.get("actualQty")), dec(r.get("targetQty"))));
            r.put("status", tg.signum() == 0 ? "no_target" : ac.compareTo(tg) >= 0 ? "achieved"
                    : ac.multiply(BigDecimal.valueOf(100)).compareTo(tg.multiply(BigDecimal.valueOf(80))) >= 0
                            ? "on_track" : "behind");
        }
        Report rep = table(rows, "label|Scope|text", "month|Month|text", "target|Target|money", "actual|Actual|money",
                "remaining|Remaining|money", "achievement|Achieved %|percent", "targetQty|Target qty|qty",
                "actualQty|Actual qty|qty", "qtyAchievement|Qty achieved %|percent", "status|Status|status")
                .sumTotals("target", "actual", "remaining");
        if ("month".equals(dimension) || "salesperson".equals(dimension)) {
            rep.columns.removeIf(c -> c.key().equals("month"));
        }
        rep.total("label", "Total");
        rep.total("achievement", pct(dec(rep.totals.get("actual")), dec(rep.totals.get("target"))));
        rep.kpi("target", "Target", rep.totals.get("target"), "money")
                .kpi("actual", "Achieved", rep.totals.get("actual"), "money")
                .kpi("achievement", "Achievement", rep.totals.get("achievement"), "percent")
                .kpi("remaining", "Remaining", rep.totals.get("remaining"), "money")
                .kpi("achieved", "Targets met", rows.stream().filter(r -> "achieved".equals(r.get("status"))).count(),
                        "count");
        rep.chart("bar", "label", bar("target", "Target"), bar("actual", "Actual"));
        if (targets.isEmpty()) {
            rep.note("No sales targets in this period — set them under Sales → Commissions & Targets.");
        }
        return rep;
    }

    private static boolean match(Object target, Object actual) {
        return target == null || String.valueOf(target).equals(String.valueOf(actual));
    }

    private static String scopeLabel(Map<String, Object> t, Map<String, String> sp, Map<String, String> br,
            Map<String, String> tm, Map<String, String> cat) {
        List<String> parts = new ArrayList<>();
        if (t.get("salespersonId") != null) {
            parts.add(sp.getOrDefault(str(t.get("salespersonId")), "?"));
        }
        if (t.get("teamId") != null) {
            parts.add("Team " + tm.getOrDefault(str(t.get("teamId")), "?"));
        }
        if (t.get("branchId") != null) {
            parts.add(br.getOrDefault(str(t.get("branchId")), "?"));
        }
        if (t.get("productCategoryId") != null) {
            parts.add(cat.getOrDefault(str(t.get("productCategoryId")), "?"));
        }
        return parts.isEmpty() ? "Company" : String.join(" · ", parts);
    }

    Report targetPlan(Filter f) {
        List<Map<String, Object>> targets = targetRows(f);
        Map<String, String> sp = facts.names(Dim.SALESPERSON, targets.stream().map(t -> str(t.get("salespersonId")))
                .toList());
        Map<String, String> br = facts.names(Dim.BRANCH, targets.stream().map(t -> str(t.get("branchId"))).toList());
        Map<String, String> tm = facts.names(Dim.TEAM, targets.stream().map(t -> str(t.get("teamId"))).toList());
        Map<String, String> cat = facts.names(Dim.CATEGORY, targets.stream().map(t -> str(t.get("productCategoryId")))
                .toList());
        List<String> months = Bucket.MONTH.keys(f.from(), f.to());
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> t : targets) {
            String scope = scopeLabel(t, sp, br, tm, cat);
            Map<String, Object> r = rows.computeIfAbsent(scope, k -> {
                Map<String, Object> n = Obj.of("label", k, "total", 0);
                months.forEach(m -> n.put("c_" + m, 0));
                return n;
            });
            String k = "c_" + t.get("ym");
            if (r.containsKey(k)) {
                r.put(k, money(dec(r.get(k)).add(dec(t.get("amount")))));
                r.put("total", money(dec(r.get("total")).add(dec(t.get("amount")))));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.values());
        Report rep = new Report().col("label", "Scope", "text");
        List<String> sums = new ArrayList<>();
        for (String m : months) {
            rep.col("c_" + m, m, "money");
            sums.add("c_" + m);
        }
        sums.add("total");
        rep.col("total", "Total", "money").rows(out).sumTotals(sums.toArray(String[]::new));
        rep.total("label", "Total");
        rep.kpi("total", "Planned sales", rep.totals.get("total"), "money").kpi("scopes", "Target lines", out.size(),
                "count");
        List<Map<String, Object>> data = new ArrayList<>();
        for (String m : months) {
            data.add(Obj.of("label", m, "value", rep.totals.get("c_" + m)));
        }
        rep.chart("bar", "label", bar("value", "Planned")).chartData(data);
        if (targets.isEmpty()) {
            rep.note("No sales targets in this period — set them under Sales → Commissions & Targets.");
        }
        return rep;
    }

    Report budget(Filter f) {
        List<Map<String, Object>> targets = targetRows(f);
        boolean company = targets.stream().anyMatch(t -> t.get("salespersonId") == null && t.get("teamId") == null
                && t.get("branchId") == null && t.get("productCategoryId") == null);
        Map<String, BigDecimal> budget = new HashMap<>();
        for (Map<String, Object> t : targets) {
            boolean isCompany = t.get("salespersonId") == null && t.get("teamId") == null && t.get("branchId") == null
                    && t.get("productCategoryId") == null;
            boolean spLevel = t.get("salespersonId") != null;
            if (company ? isCompany : spLevel) {
                budget.merge(str(t.get("ym")), dec(t.get("amount")), BigDecimal::add);
            }
        }
        Map<String, Map<String, Object>> act = new HashMap<>();
        facts.aggregate(f, Dim.MONTH).forEach(r -> act.put(str(r.get("k0")), r));
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal cumB = BigDecimal.ZERO;
        BigDecimal cumA = BigDecimal.ZERO;
        for (String m : Bucket.MONTH.keys(f.from(), f.to())) {
            BigDecimal b = budget.getOrDefault(m, BigDecimal.ZERO);
            BigDecimal a = dec(act.getOrDefault(m, Map.of()).get("revenue"));
            cumB = cumB.add(b);
            cumA = cumA.add(a);
            rows.add(Obj.of("label", m, "budget", money(b), "actual", money(a), "variance", money(a.subtract(b)),
                    "variancePercent", pct(a.subtract(b), b), "cumBudget", money(cumB), "cumActual", money(cumA)));
        }
        Report rep = table(rows, "label|Month|text", "budget|Budget|money", "actual|Actual|money",
                "variance|Variance|money", "variancePercent|Variance %|percent", "cumBudget|Budget YTD|money",
                "cumActual|Actual YTD|money").sumTotals("budget", "actual", "variance");
        rep.total("label", "Total");
        rep.total("variancePercent", pct(dec(rep.totals.get("variance")), dec(rep.totals.get("budget"))));
        rep.kpi("budget", "Budget", rep.totals.get("budget"), "money")
                .kpi("actual", "Actual", rep.totals.get("actual"), "money")
                .kpi("variance", "Variance", rep.totals.get("variance"), "money",
                        SalesReportFacts.fmt(rep.totals.get("variancePercent")) + "%")
                .kpi("achievement", "Budget achieved", pct(dec(rep.totals.get("actual")), dec(rep.totals.get("budget"))),
                        "percent");
        rep.chart("combo", "label", bar("budget", "Budget"), bar("actual", "Actual"), line("cumActual", "Actual YTD"));
        rep.note(company ? "Budget = company-level monthly sales targets." : targets.isEmpty()
                ? "No sales targets in this period — set them under Sales → Commissions & Targets."
                : "No company-level targets; budget = sum of salesperson targets.");
        return rep;
    }

    // ------------------------------------------------------------------ forecasts

    Report forecast(Filter f) {
        Map<String, Object> fc = analytics.forecast(f.limit(6));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> months = new ArrayList<>((List<Map<String, Object>>) fc.get("months"));
        months.forEach(m -> m.put("label", m.get("month")));
        Report rep = table(months, "label|Month|text", "runRate|Run rate|money", "openOrders|Open orders|money",
                "weightedQuotations|Weighted quotations|money", "agreements|Agreements|money",
                "expected|Expected sales|money").sumTotals("openOrders", "weightedQuotations", "agreements",
                        "expected");
        rep.total("label", "Total");
        rep.kpi("expected", "Expected sales", rep.totals.get("expected"), "money")
                .kpi("runRate", "Monthly run rate", months.isEmpty() ? null : months.getFirst().get("runRate"), "money")
                .kpi("orders", "Open order book", rep.totals.get("openOrders"), "money")
                .kpi("conversion", "Quotation conversion (90 d)", fc.get("conversionRate"), "percent");
        rep.stacked("bar", "label", bar("openOrders", "Open orders"), bar("weightedQuotations", "Weighted quotations"),
                bar("agreements", "Agreements"));
        rep.note("Expected = the larger of the run rate and the committed sources (orders + weighted quotations +"
                + " agreements).");
        return rep;
    }

    /** Seasonal index per calendar month (1..12) from the three years before {@code asOf}; 1 = average. */
    Map<Integer, BigDecimal> seasonalIndex(Filter f) {
        LocalDate start = f.asOf().withDayOfMonth(1).minusYears(3);
        Filter hist = f.withPeriod(start, f.asOf().withDayOfMonth(1).minusDays(1));
        Map<Integer, BigDecimal> sum = new HashMap<>();
        Map<Integer, Integer> n = new HashMap<>();
        for (Map<String, Object> r : facts.aggregate(hist, Dim.MONTH)) {
            int m = Integer.parseInt(str(r.get("k0")).substring(5, 7));
            sum.merge(m, dec(r.get("revenue")), BigDecimal::add);
            n.merge(m, 1, Integer::sum);
        }
        Map<Integer, BigDecimal> idx = new HashMap<>();
        if (sum.size() < 6) {
            for (int m = 1; m <= 12; m++) {
                idx.put(m, BigDecimal.ONE);
            }
            return idx;
        }
        BigDecimal overall = BigDecimal.ZERO;
        for (int m = 1; m <= 12; m++) {
            BigDecimal avg = n.containsKey(m) ? sum.get(m).divide(BigDecimal.valueOf(n.get(m)), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            idx.put(m, avg);
            overall = overall.add(avg);
        }
        BigDecimal mean = overall.divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
        for (int m = 1; m <= 12; m++) {
            idx.put(m, mean.signum() == 0 ? BigDecimal.ONE : idx.get(m).divide(mean, 4, RoundingMode.HALF_UP));
        }
        return idx;
    }

    Report projection(Filter f) {
        LocalDate asOf = f.asOf();
        LocalDate first = asOf.withDayOfYear(1);
        LocalDate thisMonth = asOf.withDayOfMonth(1);
        Filter year = f.withPeriod(first, asOf);
        Map<String, Map<String, Object>> act = new HashMap<>();
        facts.aggregate(year, Dim.MONTH).forEach(r -> act.put(str(r.get("k0")), r));
        BigDecimal last3 = BigDecimal.ZERO;
        Map<String, Object> rr = facts.totals(f.withPeriod(thisMonth.minusMonths(3), thisMonth.minusDays(1)));
        last3 = last3.add(dec(rr.get("revenue")));
        Map<Integer, BigDecimal> idx = seasonalIndex(f);
        BigDecimal idx3 = BigDecimal.ZERO;
        for (int i = 1; i <= 3; i++) {
            idx3 = idx3.add(idx.get(thisMonth.minusMonths(i).getMonthValue()));
        }
        BigDecimal base = idx3.signum() == 0 ? BigDecimal.ZERO : last3.divide(idx3, 2, RoundingMode.HALF_UP);
        Map<String, BigDecimal> target = new HashMap<>();
        for (Map<String, Object> t : targetRows(f.withPeriod(first, first.plusYears(1).minusDays(1)))) {
            if (t.get("salespersonId") == null && t.get("teamId") == null && t.get("productCategoryId") == null) {
                target.merge(str(t.get("ym")), dec(t.get("amount")), BigDecimal::add);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal cum = BigDecimal.ZERO;
        for (int m = 1; m <= 12; m++) {
            LocalDate d = first.withMonth(m);
            String k = d.toString().substring(0, 7);
            BigDecimal actual = dec(act.getOrDefault(k, Map.of()).get("revenue"));
            BigDecimal projected;
            String basis;
            if (d.isBefore(thisMonth)) {
                projected = actual;
                basis = "actual";
            } else if (d.equals(thisMonth)) {
                int days = asOf.lengthOfMonth();
                BigDecimal pace = actual.multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(asOf
                        .getDayOfMonth()), 2, RoundingMode.HALF_UP);
                projected = pace.max(base.multiply(idx.get(m)).setScale(2, RoundingMode.HALF_UP));
                basis = "month to date";
            } else {
                projected = base.multiply(idx.get(m)).setScale(2, RoundingMode.HALF_UP);
                basis = "projection";
            }
            cum = cum.add(projected);
            BigDecimal tg = target.getOrDefault(k, BigDecimal.ZERO);
            Map<String, Object> r = Obj.of("label", Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " "
                    + d.getYear(), "actual", d.isAfter(asOf) ? null : money(actual), "projected", money(projected),
                    "basis", basis, "target", money(tg), "gap", money(projected.subtract(tg)), "cumulative", money(cum));
            r.put("seasonalIndex", num(idx.get(m).multiply(BigDecimal.valueOf(100))));
            rows.add(r);
        }
        Report rep = table(rows, "label|Month|text", "actual|Actual|money", "projected|Actual / projected|money",
                "basis|Basis|status", "seasonalIndex|Season index|number", "target|Target|money",
                "gap|vs target|money", "cumulative|Cumulative|money").sumTotals("actual", "projected", "target",
                        "gap");
        rep.total("label", "Full year");
        rep.kpi("ytd", "Actual year to date", rep.totals.get("actual"), "money")
                .kpi("projected", "Projected full year", rep.totals.get("projected"), "money")
                .kpi("target", "Full-year target", rep.totals.get("target"), "money")
                .kpi("gap", "Projected vs target", pct(dec(rep.totals.get("projected")), dec(rep.totals.get("target"))),
                        "percent");
        rep.chart("combo", "label", bar("actual", "Actual"), line("projected", "Projected"), line("target", "Target"));
        rep.note("Projection = de-seasonalised average of the last three full months × each month's seasonal index"
                + " (three-year history; flat when history is short).");
        return rep;
    }

    Report growth(Filter f) {
        int lastYear = f.to().getYear();
        int firstYear = Math.min(f.from().getYear(), lastYear - 2);
        Filter wide = f.withPeriod(LocalDate.of(firstYear, 1, 1), f.to());
        Map<String, BigDecimal> by = new HashMap<>();
        facts.aggregate(wide, Dim.MONTH).forEach(r -> by.put(str(r.get("k0")), dec(r.get("revenue"))));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Integer, BigDecimal> yearTotals = new LinkedHashMap<>();
        for (int m = 1; m <= 12; m++) {
            Map<String, Object> r = Obj.of("label", Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            for (int y = firstYear; y <= lastYear; y++) {
                BigDecimal v = by.getOrDefault(String.format("%04d-%02d", y, m), BigDecimal.ZERO);
                r.put("y" + y, money(v));
                yearTotals.merge(y, v, BigDecimal::add);
            }
            BigDecimal cur = dec(r.get("y" + lastYear));
            BigDecimal prev = dec(r.get("y" + (lastYear - 1)));
            r.put("yoy", pct(cur.subtract(prev), prev.abs()));
            rows.add(r);
        }
        Report rep = new Report().col("label", "Month", "text");
        List<SalesReportKit.Series> series = new ArrayList<>();
        List<String> sums = new ArrayList<>();
        for (int y = firstYear; y <= lastYear; y++) {
            rep.col("y" + y, String.valueOf(y), "money");
            series.add(y == lastYear ? bar("y" + y, String.valueOf(y)) : line("y" + y, String.valueOf(y)));
            sums.add("y" + y);
        }
        rep.col("yoy", "YoY % (" + lastYear + ")", "percent").rows(rows).sumTotals(sums.toArray(String[]::new));
        rep.total("label", "Year");
        BigDecimal cur = yearTotals.getOrDefault(lastYear, BigDecimal.ZERO);
        BigDecimal prev = yearTotals.getOrDefault(lastYear - 1, BigDecimal.ZERO);
        rep.total("yoy", pct(cur.subtract(prev), prev.abs()));
        rep.kpi("current", lastYear + " sales", money(cur), "money").kpi("previous", (lastYear - 1) + " sales",
                money(prev), "money").kpi("growth", "Growth", pct(cur.subtract(prev), prev.abs()), "percent");
        BigDecimal first = yearTotals.getOrDefault(firstYear, BigDecimal.ZERO);
        int span = lastYear - firstYear;
        if (span > 0 && first.signum() > 0 && cur.signum() > 0) {
            double cagr = Math.pow(cur.doubleValue() / first.doubleValue(), 1.0 / span) - 1;
            rep.kpi("cagr", "CAGR " + firstYear + "–" + lastYear, num(BigDecimal.valueOf(cagr * 100).setScale(1,
                    RoundingMode.HALF_UP)), "percent");
        }
        rep.chart = new SalesReportKit.Chart("combo", "label", series, false);
        rep.note(lastYear + " runs to " + f.to() + "; compare like-for-like months.");
        return rep;
    }

    Report demandTrend(Filter f) {
        Report m = facts.matrix(f, Dim.PRODUCT, Dim.MONTH, "baseQty", 0, f.limit(20));
        List<String> months = Bucket.MONTH.keys(f.from(), f.to());
        for (Map<String, Object> r : m.rows) {
            double[] ys = new double[months.size()];
            for (int i = 0; i < months.size(); i++) {
                ys[i] = dec(r.get("c_" + months.get(i))).doubleValue();
            }
            double slope = slope(ys);
            double mean = java.util.Arrays.stream(ys).average().orElse(0);
            Object trend = mean == 0 ? null : num(BigDecimal.valueOf(slope / mean * 100).setScale(1,
                    RoundingMode.HALF_UP));
            r.put("trend", trend);
            r.put("direction", trend == null ? "flat" : dec(trend).compareTo(BigDecimal.valueOf(2)) > 0 ? "up"
                    : dec(trend).compareTo(BigDecimal.valueOf(-2)) < 0 ? "down" : "flat");
        }
        m.col("trend", "Trend % / month", "percent").col("direction", "Direction", "status");
        m.kpi("products", "Products", m.rows.size(), "count")
                .kpi("up", "Rising demand", m.rows.stream().filter(r -> "up".equals(r.get("direction"))).count(), "count")
                .kpi("down", "Falling demand", m.rows.stream().filter(r -> "down".equals(r.get("direction"))).count(),
                        "count");
        m.note("Quantities in each product's base unit; trend = least-squares slope ÷ average month.");
        return m;
    }

    /** Least-squares slope of evenly spaced points. */
    static double slope(double[] ys) {
        int n = ys.length;
        if (n < 2) {
            return 0;
        }
        double mx = (n - 1) / 2.0;
        double my = java.util.Arrays.stream(ys).average().orElse(0);
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (i - mx) * (ys[i] - my);
            den += (i - mx) * (i - mx);
        }
        return den == 0 ? 0 : num / den;
    }

    Report customerDemand(Filter f) {
        LocalDate end = f.asOf().withDayOfMonth(1).minusDays(1);
        LocalDate start = end.withDayOfMonth(1).minusMonths(5);
        Filter hist = f.withPeriod(start, end);
        List<String> months = Bucket.MONTH.keys(start, end);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> c : facts.aggregate(hist, Dim.CUSTOMER, Dim.PRODUCT, Dim.UOM, Dim.MONTH)) {
            String k = c.get("k0") + "|" + c.get("k1") + "|" + c.get("k2");
            Map<String, Object> r = rows.computeIfAbsent(k, x -> {
                Map<String, Object> n = Obj.of("customer", c.get("label"), "product", c.get("label1"), "unit",
                        c.get("label2"), "partnerId", c.get("k0"));
                months.forEach(m -> n.put("m_" + m, BigDecimal.ZERO));
                return n;
            });
            String mk = "m_" + c.get("k3");
            if (r.containsKey(mk)) {
                r.put(mk, dec(r.get(mk)).add(dec(c.get("quantity"))));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows.values()) {
            double[] ys = months.stream().mapToDouble(m -> dec(r.get("m_" + m)).doubleValue()).toArray();
            double avg = java.util.Arrays.stream(ys).average().orElse(0);
            double last3 = (ys[ys.length - 1] + ys[ys.length - 2] + ys[ys.length - 3]) / 3.0;
            double sl = slope(ys);
            double fc = Math.max(0, 0.6 * last3 + 0.4 * avg + sl);
            long active = java.util.Arrays.stream(ys).filter(v -> v > 0).count();
            Map<String, Object> o = Obj.of("customer", r.get("customer"), "product", r.get("product"), "unit",
                    r.get("unit"), "avg", num(BigDecimal.valueOf(avg)), "lastMonth", num(BigDecimal.valueOf(ys[ys.length
                            - 1])), "activeMonths", active, "trend", sl > 0.05 * Math.max(avg, 1e-9) ? "up"
                                    : sl < -0.05 * Math.max(avg, 1e-9) ? "down" : "flat", "forecast",
                    num(BigDecimal.valueOf(fc)));
            o.put("total", num(BigDecimal.valueOf(java.util.Arrays.stream(ys).sum())));
            SalesReportKit.drill(o, "customer-history", "partnerId", r.get("partnerId"));
            out.add(o);
        }
        out.sort(SalesReportKit.byDesc("forecast"));
        int limit = f.limit(200);
        if (out.size() > limit) {
            out = new ArrayList<>(out.subList(0, limit));
        }
        Report rep = table(out, "customer|Customer|text", "product|Product|text", "unit|Unit|text",
                "total|Last 6 months|qty", "avg|Monthly average|qty", "lastMonth|Last month|qty",
                "activeMonths|Months bought|count", "trend|Trend|status", "forecast|Next month forecast|qty");
        rep.kpi("pairs", "Customer × product", out.size(), "count")
                .kpi("rising", "Rising", out.stream().filter(r -> "up".equals(r.get("trend"))).count(), "count")
                .kpi("falling", "Falling", out.stream().filter(r -> "down".equals(r.get("trend"))).count(), "count");
        rep.chart("hbar", "customer", bar("forecast", "Next month"), bar("avg", "Monthly average"))
                .chartData(out.subList(0, Math.min(15, out.size())));
        rep.note("History " + start + " – " + end + ". Forecast = 60 % last-3-month average + 40 % six-month average +"
                + " trend.");
        return rep;
    }

    Report seasonality(Filter f) {
        String measure = "quantity".equals(f.measure()) ? "baseQty" : "revenue";
        LocalDate from = f.from().isAfter(f.to().minusYears(2)) ? f.to().withDayOfMonth(1).minusYears(3)
                : f.from().withDayOfMonth(1);
        Filter wide = f.withPeriod(from, f.to());
        Map<Integer, BigDecimal> sum = new HashMap<>();
        Map<Integer, Integer> n = new HashMap<>();
        for (Map<String, Object> r : SalesReportFacts.fill(facts.aggregate(wide, Dim.MONTH), Bucket.MONTH, from,
                f.to())) {
            int m = Integer.parseInt(str(r.get("k0")).substring(5, 7));
            sum.merge(m, dec(r.get(measure)), BigDecimal::add);
            n.merge(m, 1, Integer::sum);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int m = 1; m <= 12; m++) {
            BigDecimal avg = n.getOrDefault(m, 0) == 0 ? BigDecimal.ZERO : sum.get(m).divide(BigDecimal.valueOf(n.get(
                    m)), 2, RoundingMode.HALF_UP);
            total = total.add(avg);
            rows.add(Obj.of("label", Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH), "average", avg,
                    "years", n.getOrDefault(m, 0)));
        }
        BigDecimal mean = total.divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
        for (Map<String, Object> r : rows) {
            BigDecimal avg = (BigDecimal) r.get("average");
            r.put("average", "revenue".equals(measure) ? money(avg) : num(avg));
            r.put("index", mean.signum() == 0 ? null : num(avg.multiply(BigDecimal.valueOf(100)).divide(mean, 1,
                    RoundingMode.HALF_UP)));
            r.put("season", r.get("index") == null ? "flat" : dec(r.get("index")).compareTo(BigDecimal.valueOf(110)) > 0
                    ? "peak" : dec(r.get("index")).compareTo(BigDecimal.valueOf(90)) < 0 ? "low" : "normal");
        }
        String type = "revenue".equals(measure) ? "money" : "qty";
        Report rep = table(rows, "label|Month|text", "average|Average month|" + type, "years|Years of data|count",
                "index|Seasonal index|number", "season|Season|status");
        Map<String, Object> peak = rows.stream().max(java.util.Comparator.comparing(r -> dec(r.get("average"))))
                .orElse(Map.of());
        Map<String, Object> low = rows.stream().min(java.util.Comparator.comparing(r -> dec(r.get("average"))))
                .orElse(Map.of());
        rep.kpi("peak", "Peak month", peak.get("index"), "number", str(peak.get("label")))
                .kpi("low", "Lowest month", low.get("index"), "number", str(low.get("label")))
                .kpi("avg", "Average month", "revenue".equals(measure) ? money(mean) : num(mean), type);
        rep.chart("combo", "label", area("average", "Average month"), line("index", "Seasonal index"));
        rep.note("Based on " + from + " – " + f.to() + "; index 100 = an average month.");
        return rep;
    }
}
