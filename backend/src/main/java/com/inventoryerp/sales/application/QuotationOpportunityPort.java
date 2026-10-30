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

    /** Mark the opportunity won when it is still an open opportunity (no-op otherwise). */
    void markWonIfOpen(String dealId);
}
