package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.sales.application.SalesOrderChangeService;
import com.inventoryerp.sales.application.SalesOrderService;
import com.inventoryerp.sales.application.SalesQuotationService;
import com.inventoryerp.sales.application.SalesReportService;
import com.inventoryerp.sales.application.SalesSettingsService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sales / order-to-cash routes (ADR-S011): quotations, sales orders, change requests, reports and settings. New
 * routes beyond the NestJS reference; {@code /crm/quotations} remains as a facade over the same service.
 */
public final class SalesController {

    private SalesController() {}

    static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    static String text(Map<String, Object> b, String key) {
        Object v = body(b).get(key);
        return v == null ? null : v.toString();
    }

    /** {@code /sales/quotations}: quotation lifecycle (same service as {@code /crm/quotations}). */
    @RestController
    @RequestMapping("/sales/quotations")
    public static class QuotationController {

        private final SalesQuotationService svc;

        public QuotationController(SalesQuotationService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.SALES__QUOTATION_READ)
        public Map<String, Object> list(@RequestParam Map<String, String> q) {
            return svc.list(q);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.SALES__QUOTATION_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @GetMapping("/{id}/revisions")
        @RequirePermissions(Permissions.SALES__QUOTATION_READ)
        public List<Map<String, Object>> revisions(@PathVariable String id) {
            return svc.revisions(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.create(body(b));
        }

        @PutMapping("/{id}")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> delete(@PathVariable String id) {
            return svc.delete(id);
        }

        @PostMapping("/{id}/submit")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> submit(@PathVariable String id) {
            return svc.submit(id);
        }

        @PostMapping("/{id}/approve")
        @RequirePermissions(Permissions.SALES__QUOTATION_APPROVE)
        public Map<String, Object> approve(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.approve(id, text(b, "comment"));
        }

        @PostMapping("/{id}/reject")
        @RequirePermissions(Permissions.SALES__QUOTATION_APPROVE)
        public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.reject(id, text(b, "reason"));
        }

        @PostMapping("/{id}/send")
        @RequirePermissions(Permissions.SALES__QUOTATION_SEND)
        public Map<String, Object> send(@PathVariable String id) {
            return svc.send(id);
        }

        @PostMapping("/{id}/accept")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> accept(@PathVariable String id) {
            return svc.accept(id);
        }

        @PostMapping("/{id}/decline")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> decline(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.decline(id, text(b, "reason"));
        }

        @PostMapping("/{id}/cancel")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> cancel(@PathVariable String id) {
            return svc.cancel(id);
        }

        @PostMapping("/{id}/revise")
        @RequirePermissions(Permissions.SALES__QUOTATION_WRITE)
        public Map<String, Object> revise(@PathVariable String id) {
            return svc.revise(id);
        }

        @PostMapping("/{id}/convert")
        @Idempotent
        @RequirePermissions(Permissions.SALES__QUOTATION_CONVERT)
        public Map<String, Object> convert(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            Map<String, Object> in = body(b);
            in.putIfAbsent("target", "sales_order");
            return svc.convert(id, in);
        }
    }

    /** {@code /sales/orders}: B2B sales orders. */
    @RestController
    @RequestMapping("/sales/orders")
    public static class OrderController {

        private final SalesOrderService svc;
        private final SalesOrderChangeService changes;

        public OrderController(SalesOrderService svc, SalesOrderChangeService changes) {
            this.svc = svc;
            this.changes = changes;
        }

        @GetMapping
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> list(@RequestParam Map<String, String> q) {
            return svc.list(q);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @GetMapping("/{id}/revisions")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public List<Map<String, Object>> revisions(@PathVariable String id) {
            return svc.revisions(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__ORDER_WRITE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.create(body(b));
        }

        @PutMapping("/{id}")
        @RequirePermissions(Permissions.SALES__ORDER_WRITE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.SALES__ORDER_WRITE)
        public Map<String, Object> delete(@PathVariable String id) {
            return svc.delete(id);
        }

        @PostMapping("/{id}/confirm")
        @Idempotent
        @RequirePermissions(Permissions.SALES__ORDER_CONFIRM)
        public Map<String, Object> confirm(@PathVariable String id) {
            return svc.confirm(id);
        }

        @PostMapping("/{id}/hold")
        @RequirePermissions(Permissions.SALES__ORDER_HOLD_RELEASE)
        public Map<String, Object> hold(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.hold(id, text(b, "reason"));
        }

        @PostMapping("/{id}/release")
        @RequirePermissions(Permissions.SALES__ORDER_HOLD_RELEASE)
        public Map<String, Object> release(@PathVariable String id) {
            return svc.release(id);
        }

        @PostMapping("/{id}/cancel")
        @RequirePermissions(Permissions.SALES__ORDER_CANCEL)
        public Map<String, Object> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.cancel(id, text(b, "reason"));
        }

        @PostMapping("/{id}/duplicate")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__ORDER_WRITE)
        public Map<String, Object> duplicate(@PathVariable String id) {
            return svc.duplicate(id);
        }

        @PostMapping("/{id}/lines/{lineId}/close-short")
        @RequirePermissions(Permissions.SALES__ORDER_CLOSE_SHORT)
        public Map<String, Object> closeShort(@PathVariable String id, @PathVariable String lineId,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.closeShort(id, lineId, text(b, "reason"));
        }

        @GetMapping("/{id}/change-requests")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public List<Map<String, Object>> changeRequests(@PathVariable String id) {
            return changes.list(id);
        }

        @PostMapping("/{id}/change-requests")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__ORDER_AMEND)
        public Map<String, Object> requestChange(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return changes.request(id, body(b));
        }
    }

    /** {@code /sales/change-requests}: decide amendments of confirmed orders. */
    @RestController
    @RequestMapping("/sales/change-requests")
    public static class ChangeRequestController {

        private final SalesOrderChangeService svc;

        public ChangeRequestController(SalesOrderChangeService svc) {
            this.svc = svc;
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @PostMapping("/{id}/approve")
        @Idempotent
        @RequirePermissions(Permissions.SALES__ORDER_AMEND_APPROVE)
        public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.approve(id, text(b, "comment"));
        }

        @PostMapping("/{id}/reject")
        @RequirePermissions(Permissions.SALES__ORDER_AMEND_APPROVE)
        public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.reject(id, text(b, "comment"));
        }

        @PostMapping("/{id}/withdraw")
        @RequirePermissions(Permissions.SALES__ORDER_AMEND)
        public Map<String, Object> withdraw(@PathVariable String id) {
            return svc.withdraw(id);
        }
    }

    /** {@code /sales/reports}, {@code /sales/settings}. */
    @RestController
    @RequestMapping("/sales")
    public static class AdminController {

        private final SalesReportService reports;
        private final SalesSettingsService settings;

        public AdminController(SalesReportService reports, SalesSettingsService settings) {
            this.reports = reports;
            this.settings = settings;
        }

        @GetMapping("/reports/summary")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public Map<String, Object> summary() {
            return reports.summary();
        }

        @GetMapping("/settings")
        @RequirePermissions(Permissions.SALES__ORDER_READ)
        public Map<String, Object> settings() {
            return settings.settings();
        }

        @PutMapping("/settings")
        @RequirePermissions(Permissions.SALES__CONFIGURE)
        public Map<String, Object> update(@RequestBody Map<String, Object> b) {
            return settings.update(body(b));
        }
    }
}
