package com.inventoryerp.sales.application;

import java.util.Map;

/**
 * CRM opportunity link of a quotation. Owned by sales, implemented by the crm module (so sales never depends on
 * crm). Without an implementation (CRM absent) quotations simply carry no opportunity.
 */
public interface QuotationOpportunityPort {

    /**
     * Opportunity visible to the caller ({@code partnerId}, {@code currencyCode}, {@code ownerId}, …), or a 404
     * {@code ApiException} when it does not exist or is not visible.
     */
    Map<String, Object> visibleOpportunity(String dealId);

    /**
     * Mark the opportunity won when it is still an open opportunity (no-op otherwise). A system side effect of a
     * conversion the caller was already authorized for: it must not apply the salesperson visibility rule, so it can
     * never roll back the sales document (production-readiness fix P0-2).
     */
    void markWonIfOpen(String dealId, String quotationNumber);

    /** Record on the CRM timeline that quotation {@code number} was marked as sent (no-op by default). */
    default void quotationSent(String dealId, String partnerId, String number) {}

    /** Record on the CRM timeline that quotation {@code number} was emailed (deal and/or customer; no-op by default). */
    default void quotationEmailed(String dealId, String partnerId, String number, String sentTo, boolean delivered) {}

    /**
     * Record on the CRM timeline that the customer answered quotation {@code number} through its online link
     * ({@code title} / {@code body} already worded), assigned to the quote owner {@code ownerId}. No-op by default.
     */
    default void quotationAnsweredOnline(String dealId, String partnerId, String title, String body, String ownerId) {}
}
