package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.ForecastService;
import com.inventoryerp.manufacturing.application.MfgCommands.AddPlanLines;
import com.inventoryerp.manufacturing.application.MfgCommands.CreatePlan;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateRequest;
import com.inventoryerp.manufacturing.application.MfgCommands.RejectRequest;
import com.inventoryerp.manufacturing.application.MrpService;
import com.inventoryerp.manufacturing.application.ProductionPlanService;
import com.inventoryerp.manufacturing.application.ProductionReportService;
import com.inventoryerp.manufacturing.application.ProductionRequestService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The four controllers of {@code request-plan.controller.ts}. Only the request controller carries
 * {@code @RequiresModule} in Nest; plans, reports and MRP are gated by the module flag alone.
 */
final class RequestPlanControllers {

    private RequestPlanControllers() {}

    /** {@code /manufacturing/requests}. */
    @RestController
    @RequestMapping("/manufacturing/requests")
    @RequiresModule("manufacturing")
    @ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
    static class ProductionRequestController {

        private final ProductionRequestService requests;

        ProductionRequestController(ProductionRequestService requests) {
            this.requests = requests;
        }

        @GetMapping
        @RequirePermissions(Permissions.PRODUCTION_REQUEST__READ)
        public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
            return requests.list(status, limit, offset);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.PRODUCTION_REQUEST__READ)
        public Map<String, Object> get(@PathVariable String id) {
            return requests.get(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_REQUEST__CREATE)
        public Map<String, Object> create(@Valid @RequestBody CreateRequest dto) {
            return requests.create(dto);
        }

        @PostMapping("/{id}/submit")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_REQUEST__SUBMIT)
        public Map<String, Object> submit(@PathVariable String id) {
            return requests.submit(id);
        }

        @PostMapping("/{id}/approve")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_REQUEST__APPROVE)
        public Map<String, Object> approve(@PathVariable String id) {
            return requests.approve(id);
        }

        @PostMapping("/{id}/reject")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_REQUEST__REJECT)
        public Map<String, Object> reject(@PathVariable String id,
                @Valid @RequestBody(required = false) RejectRequest dto) {
            return requests.reject(id, dto);
        }

        @PostMapping("/{id}/cancel")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_REQUEST__CREATE)
        public Map<String, Object> cancel(@PathVariable String id) {
            return requests.cancel(id);
        }
    }

    /** {@code /manufacturing/plans}. */
    @RestController
    @RequestMapping("/manufacturing/plans")
    @ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
    static class ProductionPlanController {

        private final ProductionPlanService plans;

        ProductionPlanController(ProductionPlanService plans) {
            this.plans = plans;
        }

        @GetMapping
        @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
        public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
            return plans.list(status, limit, offset);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.PRODUCTION_PLAN__READ)
        public Map<String, Object> get(@PathVariable String id) {
            return plans.get(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_PLAN__CREATE)
        public Map<String, Object> create(@Valid @RequestBody(required = false) CreatePlan dto) {
            return plans.create(dto != null ? dto : new CreatePlan(null, null, null, null, null, null));
        }

        @PostMapping("/{id}/lines")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
        public Map<String, Object> addLines(@PathVariable String id, @Valid @RequestBody AddPlanLines dto) {
            return plans.addLines(id, dto);
        }

        @PostMapping("/{id}/confirm")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_PLAN__CONFIRM)
        public Map<String, Object> confirm(@PathVariable String id) {
            return plans.confirm(id);
        }

        @PostMapping("/{id}/cancel")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_PLAN__UPDATE)
        public Map<String, Object> cancel(@PathVariable String id) {
            return plans.cancel(id);
        }
    }

    /** {@code /manufacturing/reports}. */
    @RestController
    @RequestMapping("/manufacturing/reports")
    @ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
    static class ProductionReportController {

        private final ProductionReportService reports;
        private final ForecastService forecast;

        ProductionReportController(ProductionReportService reports, ForecastService forecast) {
            this.reports = reports;
            this.forecast = forecast;
        }

        @GetMapping("/forecast")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public Map<String, Object> demandForecast(@RequestParam(required = false) String weeks,
                @RequestParam(required = false) String targetDate) {
            return forecast.forecast(weeks != null && !weeks.isEmpty() ? JsNumbers.number(weeks) : null, targetDate);
        }

        @GetMapping("/wip-reconciliation")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public Map<String, Object> wip() {
            return reports.wipReconciliation();
        }

        @GetMapping("/kpis")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public Map<String, Object> kpis(@RequestParam(required = false) String from,
                @RequestParam(required = false) String to) {
            return reports.kpis(from, to);
        }

        @GetMapping("/genealogy/backward/{orderId}")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public Map<String, Object> backward(@PathVariable String orderId) {
            return reports.genealogyBackward(orderId);
        }

        @GetMapping("/genealogy/forward/{productId}")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public List<Map<String, Object>> forward(@PathVariable String productId) {
            return reports.genealogyForward(productId);
        }

        @GetMapping("/shortage/{productId}")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public Map<String, Object> shortage(@PathVariable String productId,
                @RequestParam(required = false) String qty, @RequestParam(required = false) String locationId) {
            return reports.shortage(productId, qty == null ? 1 : JsNumbers.number(qty), locationId);
        }

        @GetMapping("/trends")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public List<Map<String, Object>> trends(@RequestParam(required = false) String from,
                @RequestParam(required = false) String to) {
            return reports.trends(from, to);
        }

        @GetMapping("/yield-by-bom")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public List<Map<String, Object>> yieldByBom(@RequestParam(required = false) String from,
                @RequestParam(required = false) String to) {
            return reports.yieldByBom(from, to);
        }

        @GetMapping("/ingredient-usage")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public List<Map<String, Object>> ingredientUsage(@RequestParam(required = false) String from,
                @RequestParam(required = false) String to) {
            return reports.ingredientUsage(from, to);
        }

        @GetMapping("/capacity")
        @RequirePermissions(Permissions.PRODUCTION__REPORT)
        public List<Map<String, Object>> capacity() {
            return reports.capacity();
        }
    }

    /** {@code /manufacturing/mrp}. */
    @RestController
    @RequestMapping("/manufacturing/mrp")
    @ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
    static class MrpController {

        private final MrpService mrp;

        MrpController(MrpService mrp) {
            this.mrp = mrp;
        }

        @PostMapping("/from-document/{id}")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_REQUEST__CREATE)
        public Map<String, Object> fromDocument(@PathVariable String id) {
            return mrp.raiseFromDocument(id);
        }

        @PostMapping("/scan-min-stock")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_REQUEST__CREATE)
        public Map<String, Object> scanMinStock() {
            return mrp.scanMinStock();
        }

        @PostMapping("/run")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.PRODUCTION_PLAN__CONFIRM)
        public Map<String, Object> run() {
            return mrp.runMrp();
        }
    }
}
