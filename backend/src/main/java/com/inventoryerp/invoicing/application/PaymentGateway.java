package com.inventoryerp.invoicing.application;

import java.math.BigDecimal;

/**
 * Mobile-money / payment-gateway provider (ADR-S011 S6). A provider asks the customer's phone to approve a payment
 * ({@link #initiate}) and reports the outcome later (callback or {@link #status} poll). Real adapters (MTN MoMo,
 * Airtel Money) need per-organization credentials; {@code mock} is for tests and demos.
 */
public interface PaymentGateway {

    /** Provider key, e.g. {@code mtn_momo}, {@code airtel_money}, {@code mock}. */
    String code();

    /** Whether the provider is configured for use (credentials present). */
    boolean configured();

    /** Ask the payer to approve; returns the provider reference. */
    String initiate(String requestId, String phone, BigDecimal amount, String currencyCode, String narrative);

    /** Current outcome: {@code pending}, {@code succeeded} or {@code failed}. */
    String status(String providerRef);
}
