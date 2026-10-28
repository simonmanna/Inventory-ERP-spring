package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.domain.ProductionMaterial;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionOutput;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only manufacturing analytics (port of {@code production-report.service.ts}): the WIP reconciliation control,
 * batch genealogy (recall), KPIs, trends, yield by BOM version, ingredient usage, work-centre capacity and the
 * multi-level shortage check. Never mutates.
 */
@Service
@Transactional(readOnly = true)
public class ProductionReportService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal CENT = new BigDecimal("0.01");

    private final MfgDb db;
    private final BomService boms;

    public ProductionReportService(MfgDb db, BomService boms) {
        this.db = db;
        this.boms = boms;
    }

    /** GL WIP balance vs the material+overhead cost of every order in progress / on QC hold. */
    public Map<String, Object> wipReconciliation() {
        String accountId = db.jdbc().sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND key = 'wip' LIMIT 1")
                .param(MfgDb.org()).query(String.class).optional().orElse(null);
        BigDecimal glBalance = BigDecimal.ZERO;
        if (accountId != null) {
            Map<String, Object> agg = SqlRows.one(db.jdbc().sql("SELECT sum(debit) AS debit, sum(credit) AS credit"
                    + " FROM \"JournalLine\" WHERE \"organizationId\" = ? AND \"accountId\" = ?")
                    .params(MfgDb.org(), accountId));
            glBalance = dec(agg, "debit").subtract(dec(agg, "credit"));
        }
        List<Map<String, Object>> open = SqlRows.list(db.jdbc().sql("SELECT id, \"orderCode\", status,"
                + " \"materialCost\", \"overheadCost\" FROM \"ProductionOrder\" WHERE \"organizationId\" = ?"
                + " AND status IN ('in_progress', 'qc_hold')").param(MfgDb.org()));
        BigDecimal openWip = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> o : open) {
            BigDecimal wip = dec(o, "materialCost").add(dec(o, "overheadCost"));
            openWip = openWip.add(wip);
            Map<String, Object> r = new LinkedHashMap<>(o);
            r.put("wip", JsNumbers.dec(wip));
            rows.add(r);
        }
        BigDecimal drift = glBalance.subtract(openWip);
        return Obj.of("glBalance", JsNumbers.dec(glBalance), "openOrdersWip", JsNumbers.dec(openWip),
                "drift", JsNumbers.dec(drift), "balanced", drift.abs().compareTo(CENT) <= 0, "openOrders", rows);
    }

    /** From a finished batch back to the material lots that made it; {@code null} for an unknown order. */
    public Map<String, Object> genealogyBackward(String orderId) {
        Map<String, Object> order = db.byId(ProductionOrder.class, orderId);
        if (order == null) {
            return null;
        }
        List<Map<String, Object>> materials = new ArrayList<>();
        for (Map<String, Object> m : db.children(ProductionMaterial.class, "orderId", orderId, null)) {
            Map<String, Object> ledger = null;
            if (m.get("ledgerCode") != null) {
                ledger = SqlRows.one(db.jdbc().sql("SELECT \"batchId\", \"referenceType\", \"referenceId\" FROM"
                        + " \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"ledgerCode\" = ? LIMIT 1")
                        .params(MfgDb.org(), m.get("ledgerCode")));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", m.get("productId"));
            row.put("productName", m.get("productName"));
            row.put("qtyConsumed", JsNumbers.dec(dec(m, "qtyConsumed")));
            row.put("unitCost", JsNumbers.dec(dec(m, "unitCost")));
            row.put("ledgerCode", m.get("ledgerCode"));
            row.put("consumedFromBatchId", ledger == null ? null : ledger.get("batchId"));
            row.put("receivedVia", ledger == null ? null
                    : Obj.of("type", ledger.get("referenceType"), "id", ledger.get("referenceId")));
            materials.add(row);
        }
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (Map<String, Object> o : db.children(ProductionOutput.class, "orderId", orderId, null)) {
            outputs.add(Obj.of("productName", o.get("productName"), "qtyProduced", JsNumbers.dec(dec(o, "qtyProduced")),
                    "batchNumber", o.get("batchNumber"), "batchId", o.get("batchId")));
        }
        return Obj.of("order", Obj.of("id", order.get("id"), "orderCode", order.get("orderCode"), "outputUnitCost",
                JsNumbers.dec(dec(order, "outputUnitCost"))), "outputs", outputs, "materials", materials);
    }

    /** From a raw material forward to every order that consumed it and the batches produced (recall). */
    public List<Map<String, Object>> genealogyForward(String productId) {
        LinkedHashSet<String> orderIds = new LinkedHashSet<>(db.jdbc().sql("SELECT \"orderId\" FROM"
                        + " \"ProductionMaterial\" WHERE \"organizationId\" = ? AND \"productId\" = ?")
                .params(MfgDb.org(), productId).query(String.class).list());
        List<Map<String, Object>> out = new ArrayList<>();
        if (orderIds.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> orders = SqlRows.list(ProductionOrder.class, db.jdbc().sql("SELECT * FROM"
                        + " \"ProductionOrder\" WHERE \"organizationId\" = :org AND id IN (:ids)")
                .param("org", MfgDb.org()).param("ids", new ArrayList<>(orderIds)));
        for (Map<String, Object> o : orders) {
            List<Map<String, Object>> outputs = new ArrayList<>();
            for (Map<String, Object> x : db.children(ProductionOutput.class, "orderId", o.get("id"), null)) {
                outputs.add(Obj.of("productName", x.get("productName"), "qtyProduced",
                        JsNumbers.dec(dec(x, "qtyProduced")), "batchNumber", x.get("batchNumber")));
            }
            out.add(Obj.of("orderId", o.get("id"), "orderCode", o.get("orderCode"), "status", o.get("status"),
                    "completedAt", o.get("completedAt"), "outputs", outputs));
        }
        return out;
    }

    /** Period KPIs — yield %, waste %, cost per batch, throughput, waste by QC reason. */
    public Map<String, Object> kpis(String from, String to) {
        Window w = Window.of(from, to);
        List<Map<String, Object>> orders = completedInWindow(w,
                "\"plannedQty\", \"producedQty\", \"scrapQty\", \"totalCost\", \"outputUnitCost\"");
        BigDecimal planned = sum(orders, "plannedQty");
        BigDecimal produced = sum(orders, "producedQty");
        BigDecimal scrap = sum(orders, "scrapQty");
        BigDecimal totalCost = sum(orders, "totalCost");
        int count = orders.size();

        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        StringBuilder sql = new StringBuilder("SELECT q.\"wasteCategory\", sum(q.\"failedQty\") AS failed FROM"
                + " \"ProductionQcCheck\" q JOIN \"ProductionOrder\" o ON o.id = q.\"orderId\""
                + " WHERE q.\"organizationId\" = ? AND q.\"wasteCategory\" IS NOT NULL");
        w.append(sql, params, "o.");
        sql.append(" GROUP BY q.\"wasteCategory\"");
        List<Map<String, Object>> waste = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(sql.toString()).params(params))) {
            waste.add(Obj.of("reason", r.get("wasteCategory"), "qty", JsNumbers.dec(dec(r, "failed"))));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", count);
        out.put("plannedQty", JsNumbers.dec(planned));
        out.put("producedQty", JsNumbers.dec(produced));
        out.put("scrapQty", JsNumbers.dec(scrap));
        out.put("yieldPct", pct(produced, planned));
        out.put("wastePct", pct(scrap, planned));
        out.put("totalCost", JsNumbers.dec(totalCost));
        out.put("avgCostPerBatch", count > 0 ? JsNumbers.dec(Money.div(totalCost, BigDecimal.valueOf(count))) : "0");
        out.put("wasteByReason", waste);
        return out;
    }

    /** Throughput + cost per UTC day. */
    public List<Map<String, Object>> trends(String from, String to) {
        List<Map<String, Object>> orders = completedInWindow(Window.of(from, to),
                "\"completedAt\", \"producedQty\", \"scrapQty\", \"totalCost\"");
        Map<String, BigDecimal[]> buckets = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> o : orders) {
            Instant at = o.get("completedAt") instanceof Instant i ? i : Instant.now();
            String day = at.atOffset(ZoneOffset.UTC).toLocalDate().toString();
            BigDecimal[] b = buckets.computeIfAbsent(day,
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            b[0] = b[0].add(dec(o, "producedQty"));
            b[1] = b[1].add(dec(o, "scrapQty"));
            b[2] = b[2].add(dec(o, "totalCost"));
            counts.merge(day, 1, Integer::sum);
        }
        return buckets.keySet().stream().sorted().map(day -> {
            BigDecimal[] b = buckets.get(day);
            return Obj.of("date", day, "orders", counts.get(day), "produced", JsNumbers.dec(b[0]), "scrap",
                    JsNumbers.dec(b[1]), "cost", JsNumbers.dec(b[2]));
        }).toList();
    }

    /** Yield % by BOM version. */
    public List<Map<String, Object>> yieldByBom(String from, String to) {
        List<Map<String, Object>> orders = completedInWindow(Window.of(from, to),
                "\"bomId\", \"bomVersion\", \"plannedQty\", \"producedQty\"");
        Map<String, Object[]> groups = new LinkedHashMap<>();
        for (Map<String, Object> o : orders) {
            String key = (o.get("bomId") != null ? str(o, "bomId") : "adhoc") + "::"
                    + (o.get("bomVersion") != null ? o.get("bomVersion") : 0);
            Object[] g = groups.computeIfAbsent(key,
                    k -> new Object[] {o.get("bomId"), o.get("bomVersion"), BigDecimal.ZERO, BigDecimal.ZERO, 0});
            g[2] = ((BigDecimal) g[2]).add(dec(o, "plannedQty"));
            g[3] = ((BigDecimal) g[3]).add(dec(o, "producedQty"));
            g[4] = (Integer) g[4] + 1;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] g : groups.values()) {
            BigDecimal planned = (BigDecimal) g[2];
            BigDecimal produced = (BigDecimal) g[3];
            out.add(Obj.of("bomId", g[0], "version", g[1], "runs", g[4], "plannedQty", JsNumbers.dec(planned),
                    "producedQty", JsNumbers.dec(produced), "yieldPct", pct(produced, planned)));
        }
        return out;
    }

    /** Ingredient consumption of the orders completed in the window, costliest first. */
    public List<Map<String, Object>> ingredientUsage(String from, String to) {
        List<Map<String, Object>> orders = completedInWindow(Window.of(from, to), "id");
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> materials = SqlRows.list(db.jdbc().sql("SELECT \"productId\", \"productName\","
                        + " \"qtyConsumed\", \"totalCost\" FROM \"ProductionMaterial\" WHERE \"organizationId\" = :org"
                        + " AND \"orderId\" IN (:ids)")
                .param("org", MfgDb.org()).param("ids", orders.stream().map(o -> o.get("id")).toList()));
        Map<String, Object[]> groups = new LinkedHashMap<>();
        for (Map<String, Object> m : materials) {
            Object[] g = groups.computeIfAbsent(str(m, "productId"),
                    k -> new Object[] {m.get("productName"), BigDecimal.ZERO, BigDecimal.ZERO});
            g[1] = ((BigDecimal) g[1]).add(dec(m, "qtyConsumed"));
            g[2] = ((BigDecimal) g[2]).add(dec(m, "totalCost"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        groups.forEach((productId, g) -> out.add(Obj.of("productId", productId, "name", g[0], "qtyConsumed",
                JsNumbers.dec((BigDecimal) g[1]), "cost", JsNumbers.dec((BigDecimal) g[2]))));
        out.sort(Comparator.comparingDouble((Map<String, Object> r) -> Double.parseDouble((String) r.get("cost")))
                .reversed());
        return out;
    }

    /** Open work-order minutes vs daily resource capacity per active work centre. */
    public List<Map<String, Object>> capacity() {
        List<Map<String, Object>> centres = db.list(WorkCenter.class, " AND \"isActive\" = true");
        Map<String, Integer> load = new HashMap<>();
        for (Map<String, Object> wo : SqlRows.list(db.jdbc().sql("SELECT \"workCenterId\", \"plannedDurationMins\""
                + " FROM \"WorkOrder\" WHERE \"organizationId\" = ? AND status IN ('pending', 'in_progress', 'paused')")
                .param(MfgDb.org()))) {
            if (wo.get("workCenterId") == null) {
                continue;
            }
            int mins = wo.get("plannedDurationMins") instanceof Number n ? n.intValue() : 0;
            load.merge(str(wo, "workCenterId"), mins, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : centres) {
            int capacity = 0;
            for (Integer cap : db.jdbc().sql("SELECT \"capacityMinsPerDay\" FROM \"Resource\" WHERE \"workCenterId\" = ?"
                    + " AND \"isActive\" = true").param(c.get("id")).query(Integer.class).list()) {
                capacity += cap != null ? cap : 0;
            }
            int loadMins = load.getOrDefault(str(c, "id"), 0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workCenterId", c.get("id"));
            row.put("name", c.get("name"));
            row.put("capacityMinsPerDay", capacity);
            row.put("loadMins", loadMins);
            row.put("utilizationPct", capacity > 0
                    ? JsNumbers.js(new BigDecimal((double) loadMins / capacity * 100).setScale(1, RoundingMode.HALF_UP)
                            .doubleValue())
                    : null);
            out.add(row);
        }
        return out;
    }

    /** Multi-level shortage: the raw requirement of a run vs on-hand (optionally at one location). */
    public Map<String, Object> shortage(String productId, double qty, String locationId) {
        Map<String, BigDecimal> req = boms.explodeRequirements(productId, null, BigDecimal.valueOf(qty));
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasShortage = false;
        for (Map.Entry<String, BigDecimal> e : req.entrySet()) {
            String rawId = e.getKey();
            BigDecimal onHand = locationId != null
                    ? db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                            + " AND \"productId\" = ? AND \"locationId\" = ?").params(MfgDb.org(), rawId, locationId)
                            .query(BigDecimal.class).single()
                    : db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                            + " AND \"productId\" = ?").params(MfgDb.org(), rawId).query(BigDecimal.class).single();
            onHand = onHand != null ? onHand : BigDecimal.ZERO;
            Map<String, Object> product = SqlRows.one(db.jdbc().sql("SELECT name, code FROM \"Product\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(rawId, MfgDb.org()));
            BigDecimal shortQty = e.getValue().subtract(onHand);
            hasShortage |= shortQty.signum() > 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", rawId);
            row.put("name", product != null ? product.get("name") : rawId);
            row.put("code", product != null && product.get("code") != null ? product.get("code") : "");
            row.put("required", JsNumbers.dec(e.getValue()));
            row.put("onHand", JsNumbers.dec(onHand));
            row.put("shortage", shortQty.signum() > 0 ? JsNumbers.dec(shortQty) : "0");
            rows.add(row);
        }
        return Obj.of("productId", productId, "qty", JsNumbers.js(qty), "lines", rows, "hasShortage", hasShortage);
    }

    // ---- internals

    /** {@code completedAt} window of the completed-order reports. */
    private record Window(Instant from, Instant to) {

        static Window of(String from, String to) {
            return new Window(has(from) ? EntityPatch.toInstant(from) : null, has(to) ? EntityPatch.toInstant(to) : null);
        }

        void append(StringBuilder sql, List<Object> params, String alias) {
            if (from != null) {
                sql.append(" AND ").append(alias).append("\"completedAt\" >= ?");
                params.add(MfgDb.param(from));
            }
            if (to != null) {
                sql.append(" AND ").append(alias).append("\"completedAt\" <= ?");
                params.add(MfgDb.param(to));
            }
        }

        private static boolean has(String s) {
            return s != null && !s.isEmpty();
        }
    }

    private List<Map<String, Object>> completedInWindow(Window w, String columns) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        StringBuilder sql = new StringBuilder("SELECT " + columns + " FROM \"ProductionOrder\" WHERE"
                + " \"organizationId\" = ? AND status = 'completed'");
        w.append(sql, params, "");
        return SqlRows.list(db.jdbc().sql(sql.toString()).params(params));
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        BigDecimal acc = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            acc = acc.add(dec(r, key));
        }
        return acc;
    }

    /** {@code d > 0 ? Number(n.div(d).mul(100).toFixed(2)) : 0}. */
    private static Number pct(BigDecimal n, BigDecimal d) {
        if (d.signum() <= 0) {
            return 0;
        }
        return JsNumbers.js(BomService.mul(Money.div(n, d), HUNDRED).setScale(2, RoundingMode.HALF_UP).doubleValue());
    }
}
