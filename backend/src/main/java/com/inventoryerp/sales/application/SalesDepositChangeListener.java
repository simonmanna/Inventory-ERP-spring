package com.inventoryerp.sales.application;

import com.inventoryerp.invoicing.application.DepositChangeListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** ADR-S094 §4.4 / §4.5: a sales order's advance changed, so its outcome and completion are recomputed now. */
@Component
class SalesDepositChangeListener implements DepositChangeListener {

    private final SalesOrderService orders;

    SalesDepositChangeListener(@Lazy SalesOrderService orders) {
        this.orders = orders;
    }

    @Override
    public void depositChanged(String salesOrderId) {
        orders.refreshStatus(salesOrderId);
    }
}
