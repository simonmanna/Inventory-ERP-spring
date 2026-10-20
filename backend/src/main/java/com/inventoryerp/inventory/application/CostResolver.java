package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.common.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pure costing math, polymorphic on {@code product.costingMethod} (port of {@code cost-resolver.service.ts}):
 * AVCO weighted average recomputed on receipt, FIFO across batch layers, STANDARD at the product cost price,
 * SPECIFIC at the identified units' receipt cost. Touches no database.
 */
@Component
public class CostResolver {

    /** Costing inputs of a product. */
    public record ProductCost(String costingMethod, BigDecimal costPrice) {}

    /** On-hand quantity and running average of a quant. */
    public record Quant(BigDecimal quantity, BigDecimal runningAverageCost) {}

    /** One FIFO cost layer. */
    public record Layer(BigDecimal quantity, BigDecimal unitCost, Instant expiryDate, Instant receivedAt) {}

    /**
     * @param newRunningAverage AVCO receipt only: the recomputed average ({@code null} otherwise)
     * @param costCorrection    AVCO receipt onto negative on-hand: residual to post to COGS ({@code null} when zero)
     */
    public record Resolution(BigDecimal unitCost, BigDecimal totalValue, BigDecimal newRunningAverage,
            BigDecimal costCorrection) {

        static Resolution of(BigDecimal unitCost, BigDecimal totalValue) {
            return new Resolution(unitCost, totalValue, null, null);
        }
    }

    /** Unit cost for issuing {@code quantity} units. */
    public Resolution resolveIssueCost(ProductCost product, Quant stockItem, BigDecimal quantity, List<Layer> batches,
            List<BigDecimal> specificUnitCosts) {
        if (quantity.signum() <= 0) {
            return Resolution.of(Money.ZERO, Money.ZERO);
        }
        BigDecimal costPrice = product.costPrice();
        switch (product.costingMethod()) {
            case "AVCO" -> {
                if (stockItem == null || stockItem.quantity().signum() <= 0) {
                    throw new IllegalStateException("No stock to issue");
                }
                BigDecimal unitCost = stockItem.runningAverageCost();
                return Resolution.of(unitCost, unitCost.multiply(quantity));
            }
            case "FIFO" -> {
                if (batches == null || batches.isEmpty()) {
                    throw new IllegalStateException("FIFO costing requires at least one batch");
                }
                List<Layer> sorted = new ArrayList<>(batches);
                sorted.sort(fifoOrder());
                BigDecimal remaining = quantity;
                BigDecimal totalCost = Money.ZERO;
                for (Layer b : sorted) {
                    if (remaining.signum() <= 0) {
                        break;
                    }
                    BigDecimal take = Money.min(remaining, b.quantity());
                    BigDecimal unitCost = b.unitCost() == null ? Money.ZERO : b.unitCost();
                    totalCost = totalCost.add(unitCost.multiply(take));
                    remaining = remaining.subtract(take);
                }
                if (remaining.signum() > 0) {
                    throw new IllegalStateException("Insufficient batch stock to cover " + quantity.toPlainString()
                            + " (short by " + remaining.toPlainString() + ")");
                }
                return Resolution.of(Money.div(totalCost, quantity), totalCost);
            }
            case "STANDARD" -> {
                BigDecimal unitCost = costPrice == null ? Money.ZERO : costPrice;
                return Resolution.of(unitCost, unitCost.multiply(quantity));
            }
            case "SPECIFIC" -> {
                if (specificUnitCosts != null && !specificUnitCosts.isEmpty()) {
                    BigDecimal total = Money.sum(specificUnitCosts);
                    return Resolution.of(Money.div(total, quantity), total);
                }
                if (batches != null && !batches.isEmpty()) {
                    return resolveIssueCost(new ProductCost("FIFO", costPrice), stockItem, quantity, batches, null);
                }
                BigDecimal unitCost = costPrice == null ? Money.ZERO : costPrice;
                return Resolution.of(unitCost, unitCost.multiply(quantity));
            }
            default -> throw new IllegalStateException("Unknown costing method: " + product.costingMethod());
        }
    }

    /** Expiry ascending (nulls last), then receipt ascending — the FIFO sort of the Nest resolver. */
    private static Comparator<Layer> fifoOrder() {
        return (a, b) -> {
            if (a.expiryDate() != null && b.expiryDate() != null) {
                return a.expiryDate().compareTo(b.expiryDate());
            }
            if (a.expiryDate() != null) {
                return -1;
            }
            if (b.expiryDate() != null) {
                return 1;
            }
            return a.receivedAt().compareTo(b.receivedAt());
        };
    }

    /** New running average after a receipt (AVCO only), plus the negative-on-hand cost correction. */
    public Resolution resolveReceiptCost(ProductCost product, Quant stockItem, BigDecimal receiptQty,
            BigDecimal receiptUnitCost) {
        if (receiptQty.signum() <= 0) {
            throw new IllegalStateException("Receipt quantity must be positive");
        }
        BigDecimal receiptValue = receiptUnitCost.multiply(receiptQty);
        if ("AVCO".equals(product.costingMethod())) {
            BigDecimal oldQty = stockItem == null ? Money.ZERO : stockItem.quantity();
            BigDecimal oldAvg = stockItem == null ? Money.ZERO : stockItem.runningAverageCost();
            BigDecimal newQty = oldQty.add(receiptQty);
            BigDecimal newAvg = oldQty.signum() <= 0
                    ? receiptUnitCost
                    : Money.div(oldQty.multiply(oldAvg).add(receiptValue), newQty);
            BigDecimal correction = null;
            if (oldQty.signum() < 0) {
                BigDecimal before = oldQty.multiply(oldAvg);
                BigDecimal target = newQty.multiply(newAvg);
                correction = before.add(receiptValue).subtract(target);
                if (correction.signum() == 0) {
                    correction = null;
                }
            }
            return new Resolution(receiptUnitCost, receiptValue, newAvg, correction);
        }
        return Resolution.of(receiptUnitCost, receiptValue);
    }
}
