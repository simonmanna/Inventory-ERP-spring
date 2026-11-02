package com.inventoryerp.expenses.web;

import com.inventoryerp.expenses.application.ExpensesService;
import com.inventoryerp.expenses.application.ExpensesService.Allocation;
import com.inventoryerp.expenses.application.ExpensesService.CreateInput;
import com.inventoryerp.expenses.application.ExpensesService.Details;
import com.inventoryerp.expenses.application.ExpensesService.PayInput;
import com.inventoryerp.expenses.application.ExpensesService.UpdateInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /expenses} standalone expenses (port of {@code expenses.controller.ts}). */
@RestController
@RequestMapping("/expenses")
public class ExpensesController {

    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";
    private static final String DATE_MSG = "must be a valid ISO 8601 date string";
    private static final String STR = "must be a string";
    private static final String POSITIVE = "must be a positive number";

    /** {@code CreateExpenseDto}. */
    public record CreateExpenseRequest(
            @NotNull(message = STR) String title,
            String description,
            @NotNull(message = POSITIVE) @Positive(message = POSITIVE) BigDecimal amount,
            String categoryId,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expenseDate,
            String notes,
            String supplierId,
            @NotNull(message = "must be one of the following values: CASH, CREDIT") @OneOf({"CASH", "CREDIT"})
            String paymentType,
            String createdBy,
            String paymentMethod,
            String accountId,
            String paymentReference,
            // EX-1 (ADR-S052)
            String branchId, String departmentId, String costCenterId, String projectId, String receiptNumber,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueDate,
            @OneOf({"COMPANY", "EMPLOYEE"}) String paidBy, String claimantId, String currencyCode,
            @Positive(message = POSITIVE) BigDecimal exchangeRate, @Positive(message = POSITIVE) BigDecimal foreignAmount,
            String taxId, @PositiveOrZero BigDecimal taxAmount, Boolean capitalize,
            @Valid List<AllocationRequest> allocations,
            @Min(1) @Max(120) Integer prepaidMonths, @Pattern(regexp = ISO_DATE, message = DATE_MSG) String prepaidStart) {

        Details details() {
            return new Details(branchId, departmentId, costCenterId, projectId, receiptNumber, dueDate, paidBy,
                    claimantId, currencyCode, exchangeRate, foreignAmount, taxId, taxAmount, capitalize,
                    allocations == null ? null : allocations.stream().map(AllocationRequest::input).toList(),
                    prepaidMonths, prepaidStart);
        }
    }

    /** One allocation line: {@code amount} or {@code percent} of the net. */
    public record AllocationRequest(@Positive(message = POSITIVE) BigDecimal amount,
            @Positive(message = POSITIVE) @DecimalMax("100") BigDecimal percent, String accountId, String costCenterId,
            String costAllocationRuleId, String branchId, String departmentId, String projectId, String description) {

        Allocation input() {
            return new Allocation(amount, percent, accountId, costCenterId, costAllocationRuleId, branchId, departmentId,
                    projectId, description);
        }
    }

    /** {@code UpdateExpenseDto}. */
    public record UpdateExpenseRequest(
            String title,
            String description,
            @Positive(message = POSITIVE) BigDecimal amount,
            String categoryId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expenseDate,
            String notes,
            String supplierId,
            @OneOf({"CASH", "CREDIT"}) String paymentType,
            // EX-1 (ADR-S052)
            String branchId, String departmentId, String costCenterId, String projectId, String receiptNumber,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueDate,
            @OneOf({"COMPANY", "EMPLOYEE"}) String paidBy, String claimantId, String currencyCode,
            @Positive(message = POSITIVE) BigDecimal exchangeRate, @Positive(message = POSITIVE) BigDecimal foreignAmount,
            String taxId, @PositiveOrZero BigDecimal taxAmount, Boolean capitalize,
            @Valid List<AllocationRequest> allocations,
            @Min(1) @Max(120) Integer prepaidMonths, @Pattern(regexp = ISO_DATE, message = DATE_MSG) String prepaidStart) {

        Details details() {
            return new Details(branchId, departmentId, costCenterId, projectId, receiptNumber, dueDate, paidBy,
                    claimantId, currencyCode, exchangeRate, foreignAmount, taxId, taxAmount, capitalize,
                    allocations == null ? null : allocations.stream().map(AllocationRequest::input).toList(),
                    prepaidMonths, prepaidStart);
        }
    }

    /** Return-for-correction body (EX-2). */
    public record ReturnExpenseRequest(@NotNull(message = STR) String reason) {}

    /** {@code PayExpenseDto}. */
    public record PayExpenseRequest(String paidBy, @NotNull(message = STR) String paymentMethod,
            @NotNull(message = STR) String accountId, String reference, String paymentNotes,
            @Positive(message = POSITIVE) BigDecimal amount) {}

    /** {@code ApproveExpenseDto}. */
    public record ApproveExpenseRequest(String approvedBy, String approvalNotes) {}

    /** {@code RejectExpenseDto}. */
    public record RejectExpenseRequest(String reason) {}

    /** {@code VoidExpenseDto}. */
    public record VoidExpenseRequest(@NotNull(message = STR) String voidReason) {}

    private final ExpensesService expenses;

    public ExpensesController(ExpensesService expenses) {
        this.expenses = expenses;
    }

    @GetMapping("/stats")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> stats(@RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return expenses.stats(dateFrom, dateTo);
    }

    @GetMapping("/meta/accounts")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> accounts() {
        return expenses.paymentAccounts();
    }

    @GetMapping("/meta/suppliers")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> suppliers() {
        return expenses.suppliers();
    }

    @GetMapping
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> list(@RequestParam Map<String, String> query) {
        return expenses.list(query);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return expenses.findOne(id);
    }

    @GetMapping("/{id}/audit")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> audit(@PathVariable String id) {
        return expenses.getAudit(id);
    }

    @PostMapping
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateExpenseRequest dto) {
        return expenses.create(new CreateInput(dto.title(), dto.description(), dto.amount(), dto.categoryId(),
                dto.expenseDate(), dto.notes(), dto.supplierId(), dto.paymentType(), dto.paymentMethod(), dto.accountId(),
                dto.paymentReference()), null, dto.details());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.EXPENSE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateExpenseRequest dto) {
        return expenses.update(id, new UpdateInput(dto.title(), dto.description(), dto.amount(), dto.categoryId(),
                dto.expenseDate(), dto.notes(), dto.supplierId(), dto.paymentType()), dto.details());
    }

    /** EX-2: send a draft back to its raiser for correction. */
    @PostMapping("/{id}/return")
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> returnForCorrection(@PathVariable String id,
            @Valid @RequestBody ReturnExpenseRequest dto) {
        return expenses.returnForCorrection(id, dto.reason());
    }

    /** EX-2: resubmit a returned expense for approval. */
    @PostMapping("/{id}/submit")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> submit(@PathVariable String id) {
        return expenses.submit(id);
    }

    @PostMapping("/{id}/approve")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> approve(@PathVariable String id,
            @Valid @RequestBody(required = false) ApproveExpenseRequest dto) {
        return expenses.approve(id, dto == null ? null : dto.approvalNotes());
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> reject(@PathVariable String id,
            @Valid @RequestBody(required = false) RejectExpenseRequest dto) {
        return expenses.reject(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/{id}/pay")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__POST)
    public Map<String, Object> pay(@PathVariable String id, @Valid @RequestBody PayExpenseRequest dto) {
        return expenses.pay(id, new PayInput(dto.paymentMethod(), dto.accountId(), dto.reference(), dto.paymentNotes(),
                dto.amount()));
    }

    @PostMapping("/{id}/void")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__CANCEL)
    public Map<String, Object> voidExpense(@PathVariable String id, @Valid @RequestBody VoidExpenseRequest dto) {
        return expenses.voidExpense(id, dto.voidReason());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.EXPENSE__CANCEL)
    public void remove(@PathVariable String id) {
        expenses.remove(id);
    }
}
