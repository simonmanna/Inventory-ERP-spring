package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.ARR;
import static com.inventoryerp.pos.web.PosDtos.DATE_MSG;
import static com.inventoryerp.pos.web.PosDtos.ISO_DATE;
import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.POSITIVE;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** Request records of {@code order/dto/order.dto.ts} (shared by the orders, billing, tab and split controllers). */
final class PosOrderDtos {

    private PosOrderDtos() {}

    static final String MIN0 = "must not be less than 0";
    static final String MIN1 = "must not be less than 1";

    /** {@code OrderLineModifierDto}. */
    public record OrderLineModifierRequest(
            @NotNull(message = STR) String modifierId,
            @NotNull(message = STR) String name,
            @NotNull(message = NUM) Double priceDelta,
            String kitchenPrintName) {}

    /** {@code OrderLineDto}. */
    public record OrderLineRequest(
            String productId,
            String menuItemId,
            String sku,
            @NotNull(message = STR) String description,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) Double quantity,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) Double unitPrice,
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

    /** {@code CreateOrderDto}. */
    public record CreateOrderRequest(
            @OneOf({"percentage", "fixed_amount"}) String transactionDiscountType,
            @DecimalMin(value = "0", message = MIN0) Double transactionDiscountAmount,
            String discountReason,
            Double transactionDiscountPercent,
            @OneOf({"dine_in", "takeaway", "delivery"}) String orderType,
            String tableId,
            String partnerId,
            String waiterId,
            String branchId,
            String cashSessionId,
            @NotNull(message = NUM) @DecimalMin(value = "1", message = MIN1) Double guestCount,
            String notes,
            String overrideById,
            @Valid List<OrderLineRequest> lines) {}

    /** {@code SaveOrderItemsDto}. */
    public record SaveOrderItemsRequest(
            @OneOf({"percentage", "fixed_amount"}) String transactionDiscountType,
            @DecimalMin(value = "0", message = MIN0) Double transactionDiscountAmount,
            String discountReason,
            @NotNull(message = ARR) @Valid List<OrderLineRequest> lines,
            Double expectedVersion,
            Double guestCount,
            String partnerId,
            String overrideById,
            Double transactionDiscountPercent) {}

    /** {@code AddOrderItemsDto}. */
    public record AddOrderItemsRequest(
            @NotNull(message = ARR) @Valid List<OrderLineRequest> lines,
            Boolean sendToKitchen,
            Double guestCount,
            String overrideById,
            Double transactionDiscountPercent) {}

    /** {@code VoidOrderItemDto}. */
    public record VoidOrderItemRequest(
            @NotNull(message = STR) String reason,
            @DecimalMin(value = "0", message = MIN0) Double quantity,
            String overrideById,
            String overridePin) {}

    /** {@code CancelOrderDto}. */
    public record CancelOrderRequest(String reason, String overrideById, String overridePin) {}

    /** {@code MoveTableDto}. */
    public record MoveTableRequest(@NotNull(message = STR) String targetTableId) {}

    /** {@code MergeOrderDto}. */
    public record MergeOrderRequest(@NotNull(message = STR) String sourceOrderId) {}

    /** {@code FireKitchenDto}. */
    public record FireKitchenRequest(Double course) {}

    /** {@code GenerateInvoiceDto}. */
    public record GenerateInvoiceRequest(
            String cashSessionId,
            Double expectedVersion,
            String approvalToken,
            @DecimalMin(value = "0", message = MIN0) Double expectedTotal,
            String overridePin,
            @OneOf({"cash", "card", "mobile_money", "mixed", "credit"}) String paymentMode,
            String paymentTermId,
            Double transactionDiscountPercent,
            @OneOf({"percentage", "fixed_amount"}) String transactionDiscountType,
            @DecimalMin(value = "0", message = MIN0) Double transactionDiscountAmount,
            String discountReason,
            String overrideById,
            String branchId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String occurredAt) {}

    /** {@code QuoteOrderDto extends GenerateInvoiceDto}. */
    public record QuoteOrderRequest(
            String cashSessionId,
            Double expectedVersion,
            String approvalToken,
            @DecimalMin(value = "0", message = MIN0) Double expectedTotal,
            String overridePin,
            @OneOf({"cash", "card", "mobile_money", "mixed", "credit"}) String paymentMode,
            String paymentTermId,
            Double transactionDiscountPercent,
            @OneOf({"percentage", "fixed_amount"}) String transactionDiscountType,
            @DecimalMin(value = "0", message = MIN0) Double transactionDiscountAmount,
            String discountReason,
            String overrideById,
            String branchId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String occurredAt,
            @NotNull(message = ARR) @Valid List<OrderLineRequest> lines) {}

    /** {@code TenderDto}. */
    public record TenderRequest(
            String accountId,
            @NotNull(message = "method must be one of the following values: cash, bank, card, mobile_money, store_credit")
            @OneOf({"cash", "bank", "card", "mobile_money", "store_credit"}) String method,
            @NotNull(message = NUM) @Positive(message = POSITIVE) Double amount,
            String reference) {}

    /** {@code ReceivePaymentDto}. */
    public record ReceivePaymentRequest(
            @DecimalMin(value = "0", message = MIN0) Double expectedTotal,
            String approvalToken,
            @Valid List<TenderRequest> tenders,
            @OneOf({"cash", "bank", "card", "mobile_money"}) String paymentMethod,
            Double amountTendered,
            String cashSessionId,
            Boolean allowPartial,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String occurredAt) {}

    /** {@code SettleCreditDto}. */
    public record SettleCreditRequest(String partnerId, String notes) {}

    /** {@code WriteOffDto}. */
    public record WriteOffRequest(@NotNull(message = STR) String reason) {}

    /** {@code RefundLineDto}. */
    public record RefundLineRequest(
            @NotNull(message = STR) String lineId,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) Double quantity) {}

    /** {@code RefundInvoiceDto}. */
    public record RefundInvoiceRequest(
            String approvalToken,
            String overridePin,
            @NotNull(message = "stockDisposition must be one of the following values: restock, waste, no_return")
            @OneOf({"restock", "waste", "no_return"}) String stockDisposition,
            String reason,
            @NotNull(message = STR) String overrideById,
            String cashSessionId,
            @Valid List<RefundLineRequest> lines) {}
}
