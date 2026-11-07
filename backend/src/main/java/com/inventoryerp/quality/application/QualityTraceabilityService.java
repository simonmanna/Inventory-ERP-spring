package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch traceability (ADR-S013 §3) as a tree of nodes {@code {kind, key, label, productId, productName, batchNumber,
 * quantity, date, ref, lots, children}} over (product, batch):
 *
 * <ul>
 *   <li><b>backward</b> (where did it come from): posted GRN lines of the batch (supplier + supplier lot), production
 *       outputs of the batch → order → its consumed materials (recursing on their batches), quality downgrades into
 *       the batch (→ the first-grade lot);</li>
 *   <li><b>forward</b> (where did it go): production materials / {@code production_consume} ledger rows of the batch →
 *       order → its outputs (recursing), fleet trip load lines / {@code fleet_trip} ledger rows → delivery order →
 *       customer + site, downgrades out of the batch (recursing on the second grade), other batch issues (sales,
 *       waste, returns) as leaves.</li>
 * </ul>
 * Reads other modules' tables read-only and org-scoped; depth-limited and cycle-safe.
 */
@Service
public class QualityTraceabilityService {

    private static final int MAX_DEPTH = 10;

    private final QualityDb db;
    private final QualityLotService lots;

    public QualityTraceabilityService(QualityDb db, QualityLotService lots) {
        this.db = db;
        this.lots = lots;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> trace(String productId, String batchNumber, String direction, Integer depth) {
        if (!Q.has(productId) || !Q.has(batchNumber)) {
            throw ApiException.badRequest("productId and batchNumber are required");
        }
        if (!db.exists("Product", productId, false)) {
            throw ApiException.notFound("Product " + productId + " not found");
        }
        String dir = Q.has(direction) ? direction : "backward";
        if (!List.of("backward", "forward").contains(dir)) {
            throw ApiException.badRequest("direction must be backward or forward");
        }
        int max = depth == null ? 6 : Math.max(1, Math.min(MAX_DEPTH, depth));
        Set<String> seen = new HashSet<>();
        Map<String, Object> root = batchNode(productId, batchNumber.trim(), null, dir, 0, max, seen);
        return Obj.of("direction", dir, "productId", productId, "batchNumber", batchNumber.trim(), "maxDepth", max,
                "root", root);
    }

    private Map<String, Object> batchNode(String productId, String batch, BigDecimal qty, String dir, int level,
            int max, Set<String> seen) {
        Map<String, Object> node = node("batch", productId + "|" + batch, batch);
        node.put("productId", productId);
        node.put("productName", productName(productId));
        node.put("batchNumber", batch);
        node.put("quantity", qty);
        node.put("lots", lots.lotsOfBatch(productId, batch));
        List<Map<String, Object>> children = new ArrayList<>();
        node.put("children", children);
        if (!seen.add(dir + "|" + productId + "|" + batch)) {
            node.put("cycle", true);
            return node;
        }
        if (level >= max) {
            node.put("truncated", true);
            return node;
        }
        if ("backward".equals(dir)) {
            backward(productId, batch, level, max, seen, children);
        } else {
            forward(productId, batch, level, max, seen, children);
        }
        return node;
    }

    // ------------------------------------------------------------------ backward

    private void backward(String productId, String batch, int level, int max, Set<String> seen,
            List<Map<String, Object>> children) {
        String org = QualityDb.org();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT n.id, n.\"receiptNumber\", n.\"receivedAt\","
                + " n.\"partnerId\", pa.name AS \"partnerName\", SUM(l.quantity) AS qty FROM \"GoodsReceiptLine\" l"
                + " JOIN \"GoodsReceiptNote\" n ON n.id = l.\"goodsReceiptId\" AND n.\"organizationId\" = l.\"organizationId\""
                + " LEFT JOIN \"Partner\" pa ON pa.id = n.\"partnerId\" WHERE l.\"organizationId\" = ? AND"
                + " l.\"productId\" = ? AND l.\"batchNumber\" = ? AND n.status = 'posted' AND n.\"deletedAt\" IS NULL"
                + " GROUP BY n.id, n.\"receiptNumber\", n.\"receivedAt\", n.\"partnerId\", pa.name ORDER BY n.\"receivedAt\"")
                .params(org, productId, batch))) {
            Map<String, Object> grn = node("goods_receipt", "grn|" + r.get("id"), Q.str(r, "receiptNumber"));
            grn.put("ref", r.get("id"));
            grn.put("date", r.get("receivedAt"));
            grn.put("quantity", r.get("qty"));
            grn.put("supplierLot", batch);
            if (r.get("partnerId") != null) {
                Map<String, Object> supplier = node("supplier", "partner|" + r.get("partnerId"),
                        Q.str(r, "partnerName"));
                supplier.put("ref", r.get("partnerId"));
                supplier.put("children", List.of());
                grn.put("children", List.of(supplier));
            } else {
                grn.put("children", List.of());
            }
            children.add(grn);
        }
        for (Map<String, Object> o : SqlRows.list(db.jdbc().sql("SELECT DISTINCT po.id, po.\"orderCode\","
                + " po.\"completedAt\", po.\"postedAt\", po.\"producedQty\" FROM \"ProductionOrder\" po LEFT JOIN"
                + " \"ProductionOutput\" out ON out.\"orderId\" = po.id WHERE po.\"organizationId\" = ? AND po.\"postedAt\""
                + " IS NOT NULL AND ((po.\"outputProductId\" = ? AND po.\"batchNumber\" = ?) OR (out.\"productId\" = ?"
                + " AND out.\"batchNumber\" = ?)) ORDER BY po.\"postedAt\"").params(org, productId, batch, productId,
                batch))) {
            Map<String, Object> order = orderNode(o);
            List<Map<String, Object>> inputs = new ArrayList<>();
            for (Map<String, Object> m : SqlRows.list(db.jdbc().sql("SELECT m.\"productId\", m.\"productName\","
                    + " m.\"batchNumber\", SUM(m.\"qtyConsumed\") AS qty FROM \"ProductionMaterial\" m WHERE m.\"orderId\" = ?"
                    + " AND m.\"organizationId\" = ? GROUP BY m.\"productId\", m.\"productName\", m.\"batchNumber\"")
                    .params(o.get("id"), org))) {
                inputs.add(materialOrBatch(m, "backward", level, max, seen));
            }
            // FEFO-picked materials: the batch is only on the ledger row.
            for (Map<String, Object> m : ledgerBatches(o.get("orderCode"), "production_order", "production_consume")) {
                if (inputs.stream().noneMatch(x -> m.get("productId").equals(x.get("productId"))
                        && m.get("batchNumber").equals(x.get("batchNumber")))) {
                    inputs.add(batchNode(Q.str(m, "productId"), Q.str(m, "batchNumber"), Q.dec(m, "qty"), "backward",
                            level + 1, max, seen));
                }
            }
            order.put("children", inputs);
            children.add(order);
        }
        for (Map<String, Object> o : SqlRows.list(db.jdbc().sql("SELECT DISTINCT po.id, po.\"orderCode\","
                + " po.\"completedAt\", po.\"postedAt\", po.\"producedQty\" FROM \"InventoryLedger\" g JOIN \"InventoryBatch\""
                + " b ON b.id = g.\"batchId\" JOIN \"ProductionStagePosting\" p ON p.id = g.\"referenceId\" JOIN"
                + " \"ProductionOrder\" po ON po.id = p.\"orderId\" WHERE g.\"organizationId\" = ? AND g.\"productId\" = ? AND"
                + " b.\"batchNumber\" = ? AND g.\"referenceType\" = 'production_stage' AND g.\"quantityChange\" > 0 AND"
                + " p.\"reversedAt\" IS NULL").params(org, productId, batch))) {
            if (children.stream().anyMatch(c -> o.get("id").equals(c.get("ref")))) {
                continue;
            }
            Map<String, Object> order = orderNode(o);
            List<Map<String, Object>> inputs = new ArrayList<>();
            for (Map<String, Object> m : stageLots(o.get("id"), "< 0", "-SUM(g.\"quantityChange\")")) {
                inputs.add(batchNode(Q.str(m, "productId"), Q.str(m, "batchNumber"), Q.dec(m, "qty"), "backward",
                        level + 1, max, seen));
            }
            order.put("children", inputs);
            children.add(order);
        }
        for (Map<String, Object> d : SqlRows.list(db.jdbc().sql("SELECT d.\"downgradeQty\", d.\"decidedAt\","
                + " l.\"lotNumber\", l.\"productId\", l.\"batchNumber\", l.id AS \"lotId\" FROM \"QualityLotDecision\" d"
                + " JOIN \"QualityInspectionLot\" l ON l.id = d.\"lotId\" WHERE d.\"organizationId\" = ? AND"
                + " d.decision = 'downgrade' AND d.\"downgradeProductId\" = ? AND d.\"downgradeBatchNumber\" = ?")
                .params(org, productId, batch))) {
            Map<String, Object> n = node("downgrade", "downgrade|" + d.get("lotId"), Q.str(d, "lotNumber"));
            n.put("ref", d.get("lotId"));
            n.put("date", d.get("decidedAt"));
            n.put("quantity", d.get("downgradeQty"));
            n.put("children", d.get("batchNumber") == null ? List.of() : List.of(batchNode(Q.str(d, "productId"),
                    Q.str(d, "batchNumber"), null, "backward", level + 1, max, seen)));
            children.add(n);
        }
        for (Map<String, Object> r : ledgerReceipts(productId, batch)) {
            Map<String, Object> n = node("receipt", "ledger|" + r.get("referenceType") + "|" + r.get("referenceId"),
                    label(Q.str(r, "referenceType")) + (r.get("referenceId") == null ? "" : " " + r.get("referenceId")));
            n.put("ref", r.get("referenceId"));
            n.put("referenceType", r.get("referenceType"));
            n.put("quantity", r.get("qty"));
            n.put("date", r.get("at"));
            n.put("children", List.of());
            children.add(n);
        }
    }

    private Map<String, Object> materialOrBatch(Map<String, Object> m, String dir, int level, int max, Set<String> seen) {
        if (m.get("batchNumber") != null) {
            return batchNode(Q.str(m, "productId"), Q.str(m, "batchNumber"), Q.dec(m, "qty"), dir, level + 1, max, seen);
        }
        Map<String, Object> n = node("material", "material|" + m.get("productId"), Q.str(m, "productName"));
        n.put("productId", m.get("productId"));
        n.put("productName", m.get("productName"));
        n.put("quantity", m.get("qty"));
        n.put("untracked", true);
        n.put("children", List.of());
        return n;
    }

    /** Batch receipts not explained by a GRN / production / downgrade (opening balances, adjustments, returns). */
    private List<Map<String, Object>> ledgerReceipts(String productId, String batch) {
        return SqlRows.list(db.jdbc().sql("SELECT g.\"referenceType\", g.\"referenceId\", SUM(g.\"quantityChange\") AS qty,"
                + " MIN(g.\"createdAt\") AS at FROM \"InventoryLedger\" g JOIN \"InventoryBatch\" b ON b.id = g.\"batchId\""
                + " WHERE g.\"organizationId\" = ? AND g.\"productId\" = ? AND b.\"batchNumber\" = ? AND"
                + " g.\"quantityChange\" > 0 AND g.type::text IN ('receipt', 'opening_balance', 'adjustment_in', 'return_in',"
                + " 'reversal_in') AND COALESCE(g.\"referenceType\", '') NOT IN ('goods_receipt', 'production_order',"
                + " 'quality_downgrade', 'quality_lot', 'production_stage') GROUP BY g.\"referenceType\","
                + " g.\"referenceId\" ORDER BY at")
                .params(QualityDb.org(), productId, batch));
    }

    // ------------------------------------------------------------------ forward

    private void forward(String productId, String batch, int level, int max, Set<String> seen,
            List<Map<String, Object>> children) {
        String org = QualityDb.org();
        List<String> orders = new ArrayList<>();
        for (Map<String, Object> o : SqlRows.list(db.jdbc().sql("SELECT DISTINCT po.id, po.\"orderCode\","
                + " po.\"completedAt\", po.\"postedAt\", po.\"producedQty\", po.\"outputProductId\", po.\"batchNumber\""
                + " FROM \"ProductionOrder\" po WHERE po.\"organizationId\" = ? AND po.status <> 'cancelled' AND"
                + " (EXISTS (SELECT 1 FROM \"ProductionMaterial\" m WHERE m.\"orderId\" = po.id AND m.\"productId\" = ? AND"
                + " m.\"batchNumber\" = ?) OR po.\"orderCode\" IN (SELECT g.\"referenceId\" FROM \"InventoryLedger\" g JOIN"
                + " \"InventoryBatch\" b ON b.id = g.\"batchId\" WHERE g.\"organizationId\" = ? AND g.\"productId\" = ? AND"
                + " b.\"batchNumber\" = ? AND g.\"referenceType\" = 'production_order' AND g.type::text ="
                + " 'production_consume')) ORDER BY po.\"postedAt\" NULLS LAST").params(org, productId, batch, org,
                productId, batch))) {
            orders.add(Q.str(o, "orderCode"));
            Map<String, Object> order = orderNode(o);
            List<Map<String, Object>> outputs = new ArrayList<>();
            Set<String> outKeys = new HashSet<>();
            for (Map<String, Object> out : SqlRows.list(db.jdbc().sql("SELECT \"productId\", \"productName\","
                    + " \"batchNumber\", SUM(\"qtyProduced\") AS qty FROM \"ProductionOutput\" WHERE \"orderId\" = ? AND"
                    + " \"organizationId\" = ? AND \"batchNumber\" IS NOT NULL GROUP BY \"productId\", \"productName\","
                    + " \"batchNumber\"").params(o.get("id"), org))) {
                outKeys.add(out.get("productId") + "|" + out.get("batchNumber"));
                outputs.add(materialOrBatch(out, "forward", level, max, seen));
            }
            if (o.get("batchNumber") != null && outKeys.add(o.get("outputProductId") + "|" + o.get("batchNumber"))
                    && o.get("postedAt") != null) {
                outputs.add(batchNode(Q.str(o, "outputProductId"), Q.str(o, "batchNumber"), Q.dec(o, "producedQty"),
                        "forward", level + 1, max, seen));
            }
            order.put("children", outputs);
            children.add(order);
        }
        for (Map<String, Object> o : SqlRows.list(db.jdbc().sql("SELECT DISTINCT po.id, po.\"orderCode\","
                + " po.\"completedAt\", po.\"postedAt\", po.\"producedQty\" FROM \"InventoryLedger\" g JOIN \"InventoryBatch\""
                + " b ON b.id = g.\"batchId\" JOIN \"ProductionStagePosting\" p ON p.id = g.\"referenceId\" JOIN"
                + " \"ProductionOrder\" po ON po.id = p.\"orderId\" WHERE g.\"organizationId\" = ? AND g.\"productId\" = ? AND"
                + " b.\"batchNumber\" = ? AND g.\"referenceType\" = 'production_stage' AND g.\"quantityChange\" < 0 AND"
                + " p.\"reversedAt\" IS NULL AND po.status <> 'cancelled'").params(org, productId, batch))) {
            Map<String, Object> order = orderNode(o);
            List<Map<String, Object>> outputs = new ArrayList<>();
            for (Map<String, Object> m : stageLots(o.get("id"), "> 0", "SUM(g.\"quantityChange\")")) {
                outputs.add(batchNode(Q.str(m, "productId"), Q.str(m, "batchNumber"), Q.dec(m, "qty"), "forward",
                        level + 1, max, seen));
            }
            order.put("children", outputs);
            children.add(order);
        }
        Set<String> trips = new HashSet<>();
        for (Map<String, Object> t : SqlRows.list(db.jdbc().sql("SELECT t.id, t.code, t.\"deliveryNoteNumber\","
                + " t.\"dispatchedAt\", t.status, o.code AS \"orderCode\", pa.id AS \"partnerId\", pa.name AS \"partnerName\","
                + " s.id AS \"siteId\", s.name AS \"siteName\", SUM(COALESCE(ll.\"dispatchedQty\", ll.\"loadedQty\","
                + " ll.\"plannedQty\")) AS qty FROM \"FleetLoad\" t JOIN \"FleetLoadLine\" ll ON ll.\"loadId\" = t.id"
                + " JOIN \"FleetDeliveryOrder\" o ON o.id = t.\"deliveryOrderId\" LEFT JOIN \"Partner\" pa ON pa.id ="
                + " o.\"partnerId\" LEFT JOIN \"FleetCustomerSite\" s ON s.id = o.\"siteId\" WHERE t.\"organizationId\" = ?"
                + " AND t.\"stockIssuedAt\" IS NOT NULL AND ll.\"productId\" = ? AND (ll.\"batchNumber\" = ? OR t.id IN"
                + " (SELECT g.\"referenceId\" FROM \"InventoryLedger\" g JOIN \"InventoryBatch\" b ON b.id = g.\"batchId\""
                + " WHERE g.\"organizationId\" = ? AND g.\"productId\" = ? AND b.\"batchNumber\" = ? AND"
                + " g.\"referenceType\" = 'fleet_trip')) GROUP BY t.id, t.code, t.\"deliveryNoteNumber\", t.\"dispatchedAt\","
                + " t.status, o.code, pa.id, pa.name, s.id, s.name ORDER BY t.\"dispatchedAt\"").params(org, productId,
                batch, org, productId, batch))) {
            trips.add(Q.str(t, "id"));
            Map<String, Object> n = node("delivery", "trip|" + t.get("id"), Q.str(t, "code")
                    + (t.get("deliveryNoteNumber") == null ? "" : " · " + t.get("deliveryNoteNumber")));
            n.put("ref", t.get("id"));
            n.put("deliveryOrder", t.get("orderCode"));
            n.put("status", t.get("status"));
            n.put("date", t.get("dispatchedAt"));
            n.put("quantity", t.get("qty"));
            List<Map<String, Object>> leaf = new ArrayList<>();
            if (t.get("partnerId") != null) {
                Map<String, Object> c = node("customer", "partner|" + t.get("partnerId"), Q.str(t, "partnerName"));
                c.put("ref", t.get("partnerId"));
                c.put("children", t.get("siteId") == null ? List.of() : List.of(siteNode(t)));
                leaf.add(c);
            }
            n.put("children", leaf);
            children.add(n);
        }
        for (Map<String, Object> d : SqlRows.list(db.jdbc().sql("SELECT d.\"downgradeProductId\","
                + " d.\"downgradeBatchNumber\", d.\"downgradeQty\", d.\"decidedAt\", l.\"lotNumber\", l.id AS \"lotId\""
                + " FROM \"QualityLotDecision\" d JOIN \"QualityInspectionLot\" l ON l.id = d.\"lotId\" WHERE"
                + " d.\"organizationId\" = ? AND d.decision = 'downgrade' AND l.\"productId\" = ? AND l.\"batchNumber\" = ?")
                .params(org, productId, batch))) {
            Map<String, Object> n = node("downgrade", "downgrade|" + d.get("lotId"), Q.str(d, "lotNumber"));
            n.put("ref", d.get("lotId"));
            n.put("date", d.get("decidedAt"));
            n.put("quantity", d.get("downgradeQty"));
            n.put("children", d.get("downgradeProductId") == null || d.get("downgradeBatchNumber") == null ? List.of()
                    : List.of(batchNode(Q.str(d, "downgradeProductId"), Q.str(d, "downgradeBatchNumber"),
                            Q.dec(d, "downgradeQty"), "forward", level + 1, max, seen)));
            children.add(n);
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT g.type::text AS type, g.\"referenceType\","
                + " g.\"referenceId\", -SUM(g.\"quantityChange\") AS qty, MIN(g.\"createdAt\") AS at FROM \"InventoryLedger\" g"
                + " JOIN \"InventoryBatch\" b ON b.id = g.\"batchId\" WHERE g.\"organizationId\" = ? AND g.\"productId\" = ?"
                + " AND b.\"batchNumber\" = ? AND g.\"quantityChange\" < 0 AND COALESCE(g.\"referenceType\", '') NOT IN"
                + " ('production_order', 'production_stage', 'fleet_trip', 'quality_downgrade') GROUP BY g.type,"
                + " g.\"referenceType\","
                + " g.\"referenceId\" ORDER BY at").params(org, productId, batch))) {
            Map<String, Object> n = node("issue", "ledger|" + r.get("type") + "|" + r.get("referenceType") + "|"
                    + r.get("referenceId"), label(Q.str(r, "type")) + (r.get("referenceId") == null ? ""
                    : " · " + label(Q.str(r, "referenceType")) + " " + r.get("referenceId")));
            n.put("ref", r.get("referenceId"));
            n.put("referenceType", r.get("referenceType"));
            n.put("moveType", r.get("type"));
            n.put("quantity", r.get("qty"));
            n.put("date", r.get("at"));
            n.put("children", List.of());
            children.add(n);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Lots a stage order consumed or produced, from its stage ledger rows (ADR-S060). */
    private List<Map<String, Object>> stageLots(Object orderId, String sign, String qty) {
        return SqlRows.list(db.jdbc().sql("SELECT g.\"productId\", b.\"batchNumber\", " + qty + " AS qty FROM"
                + " \"InventoryLedger\" g JOIN \"InventoryBatch\" b ON b.id = g.\"batchId\" JOIN \"ProductionStagePosting\" p"
                + " ON p.id = g.\"referenceId\" WHERE g.\"organizationId\" = ? AND g.\"referenceType\" = 'production_stage'"
                + " AND g.\"quantityChange\" " + sign + " AND p.\"orderId\" = ? AND p.\"reversedAt\" IS NULL GROUP BY"
                + " g.\"productId\", b.\"batchNumber\"").params(QualityDb.org(), orderId));
    }

    private List<Map<String, Object>> ledgerBatches(Object referenceId, String referenceType, String type) {
        return SqlRows.list(db.jdbc().sql("SELECT g.\"productId\", b.\"batchNumber\", -SUM(g.\"quantityChange\") AS qty"
                + " FROM \"InventoryLedger\" g JOIN \"InventoryBatch\" b ON b.id = g.\"batchId\" WHERE"
                + " g.\"organizationId\" = ? AND g.\"referenceType\" = ? AND g.\"referenceId\" = ? AND g.type::text = ?"
                + " GROUP BY g.\"productId\", b.\"batchNumber\"").params(QualityDb.org(), referenceType, referenceId, type));
    }

    private static Map<String, Object> orderNode(Map<String, Object> o) {
        Map<String, Object> order = node("production_order", "order|" + o.get("id"), Q.str(o, "orderCode"));
        order.put("ref", o.get("id"));
        order.put("date", o.get("completedAt") != null ? o.get("completedAt") : o.get("postedAt"));
        order.put("quantity", o.get("producedQty"));
        return order;
    }

    private static Map<String, Object> siteNode(Map<String, Object> t) {
        Map<String, Object> s = node("site", "site|" + t.get("siteId"), Q.str(t, "siteName"));
        s.put("ref", t.get("siteId"));
        s.put("children", List.of());
        return s;
    }

    private static Map<String, Object> node(String kind, String key, String label) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("kind", kind);
        n.put("key", key);
        n.put("label", label);
        return n;
    }

    private static String label(String code) {
        return code == null ? "Stock" : code.replace('_', ' ');
    }

    private String productName(String productId) {
        return db.jdbc().sql("SELECT name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, QualityDb.org()).query(String.class).optional().orElse(null);
    }
}
