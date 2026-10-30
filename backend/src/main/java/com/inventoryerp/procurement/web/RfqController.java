package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.RfqService;
import com.inventoryerp.procurement.application.RfqService.AwardLine;
import com.inventoryerp.procurement.application.RfqService.CreateInput;
import com.inventoryerp.procurement.application.RfqService.LineInput;
import com.inventoryerp.procurement.application.RfqService.QuoteInput;
import com.inventoryerp.procurement.application.RfqService.QuoteLine;
import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /procurement/rfqs} — requests for quotation, supplier quotations, comparison, awards and order creation
 * (ADR-PROCUREMENT-001 wave 4; no Nest counterpart).
 */
@RestController
@RequestMapping("/procurement/rfqs")
public class RfqController {

    public record LineRequest(String requestLineId, String productId, String description,
            @Positive(message = "quantity must be a positive number") BigDecimal quantity, String uomId,
            String requiredDate, String notes) {}

    public record CreateRequest(@NotBlank(message = "title is required") String title, String deadline,
            String branchId, String deliveryLocationId, String deliveryTerms, String notes, Map<String, Integer> weights,
            List<String> requestLineIds, List<@Valid LineRequest> lines, List<String> supplierIds) {}

    public record SupplierRequest(@NotBlank(message = "partnerId is required") String partnerId) {}

    public record QuoteLineRequest(@NotBlank(message = "rfqLineId is required") String rfqLineId,
            @NotNull(message = "unitPrice is required") @DecimalMin(value = "0", message = "unitPrice must not be less"
                    + " than 0") BigDecimal unitPrice,
            @Positive(message = "quantity must be a positive number") BigDecimal quantity,
            @Min(value = 0, message = "leadTimeDays must not be less than 0") Integer leadTimeDays,
            @DecimalMin(value = "0", message = "taxRate must not be less than 0") BigDecimal taxRate, String alternative,
            String notes) {}

    public record QuoteRequest(@NotBlank(message = "partnerId is required") String partnerId, String supplierReference,
            String validUntil, String currencyCode,
            @Positive(message = "exchangeRate must be a positive number") BigDecimal exchangeRate,
            @Min(value = 0, message = "leadTimeDays must not be less than 0") Integer leadTimeDays,
            @Min(value = 0, message = "paymentTermsDays must not be less than 0") Integer paymentTermsDays,
            @DecimalMin(value = "0", message = "freightAmount must not be less than 0") BigDecimal freightAmount,
            @DecimalMin(value = "0", message = "qualityScore must be between 0 and 100")
            @DecimalMax(value = "100", message = "qualityScore must be between 0 and 100") BigDecimal qualityScore,
            String notes, @NotNull(message = "lines must be an array") @ArrayMinSize(1) List<@Valid QuoteLineRequest> lines) {}

    public record DeclineRequest(@NotBlank(message = "partnerId is required") String partnerId, String reason) {}

    public record AwardLineRequest(@NotBlank(message = "rfqLineId is required") String rfqLineId,
            @NotBlank(message = "partnerId is required") String partnerId,
            @Positive(message = "quantity must be a positive number") BigDecimal quantity, String justification) {}

    public record AwardRequest(@NotNull(message = "lines must be an array") @ArrayMinSize(1)
            List<@Valid AwardLineRequest> lines) {}

    public record OrdersRequest(@NotBlank(message = "warehouseId is required") String warehouseId,
            @OneOf({"cash", "credit"}) String paymentType, String expectedDeliveryDate) {}

    private final RfqService rfqs;

    public RfqController(RfqService rfqs) {
        this.rfqs = rfqs;
    }

    @GetMapping
    @RequirePermissions(Permissions.RFQ__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return rfqs.list(status);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.RFQ__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return rfqs.get(id);
    }

    @GetMapping("/{id}/comparison")
    @RequirePermissions(Permissions.RFQ__READ)
    public Map<String, Object> comparison(@PathVariable String id) {
        return rfqs.comparison(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RFQ__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest dto) {
        return rfqs.create(new CreateInput(dto.title(), dto.deadline(), dto.branchId(), dto.deliveryLocationId(),
                dto.deliveryTerms(), dto.notes(), dto.weights(), dto.requestLineIds(), dto.lines() == null ? null
                        : dto.lines().stream().map(l -> new LineInput(l.requestLineId(), l.productId(), l.description(),
                                l.quantity(), l.uomId(), l.requiredDate(), l.notes())).toList(), dto.supplierIds()));
    }

    @PostMapping("/{id}/suppliers")
    @RequirePermissions(Permissions.RFQ__MANAGE)
    public Map<String, Object> addSupplier(@PathVariable String id, @Valid @RequestBody SupplierRequest dto) {
        return rfqs.addSupplier(id, dto.partnerId());
    }

    @DeleteMapping("/{id}/suppliers/{partnerId}")
    @RequirePermissions(Permissions.RFQ__MANAGE)
    public Map<String, Object> removeSupplier(@PathVariable String id, @PathVariable String partnerId) {
        return rfqs.removeSupplier(id, partnerId);
    }

    @PostMapping("/{id}/send")
    @RequirePermissions(Permissions.RFQ__MANAGE)
    public Map<String, Object> send(@PathVariable String id) {
        return rfqs.send(id);
    }

    @PostMapping("/{id}/close")
    @RequirePermissions(Permissions.RFQ__MANAGE)
    public Map<String, Object> close(@PathVariable String id) {
        return rfqs.close(id);
    }

    @PostMapping("/{id}/cancel")
    @RequirePermissions(Permissions.RFQ__MANAGE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return rfqs.cancel(id);
    }

    @PostMapping("/{id}/quotations")
    @Idempotent
    @RequirePermissions(Permissions.RFQ__MANAGE)
    public Map<String, Object> recordQuotation(@PathVariable String id, @Valid @RequestBody QuoteRequest dto) {
        return rfqs.recordQuotation(id, new QuoteInput(dto.partnerId(), dto.supplierReference(), dto.validUntil(),
                dto.currencyCode(), dto.exchangeRate(), dto.leadTimeDays(), dto.paymentTermsDays(), dto.freightAmount(),
                dto.qualityScore(), dto.notes(), dto.lines().stream().map(l -> new QuoteLine(l.rfqLineId(), l.unitPrice(),
                        l.quantity(), l.leadTimeDays(), l.taxRate(), l.alternative(), l.notes())).toList()), "manual");
    }

    @PostMapping("/{id}/decline")
    @RequirePermissions(Permissions.RFQ__MANAGE)
    public Map<String, Object> decline(@PathVariable String id, @Valid @RequestBody DeclineRequest dto) {
        return rfqs.decline(id, dto.partnerId(), dto.reason());
    }

    @PostMapping("/{id}/award")
    @Idempotent
    @RequirePermissions(Permissions.RFQ__AWARD)
    public Map<String, Object> award(@PathVariable String id, @Valid @RequestBody AwardRequest dto) {
        return rfqs.award(id, dto.lines().stream().map(l -> new AwardLine(l.rfqLineId(), l.partnerId(), l.quantity(),
                l.justification())).toList());
    }

    @PostMapping("/{id}/orders")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__CREATE)
    public Map<String, Object> createOrders(@PathVariable String id, @Valid @RequestBody OrdersRequest dto) {
        return rfqs.createOrders(id, dto.warehouseId(), dto.paymentType(), dto.expectedDeliveryDate());
    }
}
