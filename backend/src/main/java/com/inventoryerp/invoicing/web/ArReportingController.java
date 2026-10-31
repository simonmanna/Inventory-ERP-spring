package com.inventoryerp.invoicing.web;

import com.inventoryerp.invoicing.application.ApAgingService;
import com.inventoryerp.invoicing.application.ArReportingService;
import com.inventoryerp.invoicing.application.SalesAnalysisService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /reports/ar/*} and {@code /reports/ap/aging} (port of {@code ar-reporting.controller.ts}). */
@RestController
@RequestMapping("/reports")
public class ArReportingController {

    private final ArReportingService reporting;
    private final ApAgingService apAging;
    private final SalesAnalysisService salesAnalysis;

    public ArReportingController(ArReportingService reporting, ApAgingService apAging,
            SalesAnalysisService salesAnalysis) {
        this.reporting = reporting;
        this.apAging = apAging;
        this.salesAnalysis = salesAnalysis;
    }

    @GetMapping("/ar/aging")
    @RequirePermissions(Permissions.REPORT__AR)
    public Map<String, Object> arAging(@RequestParam(required = false) String asOf) {
        return reporting.aging(asOf);
    }

    @GetMapping("/ar/customer-balances")
    @RequirePermissions(Permissions.REPORT__AR)
    public List<Map<String, Object>> customerBalances() {
        return reporting.customerBalances();
    }

    @GetMapping("/ar/outstanding")
    @RequirePermissions(Permissions.REPORT__AR)
    public List<Map<String, Object>> outstanding() {
        return reporting.outstandingInvoices();
    }

    @GetMapping("/ar/revenue-by-customer")
    @RequirePermissions(Permissions.REPORT__AR)
    public List<Map<String, Object>> revenueByCustomer(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reporting.revenueByCustomer(from, to);
    }

    @GetMapping("/ar/revenue-by-period")
    @RequirePermissions(Permissions.REPORT__AR)
    public List<Map<String, Object>> revenueByPeriod(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reporting.revenueByPeriod(from, to);
    }

    @GetMapping("/ar/sales-analysis")
    @RequirePermissions(Permissions.REPORT__AR)
    public Map<String, Object> salesAnalysis(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String branchId, @RequestParam(required = false) String productId,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String departmentId) {
        return salesAnalysis.analyse(new SalesAnalysisService.Filter(groupBy, from, to, branchId, productId, partnerId,
                departmentId));
    }

    @GetMapping("/ap/aging")
    @RequirePermissions(Permissions.REPORT__AR)
    public Map<String, Object> apAgingReport(@RequestParam(required = false) String asOf) {
        return apAging.apAging(asOf);
    }
}
