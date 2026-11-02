package com.inventoryerp.expenses.web;

import com.inventoryerp.expenses.application.ExpenseControlService;
import com.inventoryerp.expenses.application.ExpenseControlService.LimitInput;
import com.inventoryerp.expenses.application.ExpenseControlService.SettingsInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** EX-2 (ADR-S052): {@code /expense-limits} spending limits and {@code /expense-settings}. */
@RestController
public class ExpenseControlsController {

    /** Create / update body. */
    public record LimitRequest(String name, String userId, String categoryId, String branchId,
            @OneOf({"per_expense", "monthly"}) String period, @Positive BigDecimal amount,
            @OneOf({"block", "warn"}) String action, Boolean isActive) {

        LimitInput input() {
            return new LimitInput(name, userId, categoryId, branchId, period, amount, action, isActive);
        }
    }

    /** Settings body. */
    public record SettingsRequest(Boolean notifyOnDecision, Boolean notifyOnPayment, Boolean notifyApproversOnSubmit,
            @Min(0) @Max(60) Integer recurringReminderDays, Boolean claimReceiptRequired) {}

    private final ExpenseControlService controls;

    public ExpenseControlsController(ExpenseControlService controls) {
        this.controls = controls;
    }

    @GetMapping("/expense-limits")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> limits() {
        return controls.listLimits();
    }

    @PostMapping("/expense-limits")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> createLimit(@Valid @RequestBody LimitRequest dto) {
        return controls.createLimit(dto.input());
    }

    @PatchMapping("/expense-limits/{id}")
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> updateLimit(@PathVariable String id, @Valid @RequestBody LimitRequest dto) {
        return controls.updateLimit(id, dto.input());
    }

    @DeleteMapping("/expense-limits/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public void deleteLimit(@PathVariable String id) {
        controls.deleteLimit(id);
    }

    @GetMapping("/expense-settings")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> settings() {
        return controls.settings();
    }

    @PutMapping("/expense-settings")
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> updateSettings(@Valid @RequestBody SettingsRequest dto) {
        return controls.updateSettings(new SettingsInput(dto.notifyOnDecision(), dto.notifyOnPayment(),
                dto.notifyApproversOnSubmit(), dto.recurringReminderDays(), dto.claimReceiptRequired()));
    }
}
