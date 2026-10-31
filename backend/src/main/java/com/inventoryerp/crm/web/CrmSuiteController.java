package com.inventoryerp.crm.web;

import com.inventoryerp.crm.application.CrmCaseService;
import com.inventoryerp.crm.application.CrmConfigService;
import com.inventoryerp.crm.application.CrmPipelineService;
import com.inventoryerp.crm.application.CrmPlannerService;
import com.inventoryerp.crm.application.CrmPricingService;
import com.inventoryerp.crm.application.CrmQuotationService;
import com.inventoryerp.crm.application.CrmReportService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRM sales suite routes (ADR-S010) — configuration, leads / opportunities, planned activities, pricing, quotations,
 * reports, dashboard and customers. The parity routes of the Nest port stay in {@link CrmController}.
 */
public final class CrmSuiteController {

    private CrmSuiteController() {}

    static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    @SuppressWarnings("unchecked")
    static List<String> ids(Map<String, Object> b) {
        Object ids = body(b).get("ids");
        if (!(ids instanceof List<?> l)) {
            throw ApiException.badRequest("ids must be an array");
        }
        return ((List<Object>) l).stream().map(String::valueOf).toList();
    }

    /** {@code /crm/config}: stages, teams, tags, sources, lost reasons, settings. */
    @RestController
    @RequestMapping("/crm/config")
    public static class ConfigController {

        private final CrmConfigService svc;

        public ConfigController(CrmConfigService svc) {
            this.svc = svc;
        }

        @GetMapping("/lookups")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> lookups() {
            return svc.lookups();
        }

        @GetMapping("/stages")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public List<Map<String, Object>> stages() {
            svc.ensureDefaults();
            return svc.stages();
        }

        @PostMapping("/stages")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> createStage(@RequestBody Map<String, Object> b) {
            return svc.createStage(body(b));
        }

        @PostMapping("/stages/reorder")
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public List<Map<String, Object>> reorder(@RequestBody Map<String, Object> b) {
            return svc.reorderStages(ids(b));
        }

        @PatchMapping("/stages/{id}")
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> updateStage(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.updateStage(id, body(b));
        }

        @DeleteMapping("/stages/{id}")
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> deleteStage(@PathVariable String id) {
            return svc.deleteStage(id);
        }

        @PostMapping("/teams")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> createTeam(@RequestBody Map<String, Object> b) {
            return svc.saveTeam(null, body(b));
        }

        @PatchMapping("/teams/{id}")
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> updateTeam(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.saveTeam(id, body(b));
        }

        @DeleteMapping("/teams/{id}")
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> deleteTeam(@PathVariable String id) {
            return svc.deleteLookup("CrmTeam", id);
        }

        @PostMapping({"/tags", "/sources", "/lost-reasons"})
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> createLookup(HttpServletRequest req, @RequestBody Map<String, Object> b) {
            return svc.saveLookup(table(req), null, body(b));
        }

        @PatchMapping({"/tags/{id}", "/sources/{id}", "/lost-reasons/{id}"})
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> updateLookup(HttpServletRequest req, @PathVariable String id,
                @RequestBody Map<String, Object> b) {
            return svc.saveLookup(table(req), id, body(b));
        }

        @DeleteMapping({"/tags/{id}", "/sources/{id}", "/lost-reasons/{id}"})
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> deleteLookup(HttpServletRequest req, @PathVariable String id) {
            return svc.deleteLookup(table(req), id);
        }

        @GetMapping("/settings")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> settings() {
            return svc.settings();
        }

        @PutMapping("/settings")
        @RequirePermissions(Permissions.CRM__CONFIGURE)
        public Map<String, Object> updateSettings(@RequestBody Map<String, Object> b) {
            return svc.updateSettings(body(b));
        }

        /** Lookup table of the request path ({@code /crm/config/tags|sources|lost-reasons[/id]}). */
        private static String table(HttpServletRequest req) {
            String uri = req.getRequestURI();
            if (uri.contains("/crm/config/tags")) {
                return "CrmTag";
            }
            return uri.contains("/crm/config/sources") ? "CrmSource" : "CrmLostReason";
        }
    }

    /** {@code /crm/saved-filters}: per-user favorites (optionally shared). */
    @RestController
    @RequestMapping("/crm/saved-filters")
    public static class SavedFilterController {

        private final CrmConfigService svc;

        public SavedFilterController(CrmConfigService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public List<Map<String, Object>> list(@RequestParam(required = false) String scope) {
            return svc.savedFilters(scope);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.saveFilter(body(b));
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> delete(@PathVariable String id) {
            return svc.deleteFilter(id);
        }
    }

    /** {@code /crm/cases}: customer complaints and service requests (CRM-1). */
    @RestController
    @RequestMapping("/crm/cases")
    public static class CaseController {

        private final CrmCaseService svc;

        public CaseController(CrmCaseService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.CRM__CASE_READ)
        public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> q) {
            return svc.list(q);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.CRM__CASE_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__CASE_WRITE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.create(body(b));
        }

        @PostMapping("/from-discrepancy/{discrepancyId}")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__CASE_WRITE)
        public Map<String, Object> fromDiscrepancy(@PathVariable String discrepancyId,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.fromDiscrepancy(discrepancyId, body(b));
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.CRM__CASE_WRITE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }

        @PostMapping("/{id}/assign")
        @RequirePermissions(Permissions.CRM__CASE_WRITE)
        public Map<String, Object> assign(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.assign(id, body(b));
        }

        @PostMapping("/{id}/notes")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__CASE_WRITE)
        public Map<String, Object> addNote(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.addNote(id, body(b));
        }

        @PostMapping("/{id}/status")
        @RequirePermissions(Permissions.CRM__CASE_WRITE)
        public Map<String, Object> setStatus(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.setStatus(id, body(b));
        }
    }

    /** {@code /crm/opportunities}: pipeline, list, grouped table and the opportunity lifecycle. */
    @RestController
    @RequestMapping("/crm/opportunities")
    public static class OpportunityController {

        private final CrmPipelineService svc;

        public OpportunityController(CrmPipelineService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> list(@RequestParam Map<String, String> q) {
            Map<String, String> p = new HashMap<>(q);
            p.putIfAbsent("kind", "opportunity");
            return svc.list(p);
        }

        @GetMapping("/kanban")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> kanban(@RequestParam Map<String, String> q) {
            return svc.kanban(q);
        }

        @GetMapping("/grouped")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public List<Map<String, Object>> grouped(@RequestParam Map<String, String> q) {
            Map<String, String> p = new HashMap<>(q);
            p.putIfAbsent("kind", "opportunity");
            return svc.grouped(p.get("groupBy"), p);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @GetMapping("/{id}/timeline")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public List<Map<String, Object>> timeline(@PathVariable String id) {
            return svc.timeline(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            Map<String, Object> in = body(b);
            in.put("kind", "opportunity");
            return svc.create(in);
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }

        @PostMapping("/{id}/move")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> move(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.move(id, body(b));
        }

        @PostMapping("/{id}/won")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> won(@PathVariable String id) {
            return svc.won(id);
        }

        @PostMapping("/{id}/lost")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> lost(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.lost(id, body(b));
        }

        @PostMapping("/{id}/reopen")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> reopen(@PathVariable String id) {
            return svc.reopen(id);
        }

        @PostMapping("/{id}/assign")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> assign(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.assign(id, body(b));
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> archive(@PathVariable String id) {
            return svc.archive(id);
        }
    }

    /** {@code /crm/leads}: the unqualified-lead inbox and lead → opportunity conversion. */
    @RestController
    @RequestMapping("/crm/leads")
    public static class LeadController {

        private final CrmPipelineService svc;

        public LeadController(CrmPipelineService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.CRM__LEAD_READ)
        public Map<String, Object> list(@RequestParam Map<String, String> q) {
            Map<String, String> p = new HashMap<>(q);
            p.put("kind", "lead");
            return svc.list(p);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__LEAD_WRITE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            Map<String, Object> in = body(b);
            in.put("kind", "lead");
            return svc.create(in);
        }

        @PostMapping("/{id}/convert")
        @RequirePermissions({Permissions.CRM__LEAD_WRITE, Permissions.CRM__DEAL_WRITE})
        public Map<String, Object> convert(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> b) {
            return svc.convert(id, body(b));
        }
    }

    /** {@code /crm/planner}: planned activities ("My activities"). */
    @RestController
    @RequestMapping("/crm/planner")
    public static class PlannerController {

        private final CrmPlannerService svc;

        public PlannerController(CrmPlannerService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.CRM__ACTIVITY_READ)
        public Map<String, Object> list(@RequestParam Map<String, String> q) {
            return svc.list(q);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__ACTIVITY_WRITE)
        public Map<String, Object> schedule(@RequestBody Map<String, Object> b) {
            return svc.schedule(body(b));
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.CRM__ACTIVITY_WRITE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }

        @PostMapping("/{id}/done")
        @RequirePermissions(Permissions.CRM__ACTIVITY_WRITE)
        public Map<String, Object> done(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.done(id, body(b));
        }

        @PostMapping("/{id}/cancel")
        @RequirePermissions(Permissions.CRM__ACTIVITY_WRITE)
        public Map<String, Object> cancel(@PathVariable String id) {
            return svc.cancel(id);
        }
    }

    /** {@code /crm/pricing}: customer-specific price rules and price resolution. */
    @RestController
    @RequestMapping("/crm/pricing")
    public static class PricingController {

        private final CrmPricingService svc;

        public PricingController(CrmPricingService svc) {
            this.svc = svc;
        }

        @GetMapping("/rules")
        @RequirePermissions(Permissions.CRM__PRICING_READ)
        public List<Map<String, Object>> rules(@RequestParam Map<String, String> q) {
            return svc.rules(q);
        }

        @PostMapping("/rules")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__PRICING_MANAGE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.saveRule(null, body(b));
        }

        @PatchMapping("/rules/{id}")
        @RequirePermissions(Permissions.CRM__PRICING_MANAGE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.saveRule(id, body(b));
        }

        @DeleteMapping("/rules/{id}")
        @RequirePermissions(Permissions.CRM__PRICING_MANAGE)
        public Map<String, Object> delete(@PathVariable String id) {
            return svc.deleteRule(id);
        }

        @PostMapping("/resolve")
        @RequirePermissions(Permissions.CRM__PRICING_READ)
        public Map<String, Object> resolve(@RequestBody Map<String, Object> b) {
            return svc.resolve(body(b));
        }
    }

    /** {@code /crm/quotations}: quotation lifecycle, approval and conversion. */
    @RestController
    @RequestMapping("/crm/quotations")
    public static class QuotationController {

        private final CrmQuotationService svc;

        public QuotationController(CrmQuotationService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.CRM__QUOTATION_READ)
        public Map<String, Object> list(@RequestParam Map<String, String> q) {
            return svc.list(q);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.CRM__QUOTATION_READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__QUOTATION_WRITE)
        public Map<String, Object> create(@RequestBody Map<String, Object> b) {
            return svc.create(body(b));
        }

        @PutMapping("/{id}")
        @RequirePermissions(Permissions.CRM__QUOTATION_WRITE)
        public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
            return svc.update(id, body(b));
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.CRM__QUOTATION_WRITE)
        public Map<String, Object> delete(@PathVariable String id) {
            return svc.delete(id);
        }

        @PostMapping("/{id}/submit")
        @RequirePermissions(Permissions.CRM__QUOTATION_WRITE)
        public Map<String, Object> submit(@PathVariable String id) {
            return svc.submit(id);
        }

        @PostMapping("/{id}/approve")
        @RequirePermissions(Permissions.CRM__QUOTATION_APPROVE)
        public Map<String, Object> approve(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> b) {
            Object c = body(b).get("comment");
            return svc.approve(id, c == null ? null : c.toString());
        }

        @PostMapping("/{id}/reject")
        @RequirePermissions(Permissions.CRM__QUOTATION_APPROVE)
        public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            Object r = body(b).get("reason");
            return svc.reject(id, r == null ? null : r.toString());
        }

        @PostMapping("/{id}/send")
        @RequirePermissions(Permissions.CRM__QUOTATION_WRITE)
        public Map<String, Object> send(@PathVariable String id) {
            return svc.send(id);
        }

        @PostMapping("/{id}/accept")
        @RequirePermissions(Permissions.CRM__QUOTATION_WRITE)
        public Map<String, Object> accept(@PathVariable String id) {
            return svc.accept(id);
        }

        @PostMapping("/{id}/decline")
        @RequirePermissions(Permissions.CRM__QUOTATION_WRITE)
        public Map<String, Object> decline(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            Object r = body(b).get("reason");
            return svc.decline(id, r == null ? null : r.toString());
        }

        @PostMapping("/{id}/cancel")
        @RequirePermissions(Permissions.CRM__QUOTATION_WRITE)
        public Map<String, Object> cancel(@PathVariable String id) {
            return svc.cancel(id);
        }

        @PostMapping("/{id}/revise")
        @RequirePermissions(Permissions.CRM__QUOTATION_WRITE)
        public Map<String, Object> revise(@PathVariable String id) {
            return svc.revise(id);
        }

        @PostMapping("/{id}/convert")
        @Idempotent
        @RequirePermissions(Permissions.CRM__QUOTATION_CONVERT)
        public Map<String, Object> convert(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
            return svc.convert(id, body(b));
        }
    }

    /** {@code /crm/reports}, {@code /crm/dashboard}, {@code /crm/customers}. */
    @RestController
    @RequestMapping("/crm")
    public static class InsightController {

        private final CrmReportService svc;

        public InsightController(CrmReportService svc) {
            this.svc = svc;
        }

        @GetMapping("/reports")
        @RequirePermissions(Permissions.CRM__REPORT_READ)
        public Map<String, Object> report(@RequestParam Map<String, String> q) {
            return svc.report(q);
        }

        @GetMapping("/dashboard")
        @RequirePermissions(Permissions.CRM__DASHBOARD_READ)
        public Map<String, Object> dashboard(@RequestParam(required = false) String scope) {
            return svc.dashboard(scope);
        }

        @GetMapping("/customers")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> customers(@RequestParam Map<String, String> q) {
            return svc.customers(q);
        }

        @GetMapping("/customers/{partnerId}")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> customer(@PathVariable String partnerId) {
            return svc.customer(partnerId);
        }
    }
}
