package com.inventoryerp.expenses.web;

import com.inventoryerp.expenses.application.ExpenseReportService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** EX-6 (ADR-S052): {@code /expense-reports/*}. */
@RestController
@RequestMapping("/expense-reports")
public class ExpenseReportsController {

    private final ExpenseReportService reports;

    public ExpenseReportsController(ExpenseReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/summary")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> summary(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        return reports.summary(groupBy, dateFrom, dateTo);
    }

    @GetMapping("/trend")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> trend(@RequestParam(defaultValue = "12") int months,
            @RequestParam(required = false) String categoryId) {
        return reports.trend(months, categoryId);
    }

    @GetMapping("/pipeline")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> pipeline(@RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return reports.pipeline(dateFrom, dateTo);
    }

    @GetMapping("/reimbursements")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> reimbursements() {
        return reports.reimbursements();
    }

    @GetMapping("/advances")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> advances() {
        return reports.advances();
    }

    @GetMapping("/petty-cash")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> pettyCash() {
        return reports.pettyCash();
    }

    @GetMapping("/schedules")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> schedules() {
        return reports.schedules();
    }

    @GetMapping("/capital-purchases")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> capitalPurchases(@RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return reports.capitalPurchases(dateFrom, dateTo);
    }
}
