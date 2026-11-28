package com.inventoryerp.inventory.application;

/**
 * IPR 1b (count spec section 5, owner decision D1): a count submit first drains the sale stock postings that issue at
 * the counted location, so a sale made before the count is in the book the count compares with. Implemented by the POS
 * module (its stock posting jobs); the inventory module only calls it.
 */
public interface SaleStockDrain {

    /**
     * Process every not-done sale stock-posting job that issues at {@code locationId}, each in its own transactions
     * (never inside the caller's). Returns the number processed.
     */
    int drainAt(String locationId);

    /**
     * Sale stock-posting jobs at {@code locationId} that are failed or failing (retrying after an error) and sell one of
     * {@code productIds} (or a menu item, whose components are only known when it posts).
     */
    int stuckAt(String locationId, java.util.Collection<String> productIds);
}
