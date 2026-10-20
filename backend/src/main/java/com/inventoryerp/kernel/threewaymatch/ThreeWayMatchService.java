package com.inventoryerp.kernel.threewaymatch;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Three-way match (port of {@code three-way-match.service.ts}): per PO line compares ordered vs received vs billed
 * quantities and prices; variances beyond the org tolerance (FeatureFlag {@code procurement.match_tolerance},
 * default 2 % price / 0 qty) are {@code blocked}. A read model: never mutates the order.
 */
@Service
public class ThreeWayMatchService {

    private static final Logger log = LoggerFactory.getLogger(ThreeWayMatchService.class);

    private final JdbcClient jdbc;
    private final EventBus events;

    public ThreeWayMatchService(JdbcClient jdbc, EventBus events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    private static double n(Object v) {
        return v == null ? 0 : ((BigDecimal) v).doubleValue();
    }

    /** Recompute every line of a PO; returns the upserted match rows. */
    @Transactional
    public List<Map<String, Object>> recomputeForOrder(String purchaseOrderId) {
        String org = TenantContext.organizationId();
        long exists = jdbc.sql("SELECT count(*) FROM \"PurchaseOrder\" WHERE \"organizationId\" = ? AND id = ?")
                .params(org, purchaseOrderId).query(Long.class).single();
        if (exists == 0) {
            log.warn("recomputeForOrder: PO {} not found in org {}", purchaseOrderId, org);
            return List.of();
        }
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT * FROM \"PurchaseOrderLine\" WHERE"
                + " \"organizationId\" = ? AND \"purchaseOrderId\" = ? ORDER BY \"lineNumber\" ASC")
                .params(org, purchaseOrderId));
        if (lines.isEmpty()) {
            return List.of();
        }
        double orderedValue = 0;
        for (Map<String, Object> l : lines) {
            orderedValue += n(l.get("quantity")) * n(l.get("unitPrice"));
        }
        List<Map<String, Object>> links = SqlRows.list(jdbc.sql("SELECT amount FROM \"VendorBillLink\" WHERE"
                + " \"organizationId\" = ? AND \"purchaseOrderId\" = ?").params(org, purchaseOrderId));
        double tolerancePct = priceTolerancePct(org);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            double receivedQty = n(jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"GoodsReceiptLine\" WHERE"
                    + " \"organizationId\" = ? AND \"purchaseOrderLineId\" = ?").params(org, line.get("id"))
                    .query(BigDecimal.class).single());
            double billedAmount = 0;
            for (Map<String, Object> link : links) {
                if (orderedValue <= 0) {
                    continue;
                }
                double lineValue = n(line.get("quantity")) * n(line.get("unitPrice"));
                billedAmount += (lineValue / orderedValue) * n(link.get("amount"));
            }
            double unitPrice = n(line.get("unitPrice"));
            double billedQty = unitPrice > 0 ? billedAmount / unitPrice : 0;
            double orderedQty = n(line.get("quantity"));
            double qtyVariance = billedQty - receivedQty;
            double billedUnitPrice = billedQty > 0 ? billedAmount / billedQty : unitPrice;
            double priceVariance = billedUnitPrice - unitPrice;
            boolean exceeded = Math.abs(qtyVariance) > 0.0001 || Math.abs(priceVariance) > unitPrice * tolerancePct;
            String status;
            if (receivedQty == 0 && billedQty == 0) {
                status = "pending";
            } else if (Math.abs(qtyVariance) < 0.0001 && Math.abs(priceVariance) < unitPrice * tolerancePct) {
                status = "matched";
            } else if (exceeded) {
                status = "blocked";
            } else {
                status = "partial";
            }
            Map<String, Object> row = SqlRows.one(jdbc.sql("INSERT INTO \"ThreeWayMatch\" (id, \"organizationId\","
                            + " \"purchaseOrderId\", \"purchaseOrderLineId\", \"productId\", \"orderedQuantity\","
                            + " \"receivedQuantity\", \"billedQuantity\", \"orderedUnitPrice\", \"billedUnitPrice\","
                            + " \"quantityVariance\", \"priceVariance\", status, \"thresholdExceeded\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::\"MatchStatus\", ?, now())"
                            + " ON CONFLICT (\"purchaseOrderLineId\") DO UPDATE SET \"receivedQuantity\" = EXCLUDED.\"receivedQuantity\","
                            + " \"billedQuantity\" = EXCLUDED.\"billedQuantity\", \"orderedQuantity\" = EXCLUDED.\"orderedQuantity\","
                            + " \"orderedUnitPrice\" = EXCLUDED.\"orderedUnitPrice\", \"billedUnitPrice\" = EXCLUDED.\"billedUnitPrice\","
                            + " \"quantityVariance\" = EXCLUDED.\"quantityVariance\", \"priceVariance\" = EXCLUDED.\"priceVariance\","
                            + " status = EXCLUDED.status, \"thresholdExceeded\" = EXCLUDED.\"thresholdExceeded\","
                            + " \"lastCheckedAt\" = now(), \"updatedAt\" = now() RETURNING *")
                    .params(UUID.randomUUID().toString(), org, purchaseOrderId, line.get("id"), line.get("productId"),
                            dec(orderedQty), dec(receivedQty), dec(billedQty), dec(unitPrice), dec(billedUnitPrice),
                            dec(qtyVariance), dec(priceVariance), status, exceeded));
            results.add(row);
        }
        long blocked = results.stream().filter(r -> "blocked".equals(r.get("status"))).count();
        long matched = results.stream().filter(r -> "matched".equals(r.get("status"))).count();
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org);
        payload.put("purchaseOrderId", purchaseOrderId);
        payload.put("matched", matched);
        payload.put("mismatched", results.size() - matched);
        payload.put("blocked", blocked);
        events.publish("three_way_match.computed", payload);
        return results;
    }

    private static BigDecimal dec(double v) {
        return new BigDecimal(JsNumbers.toJsString(v));
    }

    /** Match rows (latest first, max 200) with the order's number and partner. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String purchaseOrderId) {
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ?");
        if (status != null && !status.isEmpty()) {
            where.append(" AND status = ?::\"MatchStatus\"");
            params.add(status);
        }
        if (purchaseOrderId != null && !purchaseOrderId.isEmpty()) {
            where.append(" AND \"purchaseOrderId\" = ?");
            params.add(purchaseOrderId);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM \"ThreeWayMatch\" WHERE" + where
                + " ORDER BY \"lastCheckedAt\" DESC LIMIT 200").params(params));
        for (Map<String, Object> r : rows) {
            r.put("order", SqlRows.one(jdbc.sql("SELECT \"orderNumber\", \"partnerId\" FROM \"PurchaseOrder\" WHERE id = ?")
                    .param(r.get("purchaseOrderId"))));
        }
        return rows;
    }

    /** {@code {ok, matches}} for every PO linked to a vendor bill (recomputed first). */
    @Transactional
    public Map<String, Object> validateBillForPosting(String vendorBillId) {
        String org = TenantContext.organizationId();
        List<String> orders = jdbc.sql("SELECT \"purchaseOrderId\" FROM \"VendorBillLink\" WHERE \"organizationId\" = ?"
                + " AND \"vendorBillId\" = ?").params(org, vendorBillId).query(String.class).list();
        List<Map<String, Object>> matches = new ArrayList<>();
        boolean ok = true;
        for (String po : orders) {
            recomputeForOrder(po);
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM \"ThreeWayMatch\" WHERE \"organizationId\" = ?"
                    + " AND \"purchaseOrderId\" = ?").params(org, po))) {
                matches.add(r);
                if ("blocked".equals(r.get("status"))) {
                    ok = false;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("matches", matches);
        return out;
    }

    private double priceTolerancePct(String org) {
        Object payload = SqlRows.value(jdbc.sql("SELECT payload FROM \"FeatureFlag\" WHERE \"organizationId\" = ?"
                + " AND key = 'procurement.match_tolerance'").param(org).query((rs, i) -> rs.getObject(1)).optional()
                .orElse(null));
        if (payload == null) {
            return 0.02;
        }
        JsonNode node = payload instanceof JsonNode j ? j : null;
        if (node == null || !node.has("priceTolerancePct")) {
            return 0.02;
        }
        double pct = JsNumbers.number(node.get("priceTolerancePct").asString());
        return Double.isFinite(pct) ? pct : 0.02;
    }
}
