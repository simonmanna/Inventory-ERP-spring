package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Price of a sales line: the {@link QuotationPricingPort} when one is present (CRM price rules until S2), else the
 * product list price.
 */
@Component
public class SalesLinePricing {

    private final SalesDb db;
    private final ObjectProvider<QuotationPricingPort> port;

    public SalesLinePricing(SalesDb db, ObjectProvider<QuotationPricingPort> port) {
        this.db = db;
        this.port = port;
    }

    public QuotationPricingPort.Price price(String partnerId, String productId, BigDecimal qty, LocalDate on) {
        QuotationPricingPort p = port.getIfAvailable();
        if (p != null) {
            return p.price(partnerId, productId, qty, on);
        }
        Map<String, Object> row = db.row("SELECT name, \"salesPrice\", \"taxId\", \"taxInclusive\" FROM \"Product\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", productId, org());
        if (row == null) {
            throw ApiException.notFound("Product not found");
        }
        BigDecimal list = dec(row.get("salesPrice"));
        return new QuotationPricingPort.Price(list, list, BigDecimal.ZERO, "list", null, str(row.get("taxId")),
                Boolean.TRUE.equals(row.get("taxInclusive")), str(row.get("name")));
    }
}
