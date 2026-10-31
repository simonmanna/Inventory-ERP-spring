package com.inventoryerp.orders.application;

import com.inventoryerp.credit.application.CreditExposureContributor;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Orders share of credit exposure (ADR-S022): confirmed credit orders not billed yet. An order billed through a
 * delivery order leaves this bucket once the goods invoice exists (invoicing counts it from then on); an order billed
 * to a POS invoice leaves it through {@code invoiceId} (pos counts the invoice).
 */
@Component
public class OrdersCreditContributor implements CreditExposureContributor {

    private final JdbcClient jdbc;

    public OrdersCreditContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Contribution contribute(String partnerId) {
        return contribute(partnerId, null);
    }

    @Override
    public Contribution contribute(String partnerId, String excludeDocumentId) {
        BigDecimal unbilled = jdbc.sql("""
                SELECT COALESCE(sum(o."totalAmount"), 0) FROM "Order" o
                 WHERE o."organizationId" = ? AND o."partnerId" = ? AND o."invoiceId" IS NULL AND o.id <> ?
                   AND o.status::text NOT IN ('draft', 'completed', 'closed', 'cancelled')
                   AND (o."paymentMethod" IS NULL OR o."paymentMethod"::text NOT IN ('cash', 'card', 'mobile_money'))
                   AND NOT EXISTS (SELECT 1 FROM "FleetDeliveryOrder" f WHERE f."organizationId" = o."organizationId"
                                    AND f."sourceType" = 'sales_order' AND f."sourceId" = o.id
                                    AND f."goodsInvoiceId" IS NOT NULL)""")
                .params(TenantContext.organizationId(), partnerId, excludeDocumentId == null ? "" : excludeDocumentId)
                .query(BigDecimal.class).single();
        return new Contribution(unbilled.setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }
}
