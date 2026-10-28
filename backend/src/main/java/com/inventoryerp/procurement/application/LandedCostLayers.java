package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.kernel.common.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Re-costing of the stock one goods receipt put away (shared by the legacy landed cost and the ADR-S007 voucher). The
 * share of a receipt movement still on hand raises its cost layer — the lot, the receipt's serials or the AVCO
 * running average — and the rest has already been consumed (→ COGS). STANDARD items are never rebased. With
 * {@code write = false} nothing is changed (allocation preview).
 */
@Component
class LandedCostLayers {

    private static final int SIX = 6;

    /** One receipt movement: what the receipt put into stock for (product, variant, location, lot). */
    record Group(Map<String, Object> product, String productId, String variantId, String locationId, String batchId,
            BigDecimal[] qty, BigDecimal[] value) {}

    /** Share capitalised into the layer, quantity it was spread over, unit cost before / after. */
    record Applied(BigDecimal capitalized, BigDecimal onHand, BigDecimal unitBefore, BigDecimal unitAfter) {
        static final Applied NONE = new Applied(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);
    }

    private final JdbcClient jdbc;

    LandedCostLayers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static BigDecimal mul(BigDecimal a, BigDecimal b) {
        return a.multiply(b, Money.DECIMAL_JS);
    }

    private static BigDecimal dp6(BigDecimal v) {
        return v.setScale(SIX, RoundingMode.HALF_UP);
    }

    /** Raise the cost of whatever part of one receipt movement is still on hand; returns the capitalised share. */
    Applied apply(String grnId, Group g, BigDecimal allocated, boolean write) {
        BigDecimal qty = g.qty()[0];
        if (allocated.signum() <= 0 || qty.signum() <= 0) {
            return Applied.NONE;
        }
        String orgId = org();
        String variantKey = g.variantId() == null ? "" : g.variantId();
        String lock = write ? " FOR UPDATE" : "";

        if (Boolean.TRUE.equals(g.product().get("serialTracking"))) {
            List<Map<String, Object>> serials = jdbc.sql("SELECT id, \"unitCost\" FROM \"InventorySerial\""
                            + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"receiptRef\" = ? AND status = 'in_stock'"
                            + lock)
                    .params(orgId, g.productId(), "goods_receipt:" + grnId).query().listOfRows();
            BigDecimal perUnit = Money.div(allocated, qty);
            BigDecimal onHand = Money.min(BigDecimal.valueOf(serials.size()), qty);
            BigDecimal first = serials.isEmpty() ? Money.ZERO : Money.dec(serials.getFirst().get("unitCost"));
            int n = (int) Math.min(serials.size(), Math.max(0, Math.floor(onHand.doubleValue())));
            if (write) {
                for (Map<String, Object> s : serials.subList(0, n)) {
                    jdbc.sql("UPDATE \"InventorySerial\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                                    + " AND \"organizationId\" = ?")
                            .params(Money.dec(s.get("unitCost")).add(perUnit), s.get("id"), orgId).update();
                }
            }
            return new Applied(dp6(mul(perUnit, onHand)), onHand, first, first.add(perUnit));
        }

        if (g.batchId() != null) {
            List<Map<String, Object>> found = jdbc.sql("SELECT id, quantity, \"unitCost\" FROM \"InventoryBatch\" WHERE"
                    + " id = ? AND \"organizationId\" = ?" + lock).params(g.batchId(), orgId).query().listOfRows();
            if (found.isEmpty()) {
                return Applied.NONE;
            }
            Map<String, Object> batch = found.getFirst();
            BigDecimal batchQty = Money.dec(batch.get("quantity"));
            BigDecimal before = Money.dec(batch.get("unitCost"));
            BigDecimal onHand = Money.max(Money.ZERO, Money.min(batchQty, qty));
            if (onHand.signum() <= 0) {
                return new Applied(Money.ZERO, Money.ZERO, before, before);
            }
            BigDecimal capitalized = dp6(Money.div(mul(allocated, onHand), qty));
            BigDecimal after = before.add(Money.div(capitalized, batchQty));
            if (write) {
                jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ?").params(after, batch.get("id"), orgId).update();
            }
            return new Applied(capitalized, onHand, before, after);
        }

        if ("STANDARD".equals(g.product().get("costingMethod"))) {
            return Applied.NONE; // standard cost is not rebased by a single receipt's freight
        }
        return bumpAverage(g, variantKey, allocated, write);
    }

    /** AVCO quant: same advisory lock as the stock engine, then raise the running average. */
    private Applied bumpAverage(Group g, String variantKey, BigDecimal amount, boolean write) {
        String orgId = org();
        if (write) {
            String lockKey = "avco:" + orgId + ":" + g.productId() + ":" + variantKey + ":" + g.locationId();
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param(lockKey).query().listOfRows();
        }
        List<Map<String, Object>> found = jdbc.sql("SELECT id, quantity, \"runningAverageCost\" FROM \"StockItem\""
                        + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?"
                        + (write ? " FOR UPDATE" : ""))
                .params(orgId, g.productId(), variantKey, g.locationId()).query().listOfRows();
        if (found.isEmpty()) {
            return Applied.NONE;
        }
        Map<String, Object> item = found.getFirst();
        BigDecimal qty = Money.dec(item.get("quantity"));
        BigDecimal before = Money.dec(item.get("runningAverageCost"));
        if (qty.signum() <= 0) {
            return new Applied(Money.ZERO, Money.ZERO, before, before);
        }
        // Units of this receipt assumed still on hand: at most what is on hand now.
        BigDecimal onHand = Money.min(qty, g.qty()[0]);
        BigDecimal capitalized = dp6(Money.div(mul(amount, onHand), g.qty()[0]));
        BigDecimal after = before.add(Money.div(capitalized, qty));
        if (write) {
            jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(after, item.get("id")).update();
        }
        return new Applied(capitalized, onHand, before, after);
    }

    /**
     * Undo one allocation's capitalisation as far as the goods are still on hand. The per-unit increment it added is
     * {@code unitCostAfter − unitCostBefore}; it is taken back from what is left of the layer. Returns the amount
     * removed from stock (≤ {@code capitalized}); the remainder was consumed since and is credited back to COGS.
     */
    BigDecimal remove(Map<String, Object> alloc, Map<String, Object> product, String grnId) {
        BigDecimal capitalized = Money.dec(alloc.get("capitalizedAmount"));
        BigDecimal increment = Money.dec(alloc.get("unitCostAfter")).subtract(Money.dec(alloc.get("unitCostBefore")));
        BigDecimal onHandAtPost = Money.dec(alloc.get("onHandQty"));
        if (capitalized.signum() <= 0 || increment.signum() <= 0) {
            return Money.ZERO;
        }
        String orgId = org();
        String productId = (String) alloc.get("productId");
        if (Boolean.TRUE.equals(product.get("serialTracking"))) {
            List<Map<String, Object>> serials = jdbc.sql("SELECT id, \"unitCost\" FROM \"InventorySerial\" WHERE"
                            + " \"organizationId\" = ? AND \"productId\" = ? AND \"receiptRef\" = ? AND status = 'in_stock'"
                            + " FOR UPDATE")
                    .params(orgId, productId, "goods_receipt:" + grnId).query().listOfRows();
            int n = (int) Math.min(serials.size(), Math.floor(onHandAtPost.doubleValue()));
            for (Map<String, Object> s : serials.subList(0, n)) {
                jdbc.sql("UPDATE \"InventorySerial\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                        .params(Money.max(Money.ZERO, Money.dec(s.get("unitCost")).subtract(increment)), s.get("id"))
                        .update();
            }
            return Money.min(capitalized, dp6(mul(increment, BigDecimal.valueOf(n))));
        }
        if (alloc.get("batchId") != null) {
            List<Map<String, Object>> found = jdbc.sql("SELECT id, quantity, \"unitCost\" FROM \"InventoryBatch\" WHERE"
                    + " id = ? AND \"organizationId\" = ? FOR UPDATE").params(alloc.get("batchId"), orgId).query()
                    .listOfRows();
            if (found.isEmpty()) {
                return Money.ZERO;
            }
            BigDecimal q = Money.dec(found.getFirst().get("quantity"));
            if (q.signum() <= 0) {
                return Money.ZERO;
            }
            BigDecimal removed = Money.min(capitalized, dp6(mul(increment, q)));
            jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(Money.max(Money.ZERO, Money.dec(found.getFirst().get("unitCost")).subtract(
                            Money.div(removed, q))), found.getFirst().get("id"))
                    .update();
            return removed;
        }
        if ("STANDARD".equals(product.get("costingMethod"))) {
            return Money.ZERO;
        }
        String variantKey = alloc.get("variantId") == null ? "" : alloc.get("variantId").toString();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("avco:" + orgId + ":" + productId + ":"
                + variantKey + ":" + alloc.get("locationId")).query().listOfRows();
        List<Map<String, Object>> found = jdbc.sql("SELECT id, quantity, \"runningAverageCost\" FROM \"StockItem\""
                        + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?"
                        + " FOR UPDATE")
                .params(orgId, productId, variantKey, alloc.get("locationId")).query().listOfRows();
        if (found.isEmpty()) {
            return Money.ZERO;
        }
        BigDecimal q = Money.dec(found.getFirst().get("quantity"));
        BigDecimal avg = Money.dec(found.getFirst().get("runningAverageCost"));
        if (q.signum() <= 0) {
            return Money.ZERO;
        }
        // At most the capitalised amount, at most what the remaining quantity carries.
        BigDecimal removed = Money.min(capitalized, Money.min(dp6(mul(avg, q)), dp6(mul(increment, q))));
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(avg.subtract(Money.div(removed, q)), found.getFirst().get("id")).update();
        return removed;
    }
}
