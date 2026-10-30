package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;

import com.inventoryerp.credit.application.CreditExposureContributor;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Sales share of credit exposure (credit.md §2, §4): open commitments of confirmed orders, valued at the order's
 * gross unit price ({@code lineTotal / quantity}). Undelivered = ordered − closed short − max(billable, invoiced);
 * delivered-not-invoiced = billable − invoiced. Each unit sits in exactly one bucket; once invoiced it is the
 * invoicing contributor's receivable.
 */
@Component
public class SalesCreditContributor implements CreditExposureContributor {

    private final SalesDb db;

    public SalesCreditContributor(SalesDb db) {
        this.db = db;
    }

    @Override
    public Contribution contribute(String partnerId) {
        Map<String, Object> r = db.row("""
                SELECT COALESCE(sum(GREATEST(l.quantity - l."qtyCancelled" - GREATEST(l."qtyBillable", l."qtyInvoiced"), 0)
                                    * l."lineTotal" / l.quantity), 0) AS undelivered,
                       COALESCE(sum(GREATEST(l."qtyBillable" - l."qtyInvoiced", 0) * l."lineTotal" / l.quantity), 0)
                           AS delivered
                  FROM "SalesOrderLine" l JOIN "SalesOrder" o ON o.id = l."orderId"
                 WHERE o."organizationId" = ? AND o."partnerId" = ? AND o.status IN ('confirmed', 'in_fulfilment')
                   AND o."deletedAt" IS NULL""", org(), partnerId);
        return new Contribution(scale(r.get("undelivered")), scale(r.get("delivered")), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private static BigDecimal scale(Object v) {
        return dec(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
