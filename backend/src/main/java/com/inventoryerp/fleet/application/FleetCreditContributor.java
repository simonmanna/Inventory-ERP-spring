package com.inventoryerp.fleet.application;

import com.inventoryerp.credit.application.CreditExposureContributor;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Fleet share of credit exposure (ADR-S022): manual delivery orders not invoiced yet, at their stored price. Delivery
 * orders raised from a sales order or an order are counted by that document's module, never here.
 */
@Component
public class FleetCreditContributor implements CreditExposureContributor {

    private final JdbcClient jdbc;

    public FleetCreditContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Contribution contribute(String partnerId) {
        BigDecimal open = jdbc.sql("""
                SELECT COALESCE(sum((l."orderedQty" - l."returnedQty") * COALESCE(l."unitPrice", 0)), 0)
                  FROM "FleetDeliveryOrderLine" l JOIN "FleetDeliveryOrder" f ON f.id = l."deliveryOrderId"
                 WHERE f."organizationId" = ? AND f."partnerId" = ? AND f."sourceType" = 'manual'
                   AND f."goodsInvoiceId" IS NULL AND f.status IN ('open', 'planned', 'in_progress', 'delivered')""")
                .params(TenantContext.organizationId(), partnerId).query(BigDecimal.class).single();
        return new Contribution(BigDecimal.ZERO, open.setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }
}
