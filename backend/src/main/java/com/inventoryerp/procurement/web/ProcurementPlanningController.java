package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.ProcurementAnalyticsService;
import com.inventoryerp.procurement.application.PurchaseReplenishmentService;
import com.inventoryerp.procurement.application.PurchaseReplenishmentService.RuleInput;
import com.inventoryerp.procurement.application.SupplierPortalService;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
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
 * Purchase planning, analytics and supplier links (ADR-PROCUREMENT-001 wave 7; no Nest counterpart): reorder rules and
 * replenishment suggestions, supplier scorecards, price check, spend, demand forecast, risk alerts, and the per-document
 * supplier portal links.
 */
@RestController
@RequestMapping("/procurement")
public class ProcurementPlanningController {

    public record RuleRequest(@NotBlank(message = "productId is required") String productId,
            @NotBlank(message = "locationId is required") String locationId, String branchId,
            @OneOf({"min_max", "reorder_point"}) String method,
            @DecimalMin(value = "0", message = "minQuantity must not be less than 0") BigDecimal minQuantity,
            @DecimalMin(value = "0", message = "maxQuantity must not be less than 0") BigDecimal maxQuantity,
            @DecimalMin(value = "0", message = "safetyStock must not be less than 0") BigDecimal safetyStock,
            @Min(value = 0, message = "leadTimeDays must not be less than 0")
            @Max(value = 365, message = "leadTimeDays must not be greater than 365") Integer leadTimeDays,
            @Positive(message = "orderMultiple must be a positive number") BigDecimal orderMultiple,
            String preferredSupplierId, Boolean autoRaise, Boolean isActive) {}

    public record RaiseRequest(List<String> ruleIds) {}

    public record LinkRequest(@NotNull(message = "documentType is required") @OneOf({"rfq", "purchase_order"})
            String documentType, @NotBlank(message = "documentId is required") String documentId, String partnerId,
            @Min(value = 1, message = "days must not be less than 1") @Max(value = 90, message = "days must not be"
                    + " greater than 90") Integer days) {}

    private final PurchaseReplenishmentService replenishment;
    private final ProcurementAnalyticsService analytics;
    private final SupplierPortalService portal;

    public ProcurementPlanningController(PurchaseReplenishmentService replenishment, ProcurementAnalyticsService analytics,
            SupplierPortalService portal) {
        this.replenishment = replenishment;
        this.analytics = analytics;
        this.portal = portal;
    }

    // ── replenishment ───────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/replenishment/rules")
    @RequirePermissions(Permissions.REPLENISHMENT__READ)
    public List<Map<String, Object>> rules() {
        return replenishment.rules();
    }

    @PutMapping("/replenishment/rules")
    @RequirePermissions(Permissions.REPLENISHMENT__MANAGE)
    public Map<String, Object> upsertRule(@Valid @RequestBody RuleRequest dto) {
        return replenishment.upsert(new RuleInput(dto.productId(), dto.locationId(), dto.branchId(), dto.method(),
                dto.minQuantity(), dto.maxQuantity(), dto.safetyStock(), dto.leadTimeDays(), dto.orderMultiple(),
                dto.preferredSupplierId(), dto.autoRaise(), dto.isActive()));
    }

    @DeleteMapping("/replenishment/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.REPLENISHMENT__MANAGE)
    public void deleteRule(@PathVariable String id) {
        replenishment.remove(id);
    }

    @GetMapping("/replenishment/suggestions")
    @RequirePermissions(Permissions.REPLENISHMENT__READ)
    public List<Map<String, Object>> suggestions(@RequestParam(defaultValue = "false") boolean all) {
        return replenishment.suggestions(all);
    }

    @PostMapping("/replenishment/raise")
    @RequirePermissions(Permissions.REPLENISHMENT__MANAGE)
    public Map<String, Object> raise(@RequestBody(required = false) RaiseRequest dto) {
        Map<String, Object> pr = replenishment.raise(dto == null ? null : dto.ruleIds(), false);
        return pr == null ? Map.of("raised", false, "message", "Nothing needs reordering") : pr;
    }

    // ── analytics ───────────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/analytics/scorecards")
    @RequirePermissions(Permissions.PROCUREMENT_ANALYTICS__READ)
    public List<Map<String, Object>> scorecards(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return analytics.scorecards(from, to);
    }

    @GetMapping("/analytics/scorecards/{partnerId}")
    @RequirePermissions(Permissions.PROCUREMENT_ANALYTICS__READ)
    public Map<String, Object> scorecard(@PathVariable String partnerId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return analytics.scorecard(partnerId, from, to);
    }

    @GetMapping("/analytics/price-check")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__READ)
    public Map<String, Object> priceCheck(@RequestParam(required = false) String productId,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) BigDecimal unitPrice) {
        return analytics.priceCheck(productId, partnerId, unitPrice);
    }

    @GetMapping("/analytics/spend")
    @RequirePermissions(Permissions.PROCUREMENT_ANALYTICS__READ)
    public Map<String, Object> spend(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return analytics.spend(groupBy, from, to);
    }

    @GetMapping("/analytics/forecast")
    @RequirePermissions(Permissions.PROCUREMENT_ANALYTICS__READ)
    public Map<String, Object> forecast(@RequestParam(required = false) String productId,
            @RequestParam(required = false) String locationId) {
        return analytics.forecast(productId, locationId);
    }

    @GetMapping("/analytics/alerts")
    @RequirePermissions(Permissions.PROCUREMENT_ANALYTICS__READ)
    public List<Map<String, Object>> alerts() {
        return analytics.alerts();
    }

    // ── supplier portal links ───────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/portal-links")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SUPPLIER_PORTAL__MANAGE)
    public Map<String, Object> issue(@Valid @RequestBody LinkRequest dto) {
        return portal.issue(dto.documentType(), dto.documentId(), dto.partnerId(), dto.days());
    }

    @GetMapping("/portal-links")
    @RequirePermissions(Permissions.SUPPLIER_PORTAL__MANAGE)
    public List<Map<String, Object>> links(@RequestParam String documentType, @RequestParam String documentId) {
        return portal.links(documentType, documentId);
    }

    @PostMapping("/portal-links/{id}/revoke")
    @RequirePermissions(Permissions.SUPPLIER_PORTAL__MANAGE)
    public Map<String, Object> revoke(@PathVariable String id) {
        return portal.revoke(id);
    }
}
