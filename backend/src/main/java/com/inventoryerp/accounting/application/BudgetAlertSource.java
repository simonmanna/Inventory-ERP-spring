package com.inventoryerp.accounting.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approved budgets running today: an expense account whose actual reached the threshold share of its budget from the
 * budget's first month to the current one ({@link BudgetService#vsActual}). An account with no budget has no share, so
 * it is not an overrun here; budget vs actual lists it.
 */
@Component
public class BudgetAlertSource implements AlertSource {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcClient jdbc;
    private final BudgetService budgets;

    public BudgetAlertSource(JdbcClient jdbc, BudgetService budgets) {
        this.jdbc = jdbc;
        this.budgets = budgets;
    }

    @Override
    public String alertType() {
        return AlertTypes.BUDGET_OVERRUN;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<AlertItem> collect(AlertScan scan) {
        BigDecimal threshold = scan.threshold() == null ? HUNDRED : scan.threshold();
        String month = YearMonth.from(scan.asOf()).toString();
        java.sql.Date today = java.sql.Date.valueOf(scan.asOf());
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Budget\" WHERE \"organizationId\" ="
                + " ? AND status = 'approved' AND \"deletedAt\" IS NULL AND \"startDate\" <= ? AND \"endDate\" >= ?")
                .params(scan.organizationId(), today, today))) {
            String budgetId = (String) b.get("id");
            Map<String, Object> report = budgets.vsActual(budgetId, null, month);
            for (Map<String, Object> row : (List<Map<String, Object>>) report.get("rows")) {
                BigDecimal used = (BigDecimal) row.get("utilizationPct");
                if (used == null || !"expense".equals(row.get("type")) || used.compareTo(threshold) < 0) {
                    continue;
                }
                boolean over = used.compareTo(HUNDRED) > 0;
                out.add(new AlertItem(budgetId + ":" + row.get("accountId") + (over ? ":over" : ":near"), over
                        ? AlertItem.CRITICAL : AlertItem.WARNING, "Budget " + b.get("name") + ", account "
                        + row.get("code") + " " + row.get("name") + ": " + used.toPlainString() + " % used",
                        "Actual " + row.get("actual") + " against " + row.get("budget") + " to " + month + ".",
                        "/budgets/" + budgetId, Obj.of("actual", row.get("actual"), "budget", row.get("budget"),
                                "usedPct", used)));
            }
        }
        return out;
    }
}
