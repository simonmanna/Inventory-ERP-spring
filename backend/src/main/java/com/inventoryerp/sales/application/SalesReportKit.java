package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Building blocks of the sales report center (ADR-S057): the shared filter, the report result (columns, rows, totals,
 * KPI tiles, chart hint), report definitions and a small SQL builder. Every report returns the same shape so one web
 * viewer renders, charts, exports and prints them all.
 */
public final class SalesReportKit {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SalesReportKit() {}

    // ------------------------------------------------------------------ result model

    /**
     * @param type {@code text}, {@code date}, {@code money}, {@code number}, {@code qty}, {@code percent},
     *     {@code days}, {@code status} or {@code count}
     */
    public record Column(String key, String label, String type) {}

    /** A KPI tile above the report. {@code type} as for {@link Column}. */
    public record Kpi(String key, String label, Object value, String type, String hint) {}

    /** @param kind {@code bar}, {@code line} or {@code area} (composed charts mix them) */
    public record Series(String key, String label, String kind) {}

    /**
     * Chart hint for the viewer's graphical mode.
     *
     * @param kind {@code bar}, {@code hbar}, {@code line}, {@code area}, {@code pie}, {@code combo} or {@code none}
     * @param x row key on the category axis
     */
    public record Chart(String kind, String x, List<Series> series, boolean stacked) {}

    /**
     * One report of the catalog.
     *
     * @param filters filter controls the viewer shows ({@code period}, {@code branch}, {@code salesperson},
     *     {@code customer}, {@code product}, {@code category}, {@code customerCategory}, {@code channel},
     *     {@code status}, {@code groupBy}, {@code dimension}, {@code compare}, {@code threshold}, {@code days},
     *     {@code limit}, {@code measure}, {@code asOf})
     */
    public record Def(String key, String category, String title, String description, String permission,
            List<String> filters, Function<Filter, Report> run) {

        Map<String, Object> describe() {
            return Obj.of("key", key, "category", category, "title", title, "description", description, "permission",
                    permission, "filters", filters);
        }
    }

    /** Mutable result; {@link #toMap()} is the wire shape. */
    public static final class Report {
        final List<Column> columns = new ArrayList<>();
        final List<Map<String, Object>> rows = new ArrayList<>();
        final Map<String, Object> totals = new LinkedHashMap<>();
        final List<Kpi> kpis = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        Chart chart = new Chart("none", null, List.of(), false);
        /** Chart points when they differ from the table rows (pivots, status splits); null = the rows. */
        List<Map<String, Object>> chartData;

        Report col(String key, String label, String type) {
            columns.add(new Column(key, label, type));
            return this;
        }

        Report kpi(String key, String label, Object value, String type) {
            kpis.add(new Kpi(key, label, value, type, null));
            return this;
        }

        Report kpi(String key, String label, Object value, String type, String hint) {
            kpis.add(new Kpi(key, label, value, type, hint));
            return this;
        }

        Report chart(String kind, String x, Series... series) {
            chart = new Chart(kind, x, List.of(series), false);
            return this;
        }

        Report stacked(String kind, String x, Series... series) {
            chart = new Chart(kind, x, List.of(series), true);
            return this;
        }

        Report note(String n) {
            notes.add(n);
            return this;
        }

        Report chartData(List<Map<String, Object>> data) {
            chartData = data;
            return this;
        }

        Report rows(List<Map<String, Object>> r) {
            rows.addAll(r);
            return this;
        }

        Report total(String key, Object value) {
            totals.put(key, value);
            return this;
        }

        /** Totals of the money / number / qty / count columns (sum of rows). */
        Report sumTotals(String... keys) {
            for (String k : keys) {
                totals.put(k, num(rows.stream().map(r -> dec(r.get(k))).reduce(BigDecimal.ZERO, BigDecimal::add)));
            }
            return this;
        }

        /** SG-1: drop cost and margin from the table, totals, KPIs and chart. */
        Report stripCost() {
            columns.removeIf(c -> SalesAccess.isCostField(c.key()));
            kpis.removeIf(c -> SalesAccess.isCostField(c.key()));
            rows.forEach(r -> r.keySet().removeIf(SalesAccess::isCostField));
            totals.keySet().removeIf(SalesAccess::isCostField);
            if (chartData != null) {
                chartData.forEach(r -> r.keySet().removeIf(SalesAccess::isCostField));
            }
            chart = new Chart(chart.kind(), chart.x(), chart.series().stream()
                    .filter(s -> !SalesAccess.isCostField(s.key())).toList(), chart.stacked());
            return this;
        }

        Map<String, Object> toMap(Def def, Filter f) {
            Map<String, Object> m = Obj.of("key", def.key(), "category", def.category(), "title", def.title(),
                    "description", def.description(), "from", f.from(), "to", f.to(), "columns", columns, "rows", rows,
                    "totals", totals, "kpis", kpis, "chart", chart);
            m.put("notes", notes);
            m.put("chartData", chartData);
            m.put("filters", def.filters());
            m.put("generatedAt", java.time.Instant.now());
            return m;
        }
    }

    /** Report with {@code rows} and columns given as {@code "key|Label|type"}. */
    static Report table(List<Map<String, Object>> rows, String... spec) {
        Report r = new Report();
        for (String c : spec) {
            String[] p = c.split("[|]");
            r.col(p[0], p[1], p.length > 2 ? p[2] : "text");
        }
        return r.rows(rows);
    }

    /** Rows grouped by {@code key}: count plus the sums of {@code sums}, keyed {@code label}. */
    static List<Map<String, Object>> group(List<Map<String, Object>> rows, Function<Map<String, Object>, Object> key,
            String... sums) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object k = key.apply(r);
            Map<String, Object> g = out.computeIfAbsent(String.valueOf(k), x -> {
                Map<String, Object> n = Obj.of("label", k == null ? "(none)" : k, "count", 0);
                for (String s : sums) {
                    n.put(s, 0);
                }
                return n;
            });
            g.put("count", ((Number) g.get("count")).intValue() + 1);
            for (String s : sums) {
                g.put(s, num(dec(g.get(s)).add(dec(r.get(s)))));
            }
        }
        return new ArrayList<>(out.values());
    }

    static Series bar(String key, String label) {
        return new Series(key, label, "bar");
    }

    static Series line(String key, String label) {
        return new Series(key, label, "line");
    }

    static Series area(String key, String label) {
        return new Series(key, label, "area");
    }

    // ------------------------------------------------------------------ filter

    /** Time buckets of the {@code groupBy} filter. */
    enum Bucket {
        DAY, WEEK, MONTH, QUARTER, YEAR;

        static Bucket of(String v, Bucket fallback) {
            if (blank(v)) {
                return fallback;
            }
            try {
                return valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("groupBy must be one of: day, week, month, quarter, year");
            }
        }

        /** SQL key of a date expression. */
        String sql(String date) {
            return switch (this) {
                case DAY -> "to_char(" + date + ", 'YYYY-MM-DD')";
                case WEEK -> "to_char(date_trunc('week', " + date + ")::date, 'YYYY-MM-DD')";
                case MONTH -> "to_char(" + date + ", 'YYYY-MM')";
                case QUARTER -> "to_char(" + date + ", 'YYYY-\"Q\"Q')";
                case YEAR -> "to_char(" + date + ", 'YYYY')";
            };
        }

        String key(LocalDate d) {
            return switch (this) {
                case DAY -> d.toString();
                case WEEK -> d.with(DayOfWeek.MONDAY).toString();
                case MONTH -> d.toString().substring(0, 7);
                case QUARTER -> d.getYear() + "-Q" + d.get(IsoFields.QUARTER_OF_YEAR);
                case YEAR -> String.valueOf(d.getYear());
            };
        }

        LocalDate next(LocalDate d) {
            return switch (this) {
                case DAY -> d.plusDays(1);
                case WEEK -> d.plusWeeks(1);
                case MONTH -> d.plusMonths(1);
                case QUARTER -> d.plusMonths(3);
                case YEAR -> d.plusYears(1);
            };
        }

        /** Bucket keys covering {@code [from, to]} in order (at most 800). */
        List<String> keys(LocalDate from, LocalDate to) {
            List<String> out = new ArrayList<>();
            LocalDate d = switch (this) {
                case WEEK -> from.with(DayOfWeek.MONDAY);
                case MONTH -> from.withDayOfMonth(1);
                case QUARTER -> from.withDayOfMonth(1).withMonth(((from.getMonthValue() - 1) / 3) * 3 + 1);
                case YEAR -> from.withDayOfYear(1);
                default -> from;
            };
            while (!d.isAfter(to) && out.size() < 800) {
                String k = key(d);
                if (!out.contains(k)) {
                    out.add(k);
                }
                d = next(d);
            }
            return out;
        }

        String label(String key) {
            return this == WEEK ? "Wk " + key : key;
        }
    }

    /**
     * The shared report filter. Unset fields mean "no filter". Default period: the current year to date.
     *
     * @param branchIds branch filter (comma separated on the wire)
     * @param threshold numeric parameter (discount %, margin %, amount)
     * @param days day parameter (inactivity, follow-up, due soon)
     * @param limit top-N
     */
    public record Filter(LocalDate from, LocalDate to, List<String> branchIds, String salespersonId, String partnerId,
            String productId, String categoryId, String customerCategoryId, String channel, String status,
            String groupBy, String dimension, String compare, BigDecimal threshold, Integer days, Integer limit,
            String measure, LocalDate asOf) {

        static final List<String> KEYS = List.of("from", "to", "branchId", "salespersonId", "partnerId", "productId",
                "categoryId", "customerCategoryId", "channel", "status", "groupBy", "dimension", "compare",
                "threshold", "days", "limit", "measure", "asOf", "format");

        static Filter parse(Map<String, String> q) {
            Map<String, String> p = q == null ? Map.of() : q;
            for (String k : p.keySet()) {
                if (!KEYS.contains(k)) {
                    throw ApiException.badRequest("Unknown report parameter: " + k);
                }
            }
            LocalDate to = p.get("to") == null ? LocalDate.now() : SalesDb.date(p.get("to"));
            LocalDate from = p.get("from") == null ? to.withDayOfYear(1) : SalesDb.date(p.get("from"));
            if (from.isAfter(to)) {
                throw ApiException.badRequest("from must be on or before to");
            }
            if (from.plusYears(10).isBefore(to)) {
                throw ApiException.badRequest("The report period cannot exceed 10 years");
            }
            List<String> branches = blank(p.get("branchId")) ? List.of()
                    : Arrays.stream(p.get("branchId").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            BigDecimal threshold;
            try {
                threshold = blank(p.get("threshold")) ? null : new BigDecimal(p.get("threshold").trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("threshold must be a number");
            }
            Integer days = blank(p.get("days")) ? null : SalesDb.parseInt(p.get("days"), 30);
            Integer limit = blank(p.get("limit")) ? null : Math.max(1, Math.min(500, SalesDb.parseInt(p.get("limit"),
                    10)));
            LocalDate asOf = blank(p.get("asOf")) ? to : SalesDb.date(p.get("asOf"));
            return new Filter(from, to, branches, nz(p.get("salespersonId")), nz(p.get("partnerId")),
                    nz(p.get("productId")), nz(p.get("categoryId")), nz(p.get("customerCategoryId")),
                    nz(p.get("channel")), nz(p.get("status")), nz(p.get("groupBy")), nz(p.get("dimension")),
                    nz(p.get("compare")), threshold, days, limit, nz(p.get("measure")), asOf);
        }

        private static String nz(String v) {
            return blank(v) ? null : v.trim();
        }

        /** The same filter limited to the caller's branch scope. */
        Filter inScope(java.util.Set<String> allowed) {
            List<String> wanted = branchIds.isEmpty() ? List.copyOf(allowed)
                    : branchIds.stream().filter(allowed::contains).toList();
            // An empty list means "all branches" to the query builders, so an empty scope must match nothing.
            return new Filter(from, to, wanted.isEmpty() ? List.of("-") : wanted, salespersonId, partnerId, productId,
                    categoryId, customerCategoryId, channel, status, groupBy, dimension, compare, threshold, days, limit,
                    measure, asOf);
        }

        Filter withPeriod(LocalDate f, LocalDate t) {
            return new Filter(f, t, branchIds, salespersonId, partnerId, productId, categoryId, customerCategoryId,
                    channel, status, groupBy, dimension, compare, threshold, days, limit, measure, asOf);
        }

        /** Same length period immediately before, or the same dates a year earlier ({@code compare=previous_year}). */
        Filter previous() {
            if ("previous_year".equals(compare)) {
                return withPeriod(from.minusYears(1), to.minusYears(1));
            }
            long len = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
            return withPeriod(from.minusDays(len), from.minusDays(1));
        }

        int days(int fallback) {
            return days == null ? fallback : days;
        }

        int limit(int fallback) {
            return limit == null ? fallback : limit;
        }

        BigDecimal threshold(String fallback) {
            return threshold == null ? new BigDecimal(fallback) : threshold;
        }
    }

    // ------------------------------------------------------------------ SQL

    /** SQL text and its positional parameters. */
    static final class Sql {
        final StringBuilder text = new StringBuilder();
        final List<Object> params = new ArrayList<>();

        Sql(String start, Object... p) {
            text.append(start);
            params.addAll(Arrays.asList(p));
        }

        Sql add(String part, Object... p) {
            text.append(part);
            params.addAll(Arrays.asList(p));
            return this;
        }

        Sql in(String expr, List<String> values) {
            if (!values.isEmpty()) {
                text.append(" AND ").append(expr).append(" IN (").append(SalesDb.marks(values.size())).append(')');
                params.addAll(values);
            }
            return this;
        }

        Sql eq(String expr, Object value) {
            if (value != null && expr != null) {
                text.append(" AND ").append(expr).append(" = ?");
                params.add(value);
            }
            return this;
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /**
     * Column expressions of a document for the shared filter; {@code null} skips that filter.
     *
     * @param product SQL with one {@code ?} that is true when the document has that product
     * @param category same for a product category
     */
    record Scope(String date, String branch, String salesperson, String partner, String product, String category,
            String customerCategory) {}

    /** Appends the shared filter for {@code s}; the period applies when {@code withPeriod}. */
    static void apply(Sql sql, Filter f, Scope s, boolean withPeriod) {
        if (withPeriod && s.date() != null) {
            sql.add(" AND " + s.date() + " >= ? AND " + s.date() + " <= ?", f.from(), f.to());
        }
        if (s.branch() != null) {
            sql.in(s.branch(), f.branchIds());
        }
        sql.eq(s.salesperson(), f.salespersonId());
        sql.eq(s.partner(), f.partnerId());
        if (s.product() != null && f.productId() != null) {
            sql.add(" AND " + s.product(), f.productId());
        }
        if (s.category() != null && f.categoryId() != null) {
            sql.add(" AND " + s.category(), f.categoryId());
        }
        if (s.customerCategory() != null && f.customerCategoryId() != null) {
            sql.add(" AND " + s.customerCategory() + " = ?", f.customerCategoryId());
        }
    }

    // ------------------------------------------------------------------ numbers

    static Object money(BigDecimal v) {
        return JsNumbers.js((v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP));
    }

    static Object num(BigDecimal v) {
        return JsNumbers.js((v == null ? BigDecimal.ZERO : v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    /** {@code part / whole × 100} with one decimal, or null when {@code whole} is zero. */
    static Object pct(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null
                : JsNumbers.js(part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP));
    }

    static BigDecimal ratio(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? BigDecimal.ZERO : part.divide(whole, 6, RoundingMode.HALF_UP);
    }

    static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(r -> dec(r.get(key))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static Comparator<Map<String, Object>> byDesc(String key) {
        return Comparator.comparing((Map<String, Object> r) -> dec(r.get(key))).reversed();
    }

    static Comparator<Map<String, Object>> byAsc(String key) {
        return Comparator.comparing((Map<String, Object> r) -> dec(r.get(key)));
    }

    /** Sets {@code _route} (drill-through to a document screen) on a row. */
    static Map<String, Object> route(Map<String, Object> row, String route) {
        row.put("_route", route);
        return row;
    }

    /** Sets {@code _drill} (drill-down into another report with extra filters) on a row. */
    static Map<String, Object> drill(Map<String, Object> row, String report, Object... params) {
        row.put("_drill", Obj.of("report", report, "params", Obj.of(params)));
        return row;
    }

    static String label(String s) {
        return s == null ? "—" : s.replace('_', ' ');
    }

    /** Whole days from {@code a} to {@code b} (dates or timestamps), or null. */
    static Long daysBetween(Object a, Object b) {
        LocalDate x = asDate(a);
        LocalDate y = asDate(b);
        return x == null || y == null ? null : java.time.temporal.ChronoUnit.DAYS.between(x, y);
    }

    static LocalDate asDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.time.Instant i) {
            return i.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        return SalesDb.date(v.toString());
    }
}
