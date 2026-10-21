package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Table / reservation reports (port of {@code pos-table-reports.service.ts}): hourly utilization, revenue per
 * table / zone, reservation counts. Day boundaries are UTC (the Nest server runs in UTC).
 */
@Service
public class PosTableReportsService {

    private final JdbcClient jdbc;

    public PosTableReportsService(PosDb db) {
        this.jdbc = db.jdbc();
    }

    /** JS {@code new Date(s)}; {@code null} for an Invalid Date. */
    private static Instant parse(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return PosSql.date(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant startOfDay(Instant i) {
        return i.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** {@code end.setHours(23, 59, 59, 999)}. */
    private static Instant endOfDay(Instant i) {
        return startOfDay(i).plus(1, ChronoUnit.DAYS).minusMillis(1);
    }

    private static String day(Instant i) {
        return i.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static long jsRound(double v) {
        return (long) Math.floor(v + 0.5);
    }

    private long activeTables(String orgId) {
        return jdbc.sql("SELECT count(*) FROM \"PosTable\" WHERE \"organizationId\" = ? AND active = true")
                .param(orgId).query(Long.class).single();
    }

    /** Occupancy % per hour of one day (table-hours occupied / active tables). */
    public Map<String, Object> utilization(String dateStr) {
        String orgId = org();
        Instant date = parse(dateStr);
        if (date == null) {
            throw ApiException.badRequest("Invalid date");
        }
        Instant start = startOfDay(date);
        Instant end = start.plus(1, ChronoUnit.DAYS);
        long totalActive = activeTables(orgId);
        List<Map<String, Object>> orders = jdbc.sql("SELECT \"openedAt\", \"closedAt\" FROM \"PosTableOrder\""
                + " WHERE \"organizationId\" = ? AND \"openedAt\" < ? AND (\"closedAt\" IS NULL OR \"closedAt\" > ?)")
                .params(orgId, Timestamp.from(end), Timestamp.from(start)).query().listOfRows();
        long[] occupied = new long[24];
        for (Map<String, Object> o : orders) {
            Instant opened = ((Timestamp) o.get("openedAt")).toInstant();
            Instant closed = o.get("closedAt") == null ? end : ((Timestamp) o.get("closedAt")).toInstant();
            Instant opStart = opened.isBefore(start) ? start : opened;
            Instant opEnd = closed.isAfter(end) ? end : closed;
            if (!opEnd.isAfter(opStart)) {
                continue;
            }
            ZonedDateTime s = opStart.atZone(ZoneOffset.UTC);
            ZonedDateTime e = opEnd.atZone(ZoneOffset.UTC);
            int firstHour = s.getHour();
            int lastHour = e.getHour() + (e.getMinute() > 0 || e.getSecond() > 0 ? 1 : 0);
            for (int h = firstHour; h < Math.min(24, lastHour); h++) {
                occupied[h] += 1;
            }
        }
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            hours.add(Obj.of("hour", h, "occupiedHours", occupied[h], "occupancyPct",
                    totalActive > 0 ? jsRound((double) occupied[h] / totalActive * 100) : 0));
        }
        List<Map<String, Object>> sorted = new ArrayList<>(hours);
        sorted.sort(Comparator.comparingLong((Map<String, Object> b) -> (long) b.get("occupiedHours")).reversed());
        List<Object> peak = sorted.subList(0, 3).stream().map(b -> b.get("hour")).toList();
        return Obj.of("date", day(start), "totalActiveTables", totalActive, "hours", hours, "peakHours", peak);
    }

    /** Revenue per table + per zone + top performers (POS invoices and legacy POS documents). */
    public Map<String, Object> revenue(String fromDate, String toDate) {
        String orgId = org();
        Instant start = parse(fromDate);
        Instant endRaw = parse(toDate);
        if (start == null || endRaw == null) {
            throw ApiException.badRequest("Invalid fromDate/toDate");
        }
        Instant end = endOfDay(endRaw);
        Timestamp s = Timestamp.from(start);
        Timestamp e = Timestamp.from(end);
        List<Map<String, Object>> sales = new ArrayList<>(jdbc.sql("SELECT \"tableId\", \"totalAmount\" FROM \"Invoice\""
                + " WHERE \"organizationId\" = ? AND \"tableId\" IS NOT NULL AND status IN ('posted', 'paid')"
                + " AND \"createdAt\" >= ? AND \"createdAt\" <= ?").params(orgId, s, e).query().listOfRows());
        sales.addAll(jdbc.sql("SELECT \"tableId\", \"totalAmount\" FROM \"Document\" WHERE \"organizationId\" = ?"
                + " AND \"tableId\" IS NOT NULL AND \"documentType\" = 'sales_invoice'"
                + " AND \"sourceType\" = 'pos' AND status IN ('posted', 'paid') AND \"createdAt\" >= ? AND \"createdAt\" <= ?")
                .params(orgId, s, e).query().listOfRows());

        Map<String, double[]> perTable = new LinkedHashMap<>();
        double totalRevenue = 0;
        long totalOrders = 0;
        for (Map<String, Object> d : sales) {
            String tableId = (String) d.get("tableId");
            double amount = PosSql.num(d.get("totalAmount"));
            double[] cur = perTable.computeIfAbsent(tableId, k -> new double[2]);
            cur[0] += 1;
            cur[1] += amount;
            totalRevenue += amount;
            totalOrders += 1;
        }
        Map<String, Map<String, Object>> tableMap = new LinkedHashMap<>();
        if (!perTable.isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(orgId));
            params.addAll(perTable.keySet());
            for (Map<String, Object> t : jdbc.sql("SELECT id, number, name, zone FROM \"PosTable\" WHERE \"organizationId\" = ?"
                    + " AND id IN (" + PosSql.marks(perTable.size()) + ")").params(params).query().listOfRows()) {
                tableMap.put((String) t.get("id"), t);
            }
        }
        Map<String, Map<String, Object>> zoneMeta = new LinkedHashMap<>();
        for (Map<String, Object> z : jdbc.sql("SELECT key, name, color FROM \"PosTableZone\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").param(orgId).query().listOfRows()) {
            zoneMeta.put((String) z.get("key"), z);
        }

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map.Entry<String, double[]> row : perTable.entrySet()) {
            Map<String, Object> t = tableMap.get(row.getKey());
            String zone = t == null || t.get("zone") == null ? null : (String) t.get("zone");
            Map<String, Object> meta = zoneMeta.get(zone == null ? "" : zone);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tableId", row.getKey());
            m.put("number", t == null ? null : t.get("number"));
            m.put("name", t == null || t.get("name") == null ? "Unknown" : t.get("name"));
            m.put("zone", zone == null ? "indoor" : zone);
            m.put("zoneName", meta != null && meta.get("name") != null ? meta.get("name") : zone == null ? "indoor" : zone);
            m.put("zoneColor", meta == null ? null : meta.get("color"));
            m.put("orders", (long) row.getValue()[0]);
            m.put("revenue", JsFormat.toFixed(row.getValue()[1], 2));
            enriched.add(m);
        }

        Map<String, Map<String, Object>> perZone = new LinkedHashMap<>();
        Map<String, Double> zoneRevenue = new LinkedHashMap<>();
        for (Map<String, Object> row : enriched) {
            String key = (String) row.get("zone");
            Map<String, Object> meta = zoneMeta.get(key);
            Map<String, Object> cur = perZone.computeIfAbsent(key, k -> {
                Map<String, Object> z = new LinkedHashMap<>();
                z.put("zone", k);
                z.put("zoneName", meta != null && meta.get("name") != null ? meta.get("name") : k);
                z.put("zoneColor", meta == null ? null : meta.get("color"));
                z.put("orders", 0L);
                z.put("revenue", 0);
                return z;
            });
            cur.put("orders", (long) cur.get("orders") + (long) row.get("orders"));
            zoneRevenue.merge(key, PosSql.num(row.get("revenue")), Double::sum);
        }
        List<Map<String, Object>> zones = new ArrayList<>();
        perZone.forEach((k, z) -> {
            z.put("revenue", JsFormat.toFixed(zoneRevenue.get(k), 2));
            zones.add(z);
        });

        List<Map<String, Object>> closed = jdbc.sql("SELECT \"openedAt\", \"closedAt\" FROM \"PosTableOrder\""
                + " WHERE \"organizationId\" = ? AND \"openedAt\" >= ? AND \"openedAt\" <= ? AND \"closedAt\" IS NOT NULL")
                .params(orgId, s, e).query().listOfRows();
        double totalMinutes = 0;
        for (Map<String, Object> o : closed) {
            long ms = ((Timestamp) o.get("closedAt")).getTime() - ((Timestamp) o.get("openedAt")).getTime();
            if (ms > 0) {
                totalMinutes += ms / 60000.0;
            }
        }
        long averageDiningMinutes = closed.isEmpty() ? 0 : jsRound(totalMinutes / closed.size());
        long totalTables = activeTables(orgId);
        Number turnoverRate = totalTables > 0
                ? JsNumbers.js(jsRound((double) totalOrders / totalTables * 100) / 100.0) : 0;

        enriched.sort(Comparator.comparingDouble((Map<String, Object> r) -> PosSql.num(r.get("revenue"))).reversed());
        Map<String, Object> totals = Obj.of("orders", totalOrders, "revenue", JsFormat.toFixed(totalRevenue, 2),
                "averageDiningMinutes", averageDiningMinutes, "turnoverRate", turnoverRate);
        return Obj.of("fromDate", day(start), "toDate", day(end), "totals", totals, "perTable", enriched,
                "perZone", zones, "topPerformers", enriched.subList(0, Math.min(10, enriched.size())));
    }

    /** Reservation counts by day + no-show / completion rates. */
    public Map<String, Object> reservations(String fromDate, String toDate) {
        String orgId = org();
        Instant start = parse(fromDate);
        Instant endRaw = parse(toDate);
        if (start == null || endRaw == null) {
            throw ApiException.badRequest("Invalid fromDate/toDate");
        }
        Instant end = endOfDay(endRaw);
        List<Map<String, Object>> rows = jdbc.sql("SELECT status::text AS status, \"startAt\" FROM \"PosTableReservation\""
                + " WHERE \"organizationId\" = ? AND \"startAt\" >= ? AND \"startAt\" <= ?")
                .params(orgId, Timestamp.from(start), Timestamp.from(end)).query().listOfRows();
        Map<String, long[]> byDay = new TreeMap<>();
        long[] totals = new long[6];
        for (Map<String, Object> r : rows) {
            String d = day(((Timestamp) r.get("startAt")).toInstant());
            long[] cur = byDay.computeIfAbsent(d, k -> new long[6]);
            count(cur, (String) r.get("status"));
            count(totals, (String) r.get("status"));
        }
        long noShowRate = totals[5] > 0 ? jsRound((double) totals[4] / totals[5] * 100) : 0;
        long completionRate = totals[5] > 0 ? jsRound((double) totals[2] / totals[5] * 100) : 0;
        Map<String, Object> t = counts(totals);
        t.put("noShowRate", noShowRate);
        t.put("completionRate", completionRate);
        List<Map<String, Object>> days = new ArrayList<>();
        byDay.forEach((d, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", d);
            m.putAll(counts(v));
            days.add(m);
        });
        return Obj.of("fromDate", day(start), "toDate", day(end), "totals", t, "byDay", days);
    }

    /** pending, seated, completed, cancelled, noShow, total. */
    private static void count(long[] c, String status) {
        c[5] += 1;
        switch (status) {
            case "pending" -> c[0] += 1;
            case "seated" -> c[1] += 1;
            case "completed" -> c[2] += 1;
            case "cancelled" -> c[3] += 1;
            case "no_show" -> c[4] += 1;
            default -> { }
        }
    }

    private static Map<String, Object> counts(long[] c) {
        return Obj.of("pending", c[0], "seated", c[1], "completed", c[2], "cancelled", c[3], "noShow", c[4], "total", c[5]);
    }
}
