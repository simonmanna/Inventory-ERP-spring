package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpCommands.InsightsQuery;
import com.inventoryerp.lossprevention.application.LpCommands.ReportQuery;
import com.inventoryerp.lossprevention.application.LpReportService.Period;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operations insights a reader sees (LP6a, ADR-S077 §10): read from the summary rows, never from the ledger. A
 * ratio whose denominator is zero is {@code null} ("no data"), not zero. Figures about people are per role unless the
 * caller may investigate.
 */
@Service
public class LpInsightsReportService {

    public static final String NOTE = "Figures are computed from the recorded events in the loss-prevention ledger and"
            + " describe how operations ran. Figures about staff describe recorded activity; they are not a judgement"
            + " of anyone's performance or conduct. Days with new events are computed again within a few minutes.";

    /** Said with every camera figure (LP6b). */
    public static final String CAMERA_NOTE = "Camera figures count people and tables; they never identify anyone. How"
            + " accurately a camera counts has not been measured at this site, so read them as estimates.";

    static final String GROUP_ROLE = "role";
    static final String GROUP_PERSON = "person";

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final LpRuleService rules;

    public LpInsightsReportService(JdbcClient jdbc, LpScope scope, LpRuleService rules) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.rules = rules;
    }

    // ------------------------------------------------------------------ helpers

    /** Aggregated figures of one metric. */
    record Figure(long count, BigDecimal total, BigDecimal worstP90) {

        static final Figure NONE = new Figure(0, BigDecimal.ZERO, null);

        /** Two figures summed; the worse of their 90th percentiles. */
        Figure plus(Figure o) {
            BigDecimal p90 = worstP90 == null ? o.worstP90 : o.worstP90 == null ? worstP90 : worstP90.max(o.worstP90);
            return new Figure(count + o.count, total.add(o.total), p90);
        }
    }

    private static Period period(InsightsQuery q) {
        return LpReportService.period(new ReportQuery(q.from(), q.to(), q.branchId()));
    }

    /** {@code WHERE} over {@code LpMetricValue v} for a period and an optional branch, within the caller's scope. */
    private String where(Period p, String branchId, List<Object> params) {
        scope.assertBranch(branchId);
        StringBuilder w = new StringBuilder(scope.where("v", params))
                .append(" AND v.\"businessDate\" >= ? AND v.\"businessDate\" <= ?");
        params.add(java.sql.Date.valueOf(p.from()));
        params.add(java.sql.Date.valueOf(p.to()));
        if (LpScope.has(branchId)) {
            w.append(" AND v.\"branchId\" = ?");
            params.add(branchId);
        }
        return w.toString();
    }

    /** Metric → figure, for the rows of one hour (-1: the whole day) and dimension, summed over the period. */
    private Map<String, Figure> figures(Period p, String branchId, String extra) {
        List<Object> params = new ArrayList<>();
        String w = where(p, branchId, params) + extra;
        Map<String, Figure> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT v.metric, sum(v.count) AS n, sum(v.total) AS"
                + " total, max(v.p90) AS p90 FROM \"LpMetricValue\" v" + w + " GROUP BY v.metric").params(params))) {
            out.put((String) r.get("metric"), figure(r));
        }
        return out;
    }

    private static Figure figure(Map<String, Object> r) {
        return new Figure(((Number) r.get("n")).longValue(), LpNumbers.zeroIfNull(LpNumbers.decimal(r.get("total"))),
                LpNumbers.decimal(r.get("p90")));
    }

    private static Figure get(Map<String, Figure> m, String metric) {
        return m.getOrDefault(metric, Figure.NONE);
    }

    /** a ÷ b at the given scale, or {@code null} when b is zero. */
    static BigDecimal ratio(BigDecimal a, BigDecimal b, int scale) {
        return b == null || b.signum() == 0 ? null : a.divide(b, scale, RoundingMode.HALF_UP);
    }

    static BigDecimal ratio(long a, long b, int scale) {
        return ratio(BigDecimal.valueOf(a), BigDecimal.valueOf(b), scale);
    }

    private static BigDecimal average(Figure f, int scale) {
        return ratio(f.total(), BigDecimal.valueOf(f.count()), scale);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ the eight metrics

    private Map<String, Object> kitchen(Map<String, Figure> m) {
        Figure tickets = get(m, "kitchen_tickets");
        Figure recalls = get(m, "kitchen_recalls");
        Figure prep = get(m, "kitchen_prep");
        Figure serve = get(m, "kitchen_serve");
        return Obj.of("tickets", tickets.count(), "prepared", prep.count(), "averagePrepSeconds", average(prep, 1),
                "worstDayP90PrepSeconds", prep.worstP90(), "dispatched", serve.count(), "averageServeSeconds",
                average(serve, 1), "worstDayP90ServeSeconds", serve.worstP90(), "recalled", recalls.count(),
                "recallRate", ratio(recalls.count(), tickets.count(), 4));
    }

    private static Map<String, Object> sales(Map<String, Figure> m) {
        Figure sales = get(m, "sales");
        Figure items = get(m, "sale_items");
        Figure discount = get(m, "sale_discount");
        Figure voids = get(m, "voids");
        Figure refunds = get(m, "refunds");
        return Obj.of("count", sales.count(), "total", money(sales.total()), "averageTicket", average(sales, 2),
                "items", items.total(), "itemsPerSale", average(items, 2),
                "voids", Obj.of("count", voids.count(), "amount", money(voids.total()), "per100Sales",
                        ratio(voids.count() * 100, sales.count(), 2), "amountRate",
                        ratio(voids.total(), sales.total(), 4)),
                "refunds", Obj.of("count", refunds.count(), "amount", money(refunds.total()), "per100Sales",
                        ratio(refunds.count() * 100, sales.count(), 2), "amountRate",
                        ratio(refunds.total(), sales.total(), 4)),
                "discounts", Obj.of("sales", discount.count(), "amount", money(discount.total()), "shareOfSales",
                        ratio(discount.count(), sales.count(), 4), "rate",
                        ratio(discount.total(), sales.total().add(discount.total()), 4)));
    }

    private static Map<String, Object> tables(Map<String, Figure> m) {
        Figure orders = get(m, "table_orders");
        Figure used = get(m, "tables_used");
        return Obj.of("orders", orders.count(), "total", money(orders.total()), "tableDays", used.count(),
                "turnsPerTablePerDay", ratio(orders.count(), used.count(), 2), "averagePerOrder", average(orders, 2));
    }

    private static Map<String, Object> pour(Map<String, Figure> m) {
        Figure sold = get(m, "pour_sold_ml");
        Figure consumed = get(m, "pour_consumed_ml");
        Figure cost = get(m, "pour_consumed_cost");
        return Obj.of("lines", get(m, "pour_lines").count(), "measuredLines", consumed.count(), "soldMl",
                sold.total(), "consumedMl", consumed.total(), "yield", ratio(sold.total(), consumed.total(), 4),
                "consumedCost", cost.count() == 0 ? null : money(cost.total()), "linesWithCost", cost.count());
    }

    /** The cash tolerance of rule C08 (whether or not the rule is on), or {@code null} when none is set. */
    private BigDecimal cashTolerance() {
        LpEngine.RuleConfig c08 = rules.effective().get("C08");
        return c08 == null ? null : c08.num("amount");
    }

    private Map<String, Object> cash(Period p, String branchId, BigDecimal tolerance) {
        List<Object> params = new ArrayList<>();
        String w = where(p, branchId, params) + " AND v.metric = 'cash_session' AND v.dimension = 'session'";
        String within = "NULL::bigint";
        if (tolerance != null) {
            within = "count(*) FILTER (WHERE abs(v.total) <= ?)";
            params.addFirst(tolerance);
        }
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT " + within + " AS within, count(*) AS sessions,"
                + " count(*) FILTER (WHERE v.total = 0) AS balanced, count(*) FILTER (WHERE v.total < 0) AS short,"
                + " count(*) FILTER (WHERE v.total > 0) AS over, coalesce(sum(v.total) FILTER (WHERE v.total < 0), 0)"
                + " AS \"shortAmount\", coalesce(sum(v.total) FILTER (WHERE v.total > 0), 0) AS \"overAmount\" FROM"
                + " \"LpMetricValue\" v" + w).params(params));
        long sessions = ((Number) r.get("sessions")).longValue();
        Long inside = r.get("within") == null ? null : ((Number) r.get("within")).longValue();
        return Obj.of("sessions", sessions, "balanced", r.get("balanced"), "short", r.get("short"), "over",
                r.get("over"), "shortAmount", money(LpNumbers.decimal(r.get("shortAmount"))), "overAmount",
                money(LpNumbers.decimal(r.get("overAmount"))), "tolerance", tolerance, "withinTolerance", inside,
                "withinToleranceRate", inside == null ? null : ratio(inside, sessions, 4),
                "balancedRate", ratio(((Number) r.get("balanced")).longValue(), sessions, 4));
    }

    private Map<String, Object> metrics(Period p, String branchId, BigDecimal tolerance) {
        Map<String, Figure> m = figures(p, branchId, " AND v.hour = -1 AND v.dimension = ''");
        long sales = salesWithFootfall(p, branchId).getOrDefault(new DayHour(null, -1), 0L);
        return Obj.of("kitchen", kitchen(m), "sales", sales(m), "tables", tables(m), "cash",
                cash(p, branchId, tolerance), "pour", pour(m), "camera", camera(m, sales));
    }

    // ------------------------------------------------------------------ LP6b: camera figures

    /** Footfall, queues and table occupancy from one set of figures; {@code sales}: sales on days with footfall. */
    private static Map<String, Object> camera(Map<String, Figure> m, long sales) {
        Figure in = get(m, "footfall_in");
        Figure wait = get(m, "queue_wait");
        Figure order = get(m, "table_wait_order");
        return Obj.of("footfall", Obj.of("in", in.count(), "out", get(m, "footfall_out").count(), "sales", sales,
                        "conversionRate", ratio(sales, in.count(), 4)),
                "queues", Obj.of("waits", wait.count(), "averageWaitSeconds", average(wait, 1),
                        "worstDayP90WaitSeconds", wait.worstP90(), "averageLength",
                        ratio(get(m, "queue_length").total(), get(m, "queue_seconds").total(), 2)),
                "tables", Obj.of("occupancyRate", ratio(get(m, "table_occupied").total(),
                                get(m, "table_seconds").total(), 4), "seatings", get(m, "table_seatings").count(),
                        "ordered", order.count(), "averageWaitToOrderSeconds", average(order, 1),
                        "worstDayP90WaitToOrderSeconds", order.worstP90()));
    }

    /** A day and an hour; a {@code null} day is every day, hour -1 the whole day. */
    record DayHour(LocalDate day, int hour) {}

    /**
     * Sales counted only on the days the same branch counted footfall, so conversion compares like with like: per day
     * and hour, per day ({@code hour} -1), per hour over all days ({@code day} null) and in all.
     */
    private Map<DayHour, Long> salesWithFootfall(Period p, String branchId) {
        List<Object> params = new ArrayList<>();
        String w = where(p, branchId, params) + " AND v.metric = 'sales' AND v.dimension = '' AND EXISTS (SELECT 1"
                + " FROM \"LpMetricValue\" f WHERE f.\"organizationId\" = v.\"organizationId\" AND f.\"branchId\" ="
                + " v.\"branchId\" AND f.\"businessDate\" = v.\"businessDate\" AND f.metric = 'footfall_in' AND f.hour"
                + " = -1 AND f.dimension = '' AND f.count > 0)";
        Map<DayHour, Long> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT v.\"businessDate\" AS day, v.hour, sum(v.count) AS"
                + " n FROM \"LpMetricValue\" v" + w + " GROUP BY v.\"businessDate\", v.hour").params(params))) {
            LocalDate day = (LocalDate) r.get("day");
            int hour = ((Number) r.get("hour")).intValue();
            long n = ((Number) r.get("n")).longValue();
            out.merge(new DayHour(day, hour), n, Long::sum);
            out.merge(new DayHour(null, hour), n, Long::sum);
        }
        return out;
    }

    /** Metric → figure per day and hour (dimension {@code ''}), for the given metrics. */
    private Map<DayHour, Map<String, Figure>> byDayHour(Period p, String branchId, List<String> metrics) {
        List<Object> params = new ArrayList<>();
        String w = where(p, branchId, params) + " AND v.dimension = '' AND v.metric = ANY(?::text[])";
        params.add(metrics.toArray(String[]::new));
        Map<DayHour, Map<String, Figure>> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT v.\"businessDate\" AS day, v.hour, v.metric,"
                + " sum(v.count) AS n, sum(v.total) AS total, max(v.p90) AS p90 FROM \"LpMetricValue\" v" + w
                + " GROUP BY v.\"businessDate\", v.hour, v.metric").params(params))) {
            LocalDate day = (LocalDate) r.get("day");
            int hour = ((Number) r.get("hour")).intValue();
            Figure f = figure(r);
            out.computeIfAbsent(new DayHour(day, hour), k -> new HashMap<>()).put((String) r.get("metric"), f);
            out.computeIfAbsent(new DayHour(null, hour), k -> new HashMap<>()).merge((String) r.get("metric"), f,
                    Figure::plus);
        }
        return out;
    }

    /** The zones of the caller's organization among {@code ids}: name, type, link and camera name. */
    private Map<String, Map<String, Object>> zones(java.util.Collection<String> ids) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT z.id, z.name, z.\"zoneType\", z.\"linkedType\","
                + " z.\"linkedId\", c.name AS \"cameraName\" FROM \"LpZone\" z LEFT JOIN \"LpCamera\" c ON c.id ="
                + " z.\"cameraId\" WHERE z.\"organizationId\" = ? AND z.id = ANY(?::text[])").params(LpScope.org(),
                ids.toArray(String[]::new)))) {
            out.put((String) r.get("id"), r);
        }
        return out;
    }

    private static Object zoneField(Map<String, Map<String, Object>> zones, String id, String field) {
        Map<String, Object> z = zones.get(id);
        return z == null ? null : z.get(field);
    }

    /** Figures per zone over the period, for zones that have any of {@code metrics}. */
    private Map<String, Map<String, Figure>> perZone(Period p, String branchId, List<String> metrics) {
        Map<String, Map<String, Figure>> figures = new LinkedHashMap<>();
        byKey(p, branchId, "zone", "v.\"dimensionKey\"", figures);
        figures.values().removeIf(m -> metrics.stream().noneMatch(m::containsKey));
        return figures;
    }

    /** People in and out per day and hour, sales on the days with footfall, and per entrance (LP6b). */
    @Transactional(readOnly = true)
    public Map<String, Object> footfall(InsightsQuery q) {
        Period p = period(q);
        Map<DayHour, Map<String, Figure>> f = byDayHour(p, q.branchId(), List.of("footfall_in", "footfall_out"));
        Map<DayHour, Long> sales = salesWithFootfall(p, q.branchId());
        java.util.function.Function<DayHour, Map<String, Object>> row = k -> {
            Map<String, Figure> m = f.getOrDefault(k, Map.of());
            long in = get(m, "footfall_in").count();
            long n = sales.getOrDefault(k, 0L);
            return Obj.of("in", in, "out", get(m, "footfall_out").count(), "sales", n, "conversionRate",
                    ratio(n, in, 4));
        };
        List<Map<String, Object>> days = new ArrayList<>();
        f.keySet().stream().filter(k -> k.day() != null && k.hour() == -1).map(DayHour::day).sorted()
                .forEach(day -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("day", day);
                    r.putAll(row.apply(new DayHour(day, -1)));
                    days.add(r);
                });
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("hour", h);
            r.putAll(row.apply(new DayHour(null, h)));
            hours.add(r);
        }
        Map<String, Map<String, Figure>> perZone = perZone(p, q.branchId(), List.of("footfall_in", "footfall_out"));
        Map<String, Map<String, Object>> names = zones(perZone.keySet());
        List<Map<String, Object>> zoneRows = new ArrayList<>();
        perZone.forEach((zone, m) -> zoneRows.add(Obj.of("zoneId", zone, "name", zoneField(names, zone, "name"),
                "cameraName", zoneField(names, zone, "cameraName"), "in", get(m, "footfall_in").count(), "out",
                get(m, "footfall_out").count(), "gapSeconds", get(m, "camera_gap").total())));
        return Obj.of("from", p.from(), "to", p.to(), "branchId", LpScope.has(q.branchId()) ? q.branchId() : null,
                "totals", row.apply(new DayHour(null, -1)), "days", days, "hours", hours, "zones", zoneRows,
                "definitions", LpInsightsService.METRICS, "note", NOTE, "cameraNote", CAMERA_NOTE);
    }

    private static final List<String> QUEUE_METRICS = List.of("queue_wait", "queue_seconds", "queue_length");
    private static final List<String> TABLE_METRICS = List.of("table_seconds", "table_occupied", "table_seatings",
            "table_wait_order");

    /** Waits and queue length per queue zone, and per hour over all of them (LP6b). */
    @Transactional(readOnly = true)
    public Map<String, Object> queues(InsightsQuery q) {
        Period p = period(q);
        Map<String, Map<String, Figure>> perZone = perZone(p, q.branchId(), QUEUE_METRICS);
        Map<String, Map<String, Object>> names = zones(perZone.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        perZone.forEach((zone, m) -> {
            Figure wait = get(m, "queue_wait");
            Figure length = get(m, "queue_length");
            Figure seconds = get(m, "queue_seconds");
            rows.add(Obj.of("zoneId", zone, "name", zoneField(names, zone, "name"), "tillId",
                    "till".equals(zoneField(names, zone, "linkedType")) ? zoneField(names, zone, "linkedId") : null,
                    "waits", wait.count(), "averageWaitSeconds", average(wait, 1), "worstDayP90WaitSeconds",
                    wait.worstP90(), "observedSeconds", seconds.total(), "averageLength",
                    ratio(length.total(), seconds.total(), 2), "worstDayP90Length", length.worstP90(), "gapSeconds",
                    get(m, "camera_gap").total()));
        });
        Map<DayHour, Map<String, Figure>> f = byDayHour(p, q.branchId(), QUEUE_METRICS);
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Figure> m = f.getOrDefault(new DayHour(null, h), Map.of());
            hours.add(Obj.of("hour", h, "waits", get(m, "queue_wait").count(), "averageWaitSeconds",
                    average(get(m, "queue_wait"), 1), "averageLength", ratio(get(m, "queue_length").total(),
                            get(m, "queue_seconds").total(), 2)));
        }
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "hours", hours, "definitions",
                LpInsightsService.METRICS, "note", NOTE, "cameraNote", CAMERA_NOTE);
    }

    /** Occupancy, seatings and the wait to the first order per dining zone, and per hour (LP6b). */
    @Transactional(readOnly = true)
    public Map<String, Object> occupancy(InsightsQuery q) {
        Period p = period(q);
        Map<String, Map<String, Figure>> perZone = perZone(p, q.branchId(), TABLE_METRICS);
        Map<String, Map<String, Object>> names = zones(perZone.keySet());
        Map<String, Map<String, Object>> tables = new HashMap<>();
        List<String> tableIds = names.values().stream().filter(z -> "table".equals(z.get("linkedType")))
                .map(z -> (String) z.get("linkedId")).toList();
        if (!tableIds.isEmpty()) {
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, name, number FROM \"PosTable\" WHERE"
                    + " \"organizationId\" = ? AND id = ANY(?::text[])").params(LpScope.org(),
                    tableIds.toArray(String[]::new)))) {
                tables.put((String) r.get("id"), r);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        perZone.forEach((zone, m) -> {
            Object linkedType = zoneField(names, zone, "linkedType");
            Object linkedId = zoneField(names, zone, "linkedId");
            Map<String, Object> table = "table".equals(linkedType) ? tables.get((String) linkedId) : null;
            Figure observed = get(m, "table_seconds");
            Figure occupied = get(m, "table_occupied");
            Figure order = get(m, "table_wait_order");
            rows.add(Obj.of("zoneId", zone, "name", zoneField(names, zone, "name"), "linkedType", linkedType,
                    "linkedId", linkedId, "tableName", table == null ? null : table.get("name"), "tableNumber",
                    table == null ? null : table.get("number"), "observedSeconds", observed.total(),
                    "occupiedSeconds", occupied.total(), "occupancyRate", ratio(occupied.total(), observed.total(), 4),
                    "seatings", get(m, "table_seatings").count(), "ordered", order.count(),
                    "averageWaitToOrderSeconds", average(order, 1), "worstDayP90WaitToOrderSeconds",
                    order.worstP90(), "gapSeconds", get(m, "camera_gap").total()));
        });
        Map<DayHour, Map<String, Figure>> f = byDayHour(p, q.branchId(), TABLE_METRICS);
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Figure> m = f.getOrDefault(new DayHour(null, h), Map.of());
            hours.add(Obj.of("hour", h, "occupancyRate", ratio(get(m, "table_occupied").total(),
                    get(m, "table_seconds").total(), 4), "seatings", get(m, "table_seatings").count(),
                    "averageWaitToOrderSeconds", average(get(m, "table_wait_order"), 1)));
        }
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "hours", hours, "definitions",
                LpInsightsService.METRICS, "note", NOTE, "cameraNote", CAMERA_NOTE);
    }

    /** Metric of each area whose days count as "days with data" for that area. */
    private static final List<Map.Entry<String, String>> AREA_METRIC = List.of(Map.entry("kitchen", "kitchen_tickets"),
            Map.entry("sales", "sales"), Map.entry("tables", "table_orders"), Map.entry("cash", "cash_session"),
            Map.entry("pour", "pour_lines"));

    /**
     * How complete each area's figures are for the period: days with data out of the days in the period, and for table
     * turnover the share of sales that record whether they had a table. Sales projected before LP6a carry no
     * {@code tableId} field, so turnover is {@code partial} until every sale of the period records it.
     */
    private Map<String, Object> coverage(Period p, String branchId) {
        List<Object> params = new ArrayList<>();
        String w = where(p, branchId, params) + " AND v.hour = -1 AND v.dimension = ''";
        Map<String, Long> days = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();
        LocalDate firstRecorded = null;
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT v.metric, count(DISTINCT v.\"businessDate\") AS"
                + " days, sum(v.count) AS n, min(v.\"businessDate\") FILTER (WHERE v.count > 0) AS first FROM"
                + " \"LpMetricValue\" v" + w + " GROUP BY v.metric").params(params))) {
            String metric = (String) r.get("metric");
            days.put(metric, ((Number) r.get("days")).longValue());
            counts.put(metric, ((Number) r.get("n")).longValue());
            if ("sale_table_recorded".equals(metric)) {
                firstRecorded = (LocalDate) r.get("first");
            }
        }
        long periodDays = p.to().toEpochDay() - p.from().toEpochDay() + 1;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : AREA_METRIC) {
            out.put(e.getKey(), Obj.of("periodDays", periodDays, "daysWithData", days.getOrDefault(e.getValue(), 0L),
                    "historical", true));
        }
        long sales = counts.getOrDefault("sales", 0L);
        long recorded = counts.getOrDefault("sale_table_recorded", 0L);
        out.put("tables", Obj.of("periodDays", periodDays, "daysWithData", days.getOrDefault("table_orders", 0L),
                "sales", sales, "salesRecordingTable", recorded, "recordedShare", ratio(recorded, sales, 4),
                "firstRecordedDay", firstRecorded, "partial", recorded < sales, "historical", false));
        // LP6b: days with any camera count, seconds without a picture, and that no count has been measured.
        List<Object> cameraParams = new ArrayList<>();
        String cw = where(p, branchId, cameraParams) + " AND v.hour = -1 AND v.dimension = '' AND v.metric IN"
                + " ('footfall_in', 'footfall_out', 'queue_seconds', 'queue_wait', 'table_seconds', 'camera_gap')";
        Map<String, Object> camera = SqlRows.one(jdbc.sql("SELECT count(DISTINCT v.\"businessDate\") FILTER (WHERE"
                + " v.metric <> 'camera_gap') AS days, coalesce(sum(v.total) FILTER (WHERE v.metric = 'camera_gap'),"
                + " 0) AS gaps FROM \"LpMetricValue\" v" + cw).params(cameraParams));
        out.put("camera", Obj.of("periodDays", periodDays, "daysWithData", camera == null ? 0L
                : ((Number) camera.get("days")).longValue(), "gapSeconds", camera == null ? BigDecimal.ZERO
                : LpNumbers.decimal(camera.get("gaps")), "measured", false));
        return out;
    }

    /** How far the summaries are behind the ledger, within the caller's scope. */
    private Map<String, Object> status(String branchId) {
        List<Object> params = new ArrayList<>();
        String w = scope.where("d", params);
        if (LpScope.has(branchId)) {
            w += " AND d.\"branchId\" = ?";
            params.add(branchId);
        }
        return SqlRows.one(jdbc.sql("SELECT count(*) FILTER (WHERE d.state = 'dirty') AS \"pendingDays\", count(*)"
                + " FILTER (WHERE d.\"lastError\" IS NOT NULL) AS \"failedDays\", max(d.\"computedAt\") AS"
                + " \"lastComputedAt\" FROM \"LpMetricDay\" d" + w).params(params));
    }

    // ------------------------------------------------------------------ routes

    /**
     * The eight camera-free metrics for a period (default: the last 30 days), the same for the period before it, and
     * the key figures per day.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(InsightsQuery q) {
        Period p = period(q);
        long days = p.to().toEpochDay() - p.from().toEpochDay() + 1;
        Period before = new Period(p.from().minusDays(days), p.from().minusDays(1));
        BigDecimal tolerance = cashTolerance();
        return Obj.of("from", p.from(), "to", p.to(), "branchId", LpScope.has(q.branchId()) ? q.branchId() : null,
                "status", status(q.branchId()), "metrics", metrics(p, q.branchId(), tolerance),
                "coverage", coverage(p, q.branchId()),
                "previous", Obj.of("from", before.from(), "to", before.to(), "metrics",
                        metrics(before, q.branchId(), tolerance)),
                "trend", trend(p, q.branchId()), "definitions", LpInsightsService.METRICS, "note", NOTE,
                "cameraNote", CAMERA_NOTE);
    }

    private List<Map<String, Object>> trend(Period p, String branchId) {
        List<Object> params = new ArrayList<>();
        String w = where(p, branchId, params) + " AND v.hour = -1 AND v.dimension = ''";
        Map<LocalDate, Map<String, Figure>> byDay = new TreeMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT v.\"businessDate\" AS day, v.metric, sum(v.count)"
                + " AS n, sum(v.total) AS total, max(v.p90) AS p90 FROM \"LpMetricValue\" v" + w + " GROUP BY"
                + " v.\"businessDate\", v.metric").params(params))) {
            byDay.computeIfAbsent((LocalDate) r.get("day"), k -> new HashMap<>()).put((String) r.get("metric"),
                    figure(r));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byDay.forEach((day, m) -> {
            Figure sales = get(m, "sales");
            out.add(Obj.of("day", day, "sales", sales.count(), "total", money(sales.total()), "averageTicket",
                    average(sales, 2), "voidRate", ratio(get(m, "voids").total(), sales.total(), 4), "refundRate",
                    ratio(get(m, "refunds").total(), sales.total(), 4), "kitchenTickets",
                    get(m, "kitchen_tickets").count(), "averagePrepSeconds", average(get(m, "kitchen_prep"), 1),
                    "p90PrepSeconds", get(m, "kitchen_prep").worstP90()));
        });
        return out;
    }

    /** Sales and kitchen figures per hour of the day (business time zone), summed over the period. */
    @Transactional(readOnly = true)
    public Map<String, Object> hourly(InsightsQuery q) {
        Period p = period(q);
        List<Object> params = new ArrayList<>();
        String w = where(p, q.branchId(), params) + " AND v.hour >= 0 AND v.dimension = ''";
        Map<Integer, Map<String, Figure>> byHour = new TreeMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT v.hour, v.metric, sum(v.count) AS n, sum(v.total)"
                + " AS total, max(v.p90) AS p90 FROM \"LpMetricValue\" v" + w + " GROUP BY v.hour, v.metric")
                .params(params))) {
            byHour.computeIfAbsent(((Number) r.get("hour")).intValue(), k -> new HashMap<>())
                    .put((String) r.get("metric"), figure(r));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Figure> m = byHour.getOrDefault(h, Map.of());
            Figure sales = get(m, "sales");
            rows.add(Obj.of("hour", h, "sales", sales.count(), "total", money(sales.total()), "averageTicket",
                    average(sales, 2), "voids", get(m, "voids").count(), "refunds", get(m, "refunds").count(),
                    "kitchenTickets", get(m, "kitchen_tickets").count(), "averagePrepSeconds",
                    average(get(m, "kitchen_prep"), 1), "averageServeSeconds", average(get(m, "kitchen_serve"), 1)));
        }
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "note", NOTE);
    }

    /** Groups dimension rows by key and metric over the period. */
    private Map<String, Map<String, Object>> byKey(Period p, String branchId, String dimension, String keyExpr,
            Map<String, Map<String, Figure>> figures) {
        List<Object> params = new ArrayList<>();
        String w = where(p, branchId, params) + " AND v.hour = -1 AND v.dimension = ?";
        params.add(dimension);
        Map<String, Map<String, Object>> extra = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT " + keyExpr + " AS key, v.metric, sum(v.count) AS"
                + " n, sum(v.total) AS total, max(v.p90) AS p90, count(DISTINCT v.\"dimensionKey\") AS members,"
                + " count(DISTINCT v.\"businessDate\") AS days FROM \"LpMetricValue\" v" + w + " GROUP BY 1, v.metric"
                + " ORDER BY 1").params(params))) {
            String key = (String) r.get("key");
            figures.computeIfAbsent(key, k -> new HashMap<>()).put((String) r.get("metric"), figure(r));
            Map<String, Object> e = extra.computeIfAbsent(key, k -> new HashMap<>(Map.of("members", 0L, "days", 0L)));
            e.merge("members", ((Number) r.get("members")).longValue(), (a, b) -> Math.max((long) a, (long) b));
            e.merge("days", ((Number) r.get("days")).longValue(), (a, b) -> Math.max((long) a, (long) b));
        }
        return extra;
    }

    /** Per kitchen station: tickets, preparation and dispatch times, recalls. */
    @Transactional(readOnly = true)
    public Map<String, Object> kitchenStations(InsightsQuery q) {
        Period p = period(q);
        Map<String, Map<String, Figure>> figures = new LinkedHashMap<>();
        byKey(p, q.branchId(), "station", "v.\"dimensionKey\"", figures);
        List<Map<String, Object>> rows = new ArrayList<>();
        figures.forEach((station, m) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("station", station.isEmpty() ? null : station);
            row.putAll(kitchen(m));
            rows.add(row);
        });
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "note", NOTE);
    }

    /**
     * Sales, void, refund and discount figures per role (default) or per person. Per person needs
     * {@code lp:investigate}. A person with several roles counts under the first role name; an actor that is not a
     * user of this ERP has no role.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> staff(InsightsQuery q) {
        String groupBy = LpScope.has(q.groupBy()) ? q.groupBy() : GROUP_ROLE;
        if (!GROUP_ROLE.equals(groupBy) && !GROUP_PERSON.equals(groupBy)) {
            throw ApiException.badRequest("groupBy must be one of: role, person");
        }
        if (GROUP_PERSON.equals(groupBy) && !TenantContext.has(Permissions.LOSS_PREVENTION__INVESTIGATE)) {
            throw ApiException.forbidden("Figures per person need the lp:investigate permission");
        }
        Period p = period(q);
        String keyExpr = GROUP_PERSON.equals(groupBy) ? "v.\"dimensionKey\"" : "coalesce((SELECT min(r.name) FROM"
                + " \"_UserRoles\" ur JOIN \"Role\" r ON r.id = ur.\"A\" AND r.\"deletedAt\" IS NULL WHERE ur.\"B\" ="
                + " v.\"dimensionKey\"), '')";
        Map<String, Map<String, Figure>> figures = new LinkedHashMap<>();
        Map<String, Map<String, Object>> extra = byKey(p, q.branchId(), "actor", keyExpr, figures);
        Map<String, String> names = GROUP_PERSON.equals(groupBy) ? userNames(figures.keySet()) : Map.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        figures.forEach((key, m) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            if (GROUP_PERSON.equals(groupBy)) {
                row.put("actorId", key.isEmpty() ? null : key);
                row.put("name", names.get(key));
            } else {
                row.put("role", key.isEmpty() ? null : key);
                row.put("people", extra.get(key).get("members"));
            }
            row.putAll(sales(m));
            rows.add(row);
        });
        rows.sort((a, b) -> Long.compare(((Number) b.get("count")).longValue(), ((Number) a.get("count")).longValue()));
        return Obj.of("from", p.from(), "to", p.to(), "groupBy", groupBy, "rows", rows, "note", NOTE);
    }

    private Map<String, String> userNames(java.util.Collection<String> ids) {
        Map<String, String> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, coalesce(nullif(trim(coalesce(\"firstName\", '')"
                + " || ' ' || coalesce(\"lastName\", '')), ''), email) AS name FROM \"User\" WHERE \"organizationId\" ="
                + " ? AND id = ANY(?::text[])").params(LpScope.org(), ids.toArray(String[]::new)))) {
            out.put((String) r.get("id"), (String) r.get("name"));
        }
        return out;
    }

    /** Per table: orders, amount, days used and turns per day used. */
    @Transactional(readOnly = true)
    public Map<String, Object> tables(InsightsQuery q) {
        Period p = period(q);
        Map<String, Map<String, Figure>> figures = new LinkedHashMap<>();
        Map<String, Map<String, Object>> extra = byKey(p, q.branchId(), "table", "v.\"dimensionKey\"", figures);
        Map<String, Map<String, Object>> names = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, name, number FROM \"PosTable\" WHERE"
                + " \"organizationId\" = ? AND id = ANY(?::text[])").params(LpScope.org(),
                figures.keySet().toArray(String[]::new)))) {
            names.put((String) r.get("id"), r);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        figures.forEach((table, m) -> {
            Figure orders = get(m, "table_orders");
            long days = (long) extra.get(table).get("days");
            Map<String, Object> t = names.get(table);
            rows.add(Obj.of("tableId", table, "name", t == null ? null : t.get("name"), "number",
                    t == null ? null : t.get("number"), "orders", orders.count(), "total", money(orders.total()),
                    "averagePerOrder", average(orders, 2), "daysUsed", days, "turnsPerDayUsed",
                    ratio(orders.count(), days, 2)));
        });
        rows.sort((a, b) -> Long.compare((long) b.get("orders"), (long) a.get("orders")));
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "note", NOTE);
    }

    /** Per product, from bottle counts: volume sold, volume consumed, yield and the cost of what was consumed. */
    @Transactional(readOnly = true)
    public Map<String, Object> pourYield(InsightsQuery q) {
        Period p = period(q);
        Map<String, Map<String, Figure>> figures = new LinkedHashMap<>();
        byKey(p, q.branchId(), "product", "v.\"dimensionKey\"", figures);
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Product\" WHERE \"organizationId\""
                + " = ? AND id = ANY(?::text[])").params(LpScope.org(), figures.keySet().toArray(String[]::new)))) {
            names.put((String) r.get("id"), (String) r.get("name"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        figures.forEach((product, m) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", product.isEmpty() ? null : product);
            row.put("productName", names.get(product));
            row.putAll(pour(m));
            rows.add(row);
        });
        // Lowest yield first: the products to look at.
        rows.sort((a, b) -> {
            BigDecimal ya = (BigDecimal) a.get("yield");
            BigDecimal yb = (BigDecimal) b.get("yield");
            return ya == null ? (yb == null ? 0 : 1) : yb == null ? -1 : ya.compareTo(yb);
        });
        return Obj.of("from", p.from(), "to", p.to(), "rows", rows, "note", NOTE);
    }
}
