package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Stock transfers still on their way into a location, as incoming supply for the replenishment scan (PR-5). A transfer
 * not yet dispatched counts its requested quantity; a dispatched transit transfer counts what is still in transit
 * (dispatched less received, damaged, short and recalled). Transfer quantities are in the product's base unit.
 */
@Component
public class TransferIncomingSupply implements IncomingSupplyProvider {

    private final JdbcClient jdbc;

    public TransferIncomingSupply(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal incoming(String productId, String locationId, boolean onlyRule) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE
                                   WHEN t.status::text IN ('draft', 'pending', 'approved') AND i."qtyDispatched" = 0
                                       THEN i."qtyRequested"
                                   ELSE GREATEST(0, i."qtyDispatched" - i."qtyReceived" - i."qtyDamaged" - i."qtyShort"
                                                    - i."qtyRecalled") END), 0)
                        FROM "StockTransferItem" i
                        JOIN "StockTransfer" t ON t.id = i."transferId"
                        WHERE i."organizationId" = ? AND i."productId" = ? AND t."toLocId" = ?
                          AND t.status::text IN ('draft', 'pending', 'approved', 'in_transit', 'partially_received')""")
                .params(TenantContext.organizationId(), productId, locationId)
                .query(BigDecimal.class)
                .single();
    }
}
