package com.inventoryerp.expenses.web;

import com.inventoryerp.expenses.application.ExpenseAdvanceService;
import com.inventoryerp.expenses.application.ExpenseAdvanceService.AdvanceInput;
import com.inventoryerp.expenses.application.ExpenseAdvanceService.MoneyInput;
import com.inventoryerp.expenses.application.ExpenseClaimService;
import com.inventoryerp.expenses.application.ExpenseClaimService.ClaimInput;
import com.inventoryerp.expenses.application.ExpenseClaimService.LineInput;
import com.inventoryerp.expenses.application.ExpenseClaimService.PayInput;
import com.inventoryerp.expenses.application.ExpensesService.Details;
import com.inventoryerp.expenses.web.ExpensesController.AllocationRequest;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** EX-3 (ADR-S052): {@code /expense-claims} employee claims and {@code /expense-advances} cash advances. */
@RestController
public class ExpenseClaimsController {

    private static final String DATE_MSG = "must be a valid ISO 8601 date string";

    /** One claim line. */
    public record LineRequest(@NotNull(message = "must be a string") String title,
            @NotNull @Positive BigDecimal amount,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ExpensesController.ISO_DATE, message = DATE_MSG)
            String expenseDate,
            String categoryId, String description, String supplierId, String receiptNumber, String branchId,
            String departmentId, String costCenterId, String projectId, String currencyCode,
            @Positive BigDecimal exchangeRate, @Positive BigDecimal foreignAmount, String taxId,
            @PositiveOrZero BigDecimal taxAmount, @Valid List<AllocationRequest> allocations) {

        LineInput input() {
            return new LineInput(title, amount, expenseDate, categoryId, description, supplierId, new Details(branchId,
                    departmentId, costCenterId, projectId, receiptNumber, null, "EMPLOYEE", null, currencyCode,
                    exchangeRate, foreignAmount, taxId, taxAmount, null,
                    allocations == null ? null : allocations.stream().map(AllocationRequest::input).toList(), null,
                    null));
        }
    }

    /** Create / update body. */
    public record ClaimRequest(String title, String claimantId, String advanceId, String notes,
            @Valid List<LineRequest> lines) {

        ClaimInput input() {
            return new ClaimInput(title, claimantId, advanceId, notes,
                    lines == null ? null : lines.stream().map(LineRequest::input).toList());
        }
    }

    /** Approve / reject / return body. */
    public record DecisionRequest(String notes, String reason) {}

    /** Reimbursement / disbursement / refund body. */
    public record MoneyRequest(@NotNull(message = "must be a string") String paymentMethod,
            @NotNull(message = "must be a string") String accountId, String reference, @Positive BigDecimal amount) {}

    /** Advance request body. */
    public record AdvanceRequest(String employeeId, @NotNull(message = "must be a string") String purpose,
            @NotNull @Positive BigDecimal amount,
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = DATE_MSG) String neededBy, String branchId,
            String notes) {}

    private final ExpenseClaimService claims;
    private final ExpenseAdvanceService advances;

    public ExpenseClaimsController(ExpenseClaimService claims, ExpenseAdvanceService advances) {
        this.claims = claims;
        this.advances = advances;
    }

    // ------------------------------------------------------------------ claims

    @GetMapping("/expense-claims")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> listClaims(@RequestParam(required = false) String status,
            @RequestParam(required = false) String claimantId, @RequestParam(required = false) Boolean mine) {
        return claims.list(status, claimantId, Boolean.TRUE.equals(mine));
    }

    @GetMapping("/expense-claims/{id}")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> claim(@PathVariable String id) {
        return claims.findOne(id);
    }

    @PostMapping("/expense-claims")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> createClaim(@Valid @RequestBody ClaimRequest dto) {
        return claims.create(dto.input());
    }

    @PatchMapping("/expense-claims/{id}")
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> updateClaim(@PathVariable String id, @Valid @RequestBody ClaimRequest dto) {
        return claims.update(id, dto.input());
    }

    @PostMapping("/expense-claims/{id}/submit")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> submitClaim(@PathVariable String id) {
        return claims.submit(id);
    }

    @PostMapping("/expense-claims/{id}/approve")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> approveClaim(@PathVariable String id,
            @RequestBody(required = false) DecisionRequest dto) {
        return claims.approve(id, dto == null ? null : dto.notes());
    }

    @PostMapping("/expense-claims/{id}/reject")
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> rejectClaim(@PathVariable String id, @RequestBody(required = false) DecisionRequest dto) {
        return claims.reject(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/expense-claims/{id}/return")
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> returnClaim(@PathVariable String id, @RequestBody(required = false) DecisionRequest dto) {
        return claims.returnForCorrection(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/expense-claims/{id}/cancel")
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> cancelClaim(@PathVariable String id) {
        return claims.cancel(id);
    }

    @PostMapping("/expense-claims/{id}/pay")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__POST)
    public Map<String, Object> payClaim(@PathVariable String id, @Valid @RequestBody MoneyRequest dto) {
        return claims.pay(id, new PayInput(dto.paymentMethod(), dto.accountId(), dto.reference(), dto.amount()));
    }

    // ------------------------------------------------------------------ advances

    @GetMapping("/expense-advances")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> listAdvances(@RequestParam(required = false) String status,
            @RequestParam(required = false) String employeeId) {
        return advances.list(status, employeeId);
    }

    @GetMapping("/expense-advances/{id}")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> advance(@PathVariable String id) {
        return advances.findOne(id);
    }

    @PostMapping("/expense-advances")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> createAdvance(@Valid @RequestBody AdvanceRequest dto) {
        return advances.create(new AdvanceInput(dto.employeeId(), dto.purpose(), dto.amount(), dto.neededBy(),
                dto.branchId(), dto.notes()));
    }

    @PostMapping("/expense-advances/{id}/approve")
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> approveAdvance(@PathVariable String id,
            @RequestBody(required = false) DecisionRequest dto) {
        return advances.approve(id, dto == null ? null : dto.notes());
    }

    @PostMapping("/expense-advances/{id}/reject")
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> rejectAdvance(@PathVariable String id,
            @RequestBody(required = false) DecisionRequest dto) {
        return advances.reject(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/expense-advances/{id}/cancel")
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> cancelAdvance(@PathVariable String id) {
        return advances.cancel(id);
    }

    @PostMapping("/expense-advances/{id}/disburse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__POST)
    public Map<String, Object> disburse(@PathVariable String id, @Valid @RequestBody MoneyRequest dto) {
        return advances.disburse(id, new MoneyInput(dto.paymentMethod(), dto.accountId(), dto.reference(), null));
    }

    @PostMapping("/expense-advances/{id}/refund")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__POST)
    public Map<String, Object> refund(@PathVariable String id, @Valid @RequestBody MoneyRequest dto) {
        return advances.refund(id, new MoneyInput(dto.paymentMethod(), dto.accountId(), dto.reference(), dto.amount()));
    }
}
