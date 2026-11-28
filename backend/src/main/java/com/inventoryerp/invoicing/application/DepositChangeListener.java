package com.inventoryerp.invoicing.application;

/**
 * ADR-S094 §4.4: a module whose documents carry customer deposits (a sales order's advance) recomputes what depends on
 * the deposit balance — a sale held open by an advance completes once the advance is applied or refunded. Called inside
 * the deposit's transaction after a receipt, application, refund, void or reversed application; throwing rolls it back.
 * Invoicing calls every listener and depends on none of them.
 */
public interface DepositChangeListener {

    void depositChanged(String salesOrderId);
}
