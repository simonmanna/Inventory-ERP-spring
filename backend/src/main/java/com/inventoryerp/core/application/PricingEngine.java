package com.inventoryerp.core.application;

import com.inventoryerp.core.application.PriceListService.PriceQuery;
import java.math.BigDecimal;
import java.util.Map;

/**
 * The one sales pricing engine (ADR-S022), seen from {@code core}. Implemented by the sales module's
 * {@code SalesPricingEngine}; POS orders, invoices and delivery orders reach it through
 * {@link PriceListService#resolve}, so every channel prices with the same tiers, precedence and rules.
 */
public interface PricingEngine {

    /**
     * Price one line, or {@code null} when the product does not exist in this organization.
     *
     * @return the winning tier ({@code source}): {@code agreement}, {@code customer_rule}, {@code crm_rule},
     *     {@code customer_pricelist}, {@code category_pricelist}, {@code price_list}, {@code branch_default},
     *     {@code org_default}, {@code standing_discount} or {@code list}
     */
    Quote quote(PriceQuery query);

    /**
     * {@code unitPrice} is per the requested unit; null (named price lists only) means the catalogue price with
     * {@code discountPercent}. {@code breakdown}: base price, discount, net, tax and final price.
     */
    record Quote(String source, BigDecimal unitPrice, BigDecimal discountPercent, String ruleId, String ruleName,
            Map<String, Object> breakdown) {}
}
