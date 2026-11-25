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
     * Products newly put on a point-of-sale order or rung up in a counter checkout. Lines already on a ticket are
     * never re-checked. Owner decision 2026-11-25: a sale replayed from an offline till is checked again on the
     * server like an online one; a refused replay rolls back and is held as a dead letter for a supervisor (see
     * {@link #BLOCKED_CODE}).
     */
    String POS = "pos";

    /** Reorder-rule and alert-driven replenishment. */
    String REPLENISH = "replenish";

    /**
     * The {@code code} of every lifecycle refusal (400, same message). An offline sale refused for it is dead-lettered
     * under this failure code, and a supervisor may retry it under its original operation id once the product is
     * released or an exception is granted.
     */
    String BLOCKED_CODE = "LIFECYCLE_BLOCKED";

    /**
     * Refuse (400, {@code code} {@link #BLOCKED_CODE}) when the lifecycle policy blocks {@code activity} for one of
     * {@code productIds} (nulls ignored), naming the product, its lifecycle state and its replacement.
     */
    void requireAllowed(String activity, Collection<String> productIds);

    /** The products among {@code productIds} the lifecycle policy blocks for {@code activity}. */
    Set<String> blocked(String activity, Collection<String> productIds);
}
