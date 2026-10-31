package com.inventoryerp.procurement.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Active procurement budgets whose committed and reserved spend reached the threshold share of the amount. */
@Component
public class ProcurementBudgetAlertSource implements AlertSource {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_BUDGETS = 200;

    private final ProcurementBudgetService budgets;

    public ProcurementBudgetAlertSource(ProcurementBudgetService budgets) {
        this.budgets = budgets;
    }

    @Override
    public String alertType() {
        return AlertTypes.BUDGET_OVERRUN;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertItem> collect(AlertScan scan) {
        BigDecimal threshold = scan.threshold() == null ? HUNDRED : scan.threshold();
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> b : budgets.list(1, MAX_BUDGETS, "active", null).data()) {
            BigDecimal used = (BigDecimal) b.get("percent");
            if (used == null || used.compareTo(threshold) < 0) {
                continue;
            }
            boolean over = used.compareTo(HUNDRED) > 0;
            out.add(new AlertItem("procurement:" + b.get("id") + (over ? ":over" : ":near"), over ? AlertItem.CRITICAL
                    : AlertItem.WARNING, "Procurement budget " + b.get("name") + ": " + used.toPlainString()
                    + " % used",
                    "Committed and reserved spend against the budget amount.", "/procurement/budgets",
                    Obj.of("committed", b.get("committed"), "reserved", b.get("reserved"), "amount", b.get("amount"),
                            "usedPct", used)));
        }
        return out;
    }
}
