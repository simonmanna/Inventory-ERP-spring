package com.inventoryerp.core.application;

import java.util.Collection;
import java.util.Set;

/**
 * What a product's lifecycle allows (ADR-S086 §6). The PLM application implements it from its lifecycle policy — an
 * obsolete product cannot be bought or sold, a product being phased out is no longer bought or replenished, and each
 * organization can tighten the matrix. Purchasing, sales and replenishment ask it before they use a product; without
 * an implementation (or with PLM switched off) everything is allowed.
 */
public interface ProductLifecycleGuard {

    /** Purchase requests, RFQs and purchase orders. */
    String PURCHASE = "purchase";

    /** Sales quotations and sales orders. */
    String SELL = "sell";

    /** Reorder-rule and alert-driven replenishment. */
    String REPLENISH = "replenish";

    /**
     * Refuse (400) when the lifecycle policy blocks {@code activity} for one of {@code productIds} (nulls ignored),
     * naming the product, its lifecycle state and its replacement.
     */
    void requireAllowed(String activity, Collection<String> productIds);

    /** The products among {@code productIds} the lifecycle policy blocks for {@code activity}. */
    Set<String> blocked(String activity, Collection<String> productIds);
}
