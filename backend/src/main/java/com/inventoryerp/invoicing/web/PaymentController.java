package com.inventoryerp.invoicing.web;

import static com.inventoryerp.invoicing.web.InvoicingDtos.DATE_MSG;
import static com.inventoryerp.invoicing.web.InvoicingDtos.ISO_DATE;

import com.inventoryerp.invoicing.application.PaymentService;
import com.inventoryerp.invoicing.application.PaymentService.AllocationInput;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /payments} customer receipts and {@code /supplier-payments} (port of {@code payment.controller.ts}). */
@RestController
public class PaymentController {

    private static final String POSITIVE = "must be a positive number";

    /** {@code PaymentAllocationDto}. */
    public record AllocationRequest(String documentId, String invoiceId,
            @NotNull(message = POSITIVE) @Positive(message = POSITIVE) BigDecimal amount) {}

    /** {@code CreatePaymentDto}. */
    public record CreatePaymentRequest(
            @NotNull(message = "must be a string") String partnerId,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String paymentDate,
            @NotNull(message = POSITIVE) @Positive(message = POSITIVE) BigDecimal amount,
            @OneOf({"cash", "bank", "mobile_money", "card", "cheque"}) String paymentMethod,
            String accountId,
            String reference,
            String branchId,
            List<@Valid AllocationRequest> allocations,
            String cashSessionId,
            Boolean allowOverpayment,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal withholdingAmount) {

        PaymentInput toInput() {
            return new PaymentInput(partnerId, paymentDate, amount, paymentMethod, accountId, reference, branchId,
                    allocations == null ? null : allocations.stream()
                            .map(a -> new AllocationInput(a.documentId(), a.invoiceId(), a.amount())).toList(),
                    cashSessionId, allowOverpayment, withholdingAmount, null);
        }
    }

    /** {@code VoidPaymentDto}. */
    public record VoidPaymentRequest(@NotEmpty(message = "should not be empty") String reason,
            String correctionSessionId) {}

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/payments")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public PageResult<Map<String, Object>> listReceipts(@RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize, @RequestParam(required = false) String search,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return payments.list(InvoicingDtos.parseInt(page, 1), InvoicingDtos.parseInt(pageSize, 25), search, "inbound",
                partnerId, dateFrom, dateTo);
    }

    @GetMapping("/payments/{id}")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return payments.findOne(id);
    }

    @PostMapping("/payments")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PAYMENT__CREATE)
    public Map<String, Object> createReceipt(@Valid @RequestBody CreatePaymentRequest dto) {
        return payments.createReceipt(dto.toInput(), false, false);
    }

    @PostMapping("/payments/{id}/void")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PAYMENT__VOID)
    public Map<String, Object> voidPayment(@PathVariable String id, @Valid @RequestBody VoidPaymentRequest dto) {
        return payments.voidPayment(id, dto.reason(), dto.correctionSessionId());
    }

    @GetMapping("/supplier-payments")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public PageResult<Map<String, Object>> listSupplierPayments(@RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize, @RequestParam(required = false) String search,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return payments.list(InvoicingDtos.parseInt(page, 1), InvoicingDtos.parseInt(pageSize, 25), search, "outbound",
                partnerId, dateFrom, dateTo);
    }

    @PostMapping("/supplier-payments")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PAYMENT__CREATE)
    public Map<String, Object> createSupplierPayment(@Valid @RequestBody CreatePaymentRequest dto) {
        return payments.createSupplierPayment(dto.toInput(), false);
    }
}
