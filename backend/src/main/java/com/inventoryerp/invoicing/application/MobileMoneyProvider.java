package com.inventoryerp.invoicing.application;

import java.math.BigDecimal;

/**
 * Mobile money provider SPI (INT-1). One Spring bean per provider, selected by {@link #code()}; new collections use
 * {@code app.mobile-money.provider} (env {@code MOBILE_MONEY_PROVIDER}, default {@code mock}). A real aggregator or
 * network adapter implements this and reads {@code MOBILE_MONEY_URL} / {@code MOBILE_MONEY_API_KEY}, never logged.
 *
 * <p>A collection asks a customer's phone to approve a payment. The answer is usually {@code pending}; the final state
 * comes from {@link #status} (poll) or a signed callback ({@link #parseCallback}). Implementations do no database work
 * and throw on a failed call, leaving the collection pending.
 */
public interface MobileMoneyProvider {

    String PENDING = "pending";
    String SUCCESSFUL = "successful";
    String FAILED = "failed";

    String code();

    /** Whether the provider can be called (URL and key present for a real one). */
    boolean configured();

    /** Starts a collection; {@code reference} is ours and unique, so a retry is safe at a provider that honours it. */
    CollectionState collect(CollectRequest request);

    /** The current state of a collection. */
    CollectionState status(String providerReference);

    /** The state a provider callback reports; the signature was verified before this is called. */
    CollectionState parseCallback(String body);

    /** @param msisdn full number in international form, for this call only: it is not stored. */
    record CollectRequest(String reference, String msisdn, BigDecimal amount, String currency, String description) {}

    /** @param status {@link #PENDING}, {@link #SUCCESSFUL} or {@link #FAILED} */
    record CollectionState(String providerReference, String status, String failureReason) {}
}
