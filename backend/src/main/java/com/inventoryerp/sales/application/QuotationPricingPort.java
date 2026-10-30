package com.inventoryerp.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Price resolution for quotation lines. Phase S0: implemented by the crm module over its customer price rules
 * (ADR-S010 §6); phase S2 replaces it with the sales pricelist engine.
 */
public interface QuotationPricingPort {

    /** Resolved price of {@code qty} of a product for a customer on a date. */
    Price price(String partnerId, String productId, BigDecimal qty, LocalDate on);

    /** A resolved price: list price, authorised unit price and discount, where it came from, default tax. */
    record Price(BigDecimal listPrice, BigDecimal unitPrice, BigDecimal discountPercent, String source, String ruleId,
            String taxId, boolean taxInclusive, String description) {

        private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

        /** Unit price after the authorised discount. */
        public BigDecimal net() {
            return unitPrice.multiply(BigDecimal.ONE.subtract(discountPercent.divide(HUNDRED, 10, RoundingMode.HALF_UP)));
        }
    }
}
