package com.inventoryerp.sales.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** Price of a sales order line through the sales pricing engine (unit-aware). */
@Component
public class SalesLinePricing {

    private final SalesPricingEngine engine;

    public SalesLinePricing(SalesPricingEngine engine) {
        this.engine = engine;
    }

    public QuotationPricingPort.Price price(String partnerId, String productId, BigDecimal qty, String uomId,
            LocalDate on) {
        return price(partnerId, productId, qty, uomId, on, null);
    }

    /** Price of a line released against {@code agreementId} (agreement price first, S9). */
    public QuotationPricingPort.Price price(String partnerId, String productId, BigDecimal qty, String uomId,
            LocalDate on, String agreementId) {
        return price(partnerId, productId, null, qty, uomId, on, agreementId);
    }

    /** Price of a line of variant {@code variantId} (ADR-S058; null = product without variants). */
    public QuotationPricingPort.Price price(String partnerId, String productId, String variantId, BigDecimal qty,
            String uomId, LocalDate on, String agreementId) {
        return price(partnerId, productId, variantId, qty, uomId, on, agreementId, null, null, null);
    }

    /** Price of a line on a document, so the document's site, branch and currency select its price rules. */
    public QuotationPricingPort.Price price(String partnerId, String productId, String variantId, BigDecimal qty,
            String uomId, LocalDate on, String agreementId, String siteId, String branchId, String currencyCode) {
        SalesPricingEngine.Result r = engine.resolve(new SalesPricingEngine.Context(partnerId, productId, qty, uomId,
                siteId, branchId, currencyCode, on, agreementId, variantId));
        return new QuotationPricingPort.Price(r.listPrice(), r.unitPrice(), r.discountPercent(), r.source(), r.itemId(),
                r.taxId(), r.taxInclusive(), r.description());
    }
}
