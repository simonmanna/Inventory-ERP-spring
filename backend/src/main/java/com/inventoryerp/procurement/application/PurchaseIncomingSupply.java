package com.inventoryerp.procurement.application;

import com.inventoryerp.inventory.application.IncomingSupplyProvider;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Open purchase orders and requests as incoming supply for the inventory replenishment scan (PR-1). An order line's
 * destination is the delivery location of the request line it came from; order and request quantities are converted
 * to the base unit. Lines with no destination count only for a product's only rule.
 */
@Component
public class PurchaseIncomingSupply implements IncomingSupplyProvider {

    private final JdbcClient jdbc;

    public PurchaseIncomingSupply(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal incoming(String productId, String locationId, boolean onlyRule) {
        String org = TenantContext.organizationId();
        BigDecimal ordered = jdbc.sql("""
                        SELECT COALESCE(SUM(GREATEST(0, l.quantity - l."receivedQuantity" - l."cancelledQuantity")
                                            * COALESCE(l."uomFactor", 1)), 0)
                        FROM "PurchaseOrderLine" l
                        JOIN "PurchaseOrder" o ON o.id = l."purchaseOrderId"
                        LEFT JOIN "PurchaseRequestLine" rl ON rl.id = l."requestLineId"
                        WHERE l."organizationId" = ? AND l."productId" = ? AND o."deletedAt" IS NULL
                          AND o.status::text IN ('draft', 'active', 'partially_received')
                          AND (rl."deliveryLocationId" = ? OR (? AND rl."deliveryLocationId" IS NULL))""")
                .params(org, productId, locationId, onlyRule)
                .query(BigDecimal.class)
                .single();
        BigDecimal requested = jdbc.sql("""
                        SELECT COALESCE(SUM(GREATEST(0, l.quantity - l."orderedQuantity")), 0)
                        FROM "PurchaseRequestLine" l
                        JOIN "PurchaseRequest" q ON q.id = l."purchaseRequestId"
                        WHERE l."organizationId" = ? AND l."productId" = ? AND q."deletedAt" IS NULL
                          AND q.status::text IN ('draft', 'submitted', 'approved')
                          AND (l."deliveryLocationId" = ? OR (? AND l."deliveryLocationId" IS NULL))""")
                .params(org, productId, locationId, onlyRule)
                .query(BigDecimal.class)
                .single();
        return ordered.add(requested);
    }
}
