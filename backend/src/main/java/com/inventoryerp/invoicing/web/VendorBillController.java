package com.inventoryerp.invoicing.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import static com.inventoryerp.invoicing.web.InvoicingDtos.DATE_MSG;
import static com.inventoryerp.invoicing.web.InvoicingDtos.ISO_DATE;

import com.inventoryerp.invoicing.application.SupplierPaymentScheduleService;
import com.inventoryerp.invoicing.application.SupplierPaymentScheduleService.PlanInput;
import com.inventoryerp.invoicing.application.VendorBillService;
import com.inventoryerp.invoicing.application.VendorBillService.BillInput;
import com.inventoryerp.invoicing.application.VendorBillWriteOffService;
import com.inventoryerp.invoicing.application.VendorBillWriteOffService.WriteOffInput;
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

    /**
     * Supplier write-off of a posted bill's open balance (control-account policy, owner decision 2026-11-17):
     * {@code amount} defaults to the whole open balance, {@code accountId} to the {@code purchase_discount} mapping;
     * a reason is required.
     */
    public record WriteOffRequest(
            @DecimalMin(value = "0.01", message = "must not be less than 0.01") BigDecimal amount,
            String accountId,
            String reason) {}

    private final VendorBillService bills;
    private final SupplierPaymentScheduleService schedule;
    private final VendorBillWriteOffService writeOffs;

    public VendorBillController(VendorBillService bills, SupplierPaymentScheduleService schedule,
            VendorBillWriteOffService writeOffs) {
        this.bills = bills;
        this.schedule = schedule;
        this.writeOffs = writeOffs;
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
        Map<String, Object> bill = bills.findOne(id);
        if (bill != null) {
            // Only on a bill that was written off, so every other bill reads exactly as before.
            BigDecimal writtenOff = writeOffs.writtenOff(id);
            if (writtenOff.signum() > 0) {
                bill.put("writtenOff", com.inventoryerp.kernel.common.JsNumbers.dec(writtenOff));
            }
        }
        return bill;
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

    /** Document reversal request (owner decision 2026-11-17): the reason is mandatory. */
    public record ReversalRequest(
            @jakarta.validation.constraints.Size(max = 2000, message = "must be shorter than or equal to 2000 characters")
            String reason) {}

    /** Supplier-bill document reversal (void); see {@link VendorBillService#cancel(String, String)}. */
    @PostMapping("/{id}/cancel")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id,
            @Valid @RequestBody(required = false) ReversalRequest dto) {
        return bills.cancel(id, dto == null ? null : dto.reason());
    }

    /**
     * Write off (part of) a posted bill's open balance through the supplier sub-ledger: Dr the supplier's payable
     * (partner-tagged) / Cr a non-control income account, lowering the bill's residual in the same transaction. A
     * settlement of what is owed, under its own permission {@code expense:write_off} (bills sit in the expense group).
     */
    @PostMapping("/{id}/write-off")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__WRITE_OFF)
    public Map<String, Object> writeOff(@PathVariable String id, @Valid @RequestBody WriteOffRequest dto) {
        return writeOffs.writeOff(id, new WriteOffInput(dto.amount(), dto.accountId(), dto.reason()));
    }

    /**
     * Document-level reversal of one supplier write-off (its journal entry id): the GL entry is mirrored and the
     * amount goes back on the bill's open balance; see {@link VendorBillWriteOffService#reverseWriteOff}.
     */
    @PostMapping("/{id}/write-offs/{entryId}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__WRITE_OFF)
    public Map<String, Object> reverseWriteOff(@PathVariable String id, @PathVariable String entryId,
            @Valid @RequestBody(required = false) ReversalRequest dto) {
        return writeOffs.reverseWriteOff(id, entryId, dto == null ? null : dto.reason());
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
