package com.inventoryerp.crm.web;

import com.inventoryerp.crm.application.CrmAnalyticsService;
import com.inventoryerp.crm.application.CrmService;
import com.inventoryerp.crm.application.CrmService.CreateActivityInput;
import com.inventoryerp.crm.application.CrmService.CreateDealInput;
import com.inventoryerp.crm.application.CrmService.ListQuery;
import com.inventoryerp.crm.application.CrmService.UpdateActivityInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /crm/deals}, {@code /crm/activities}, {@code /crm/analytics} (port of {@code crm.controller.ts}). */
public final class CrmController {

    private CrmController() {}

    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";
    static final String DATE_MSG = "must be a valid ISO 8601 date string";
    static final String STR = "must be a string";
    static final String STAGES_MSG = "must be one of the following values: lead, qualified, proposal, negotiation, won, lost";
    static final String TYPES_MSG = "must be one of the following values: call, email, meeting, note, task";

    /** {@code CreateDealDto}. */
    public record CreateDealRequest(
            @NotNull(message = STR) String name,
            @NotNull(message = STR) String partnerId,
            String ownerId,
            @OneOf({"lead", "qualified", "proposal", "negotiation", "won", "lost"}) String stage,
            BigDecimal amount,
            String currencyCode,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expectedClose,
            String notes) {}

    /** {@code UpdateDealDto}. */
    public record UpdateDealRequest(
            String name,
            @OneOf({"lead", "qualified", "proposal", "negotiation", "won", "lost"}) String stage,
            BigDecimal amount,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expectedClose,
            String notes,
            String ownerId) {}

    /** {@code CreateActivityDto}. */
    public record CreateActivityRequest(
            @NotNull(message = TYPES_MSG) @OneOf({"call", "email", "meeting", "note", "task"}) String type,
            @NotNull(message = STR) String title,
            String body,
            String subjectType,
            String subjectId,
            String dealId,
            String partnerId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueAt,
            Integer duration) {}

    /** {@code UpdateActivityDto}. */
    public record UpdateActivityRequest(
            String title,
            String body,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueAt,
            @OneOf({"todo", "in_progress", "done", "cancelled"}) String status,
            Boolean completed) {}

    static Integer optInt(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        double n = JsNumbers.number(v);
        return Double.isNaN(n) ? null : (int) n;
    }

    /** {@code DealsController}. */
    @RestController
    @RequestMapping("/crm/deals")
    public static class DealsController {

        private final CrmService svc;

        public DealsController(CrmService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> list(@RequestParam(required = false) String stage,
                @RequestParam(required = false) String partnerId, @RequestParam(required = false) String ownerId,
                @RequestParam(required = false) String q, @RequestParam(required = false) String page,
                @RequestParam(required = false) String pageSize) {
            return svc.listDeals(new ListQuery(stage, null, null, partnerId, ownerId, null, q, optInt(page),
                    optInt(pageSize)));
        }

        @GetMapping("/deleted")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public List<Map<String, Object>> listDeleted() {
            return svc.listDeletedDeals();
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> findOne(@PathVariable String id) {
            return svc.findDeal(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> create(@Valid @RequestBody CreateDealRequest dto) {
            return svc.createDeal(new CreateDealInput(dto.name(), dto.partnerId(), dto.ownerId(), dto.stage(),
                    dto.amount(), dto.currencyCode(), dto.expectedClose(), dto.notes()));
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateDealRequest dto) {
            return svc.updateDeal(id, EntityPatch.data(dto));
        }

        /** Body is an untyped {@code { stage }} in Nest (no DTO validation). */
        @PatchMapping("/{id}/stage")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> changeStage(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> body) {
            Object stage = body == null ? null : body.get("stage");
            return svc.changeStage(id, stage == null ? null : stage.toString());
        }

        @PatchMapping("/{id}/restore")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> restore(@PathVariable String id) {
            return svc.restoreDeal(id);
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.CRM__DEAL_WRITE)
        public Map<String, Object> remove(@PathVariable String id) {
            return svc.removeDeal(id);
        }
    }

    /** {@code ActivitiesController}. */
    @RestController
    @RequestMapping("/crm/activities")
    public static class ActivitiesController {

        private final CrmService svc;

        public ActivitiesController(CrmService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.CRM__ACTIVITY_READ)
        public Map<String, Object> list(@RequestParam(required = false) String type,
                @RequestParam(required = false) String dealId, @RequestParam(required = false) String partnerId,
                @RequestParam(required = false) String status, @RequestParam(required = false) String page,
                @RequestParam(required = false) String pageSize) {
            return svc.listActivities(new ListQuery(null, type, dealId, partnerId, null, status, null, optInt(page),
                    optInt(pageSize)));
        }

        @GetMapping("/upcoming")
        @RequirePermissions(Permissions.CRM__ACTIVITY_READ)
        public List<Map<String, Object>> upcoming(@RequestParam(required = false) String limit) {
            Integer n = limit == null ? Integer.valueOf(20) : optInt(limit);
            return svc.upcomingTasks(n == null ? 20 : n);
        }

        @GetMapping("/deleted")
        @RequirePermissions(Permissions.CRM__ACTIVITY_READ)
        public List<Map<String, Object>> listDeleted() {
            return svc.listDeletedActivities();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__ACTIVITY_WRITE)
        public Map<String, Object> create(@Valid @RequestBody CreateActivityRequest dto) {
            return svc.createActivity(new CreateActivityInput(dto.type(), dto.title(), dto.body(), dto.subjectType(),
                    dto.subjectId(), dto.dealId(), dto.partnerId(), dto.dueAt(), dto.duration()));
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.CRM__ACTIVITY_WRITE)
        public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateActivityRequest dto) {
            return svc.updateActivity(id, new UpdateActivityInput(dto.title(), dto.body(), dto.dueAt(), dto.status(),
                    dto.completed()));
        }

        @PostMapping("/{id}/complete")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.CRM__ACTIVITY_WRITE)
        public Map<String, Object> complete(@PathVariable String id) {
            return svc.completeActivity(id);
        }

        @PatchMapping("/{id}/restore")
        @RequirePermissions(Permissions.CRM__ACTIVITY_WRITE)
        public Map<String, Object> restore(@PathVariable String id) {
            return svc.restoreActivity(id);
        }

        @DeleteMapping("/{id}")
        @RequirePermissions(Permissions.CRM__ACTIVITY_WRITE)
        public Map<String, Object> remove(@PathVariable String id) {
            return svc.removeActivity(id);
        }
    }

    /** {@code CrmAnalyticsController}. */
    @RestController
    @RequestMapping("/crm/analytics")
    public static class CrmAnalyticsController {

        private final CrmAnalyticsService svc;

        public CrmAnalyticsController(CrmAnalyticsService svc) {
            this.svc = svc;
        }

        @GetMapping("/pipeline")
        @RequirePermissions(Permissions.CRM__DASHBOARD_READ)
        public Map<String, Object> pipeline() {
            return svc.getPipeline();
        }

        @GetMapping("/win-rate")
        @RequirePermissions(Permissions.CRM__DASHBOARD_READ)
        public Map<String, Object> winRate(@RequestParam(required = false) String from,
                @RequestParam(required = false) String to) {
            return svc.getWinRate(from, to);
        }

        @GetMapping("/forecast")
        @RequirePermissions(Permissions.CRM__DASHBOARD_READ)
        public List<Map<String, Object>> forecast(@RequestParam(required = false) String months) {
            return svc.getForecast(months == null || months.isEmpty() ? null : JsNumbers.number(months));
        }

        @GetMapping("/top-customers")
        @RequirePermissions(Permissions.CRM__DASHBOARD_READ)
        public List<Map<String, Object>> topCustomers(@RequestParam(required = false) String limit) {
            Integer n = optInt(limit);
            return svc.getTopCustomers(n == null ? 10 : n);
        }

        @GetMapping("/partner/{partnerId}")
        @RequirePermissions(Permissions.CRM__DEAL_READ)
        public Map<String, Object> partner360(@PathVariable String partnerId) {
            return svc.getPartner360(partnerId);
        }
    }
}
