package com.inventoryerp.procurement.web;

import static com.inventoryerp.procurement.web.ProcurementDtos.DATE_MSG;
import static com.inventoryerp.procurement.web.ProcurementDtos.ISO_DATE;
import static com.inventoryerp.procurement.web.ProcurementDtos.NOT_EMPTY;
import static com.inventoryerp.procurement.web.ProcurementDtos.NUM;
import static com.inventoryerp.procurement.web.ProcurementDtos.STR;

import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.PriceAgreementService;
import com.inventoryerp.procurement.application.PriceAgreementService.AgreementInput;
import com.inventoryerp.procurement.application.PriceAgreementService.AgreementPatch;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /procurement/price-agreements} and {@code /procurement/reports/price-history} (PROC-2). */
@RestController
@RequestMapping("/procurement")
public class PriceAgreementController {

    /** New agreement. */
    public record CreateAgreementRequest(
            @NotNull(message = STR) String partnerId,
            @NotNull(message = STR) String productId,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal unitPrice,
            @NotEmpty(message = NOT_EMPTY) String currencyCode,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal minQuantity,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String validFrom,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String validTo,
            String reference,
            String notes) {}

    /** End date and notes; everything else stays as agreed. */
    public record UpdateAgreementRequest(
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String validTo,
            String reference,
            String notes) {}

    private final PriceAgreementService svc;

    public PriceAgreementController(PriceAgreementService svc) {
        this.svc = svc;
    }

    @GetMapping("/price-agreements")
    @RequirePermissions(Permissions.PROCUREMENT__PRICE_AGREEMENT__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return svc.list(QueryParams.page(query, "partnerId", "productId", "status"), query.get("partnerId"),
                query.get("productId"), query.get("status"));
    }

    @GetMapping("/price-agreements/lookup")
    @RequirePermissions(Permissions.PROCUREMENT__PRICE_AGREEMENT__READ)
    public Map<String, Object> lookup(@RequestParam String partnerId, @RequestParam String productId,
            @RequestParam(required = false) BigDecimal quantity, @RequestParam(required = false) String date) {
        return svc.lookup(partnerId, productId, quantity, date);
    }

    @GetMapping("/price-agreements/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__PRICE_AGREEMENT__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return svc.findOne(id);
    }

    @PostMapping("/price-agreements")
    @RequirePermissions(Permissions.PROCUREMENT__PRICE_AGREEMENT__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody CreateAgreementRequest dto) {
        return svc.create(new AgreementInput(dto.partnerId(), dto.productId(), dto.unitPrice(), dto.currencyCode(),
                dto.minQuantity(), dto.validFrom(), dto.validTo(), dto.reference(), dto.notes()));
    }

    @PatchMapping("/price-agreements/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__PRICE_AGREEMENT__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateAgreementRequest dto) {
        return svc.update(id, new AgreementPatch(dto.validTo(), dto.reference(), dto.notes()));
    }

    @PostMapping("/price-agreements/{id}/cancel")
    @RequirePermissions(Permissions.PROCUREMENT__PRICE_AGREEMENT__MANAGE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return svc.cancel(id);
    }

    @GetMapping("/reports/price-history")
    @RequirePermissions(Permissions.PROCUREMENT__PRICE_AGREEMENT__READ)
    public Map<String, Object> priceHistory(@RequestParam(required = false) String productId,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return svc.priceHistory(productId, partnerId, from, to);
    }
}
