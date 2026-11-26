package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.org;

import com.inventoryerp.invoicing.application.DepositReceiptGuard;
import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Sales readiness R6 (Q1, VAT on advances). With {@code depositTaxPoint = receipt} VAT is due when an advance is
 * received, and only a down-payment invoice charges it. A plain deposit against an order with taxed lines would take
 * the money without the VAT, so it is refused and the user is pointed at the down-payment invoice. With the default
 * ({@code delivery}) nothing changes: VAT arises on the final invoice.
 */
@Component
class SalesDepositReceiptGuard implements DepositReceiptGuard {

    private final SalesDb db;

    SalesDepositReceiptGuard(SalesDb db) {
        this.db = db;
    }

    @Override
    public void beforeAdvance(String salesOrderId) {
        Map<String, Object> s = db.row("SELECT \"depositTaxPoint\" FROM \"SalesSettings\" WHERE \"organizationId\" = ?",
                org());
        if (s == null || !"receipt".equals(s.get("depositTaxPoint"))) {
            return;
        }
        Map<String, Object> taxed = db.row("SELECT o.number FROM \"SalesOrder\" o WHERE o.id = ? AND o.\"organizationId\" = ?"
                + " AND EXISTS (SELECT 1 FROM \"SalesOrderLine\" l WHERE l.\"orderId\" = o.id AND l.\"taxId\" IS NOT NULL)",
                salesOrderId, org());
        if (taxed != null) {
            throw ApiException.badRequest("VAT is due when an advance is received (Sales settings: deposit tax point ="
                    + " receipt); take the advance on " + taxed.get("number") + " as a down-payment invoice");
        }
    }
}
