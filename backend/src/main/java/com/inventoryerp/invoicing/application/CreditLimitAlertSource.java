package com.inventoryerp.invoicing.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.invoicing.application.CreditControlService.Exposure;
import com.inventoryerp.invoicing.application.CreditControlService.NamedExposure;
import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Customers whose exposure reached the threshold share of their limit; past the limit is critical. */
@Component
public class CreditLimitAlertSource implements AlertSource {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CreditControlService credit;

    public CreditLimitAlertSource(CreditControlService credit) {
        this.credit = credit;
    }

    @Override
    public String alertType() {
        return AlertTypes.CREDIT_LIMIT_BREACH;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertItem> collect(AlertScan scan) {
        BigDecimal threshold = scan.threshold() == null ? HUNDRED : scan.threshold();
        List<AlertItem> out = new ArrayList<>();
        for (NamedExposure n : credit.limitedCustomers()) {
            Exposure e = n.exposure();
            BigDecimal limit = e.effectiveLimit();
            BigDecimal used = e.total().multiply(HUNDRED).divide(limit, 2, RoundingMode.HALF_UP);
            if (used.compareTo(threshold) < 0) {
                continue;
            }
            String amount = e.total().setScale(2, RoundingMode.HALF_UP).toPlainString();
            String ceiling = limit.setScale(2, RoundingMode.HALF_UP).toPlainString();
            // The state is in the key: going from near the limit to past it alerts again within the period.
            out.add(new AlertItem(e.partnerId() + (e.exceeded() ? ":exceeded" : ":near"), e.exceeded()
                    ? AlertItem.CRITICAL : AlertItem.WARNING, n.partnerName() + (e.exceeded() ? " is over" : " is near")
                    + " the credit limit: " + amount + " of " + ceiling, "Overdue receivables: "
                    + e.overdueReceivables().setScale(2, RoundingMode.HALF_UP).toPlainString() + ".",
                    "/credit-control", Obj.of("exposure", e.total(), "limit", limit, "usedPct", used, "overdue",
                            e.overdueReceivables())));
        }
        return out;
    }
}
