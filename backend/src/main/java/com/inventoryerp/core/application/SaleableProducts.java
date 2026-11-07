package com.inventoryerp.core.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Sell at any stage (ADR-S060): nothing about a product's production stage stops a sale; only its own
 * {@code canBeSold} switch does. Quotations, orders, invoices and deliveries all ask here. Stock is not touched.
 */
@Component
public class SaleableProducts {

    private final JdbcClient jdbc;

    public SaleableProducts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Refuses a product switched off for sale; an unknown id is left to the caller's own check. */
    public void assertSaleable(String productId) {
        if (productId == null) {
            return;
        }
        Map<String, Object> p = jdbc.sql("SELECT name, \"canBeSold\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, TenantContext.organizationId()).query().listOfRows().stream().findFirst().orElse(null);
        if (p != null && Boolean.FALSE.equals(p.get("canBeSold"))) {
            throw ApiException.badRequest(p.get("name") + " cannot be sold. Switch on \"Can be sold\" on the product first.");
        }
    }
}
