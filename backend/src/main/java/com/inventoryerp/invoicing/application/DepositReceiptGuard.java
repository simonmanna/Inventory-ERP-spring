package com.inventoryerp.invoicing.application;

/**
 * Sales readiness R6 (Q1, VAT on advances): a module that owns sales orders may refuse a plain advance against one
 * (for example when VAT is due on receipt, so the advance must be a down-payment invoice). Called before money is
 * taken: at the deposit routes and when a mobile-money deposit collection starts. Invoicing calls every guard and
 * depends on none of them.
 */
public interface DepositReceiptGuard {

    /** Throws an {@code ApiException} when a plain advance against {@code salesOrderId} is not allowed. */
    void beforeAdvance(String salesOrderId);
}
