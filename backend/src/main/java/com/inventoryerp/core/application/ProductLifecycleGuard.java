package com.inventoryerp.core.application;

import java.util.Collection;
import java.util.Set;

/**
 * What a product's lifecycle allows (ADR-S086 §6). The PLM application implements it from its lifecycle policy (owner
 * decisions 2026-11-24): a product that has not completed its release is not sold, a product being phased out sells
 * its stock but is no longer bought, made or replenished, an obsolete product is neither bought, made nor sold, and
 * each organization can adjust the matrix; an authorized, time-boxed exception allows one activity for one product.
 * Purchasing, production, sales, the till and replenishment ask it before they use a product; without an
 * implementation (or with PLM switched off) everything is allowed.
 */
public interface ProductLifecycleGuard {

    /** Purchase requests, RFQs and purchase orders. */
    String PURCHASE = "purchase";

    /** Production orders. */
    String PRODUCE = "produce";

    /** Sales quotations, sales orders and customer invoices raised by hand. */
    String SELL = "sell";

    /**
     * Products newly put on an online point-of-sale order. Lines already on a ticket are never re-checked and sales
     * replayed from an offline till are never refused, so a sale already rung up is never interrupted.
     */
    String POS = "pos";

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
