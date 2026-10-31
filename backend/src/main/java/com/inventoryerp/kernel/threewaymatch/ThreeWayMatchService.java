package com.inventoryerp.kernel.threewaymatch;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Three-way match (port of {@code three-way-match.service.ts}, corrected post-parity — P1-1): per PO line compares
 * ordered vs received (posted receipts) vs billed (net bill lines) quantities and prices in BigDecimal; variances beyond
 * the org tolerance (FeatureFlag {@code procurement.match_tolerance}, default 2 % price / 0 qty) are {@code blocked}.
 * A read model: never mutates the order.
 */
@Service
public class ThreeWayMatchService {

    private static final Logger log = LoggerFactory.getLogger(ThreeWayMatchService.class);

    private final JdbcClient jdbc;
    private final EventBus events;

    private final org.springframework.beans.factory.ObjectProvider<MatchToleranceProvider> tolerances;

    public ThreeWayMatchService(JdbcClient jdbc, EventBus events,
            org.springframework.beans.factory.ObjectProvider<MatchToleranceProvider> tolerances) {
        this.jdbc = jdbc;
        this.events = events;
        this.tolerances = tolerances;
    }

    private static BigDecimal n(Object v) {
        return v == null ? BigDecimal.ZERO : v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    private static final BigDecimal EPS = new BigDecimal("0.0001");

    /** Recompute every line of a PO; returns the upserted match rows. */
    @Transactional
    public List<Map<String, Object>> recomputeForOrder(String purchaseOrderId) {
        return recomputeForOrder(purchaseOrderId, null);
    }

    /**
     * Recompute every line of a PO (P1-1, post-parity): received = posted (not reversed / draft) receipt lines; billed =
     * the net (ex-tax) lines of the PO's posted or paid bills, plus {@code includeBillId} when a draft bill is being
     * validated before posting. Product lines match bill lines of the same product (spread over the PO lines of that
     * product in line order); lines without a product share the bills' product-less net amount by ordered value.
     */
    @Transactional
    public List<Map<String, Object>> recomputeForOrder(String purchaseOrderId, String includeBillId) {
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
        // Bill lines (net) of the bills linked to this order.
        List<Map<String, Object>> billLines = SqlRows.list(jdbc.sql("SELECT dl.\"productId\", dl.quantity, dl.subtotal,"
                + " dl.\"uomFactor\" FROM \"DocumentLine\" dl JOIN \"Document\" d ON d.id = dl.\"documentId\" WHERE"
                + " d.\"organizationId\" = ? AND d.\"documentType\" = 'vendor_bill'"
                + " AND (d.status IN ('posted', 'paid') OR d.id = ?::text) AND d.id IN (SELECT \"vendorBillId\" FROM"
                + " \"VendorBillLink\" WHERE \"organizationId\" = ? AND \"purchaseOrderId\" = ?)")
                .params(org, includeBillId, org, purchaseOrderId));
        Map<String, BigDecimal[]> billedByProduct = new HashMap<>(); // productId -> {qty, net}
        BigDecimal unlinkedNet = BigDecimal.ZERO;
        for (Map<String, Object> bl : billLines) {
            Object pid = bl.get("productId");
            if (pid == null) {
                unlinkedNet = unlinkedNet.add(n(bl.get("subtotal")));
                continue;
            }
            BigDecimal[] acc = billedByProduct.computeIfAbsent((String) pid, k -> new BigDecimal[] {BigDecimal.ZERO,
                    BigDecimal.ZERO});
            acc[0] = acc[0].add(n(bl.get("quantity")));
            acc[1] = acc[1].add(n(bl.get("subtotal")));
        }
        BigDecimal productlessOrdered = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            if (l.get("productId") == null) {
                productlessOrdered = productlessOrdered.add(n(l.get("quantity")).multiply(n(l.get("unitPrice"))));
            }
        }
        MatchToleranceProvider provider = tolerances.getIfAvailable();
        MatchToleranceProvider.Tolerance rule = provider == null ? null : provider.forOrder(purchaseOrderId);
        BigDecimal tolerancePct = rule != null && rule.priceFraction() != null ? rule.priceFraction()
                : BigDecimal.valueOf(priceTolerancePct(org));
        BigDecimal qtyFraction = rule != null && rule.qtyFraction() != null ? rule.qtyFraction() : BigDecimal.ZERO;
        boolean twoWay = rule != null && "two_way".equals(rule.mode());
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            BigDecimal receivedQty = n(jdbc.sql("SELECT COALESCE(SUM(l.quantity), 0) FROM \"GoodsReceiptLine\" l JOIN"
                    + " \"GoodsReceiptNote\" g ON g.id = l.\"goodsReceiptId\" WHERE l.\"organizationId\" = ?"
                    + " AND l.\"purchaseOrderLineId\" = ? AND g.status = 'posted' AND g.\"deletedAt\" IS NULL")
                    .params(org, line.get("id")).query(BigDecimal.class).single());
            BigDecimal unitPrice = n(line.get("unitPrice"));
            BigDecimal orderedQty = n(line.get("quantity"));
            BigDecimal billedQty;
            BigDecimal billedAmount;
            Object pid = line.get("productId");
            if (pid != null) {
                BigDecimal[] acc = billedByProduct.get((String) pid);
                if (acc == null || acc[0].signum() <= 0) {
                    billedQty = BigDecimal.ZERO;
                    billedAmount = BigDecimal.ZERO;
                } else {
                    // Consume the product's billed quantity line by line, up to each line's ordered quantity; the
                    // last line of the product takes any excess (an over-bill is a variance, not lost).
                    boolean lastOfProduct = lines.stream().filter(o -> pid.equals(o.get("productId")))
                            .reduce((a, b) -> b).orElse(line) == line;
                    BigDecimal take = lastOfProduct ? acc[0] : acc[0].min(orderedQty);
                    BigDecimal unit = acc[1].divide(acc[0], 6, RoundingMode.HALF_UP);
                    billedQty = take;
                    billedAmount = unit.multiply(take);
                    acc[1] = acc[1].subtract(billedAmount);
                    acc[0] = acc[0].subtract(take);
                }
            } else {
                BigDecimal value = orderedQty.multiply(unitPrice);
                billedAmount = productlessOrdered.signum() > 0
                        ? unlinkedNet.multiply(value).divide(productlessOrdered, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                billedQty = unitPrice.signum() > 0 ? billedAmount.divide(unitPrice, 6, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            }
            // Two-way matching (services, direct deliveries) compares the bill with the order, not the receipt.
            BigDecimal referenceQty = twoWay ? orderedQty : receivedQty;
            BigDecimal qtyVariance = billedQty.subtract(referenceQty);
            BigDecimal billedUnitPrice = billedQty.signum() > 0 ? billedAmount.divide(billedQty, 6, RoundingMode.HALF_UP)
                    : unitPrice;
            BigDecimal priceVariance = billedUnitPrice.subtract(unitPrice);
            BigDecimal priceLimit = unitPrice.multiply(tolerancePct);
            boolean qtyOk = qtyVariance.abs().compareTo(EPS) < 0 || qtyVariance.signum() > 0
                    && qtyVariance.compareTo(referenceQty.multiply(qtyFraction)) <= 0;
            boolean priceOk = priceVariance.abs().compareTo(priceLimit) <= 0 || priceVariance.abs().compareTo(EPS) < 0;
            boolean exceeded = !qtyOk || !priceOk;
            String status;
            if (referenceQty.signum() == 0 && billedQty.signum() == 0 || twoWay && billedQty.signum() == 0) {
                status = "pending";
            } else if (qtyOk && priceOk) {
                status = "matched";
            } else if (billedQty.signum() == 0 || billedQty.compareTo(referenceQty) < 0 && priceOk) {
                // Received but not (fully) billed yet: still in progress, not an exception.
                status = "partial";
                exceeded = false;
            } else {
                status = "blocked";
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
                            scale(orderedQty), scale(receivedQty), scale(billedQty), scale(unitPrice),
                            scale(billedUnitPrice), scale(qtyVariance), scale(priceVariance), status, exceeded));
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

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
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
            r.put("order", SqlRows.one(jdbc.sql("SELECT \"orderNumber\", \"partnerId\" FROM \"PurchaseOrder\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(r.get("purchaseOrderId"), TenantContext.organizationId())));
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
            recomputeForOrder(po, vendorBillId);
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
