package com.inventoryerp.inventory.application;

import java.math.BigDecimal;

/**
 * Stock already on its way to a location (PR-1). Declared here and implemented by procurement (open purchase orders
 * and requests), so inventory never depends on procurement. The replenishment scan adds it to available stock before
 * comparing with a rule's minimum, so goods already ordered are not ordered twice.
 */
public interface IncomingSupplyProvider {

    /**
     * Quantity in the product's base unit that is ordered or requested for {@code locationId} (current tenant) and not
     * yet received or cancelled. {@code onlyRule} is true when this is the product's only active replenishment rule:
     * supply with no known destination then counts for it too.
     */
    BigDecimal incoming(String productId, String locationId, boolean onlyRule);
}
