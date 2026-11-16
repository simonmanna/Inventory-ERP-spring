package com.inventoryerp.invoicing.application;

import java.util.List;

/**
 * Extra checks before a supplier payment is recorded (ADR-S074), implemented by the projects module (subcontractor
 * compliance gating of vendor bills that belong to a subcontract). Runs inside the payment transaction, before the
 * approval gate and before anything is written.
 */
public interface SupplierPaymentGuard {

    /**
     * Inspect the payment about to be made to {@code partnerId} against the vendor bills it settles
     * ({@code documentIds}, possibly empty). Throw an {@code ApiException} to refuse the payment.
     */
    void beforeSupplierPayment(String partnerId, List<String> documentIds);
}
