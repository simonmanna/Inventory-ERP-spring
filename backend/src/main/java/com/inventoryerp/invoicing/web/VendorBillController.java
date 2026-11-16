package com.inventoryerp.invoicing.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import static com.inventoryerp.invoicing.web.InvoicingDtos.DATE_MSG;
import static com.inventoryerp.invoicing.web.InvoicingDtos.ISO_DATE;

import com.inventoryerp.invoicing.application.SupplierPaymentScheduleService;
import com.inventoryerp.invoicing.application.SupplierPaymentScheduleService.PlanInput;
import com.inventoryerp.invoicing.application.VendorBillService;
import com.inventoryerp.invoicing.application.VendorBillService.BillInput;
import com.inventoryerp.invoicing.web.InvoicingDtos.InvoiceLine;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /vendor-bills} Accounts Payable (port of {@code vendor-bill.controller.ts}). */
@RestController
@RequestMapping("/vendor-bills")
public class VendorBillController {

    /** {@code CreateVendorBillDto}. */
    public record CreateVendorBillRequest(
            @NotNull(message = "must be a string") String partnerId,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String issueDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueDate,
            String currencyId,
            String reference,
            String notes,
            String branchId,
            List<String> purchaseOrderIds,
            @NotNull(message = "must be an array") @ArrayMinSize(1)
            List<@Valid InvoiceLine> lines,
            /* ADR-S071: a withholding tax applied to every product line of the bill. */
            String withholdingTaxId) {

        BillInput toInput() {
            return new BillInput(partnerId, issueDate, dueDate, currencyId, reference, notes, branchId, purchaseOrderIds,
                    InvoicingDtos.lines(lines), withholdingTaxId);
        }
    }

    /** {@code PostOptionsDto}. */
    public record PostOptionsRequest(Boolean override3WM, Boolean overrideApproval) {}

    /** A payment plan: all fields optional, a null date or amount clears that part of the plan. */
    public record PaymentPlanRequest(
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String plannedDate,
            @DecimalMin(value = "0.01", message = "must not be less than 0.01") BigDecimal plannedAmount,
            Boolean onHold,
            String notes) {}

    private final VendorBillService bills;
    private final SupplierPaymentScheduleService schedule;

    public VendorBillController(VendorBillService bills, SupplierPaymentScheduleService schedule) {
        this.bills = bills;
        this.schedule = schedule;
    }

    @GetMapping
    @RequirePermissions(Permissions.EXPENSE__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return bills.list(QueryParams.page(query, "status", "partnerId"), null, null, query.get("status"),
                query.get("partnerId"));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return bills.findOne(id);
    }

    @PostMapping
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateVendorBillRequest dto) {
        return bills.create(dto.toInput());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.EXPENSE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody CreateVendorBillRequest dto) {
        return bills.update(id, dto.toInput());
    }

    @PostMapping("/{id}/post")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__POST)
    public Map<String, Object> post(@PathVariable String id,
            @Valid @RequestBody(required = false) PostOptionsRequest body) {
        return bills.post(id, body == null ? null : body.override3WM(), body == null ? null : body.overrideApproval());
    }

    @PostMapping("/{id}/cancel")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id) {
        return bills.cancel(id);
    }

    /** Open bills by planned or due date (PROC-4). */
    @GetMapping("/payment-schedule")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> paymentSchedule(@RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return schedule.schedule(partnerId, from, to);
    }

    /** Plan when an open bill is paid, or hold it; no money moves. */
    @PutMapping("/{id}/payment-schedule")
    @RequirePermissions(Permissions.EXPENSE__UPDATE)
    public Map<String, Object> planPayment(@PathVariable String id, @Valid @RequestBody PaymentPlanRequest dto) {
        return schedule.plan(id, new PlanInput(dto.plannedDate(), dto.plannedAmount(), dto.onHold(), dto.notes()));
    }

    @GetMapping("/suppliers/{partnerId}/ledger")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> ledger(@PathVariable String partnerId) {
        return bills.ledger(partnerId);
    }
}
