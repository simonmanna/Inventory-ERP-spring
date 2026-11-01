package com.inventoryerp.costing.web;

import com.inventoryerp.costing.application.QuarryCostingService;
import com.inventoryerp.costing.application.QuarryCostingService.AreaInput;
import com.inventoryerp.costing.application.QuarryCostingService.CapitaliseInput;
import com.inventoryerp.costing.application.QuarryCostingService.MovementInput;
import com.inventoryerp.costing.application.QuarryCostingService.ObligationInput;
import com.inventoryerp.costing.application.QuarryCostingService.StrippingInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
 * {@code /quarry/areas}, {@code /quarry/stripping-*}, {@code /quarry/rehab-*} and {@code /quarry/cost-kpis} (Cost
 * Management CM-5, ADR-S035; extension): quarry area dimension, IFRIC 20 stripping-activity assets, IAS 37
 * rehabilitation provisions and the extraction cost-per-tonne KPI.
 */
@RestController
@RequestMapping("/quarry")
public class QuarryCostingController {

    public record AreaRequest(String areaStatus, BigDecimal disturbedAreaM2, BigDecimal reservesTonnes,
            @Size(max = 60, message = "oreBodyComponent must be at most 60 characters") String oreBodyComponent) {}

    public record StrippingRequest(
            @NotBlank(message = "code should not be empty") String code,
            @NotBlank(message = "siteId should not be empty") String siteId,
            @NotBlank(message = "zoneId should not be empty") String zoneId,
            @Size(max = 60, message = "component must be at most 60 characters") String component,
            BigDecimal expectedOreTonnes,
            BigDecimal expectedWasteTonnes,
            Boolean benefitProbable,
            Boolean componentIdentified,
            Boolean costMeasurable,
            @Size(max = 4000, message = "assessment must be at most 4000 characters") String assessment) {}

    public record CapitaliseRequest(
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to,
            BigDecimal strippingCost,
            BigDecimal wasteTonnes,
            @NotBlank(message = "creditAccountId should not be empty") String creditAccountId) {}

    public record PeriodRequest(
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to) {}

    public record ObligationRequest(
            @NotBlank(message = "code should not be empty") String code,
            @NotBlank(message = "siteId should not be empty") String siteId,
            @NotBlank(message = "zoneId should not be empty") String zoneId,
            BigDecimal discountRatePct,
            BigDecimal estimatePerM2,
            @Size(max = 2000, message = "description must be at most 2000 characters") String description) {}

    public record MovementRequest(
            @NotBlank(message = "type should not be empty") String type,
            String date,
            String from,
            BigDecimal amount,
            BigDecimal disturbedM2,
            String layerId,
            String creditAccountId,
            @Size(max = 2000, message = "notes must be at most 2000 characters") String notes) {}

    private final QuarryCostingService quarry;

    public QuarryCostingController(QuarryCostingService quarry) {
        this.quarry = quarry;
    }

    @GetMapping("/areas")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public List<Map<String, Object>> areas(@RequestParam(required = false) String siteId) {
        return quarry.areas(siteId);
    }

    @PutMapping("/areas/{zoneId}")
    @RequirePermissions(Permissions.QUARRY_SITE__UPDATE)
    public Map<String, Object> updateArea(@PathVariable String zoneId, @Valid @RequestBody AreaRequest r) {
        return quarry.updateArea(zoneId, new AreaInput(r.areaStatus(), r.disturbedAreaM2(), r.reservesTonnes(),
                r.oreBodyComponent()));
    }

    @GetMapping("/stripping-assets")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public List<Map<String, Object>> strippingAssets(@RequestParam(required = false) String siteId) {
        return quarry.strippingAssets(siteId);
    }

    @GetMapping("/stripping-assets/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> strippingAsset(@PathVariable String id) {
        return quarry.strippingAsset(id);
    }

    @PostMapping("/stripping-assets")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> createStrippingAsset(@Valid @RequestBody StrippingRequest r) {
        return quarry.createStrippingAsset(new StrippingInput(r.code(), r.siteId(), r.zoneId(), r.component(),
                r.expectedOreTonnes(), r.expectedWasteTonnes(), r.benefitProbable(), r.componentIdentified(),
                r.costMeasurable(), r.assessment()));
    }

    @PostMapping("/stripping-assets/{id}/approve")
    @RequirePermissions(Permissions.ACCOUNTING_POLICY__APPROVE)
    public Map<String, Object> approveStrippingAsset(@PathVariable String id) {
        return quarry.approveStrippingAsset(id);
    }

    @PostMapping("/stripping-assets/{id}/capitalise")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__POST)
    public Map<String, Object> capitalise(@PathVariable String id, @Valid @RequestBody CapitaliseRequest r) {
        return quarry.capitalise(id, new CapitaliseInput(r.from(), r.to(), r.strippingCost(), r.wasteTonnes(),
                r.creditAccountId()));
    }

    @PostMapping("/stripping-assets/{id}/amortise")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__POST)
    public Map<String, Object> amortise(@PathVariable String id, @Valid @RequestBody PeriodRequest r) {
        return quarry.amortise(id, r.from(), r.to());
    }

    @PostMapping("/stripping-movements/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__POST)
    public Map<String, Object> reverseStripping(@PathVariable String id) {
        return quarry.reverseStrippingMovement(id);
    }

    @GetMapping("/rehab-obligations")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public List<Map<String, Object>> obligations(@RequestParam(required = false) String siteId) {
        return quarry.obligations(siteId);
    }

    @GetMapping("/rehab-obligations/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> obligation(@PathVariable String id) {
        return quarry.obligation(id);
    }

    @PostMapping("/rehab-obligations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> createObligation(@Valid @RequestBody ObligationRequest r) {
        return quarry.createObligation(new ObligationInput(r.code(), r.siteId(), r.zoneId(), r.discountRatePct(),
                r.estimatePerM2(), r.description()));
    }

    @PostMapping("/rehab-obligations/{id}/movements")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__POST)
    public Map<String, Object> movement(@PathVariable String id, @Valid @RequestBody MovementRequest r) {
        return quarry.movement(id, new MovementInput(r.type(), r.date(), r.from(), r.amount(), r.disturbedM2(),
                r.layerId(), r.creditAccountId(), r.notes()));
    }

    @PostMapping("/rehab-movements/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__POST)
    public Map<String, Object> reverseMovement(@PathVariable String id) {
        return quarry.reverseMovement(id);
    }

    @GetMapping("/rehab-roll-forward")
    @RequirePermissions(Permissions.QUARRY__REPORT)
    public Map<String, Object> rollForward(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String siteId) {
        return quarry.rollForward(from, to, siteId);
    }

    @GetMapping("/cost-kpis")
    @RequirePermissions(Permissions.QUARRY__REPORT)
    public Map<String, Object> kpis(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String siteId) {
        return quarry.kpis(from, to, siteId);
    }
}
