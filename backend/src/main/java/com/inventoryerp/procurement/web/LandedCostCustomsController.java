package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.LandedCostCustomsService;
import com.inventoryerp.procurement.application.LandedCostCustomsService.CalculationInput;
import com.inventoryerp.procurement.application.LandedCostCustomsService.TariffInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Landed cost LC-7 (ADR-S053; extension, no NestJS counterpart): customs tariff lines (versioned), immutable customs
 * calculations, and landed cost allocation templates.
 */
@RestController
@RequestMapping("/procurement")
public class LandedCostCustomsController {

    public record TariffRequest(@NotBlank(message = "importCountry should not be empty") String importCountry,
            @NotBlank(message = "hsCode should not be empty") String hsCode, String originCountry, String preferenceCode,
            @NotBlank(message = "effectiveFrom should not be empty") String effectiveFrom, String effectiveTo,
            String valuationBasis, String dutyType, BigDecimal adValoremPct, BigDecimal specificAmount,
            String specificUom, BigDecimal additionalDutyPct, BigDecimal excisePct, String legalReference,
            String source) {

        TariffInput input() {
            return new TariffInput(importCountry, hsCode, originCountry, preferenceCode, effectiveFrom, effectiveTo,
                    valuationBasis, dutyType, adValoremPct, specificAmount, specificUom, additionalDutyPct, excisePct,
                    legalReference, source);
        }
    }

    public record CalculationRequest(@NotBlank(message = "importCountry should not be empty") String importCountry,
            @NotBlank(message = "hsCode should not be empty") String hsCode, String originCountry, String preferenceCode,
            @NotBlank(message = "declarationDate should not be empty") String declarationDate,
            BigDecimal transactionValue, BigDecimal fobValue, BigDecimal freight, BigDecimal insurance,
            BigDecimal quantity, BigDecimal manualAdjustment, String adjustmentReason) {}

    public record LinkRequest(@NotBlank(message = "chargeId should not be empty") String chargeId) {}

    public record TemplateRequest(@NotBlank(message = "name should not be empty") String name, String description,
            List<Map<String, Object>> charges) {}

    public record ApplyRequest(List<String> goodsReceiptIds) {}

    private final LandedCostCustomsService customs;

    public LandedCostCustomsController(LandedCostCustomsService customs) {
        this.customs = customs;
    }

    @GetMapping("/customs/tariffs")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> tariffs(@RequestParam(required = false) String hsCode,
            @RequestParam(required = false) Boolean history) {
        return customs.tariffs(hsCode, Boolean.TRUE.equals(history));
    }

    @PostMapping("/customs/tariffs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LANDED_COST__CONFIGURE)
    public Map<String, Object> createTariff(@Valid @RequestBody TariffRequest r) {
        return customs.createTariff(r.input());
    }

    @PutMapping("/customs/tariffs/{id}")
    @RequirePermissions(Permissions.LANDED_COST__CONFIGURE)
    public Map<String, Object> reviseTariff(@PathVariable String id, @Valid @RequestBody TariffRequest r) {
        return customs.reviseTariff(id, r.input());
    }

    @DeleteMapping("/customs/tariffs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.LANDED_COST__CONFIGURE)
    public void retireTariff(@PathVariable String id) {
        customs.retireTariff(id);
    }

    @GetMapping("/customs/calculations")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> calculations() {
        return customs.calculations();
    }

    @GetMapping("/customs/calculations/{id}")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public Map<String, Object> calculation(@PathVariable String id) {
        return customs.calculation(id);
    }

    @PostMapping("/customs/calculations")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> calculate(@Valid @RequestBody CalculationRequest r) {
        return customs.calculate(new CalculationInput(r.importCountry(), r.hsCode(), r.originCountry(),
                r.preferenceCode(), r.declarationDate(), r.transactionValue(), r.fobValue(), r.freight(), r.insurance(),
                r.quantity(), r.manualAdjustment(), r.adjustmentReason()));
    }

    @PostMapping("/customs/calculations/{id}/link")
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> link(@PathVariable String id, @Valid @RequestBody LinkRequest r) {
        return customs.link(id, r.chargeId());
    }

    @GetMapping("/landed-cost-templates")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> templates() {
        return customs.templates();
    }

    @PostMapping("/landed-cost-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LANDED_COST__CONFIGURE)
    public Map<String, Object> saveTemplate(@Valid @RequestBody TemplateRequest r) {
        return customs.saveTemplate(r.name(), r.description(), r.charges());
    }

    @DeleteMapping("/landed-cost-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.LANDED_COST__CONFIGURE)
    public void deleteTemplate(@PathVariable String id) {
        customs.deleteTemplate(id);
    }

    @PostMapping("/landed-cost-templates/{id}/apply")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> applyTemplate(@PathVariable String id, @RequestBody ApplyRequest r) {
        return customs.applyTemplate(id, r.goodsReceiptIds());
    }
}
