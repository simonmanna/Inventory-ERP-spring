package com.inventoryerp.core.application;

import java.math.BigDecimal;

/**
 * Stock-side checks for a product edit, implemented by the {@code inventory} module (core cannot depend on it). Changing
 * the costing method or the cost price of a product that holds stock changes what that stock is worth, so
 * {@link ProductService} asks here first: the costing method only changes through a cost policy / standard cost while
 * stock is on hand, and a STANDARD product's cost price change revalues the stock on hand in the same transaction. No
 * implementation = legacy behaviour (the fields are free toggles).
 */
public interface ProductStockGuard {

    /** True when any quant (location / variant) of the product has a non-zero quantity. */
    boolean hasStockOnHand(String productId);

    /**
     * IPR C-8: before a tracking (batch / expiry / serial) or costing-method change, take the product's exclusive costing
     * lock (L3, held until the caller's transaction ends, so no stock move of the product runs meanwhile) and say what
     * still blocks the change: {@code "stock"} (a quant holds a non-zero quantity), {@code "reservations"} (active stock
     * reservations), {@code "pending_pos"} (a not-done POS stock posting job sells the product), or null. The default
     * (no inventory module) only reads the stock on hand.
     */
    default String lockForConfigChange(String productId) {
        return hasStockOnHand(productId) ? "stock" : null;
    }

    /** True when an active standard cost record governs the product's cost. */
    boolean standardGoverned(String productId);

    /**
     * Revalue the stock on hand of a STANDARD product from {@code oldCost} to {@code newCost} (posts the difference and
     * the value-only ledger rows in the caller's transaction).
     */
    void revalueStandardCostPrice(String productId, BigDecimal oldCost, BigDecimal newCost);
}
