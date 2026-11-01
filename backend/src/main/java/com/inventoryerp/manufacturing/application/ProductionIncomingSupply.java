package com.inventoryerp.manufacturing.application;

import com.inventoryerp.inventory.application.IncomingSupplyProvider;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Production still to come into a location, as incoming supply for the replenishment scan (PR-5): the remaining output
 * of open production orders that deliver there (output location, else the order's location), and production request
 * lines raised for that location and not yet planned.
 */
@Component
public class ProductionIncomingSupply implements IncomingSupplyProvider {

    private final JdbcClient jdbc;

    public ProductionIncomingSupply(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal incoming(String productId, String locationId, boolean onlyRule) {
        String org = TenantContext.organizationId();
        BigDecimal orders = jdbc.sql("""
                        SELECT COALESCE(SUM(GREATEST(0, "plannedQty" - "producedQty")), 0)
                        FROM "ProductionOrder"
                        WHERE "organizationId" = ? AND "outputProductId" = ?
                          AND COALESCE("outputLocationId", "locationId") = ?
                          AND status::text IN ('draft', 'confirmed', 'in_progress')""")
                .params(org, productId, locationId)
                .query(BigDecimal.class)
                .single();
        BigDecimal requested = jdbc.sql("""
                        SELECT COALESCE(SUM(l.quantity), 0)
                        FROM "ProductionRequestLine" l
                        JOIN "ProductionRequest" r ON r.id = l."requestId"
                        WHERE l."organizationId" = ? AND l."productId" = ? AND l."deliveryLocationId" = ?
                          AND r."deletedAt" IS NULL AND l."plannedLineId" IS NULL
                          AND r.status::text IN ('draft', 'submitted', 'approved')""")
                .params(org, productId, locationId)
                .query(BigDecimal.class)
                .single();
        return orders.add(requested);
    }
}
