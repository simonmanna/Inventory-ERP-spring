package com.inventoryerp.pos.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosReportsService;
import com.inventoryerp.pos.application.PosReportsService.Filters;
import com.inventoryerp.pos.application.PosSql;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/reports} (port of {@code pos-reports.controller.ts}); blank query values mean "no filter". */
@RestController
@RequestMapping("/pos/reports")
public class PosReportsController {

    private final PosReportsService svc;

    public PosReportsController(PosReportsService svc) {
        this.svc = svc;
    }

    /** {@code opt}: trimmed, or {@code null} when blank. */
    private static String opt(String v) {
        return v != null && !v.trim().isEmpty() ? v.trim() : null;
    }

    @GetMapping("/filter-options")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> filterOptions(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return svc.filterOptions(fromDate, toDate);
    }

    @GetMapping("/x-report")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> xReport(@RequestParam(required = false) String cashSessionId) {
        return svc.xReport(opt(cashSessionId));
    }

    @GetMapping("/z-report")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Object zReport(@RequestParam(required = false) String cashSessionId) {
        return svc.zReport(opt(cashSessionId));
    }

    @GetMapping("/z-report/{cashSessionId}")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> getZReport(@PathVariable String cashSessionId) {
        return svc.getZReportSnapshot(cashSessionId);
    }

    @GetMapping("/sales-by-hour")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> salesByHour(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String hours,
            @RequestParam(required = false) String orderType, @RequestParam(required = false) String waiterId,
            @RequestParam(required = false) String paymentMethod) {
        return svc.salesByHour(fromDate, toDate, opt(hours),
                Filters.of(opt(waiterId), opt(paymentMethod), opt(orderType), null));
    }

    @GetMapping("/top-items")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> topItems(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String categoryId, @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String waiterId, @RequestParam(required = false) String paymentMethod) {
        double n = limit != null && !limit.isEmpty() ? PosSql.num(limit) : 20;
        return svc.topItems(fromDate, toDate, n, opt(categoryId),
                Filters.of(opt(waiterId), opt(paymentMethod), opt(orderType), null));
    }

    @GetMapping("/sales-summary")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> salesSummary(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String orderType, @RequestParam(required = false) String waiterId,
            @RequestParam(required = false) String paymentMethod) {
        return svc.salesSummary(fromDate, toDate, groupBy,
                Filters.of(opt(waiterId), opt(paymentMethod), opt(orderType), null));
    }

    @GetMapping("/sold-items")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> soldItems(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String waiterId, @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String search, @RequestParam(required = false) String itemSearch,
            @RequestParam(required = false) String paymentMethod) {
        return svc.soldItems(fromDate, toDate, opt(categoryId), opt(waiterId), opt(orderType), opt(search),
                opt(itemSearch), opt(paymentMethod));
    }

    @GetMapping("/sales-report")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> salesReport(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String waiterId,
            @RequestParam(required = false) String search, @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String orderType) {
        return svc.salesReport(fromDate, toDate, opt(waiterId), opt(search), opt(paymentMethod), opt(orderType));
    }

    @GetMapping("/order-report")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> orderReport(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String status, @RequestParam(required = false) String waiterId,
            @RequestParam(required = false) String search, @RequestParam(required = false) String includeCancelled) {
        return svc.orderReport(fromDate, toDate, opt(orderType), opt(status), opt(waiterId), opt(search),
                "true".equals(includeCancelled) || "1".equals(includeCancelled));
    }

    @GetMapping("/cashier-report")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> cashierReport(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String waiterId,
            @RequestParam(required = false) String search, @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String orderType) {
        return svc.cashierReport(fromDate, toDate, opt(waiterId), opt(search), opt(paymentMethod), opt(orderType));
    }

    @GetMapping("/cashier-shift-summary")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> cashierShiftSummary(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String cashierId,
            @RequestParam(required = false) String registerId, @RequestParam(required = false) String status) {
        return svc.cashierShiftSummary(fromDate, toDate, opt(cashierId), opt(registerId), opt(status));
    }

    @GetMapping("/waiter-report")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> waiterReport(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String waiterId,
            @RequestParam(required = false) String orderType, @RequestParam(required = false) String search,
            @RequestParam(required = false) String paymentMethod) {
        return svc.waiterReport(fromDate, toDate, opt(waiterId), opt(orderType), opt(search), opt(paymentMethod));
    }

    @GetMapping("/item-sales")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> itemSales(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String itemKey,
            @RequestParam(required = false) String categoryId, @RequestParam(required = false) String waiterId,
            @RequestParam(required = false) String orderType, @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String itemSearch) {
        return svc.itemSales(fromDate, toDate, opt(itemKey), opt(categoryId), opt(waiterId), opt(orderType),
                opt(paymentMethod), opt(itemSearch));
    }

    /** Per-item server attribution, grouped by day / shift / none. */
    @GetMapping("/items-by-server")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> itemsByServer(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String waiterId, @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String paymentMethod, @RequestParam(required = false) String cashSessionId,
            @RequestParam(required = false) String categoryId, @RequestParam(required = false) String itemSearch,
            @RequestParam(required = false) String search) {
        String g = opt(groupBy);
        return svc.itemsByServer(fromDate, toDate, g == null ? "day" : g, new Filters(opt(waiterId), opt(paymentMethod),
                opt(orderType), opt(search), opt(cashSessionId), null), opt(categoryId), opt(itemSearch));
    }

    @GetMapping("/items-by-group")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> itemsByGroup(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate, @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String waiterId, @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String paymentMethod) {
        return svc.itemsByGroup(fromDate, toDate, opt(orderType), opt(waiterId), opt(categoryId), opt(paymentMethod));
    }
}
