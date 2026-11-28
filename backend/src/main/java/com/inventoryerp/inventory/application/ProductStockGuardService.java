package com.inventoryerp.inventory.application;

import com.inventoryerp.core.application.ProductStockGuard;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ProductStockGuard} for the product master: on-hand check and STANDARD cost price revaluation. */
@Service
public class ProductStockGuardService implements ProductStockGuard {

    private final JdbcClient jdbc;
    private final StandardCostService standards;

    public ProductStockGuardService(JdbcClient jdbc, StandardCostService standards) {
        this.jdbc = jdbc;
        this.standards = standards;
    }

    @Override
    public boolean hasStockOnHand(String productId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                        + " AND quantity <> 0)")
                .params(TenantContext.organizationId(), productId).query(Boolean.class).single();
    }

    @Override
    @Transactional
    public String lockForConfigChange(String productId) {
        String org = TenantContext.organizationId();
        // IPR C-8: L3 exclusive (organisation key shared, product key exclusive), as a standard cost activation: every
        // move of the product holds the product key shared from before it reads the product's tracking and method.
        CostingLocks.lockProduct(jdbc, org, productId);
        if (hasStockOnHand(productId)) {
            return "stock";
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"StockReservation\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                        + " AND status = 'active' AND quantity > 0)")
                .params(org, productId).query(Boolean.class).single()) {
            return "reservations";
        }
        boolean pendingPos = jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"StockPostingJob\" j WHERE j.\"organizationId\" = ?"
                        + " AND j.status NOT IN ('done', 'abandoned') AND (EXISTS (SELECT 1 FROM \"InvoiceItem\" ii"
                        + " WHERE ii.\"invoiceId\" = j.\"invoiceId\" AND ii.\"productId\" = ?) OR EXISTS (SELECT 1 FROM"
                        + " \"OrderItem\" oi WHERE oi.\"orderId\" = j.\"orderId\" AND oi.\"productId\" = ?)))")
                .params(org, productId, productId).query(Boolean.class).single();
        return pendingPos ? "pending_pos" : null;
    }

    @Override
    public boolean standardGoverned(String productId) {
        return standards.activeStandard(productId).isPresent();
    }

    @Override
    @Transactional
    public void revalueStandardCostPrice(String productId, BigDecimal oldCost, BigDecimal newCost) {
        standards.revalueCostPriceChange(productId, oldCost, newCost);
    }
}
