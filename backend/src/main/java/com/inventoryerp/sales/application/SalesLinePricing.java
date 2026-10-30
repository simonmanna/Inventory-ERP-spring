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
        SalesPricingEngine.Result r = engine.resolve(new SalesPricingEngine.Context(partnerId, productId, qty, uomId, null,
                null, null, on));
        return new QuotationPricingPort.Price(r.listPrice(), r.unitPrice(), r.discountPercent(), r.source(), r.itemId(),
                r.taxId(), r.taxInclusive(), r.description());
    }
}
