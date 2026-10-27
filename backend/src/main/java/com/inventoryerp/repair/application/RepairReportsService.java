package com.inventoryerp.repair.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.repair.domain.RepairJob;
import com.inventoryerp.repair.domain.RepairLabourType;
import com.inventoryerp.repair.domain.RepairOrder;
import com.inventoryerp.repair.domain.RepairPart;
import com.inventoryerp.repair.domain.RepairSchedule;
import com.inventoryerp.repair.domain.RepairServiceContract;
import com.inventoryerp.repair.domain.RepairTechnician;
import com.inventoryerp.repair.domain.RepairWarranty;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Dashboard KPIs and operational reports (port of {@code repair-reports.service.ts}). Organization-scoped; date
 * filters default to all time and accept {@code from}/{@code to} ({@code to} inclusive to 23:59:59.999).
 */
@Service
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairReportsService {

    private static final Duration DAYS_30 = Duration.ofDays(30);

    private final RepairDb db;

    public RepairReportsService(RepairDb db) {
        this.db = db;
    }

    /** {@code dateRange(from, to)} as a SQL condition on {@code column} (empty range = no condition). */
    private static void range(RepairDb.Where w, String column, String from, String to) {
        if (PosSql.truthy(from)) {
            w.and("\"" + column + "\" >= ?", PosDb.param(RepairDb.jsDate(from)));
        }
        if (PosSql.truthy(to)) {
            Instant d = RepairDb.jsDate(to);
            Instant end = LocalDateTime.of(d.atOffset(ZoneOffset.UTC).toLocalDate(), LocalTime.of(23, 59, 59, 999_000_000))
                    .toInstant(ZoneOffset.UTC);
            w.and("\"" + column + "\" <= ?", PosDb.param(end));
        }
    }

    private BigDecimal sum(RepairDb.Where w, Class<?> entity, String column) {
        return db.jdbc().sql("SELECT sum(\"" + column + "\") FROM \"" + PosDb.table(entity) + "\" WHERE " + w.sql())
                .params(w.args()).query(BigDecimal.class).optional().orElse(null);
    }

    private static Number num(BigDecimal v) {
        return v == null ? 0L : JsNumbers.js(v);
    }

    private Map<String, Object> groupCount(Class<?> entity, String column) {
        RepairDb.Where w = db.where(entity);
        Map<String, Object> out = new LinkedHashMap<>();
        db.jdbc().sql("SELECT \"" + column + "\"::text AS k, count(*) AS n FROM \"" + PosDb.table(entity) + "\" WHERE "
                        + w.sql() + " GROUP BY 1 ORDER BY 1").params(w.args()).query()
                .listOfRows().forEach(r -> out.put((String) r.get("k"), ((Number) r.get("n")).longValue()));
        return out;
    }

    private RepairDb.Where orders(String... statuses) {
        RepairDb.Where w = db.where(RepairOrder.class);
        List<Object> p = new ArrayList<>(List.of(statuses));
        return w.and("status::text IN (" + PosSql.marks(statuses.length) + ")", p.toArray());
    }

    public Map<String, Object> dashboard() {
        Object since = PosDb.param(Instant.now().minus(DAYS_30));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openOrders", orders("received", "diagnosis", "waiting_approval", "approved").count());
        out.put("inWorkshop", orders("repairing", "testing").count());
        out.put("readyPickup", orders("ready_pickup").count());
        out.put("awaitingApproval", orders("waiting_approval").count());
        out.put("completed30", orders("closed").and("\"closedAt\" >= ?", since).count());
        out.put("revenue30", num(sum(orders("closed").and("\"closedAt\" >= ?", since), RepairOrder.class,
                "totalAmount")));
        out.put("labourTypes", db.where(RepairLabourType.class).count());
        out.put("technicians", db.where(RepairTechnician.class).count());
        out.put("byStatus", groupCount(RepairOrder.class, "status"));
        return out;
    }

    /** Revenue of closed orders by {@code closedAt} month ({@code YYYY-MM}), ascending. */
    public List<Map<String, Object>> revenueByMonth(String from, String to) {
        RepairDb.Where w = orders("closed");
        range(w, "closedAt", from, to);
        Map<String, Double> buckets = new TreeMap<>();
        for (Map<String, Object> r : w.list(null)) {
            Object closedAt = r.get("closedAt");
            String key = closedAt instanceof Instant i ? i.toString().substring(0, 7) : "unknown";
            buckets.merge(key, PosSql.num(r.get("totalAmount")), Double::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        buckets.forEach((month, revenue) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", month);
            m.put("revenue", JsNumbers.js(revenue));
            out.add(m);
        });
        return out;
    }

    /** Open jobs (pending / in progress / testing) per technician — the workshop load board. */
    public List<Map<String, Object>> technicianLoad() {
        Map<Object, Map<String, Object>> byTech = new LinkedHashMap<>();
        for (Map<String, Object> t : db.where(RepairTechnician.class).list(null)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.get("id"));
            row.put("name", t.get("name"));
            row.put("availability", t.get("availability"));
            row.put("pending", 0L);
            row.put("in_progress", 0L);
            row.put("testing", 0L);
            byTech.put(t.get("id"), row);
        }
        List<Map<String, Object>> jobs = db.where(RepairJob.class)
                .and("status::text IN ('pending', 'in_progress', 'testing')").list(null);
        for (Map<String, Object> j : jobs) {
            Map<String, Object> row = byTech.get(j.get("technicianId") == null ? "" : j.get("technicianId"));
            if (row != null) {
                row.merge((String) j.get("status"), 1L, (a, b) -> ((Long) a) + (Long) b);
            }
        }
        return new ArrayList<>(byTech.values());
    }

    /** Warranty counts by status. */
    public Map<String, Object> warrantyHealth() {
        return groupCount(RepairWarranty.class, "status");
    }

    /** Active contracts, preventive runs due within 30 days and contracts ending within 30 days. */
    public Map<String, Object> contractsOverview() {
        Object horizon = PosDb.param(Instant.now().plus(DAYS_30));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", db.where(RepairServiceContract.class).eq("status", "active").count());
        out.put("upcomingRuns", db.where(RepairSchedule.class).eq("status", "active")
                .and("\"nextDueAt\" <= ?", horizon).count());
        out.put("expiringSoon", db.where(RepairServiceContract.class).eq("status", "active")
                .and("\"endDate\" <= ?", horizon).count());
        return out;
    }

    /** Counts, revenue, labour, parts and per-type totals over orders created in the range. */
    public Map<String, Object> operationalReport(String from, String to) {
        RepairDb.Where w = db.where(RepairOrder.class);
        range(w, "createdAt", from, to);
        List<Map<String, Object>> orders = w.list(null);
        long closed = 0;
        long cancelled = 0;
        double revenue = 0;
        for (Map<String, Object> o : orders) {
            if ("closed".equals(o.get("status"))) {
                closed++;
                revenue += PosSql.num(o.get("totalAmount"));
            }
            if ("cancelled".equals(o.get("status"))) {
                cancelled++;
            }
        }
        RepairDb.Where jobs = db.where(RepairJob.class);
        range(jobs, "createdAt", from, to);
        RepairDb.Where parts = db.where(RepairPart.class).eq("status", "issued");
        range(parts, "issuedAt", from, to);

        List<Map<String, Object>> byType = new ArrayList<>();
        db.jdbc().sql("SELECT \"orderType\"::text AS t, count(*) AS n, sum(\"totalAmount\") AS s FROM \"RepairOrder\""
                        + " WHERE " + w.sql() + " GROUP BY 1 ORDER BY 1").params(w.args()).query().listOfRows()
                .forEach(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", r.get("t"));
                    m.put("count", ((Number) r.get("n")).longValue());
                    m.put("revenue", num((BigDecimal) r.get("s")));
                    byType.add(m);
                });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalOrders", (long) orders.size());
        out.put("closedOrders", closed);
        out.put("cancelledOrders", cancelled);
        out.put("revenue", JsNumbers.js(revenue));
        out.put("labourTotal", num(sum(w, RepairOrder.class, "labourTotal")));
        out.put("partsTotal", num(sum(w, RepairOrder.class, "partsTotal")));
        out.put("jobs", jobs.count());
        out.put("partsUsedQty", num(sum(parts, RepairPart.class, "quantity")));
        out.put("partsCost", num(sum(parts, RepairPart.class, "totalCost")));
        out.put("byType", byType);
        return out;
    }
}
