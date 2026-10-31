package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.PurchaseAgreementService;
import com.inventoryerp.procurement.application.PurchaseAgreementService.CreateInput;
import com.inventoryerp.procurement.application.PurchaseAgreementService.LineInput;
import com.inventoryerp.procurement.application.PurchaseAgreementService.ReleaseInput;
import com.inventoryerp.procurement.application.PurchaseAgreementService.ReleaseLine;
import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * {@code /procurement/agreements} — blanket agreements and rate contracts with releases (ADR-PROCUREMENT-001 wave 4;
 * no Nest counterpart).
 */
@RestController
@RequestMapping("/procurement/agreements")
public class PurchaseAgreementController {

    public record LineRequest(String productId, @NotBlank(message = "description is required") String description,
            String uomId, @NotNull(message = "unitPrice is required") @DecimalMin(value = "0", message = "unitPrice must"
                    + " not be less than 0") BigDecimal unitPrice,
            @DecimalMin(value = "0", message = "taxRate must not be less than 0") BigDecimal taxRate,
            @Positive(message = "committedQuantity must be a positive number") BigDecimal committedQuantity) {}

    public record CreateRequest(@NotBlank(message = "partnerId is required") String partnerId,
            @OneOf({"blanket", "rate"}) String type, String title,
            @NotBlank(message = "validFrom is required") String validFrom,
            @NotBlank(message = "validTo is required") String validTo, String currencyCode,
            @Positive(message = "committedValue must be a positive number") BigDecimal committedValue, String branchId,
            String rfqId, String terms, String notes,
            @NotNull(message = "lines must be an array") @ArrayMinSize(1) List<@Valid LineRequest> lines) {}

    public record ReasonRequest(String reason) {}

    public record ReleaseLineRequest(@NotBlank(message = "agreementLineId is required") String agreementLineId,
            @NotNull(message = "quantity is required") @Positive(message = "quantity must be a positive number")
            BigDecimal quantity) {}

    public record ReleaseRequest(@NotBlank(message = "warehouseId is required") String warehouseId,
            String expectedDeliveryDate, @OneOf({"cash", "credit"}) String paymentType, String notes,
            @NotNull(message = "lines must be an array") @ArrayMinSize(1) List<@Valid ReleaseLineRequest> lines) {}

    private final PurchaseAgreementService agreements;

    public PurchaseAgreementController(PurchaseAgreementService agreements) {
        this.agreements = agreements;
    }

    @GetMapping
    @RequirePermissions(Permissions.PURCHASE_AGREEMENT__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String partnerId) {
        return agreements.list(status, partnerId);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PURCHASE_AGREEMENT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return agreements.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PURCHASE_AGREEMENT__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest dto) {
        return agreements.create(new CreateInput(dto.partnerId(), dto.type(), dto.title(), dto.validFrom(),
                dto.validTo(), dto.currencyCode(), dto.committedValue(), dto.branchId(), dto.rfqId(), dto.terms(),
                dto.notes(), dto.lines().stream().map(l -> new LineInput(l.productId(), l.description(), l.uomId(),
                        l.unitPrice(), l.taxRate(), l.committedQuantity())).toList()));
    }

    @PostMapping("/{id}/activate")
    @RequirePermissions(Permissions.PURCHASE_AGREEMENT__APPROVE)
    public Map<String, Object> activate(@PathVariable String id) {
        return agreements.activate(id);
    }

    @PostMapping("/{id}/hold")
    @RequirePermissions(Permissions.PURCHASE_AGREEMENT__MANAGE)
    public Map<String, Object> hold(@PathVariable String id, @RequestBody(required = false) ReasonRequest dto) {
        return agreements.hold(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/{id}/resume")
    @RequirePermissions(Permissions.PURCHASE_AGREEMENT__MANAGE)
    public Map<String, Object> resume(@PathVariable String id) {
        return agreements.resume(id);
    }

    @PostMapping("/{id}/terminate")
    @RequirePermissions(Permissions.PURCHASE_AGREEMENT__APPROVE)
    public Map<String, Object> terminate(@PathVariable String id, @RequestBody(required = false) ReasonRequest dto) {
        return agreements.terminate(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/{id}/releases")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__CREATE)
    public Map<String, Object> release(@PathVariable String id, @Valid @RequestBody ReleaseRequest dto) {
        return agreements.release(id, new ReleaseInput(dto.warehouseId(), dto.expectedDeliveryDate(),
                dto.paymentType(), dto.notes(), dto.lines().stream().map(l -> new ReleaseLine(l.agreementLineId(),
                        l.quantity())).toList()));
    }
}
