package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.Throttle;
import com.inventoryerp.procurement.application.RfqService.QuoteInput;
import com.inventoryerp.procurement.application.RfqService.QuoteLine;
import com.inventoryerp.procurement.application.SupplierPortalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public supplier portal (ADR-PROCUREMENT-001 wave 7): no login, the link token is the only credential and opens
 * exactly one document. Throttled per IP (30 requests a minute).
 */
@RestController
@RequestMapping("/supplier-portal/{token}")
@Public
public class SupplierPortalController {

    public record QuoteLineRequest(@NotBlank(message = "rfqLineId is required") String rfqLineId,
            @NotNull(message = "unitPrice is required") @DecimalMin(value = "0", message = "unitPrice must not be less"
                    + " than 0") BigDecimal unitPrice,
            @DecimalMin(value = "0", message = "quantity must not be less than 0") BigDecimal quantity,
            @Min(value = 0, message = "leadTimeDays must not be less than 0") Integer leadTimeDays,
            @DecimalMin(value = "0", message = "taxRate must not be less than 0") BigDecimal taxRate,
            @Size(max = 500, message = "alternative is too long") String alternative,
            @Size(max = 1000, message = "notes is too long") String notes) {}

    public record QuoteRequest(@Size(max = 100, message = "supplierReference is too long") String supplierReference,
            String validUntil, @Size(max = 3, message = "currencyCode is too long") String currencyCode,
            @Min(value = 0, message = "leadTimeDays must not be less than 0") Integer leadTimeDays,
            @Min(value = 0, message = "paymentTermsDays must not be less than 0") Integer paymentTermsDays,
            @DecimalMin(value = "0", message = "freightAmount must not be less than 0") BigDecimal freightAmount,
            @Size(max = 2000, message = "notes is too long") String notes,
            @NotNull(message = "lines must be an array") @Size(min = 1, max = 500, message = "lines must contain at"
                    + " least 1 element") List<@Valid QuoteLineRequest> lines) {}

    public record DeclineRequest(@Size(max = 1000, message = "reason is too long") String reason) {}

    public record ConfirmRequest(@Size(max = 100, message = "supplierReference is too long") String supplierReference,
            String promisedDate) {}

    private final SupplierPortalService portal;

    public SupplierPortalController(SupplierPortalService portal) {
        this.portal = portal;
    }

    @GetMapping
    @Throttle(limit = 30, ttlMs = 60_000)
    public Map<String, Object> view(@PathVariable String token) {
        return portal.view(token);
    }

    @PostMapping("/quotation")
    @Throttle(limit = 10, ttlMs = 60_000)
    public Map<String, Object> quote(@PathVariable String token, @Valid @RequestBody QuoteRequest dto) {
        return portal.quote(token, new QuoteInput(null, dto.supplierReference(), dto.validUntil(), dto.currencyCode(),
                null, dto.leadTimeDays(), dto.paymentTermsDays(), dto.freightAmount(), null, dto.notes(),
                dto.lines().stream().map(l -> new QuoteLine(l.rfqLineId(), l.unitPrice(), l.quantity(), l.leadTimeDays(),
                        l.taxRate(), l.alternative(), l.notes())).toList()));
    }

    @PostMapping("/decline")
    @Throttle(limit = 10, ttlMs = 60_000)
    public Map<String, Object> decline(@PathVariable String token, @Valid @RequestBody(required = false)
            DeclineRequest dto) {
        return portal.decline(token, dto == null ? null : dto.reason());
    }

    @PostMapping("/confirm")
    @Throttle(limit = 10, ttlMs = 60_000)
    public Map<String, Object> confirm(@PathVariable String token, @Valid @RequestBody ConfirmRequest dto) {
        return portal.confirm(token, dto.supplierReference(), dto.promisedDate());
    }
}
