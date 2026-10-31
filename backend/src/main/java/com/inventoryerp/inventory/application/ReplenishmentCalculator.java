package com.inventoryerp.inventory.application;

import java.math.BigDecimal;

/** How much to reorder (INV-3), free of the database. */
public final class ReplenishmentCalculator {

    private ReplenishmentCalculator() {}

    /**
     * The quantity to order when {@code available} is below {@code min}, else zero. With a {@code max}, order up to it;
     * with a {@code reorderQty}, order that much, or the shortfall if that is more; otherwise order the shortfall.
     */
    public static BigDecimal suggest(BigDecimal available, BigDecimal min, BigDecimal max, BigDecimal reorderQty) {
        if (available.compareTo(min) >= 0) {
            return BigDecimal.ZERO;
        }
        if (max != null && max.compareTo(min) > 0) {
            return max.subtract(available);
        }
        BigDecimal shortfall = min.subtract(available);
        return reorderQty != null && reorderQty.compareTo(shortfall) > 0 ? reorderQty : shortfall;
    }
}
