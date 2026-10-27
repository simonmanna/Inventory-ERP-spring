package com.inventoryerp.orders.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/** Request records of {@code orders/dto/orders.dto.ts}. */
final class OrdersDtos {

    private OrdersDtos() {}

    static final String STR = "must be a string";
    static final String NUM = "must be a number conforming to the specified constraints";
    static final String INT = "must be an integer number";
    static final String ARR = "must be an array";
    static final String MIN0 = "must not be less than 0";
    static final String MIN1 = "must not be less than 1";
    static final String ISO_DATE =
            "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";
    static final String DATE_MSG = "must be a valid ISO 8601 date string";
    static final String[] PAYMENT = {"cash", "card", "mobile_money", "mixed", "credit"};

    /**
     * {@code @IsOrderLineRef()} ({@code customOrderLineRef}): a line must reference a menu item or a product. The
     * leading {@code @} makes the error renderer join the message to the line's path ({@code lines.0.line must ...}),
     * which is how class-validator reports this message.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = OrderLineRef.Validator.class)
    @interface OrderLineRef {
        String message() default "@line must reference a menuItemId or a productId";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        class Validator implements ConstraintValidator<OrderLineRef, OrderLineInput> {
            @Override
            public boolean isValid(OrderLineInput line, ConstraintValidatorContext ctx) {
                return line == null || notEmpty(line.productId()) || notEmpty(line.menuItemId());
            }

            private static boolean notEmpty(String s) {
                return s != null && !s.isEmpty();
            }
        }
    }

    /** {@code OrderLineInputDto}. */
    @OrderLineRef
    public record OrderLineInput(
            String productId,
            String menuItemId,
            String sku,
            String description,
            @NotNull(message = NUM) @DecimalMin(value = "1", message = MIN1) Double quantity,
            @DecimalMin(value = "0", message = MIN0) Double unitPrice,
            String taxId,
            Double discountPercent,
            @OneOf({"percentage", "fixed_amount"}) String discountType,
            @DecimalMin(value = "0", message = MIN0) Double discountAmount,
            String discountReason,
            String note,
            String variantId,
            String variantName,
            Double variantPrice,
            Boolean taxInclusive) {}

    /** {@code CreateOrderDto}. */
    public record CreateOrderRequest(
            @OneOf({"dine_in", "takeaway", "delivery"}) String orderType,
            String partnerId,
            String transactionKind,
            @Min(value = 1, message = MIN1) Integer guestCount,
            String notes,
            String branchId,
            String paymentTermId,
            @OneOf({"cash", "card", "mobile_money", "mixed", "credit"}) String paymentMethod,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String openedAt,
            @Valid List<OrderLineInput> lines) {}

    /** {@code UpdateOrderHeaderDto}. */
    public record UpdateOrderHeaderRequest(
            @OneOf({"dine_in", "takeaway", "delivery"}) String orderType,
            String partnerId,
            @Min(value = 1, message = MIN1) Integer guestCount,
            String notes,
            String paymentTermId,
            @OneOf({"cash", "card", "mobile_money", "mixed", "credit"}) String paymentMethod,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String openedAt) {}

    /** {@code SaveOrderItemsDto}. */
    public record SaveOrderItemsRequest(
            @NotNull(message = ARR) @Valid List<OrderLineInput> lines,
            Double expectedVersion) {}

    /** {@code AddOrderItemsDto}. */
    public record AddOrderItemsRequest(
            @NotNull(message = ARR) @ArrayMinSize(1) @Valid
                    List<OrderLineInput> lines) {}

    /** {@code CancelOrderDto}. */
    public record CancelOrderRequest(String reason) {}

    /** {@code UpdateOrderSettingsDto}. */
    public record UpdateOrderSettingsRequest(@OneOf({"auto", "menu", "products", "both"}) String lineSource) {}

    /** {@code BillOrderDto} — forwarded to {@code PosInvoiceService.generateInvoice}. */
    public record BillOrderRequest(
            @OneOf({"cash", "card", "mobile_money", "mixed", "credit"}) String paymentMode,
            Double transactionDiscountPercent,
            @OneOf({"percentage", "fixed_amount"}) String transactionDiscountType,
            @DecimalMin(value = "0", message = MIN0) Double transactionDiscountAmount,
            String discountReason,
            String overrideById,
            String branchId) {}
}
