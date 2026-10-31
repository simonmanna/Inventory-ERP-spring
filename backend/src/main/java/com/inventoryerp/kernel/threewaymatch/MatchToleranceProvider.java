package com.inventoryerp.kernel.threewaymatch;

import java.math.BigDecimal;

/**
 * Per-order matching rules (ADR-PROCUREMENT-001 wave 5), supplied by the purchasing policy layer. Without a provider,
 * or when it returns {@code null}, the organisation-wide behaviour applies: three-way, the
 * {@code procurement.match_tolerance} price tolerance, exact quantities.
 */
public interface MatchToleranceProvider {

    /**
     * {@code mode} is {@code three_way} (bill vs received) or {@code two_way} (bill vs ordered); {@code priceFraction}
     * (e.g. 0.02 = 2 %, {@code null} = organisation default) and {@code qtyFraction} (allowed over-bill, 0 = exact).
     */
    record Tolerance(String mode, BigDecimal priceFraction, BigDecimal qtyFraction) {}

    /** Rules for one purchase order (current tenant), or {@code null} for the defaults. */
    Tolerance forOrder(String purchaseOrderId);
}
