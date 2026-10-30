/**
 * Application services of the sales module — its public API for other modules (synchronous calls inside the
 * caller's transaction) and the ports other modules implement ({@link QuotationOpportunityPort},
 * {@link QuotationPricingPort}). Exposed as the {@code application} named interface.
 */
@org.springframework.modulith.NamedInterface("application")
package com.inventoryerp.sales.application;
