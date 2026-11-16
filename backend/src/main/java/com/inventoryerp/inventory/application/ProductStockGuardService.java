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
    public boolean standardGoverned(String productId) {
        return standards.activeStandard(productId).isPresent();
    }

    @Override
    @Transactional
    public void revalueStandardCostPrice(String productId, BigDecimal oldCost, BigDecimal newCost) {
        standards.revalueCostPriceChange(productId, oldCost, newCost);
    }
}
