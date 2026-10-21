package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.ARR;
import static com.inventoryerp.pos.web.PosDtos.DATE_MSG;
import static com.inventoryerp.pos.web.PosDtos.ISO_DATE;
import static com.inventoryerp.pos.web.PosDtos.MIN1;
import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.STR;
import static com.inventoryerp.pos.web.PosOrderDtos.MIN0;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.pos.application.PosService;
import com.inventoryerp.pos.web.PosOrderDtos.OrderLineModifierRequest;
import com.inventoryerp.pos.web.PosOrderDtos.TenderRequest;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos} checkout, void, open tabs and order resume/settle (rest of {@code pos.controller.ts}). */
@RestController
@RequestMapping("/pos")
public class PosSalesController {

    /** {@code CheckoutLineDto}. */
    public record CheckoutLineRequest(
            String productId,
            String menuItemId,
            String sku,
            @NotNull(message = STR) String description,
            @NotNull(message = NUM) Double quantity,
            @NotNull(message = NUM) Double unitPrice,
            String taxId,
            Double discountPercent,
            @OneOf({"percentage", "fixed_amount"}) String discountType,
            @DecimalMin(value = "0", message = MIN0) Double discountAmount,
            String discountReason,
            String note,
            @Valid List<OrderLineModifierRequest> modifiers,
            String variantId,
            String variantName,
            Double variantPrice,
            List<String> accompanimentOptionIds,
            List<String> accompanimentNames,
            Double accompanimentPriceImpact,
            String comboId,
            Boolean taxInclusive,
            Double course) {}

    /** {@code CheckoutDto}. */
    public record CheckoutRequest(
            String approvalToken,
            @DecimalMin(value = "0", message = MIN0) Double expectedTotal,
            String partnerId,
            @NotNull(message = ARR) @Size(min = 1, message = MIN1) @Valid List<CheckoutLineRequest> lines,
            Double transactionDiscountPercent,
            @OneOf({"percentage", "fixed_amount"}) String transactionDiscountType,
            @DecimalMin(value = "0", message = MIN0) Double transactionDiscountAmount,
            String discountReason,
            String overrideById,
            String overridePin,
            @Valid List<TenderRequest> tenders,
            String paymentMethod,
            Double amountTendered,
            String cashSessionId,
            String branchId,
            String reference,
            String notes,
            String tableId,
            Double guestCount,
            @OneOf({"dine_in", "takeaway", "delivery"}) String orderType,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String occurredAt,
            @OneOf({"tender", "credit"}) String settleMode) {}

    /** {@code VoidDto}. */
    public record VoidRequest(
            String approvalToken,
            String overridePin,
            String cashSessionId,
            @NotNull(message = "stockDisposition must be one of the following values: restock, waste, no_return")
            @OneOf({"restock", "waste", "no_return"}) String stockDisposition,
            @NotNull(message = STR) String reason,
            @NotNull(message = STR) String overrideById) {}

    /** {@code AddToTabDto}. */
    public record AddToTabRequest(
            String cashSessionId,
            @NotNull(message = ARR) @Size(min = 1, message = MIN1) @Valid List<CheckoutLineRequest> lines,
            String partnerId,
            Double guestCount,
            Boolean sendToKitchen,
            String overrideById,
            Double transactionDiscountPercent) {}

    /** {@code SaveTabDto}. */
    public record SaveTabRequest(
            String cashSessionId,
            @OneOf({"percentage", "fixed_amount"}) String transactionDiscountType,
            @DecimalMin(value = "0", message = MIN0) Double transactionDiscountAmount,
            String discountReason,
            Double transactionDiscountPercent,
            @NotNull(message = ARR) @Valid List<CheckoutLineRequest> lines,
            String partnerId,
            Boolean newRound,
            Double guestCount,
            Double expectedVersion,
            String cancelReason,
            String overrideById,
            String overridePin) {}

    /** {@code SettleTabDto}. */
    public record SettleTabRequest(
            Double expectedVersion,
            String approvalToken,
            @DecimalMin(value = "0", message = MIN0) Double expectedTotal,
            @Valid List<TenderRequest> tenders,
            String paymentMethod,
            Double amountTendered,
            Double transactionDiscountPercent,
            @OneOf({"percentage", "fixed_amount"}) String transactionDiscountType,
            @DecimalMin(value = "0", message = MIN0) Double transactionDiscountAmount,
            String discountReason,
            String overrideById,
            String overridePin,
            String cashSessionId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String occurredAt,
            @OneOf({"tender", "credit"}) String settleMode,
            String partnerId) {}

    private final PosService svc;
    private final PosInvoiceService billing;

    public PosSalesController(PosService svc, PosInvoiceService billing) {
        this.svc = svc;
        this.billing = billing;
    }

    @PostMapping("/checkout")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> checkout(@Valid @RequestBody CheckoutRequest dto) {
        return svc.checkout(PosDtos.data(dto));
    }

    @PostMapping("/sales/{id}/void")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__VOID)
    public Map<String, Object> voidSale(@PathVariable String id, @Valid @RequestBody VoidRequest dto) {
        return billing.refund(id, "VOID: " + dto.reason(), dto.overrideById(), dto.overridePin(), dto.stockDisposition(),
                dto.cashSessionId(), null);
    }

    @GetMapping("/tabs/{tableId}")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> getTab(@PathVariable String tableId) {
        return svc.getTab(tableId);
    }

    @PostMapping("/tabs/{tableId}/items")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> addToTab(@PathVariable String tableId, @Valid @RequestBody AddToTabRequest dto) {
        return svc.addToTab(tableId, PosDtos.data(dto));
    }

    @PostMapping("/tabs/{tableId}/save")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> saveTab(@PathVariable String tableId, @Valid @RequestBody SaveTabRequest dto) {
        return svc.saveTabItems(tableId, PosDtos.data(dto));
    }

    @PostMapping("/tabs/{tableId}/fire-kitchen")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> fireKitchen(@PathVariable String tableId) {
        return svc.fireTabToKitchen(tableId);
    }

    @PostMapping("/tabs/{tableId}/settle")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> settleTab(@PathVariable String tableId, @Valid @RequestBody SettleTabRequest dto) {
        return svc.settleTab(tableId, PosDtos.data(dto));
    }

    @GetMapping("/orders/{id}/resume")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> resumeOrder(@PathVariable String id) {
        return svc.resumeOrder(id);
    }

    @PostMapping("/orders/{id}/settle")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> settleOrder(@PathVariable String id, @Valid @RequestBody SettleTabRequest dto) {
        return svc.settleOrder(id, PosDtos.data(dto));
    }
}
