package com.inventoryerp.sales.web;

import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.security.Throttle;
import com.inventoryerp.sales.application.SalesAdvisorService;
import com.inventoryerp.sales.application.SalesAnalyticsService;
import com.inventoryerp.sales.application.SalesPortalService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Analytics, advisor, customer-portal administration and the customer portal itself (ADR-S011 S10). */
public final class SalesInsightsController {

    static final String TOKEN = "X-Portal-Token";

    private SalesInsightsController() {}

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    /** {@code /sales/analytics/*} and {@code /sales/advisor}. */
    @RestController
    @RequestMapping("/sales")
    public static class Analytics {

        private final SalesAnalyticsService analytics;
        private final SalesAdvisorService advisor;

        public Analytics(SalesAnalyticsService analytics, SalesAdvisorService advisor) {
            this.analytics = analytics;
            this.advisor = advisor;
        }

        @GetMapping("/analytics/dashboard")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public Map<String, Object> dashboard() {
            return analytics.dashboard();
        }

        @GetMapping("/analytics/analysis")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public Map<String, Object> analysis(@RequestParam(required = false) String groupBy,
                @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
            return analytics.analysis(groupBy, from, to);
        }

        @GetMapping("/analytics/profitability")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public Map<String, Object> profitability(@RequestParam(required = false) String from,
                @RequestParam(required = false) String to) {
            return analytics.profitability(from, to);
        }

        @GetMapping("/analytics/forecast")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public Map<String, Object> forecast(@RequestParam(required = false, defaultValue = "3") int months) {
            return analytics.forecast(months);
        }

        @GetMapping("/advisor")
        @RequirePermissions(Permissions.SALES__REPORT_READ)
        public List<Map<String, Object>> advisor() {
            return advisor.advisories();
        }
    }

    /** {@code /sales/portal/access}: staff issue and revoke customer portal links. */
    @RestController
    @RequestMapping("/sales/portal/access")
    @RequiresFeature(LicenseFeatures.CUSTOMER_PORTAL)
    public static class PortalAccess {

        private final SalesPortalService portal;

        public PortalAccess(SalesPortalService portal) {
            this.portal = portal;
        }

        @GetMapping
        @RequirePermissions(Permissions.SALES__PORTAL_MANAGE)
        public List<Map<String, Object>> list(@RequestParam(required = false) String partnerId) {
            return portal.accesses(partnerId);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.SALES__PORTAL_MANAGE)
        public Map<String, Object> issue(@RequestBody Map<String, Object> b) {
            return portal.issue(body(b));
        }

        @PostMapping("/{id}/revoke")
        @RequirePermissions(Permissions.SALES__PORTAL_MANAGE)
        public Map<String, Object> revoke(@PathVariable String id) {
            return portal.revoke(id);
        }
    }

    /** {@code /portal/*}: the customer's own view, authenticated by the {@code X-Portal-Token} magic-link token. */
    @RestController
    @RequestMapping("/portal")
    @Public
    @RequiresFeature(LicenseFeatures.CUSTOMER_PORTAL)
    @Throttle(limit = 120, ttlMs = 60 * 1000)
    public static class Portal {

        private final SalesPortalService portal;

        public Portal(SalesPortalService portal) {
            this.portal = portal;
        }

        @GetMapping("/me")
        public Map<String, Object> me(@RequestHeader(value = TOKEN, required = false) String token) {
            return portal.withToken(token, portal::me);
        }

        @GetMapping("/quotations")
        public List<Map<String, Object>> quotations(@RequestHeader(value = TOKEN, required = false) String token) {
            return portal.withToken(token, portal::quotations);
        }

        @GetMapping("/quotations/{id}")
        public Map<String, Object> quotation(@RequestHeader(value = TOKEN, required = false) String token,
                @PathVariable String id) {
            return portal.withToken(token, () -> portal.quotation(id));
        }

        @PostMapping("/quotations/{id}/accept")
        public Map<String, Object> accept(@RequestHeader(value = TOKEN, required = false) String token,
                @PathVariable String id) {
            return portal.withToken(token, () -> portal.acceptQuotation(id));
        }

        @PostMapping("/quotations/{id}/decline")
        public Map<String, Object> decline(@RequestHeader(value = TOKEN, required = false) String token,
                @PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            Object reason = body(b).get("reason");
            return portal.withToken(token, () -> portal.declineQuotation(id, reason == null ? null : reason.toString()));
        }

        @GetMapping("/orders")
        public List<Map<String, Object>> orders(@RequestHeader(value = TOKEN, required = false) String token) {
            return portal.withToken(token, portal::orders);
        }

        @GetMapping("/orders/{id}")
        public Map<String, Object> order(@RequestHeader(value = TOKEN, required = false) String token,
                @PathVariable String id) {
            return portal.withToken(token, () -> portal.order(id));
        }

        @GetMapping("/invoices")
        public List<Map<String, Object>> invoices(@RequestHeader(value = TOKEN, required = false) String token) {
            return portal.withToken(token, portal::invoices);
        }

        @PostMapping("/invoices/{id}/pay")
        public Map<String, Object> pay(@RequestHeader(value = TOKEN, required = false) String token,
                @PathVariable String id, @RequestBody Map<String, Object> b) {
            return portal.withToken(token, () -> portal.pay(id, body(b)));
        }

        @GetMapping("/payments/{id}")
        public Map<String, Object> payment(@RequestHeader(value = TOKEN, required = false) String token,
                @PathVariable String id) {
            return portal.withToken(token, () -> portal.payment(id));
        }

        @GetMapping("/statement.pdf")
        public ResponseEntity<byte[]> statement(@RequestHeader(value = TOKEN, required = false) String token,
                @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
            byte[] pdf = portal.withToken(token, () -> portal.statementPdf(from, to));
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"statement.pdf\"").body(pdf);
        }

        @GetMapping("/complaints")
        public List<Map<String, Object>> complaints(@RequestHeader(value = TOKEN, required = false) String token) {
            return portal.withToken(token, portal::complaints);
        }

        @PostMapping("/complaints")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, Object> complain(@RequestHeader(value = TOKEN, required = false) String token,
                @RequestBody Map<String, Object> b) {
            return portal.withToken(token, () -> portal.complain(body(b)));
        }
    }
}
