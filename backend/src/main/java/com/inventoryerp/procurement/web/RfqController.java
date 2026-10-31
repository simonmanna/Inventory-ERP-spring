package com.inventoryerp.procurement.web;

import static com.inventoryerp.procurement.web.ProcurementDtos.ARR;
import static com.inventoryerp.procurement.web.ProcurementDtos.DATE_MSG;
import static com.inventoryerp.procurement.web.ProcurementDtos.ISO_DATE;
import static com.inventoryerp.procurement.web.ProcurementDtos.NOT_EMPTY;
import static com.inventoryerp.procurement.web.ProcurementDtos.NUM;
import static com.inventoryerp.procurement.web.ProcurementDtos.STR;

import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.RfqService;
import com.inventoryerp.procurement.application.RfqService.AwardInput;
import com.inventoryerp.procurement.application.RfqService.LineInput;
import com.inventoryerp.procurement.application.RfqService.QuotationInput;
import com.inventoryerp.procurement.application.RfqService.QuoteLineInput;
import com.inventoryerp.procurement.application.RfqService.RfqInput;
import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /procurement/rfqs}: requests for quotation, supplier quotations, comparison and award (PROC-1). */
@RestController
@RequestMapping("/procurement/rfqs")
public class RfqController {

    /** One requested item. */
    public record RfqLineRequest(
            String productId,
            @NotEmpty(message = NOT_EMPTY) String description,
            @NotNull(message = NUM) @DecimalMin(value = "0.001", message = "must not be less than 0.001")
            BigDecimal quantity,
            String unitOfMeasureId) {}

    /** Draft body. */
    public record RfqRequest(
            @NotEmpty(message = NOT_EMPTY) String title,
            String purchaseRequestId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueDate,
            @NotEmpty(message = NOT_EMPTY) String currencyCode,
            String notes,
            @NotNull(message = ARR) @ArrayMinSize(1) List<@Valid RfqLineRequest> lines,
            List<String> supplierIds) {

        RfqInput toInput() {
            return new RfqInput(title, purchaseRequestId, dueDate, currencyCode, notes, lines.stream()
                    .map(l -> new LineInput(l.productId(), l.description(), l.quantity(),
                    l.unitOfMeasureId())).toList(),
                    supplierIds);
        }
    }

    /** One quoted price. */
    public record QuoteLineRequest(
            @NotNull(message = STR) String rfqLineId,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal unitPrice,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal taxRate) {}

    /** A supplier's quotation. */
    public record QuotationRequest(
            @NotNull(message = STR) String partnerId,
            String reference,
            String currencyCode,
            @DecimalMin(value = "0.00000001", message = "must be a positive number") BigDecimal exchangeRate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String validUntil,
            @Min(value = 0, message = "must not be less than 0") Integer leadTimeDays,
            String paymentTerms,
            String notes,
            @NotNull(message = ARR) @ArrayMinSize(1) List<@Valid QuoteLineRequest> lines) {

        QuotationInput toInput() {
            return new QuotationInput(partnerId, reference, currencyCode, exchangeRate, validUntil, leadTimeDays,
                    paymentTerms, notes, lines.stream()
                    .map(l -> new QuoteLineInput(l.rfqLineId(), l.unitPrice(), l.taxRate())).toList());
        }
    }

    /** Award one quotation. */
    public record AwardRequest(
            @NotNull(message = STR) String quotationId,
            @NotNull(message = STR) String warehouseId,
            @OneOf({"cash", "credit"}) String paymentType) {}

    private final RfqService svc;

    public RfqController(RfqService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.PROCUREMENT__RFQ__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return svc.list(QueryParams.page(query, "status"), query.get("status"));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__RFQ__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return svc.findOne(id);
    }

    @GetMapping("/{id}/comparison")
    @RequirePermissions(Permissions.PROCUREMENT__RFQ__READ)
    public Map<String, Object> comparison(@PathVariable String id) {
        return svc.comparison(id);
    }

    @PostMapping
    @RequirePermissions(Permissions.PROCUREMENT__RFQ__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody RfqRequest dto) {
        return svc.create(dto.toInput());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__RFQ__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody RfqRequest dto) {
        return svc.update(id, dto.toInput());
    }

    @PostMapping("/{id}/send")
    @RequirePermissions(Permissions.PROCUREMENT__RFQ__MANAGE)
    public Map<String, Object> send(@PathVariable String id) {
        return svc.send(id);
    }

    @PostMapping("/{id}/cancel")
    @RequirePermissions(Permissions.PROCUREMENT__RFQ__MANAGE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return svc.cancel(id);
    }

    @PostMapping("/{id}/quotations")
    @RequirePermissions(Permissions.PROCUREMENT__RFQ__MANAGE)
    public Map<String, Object> recordQuotation(@PathVariable String id, @Valid @RequestBody QuotationRequest dto) {
        return svc.recordQuotation(id, dto.toInput());
    }

    /** Raises a purchase order, so a retry must replay instead of ordering twice. */
    @PostMapping("/{id}/award")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROCUREMENT__RFQ__AWARD)
    public Map<String, Object> award(@PathVariable String id, @Valid @RequestBody AwardRequest dto) {
        return svc.award(id, new AwardInput(dto.quotationId(), dto.warehouseId(), dto.paymentType()));
    }
}
