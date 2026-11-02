package com.inventoryerp.expenses.web;

import com.inventoryerp.expenses.application.ExpensePrepaymentService;
import com.inventoryerp.expenses.application.ExpenseScheduleService;
import com.inventoryerp.expenses.application.ExpenseScheduleService.AccrualInput;
import com.inventoryerp.expenses.application.ExpenseScheduleService.RecurrenceInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/**
 * EX-5 (ADR-S052): {@code /expense-recurrences}, {@code /expense-prepayments}, {@code /expense-accruals} and
 * {@code POST /expense-schedules/run}.
 */
@RestController
public class ExpenseSchedulesController {

    private static final String DAY = "^\\d{4}-\\d{2}-\\d{2}$";
    private static final String DAY_MSG = "must be a date (YYYY-MM-DD)";

    /** Recurring template body. */
    public record RecurrenceRequest(String name, String title, String description, @Positive BigDecimal amount,
            String categoryId, String supplierId, String branchId, String departmentId, String costCenterId,
            String projectId, String taxId, @OneOf({"weekly", "monthly", "quarterly", "yearly"}) String frequency,
            @Min(1) @Max(12) Integer intervalCount, @Pattern(regexp = DAY, message = DAY_MSG) String nextRunDate,
            @Pattern(regexp = DAY, message = DAY_MSG) String endDate, @Min(0) @Max(365) Integer dueDays,
            @Min(0) @Max(60) Integer reminderDays) {

        RecurrenceInput input() {
            return new RecurrenceInput(name, title, description, amount, categoryId, supplierId, branchId, departmentId,
                    costCenterId, projectId, taxId, frequency, intervalCount, nextRunDate, endDate, dueDays,
                    reminderDays);
        }
    }

    /** Period-end accrual body. */
    public record AccrualRequest(String title, @Positive BigDecimal amount, String accountId, String categoryId,
            String supplierId, String branchId, String costCenterId,
            @Pattern(regexp = DAY, message = DAY_MSG) String accrualDate,
            @Pattern(regexp = DAY, message = DAY_MSG) String reversalDate, String notes) {}

    /** Manual run body. */
    public record RunRequest(@Pattern(regexp = DAY, message = DAY_MSG) String asOf) {}

    private final ExpenseScheduleService schedules;
    private final ExpensePrepaymentService prepayments;

    public ExpenseSchedulesController(ExpenseScheduleService schedules, ExpensePrepaymentService prepayments) {
        this.schedules = schedules;
        this.prepayments = prepayments;
    }

    @GetMapping("/expense-recurrences")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> recurrences(@RequestParam(required = false) String status) {
        return schedules.recurrences(status);
    }

    @GetMapping("/expense-recurrences/{id}")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> recurrence(@PathVariable String id) {
        return schedules.recurrence(id);
    }

    @PostMapping("/expense-recurrences")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> createRecurrence(@Valid @RequestBody RecurrenceRequest dto) {
        return schedules.createRecurrence(dto.input());
    }

    @PatchMapping("/expense-recurrences/{id}")
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> updateRecurrence(@PathVariable String id, @Valid @RequestBody RecurrenceRequest dto) {
        return schedules.updateRecurrence(id, dto.input());
    }

    @PostMapping("/expense-recurrences/{id}/pause")
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> pause(@PathVariable String id) {
        return schedules.setRecurrenceStatus(id, "paused");
    }

    @PostMapping("/expense-recurrences/{id}/resume")
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> resume(@PathVariable String id) {
        return schedules.setRecurrenceStatus(id, "active");
    }

    @PostMapping("/expense-recurrences/{id}/end")
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> end(@PathVariable String id) {
        return schedules.setRecurrenceStatus(id, "ended");
    }

    @PostMapping("/expense-recurrences/{id}/run")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__CREATE)
    public Map<String, Object> run(@PathVariable String id) {
        return schedules.runRecurrence(id);
    }

    @GetMapping("/expense-prepayments")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> prepayments(@RequestParam(required = false) String status) {
        return prepayments.list(status);
    }

    @GetMapping("/expense-prepayments/{id}")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> prepayment(@PathVariable String id) {
        return prepayments.findOne(id);
    }

    @GetMapping("/expense-accruals")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> accruals(@RequestParam(required = false) String status) {
        return schedules.accruals(status);
    }

    @PostMapping("/expense-accruals")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__POST)
    public Map<String, Object> createAccrual(@Valid @RequestBody AccrualRequest dto) {
        return schedules.createAccrual(new AccrualInput(dto.title(), dto.amount(), dto.accountId(), dto.categoryId(),
                dto.supplierId(), dto.branchId(), dto.costCenterId(), dto.accrualDate(), dto.reversalDate(),
                dto.notes()));
    }

    @PostMapping("/expense-accruals/{id}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.EXPENSE__POST)
    public Map<String, Object> reverseAccrual(@PathVariable String id) {
        return schedules.reverseAccrual(id);
    }

    /** Run everything due (reminders, recurring, accrual reversals, prepaid releases) — the daily job, by hand. */
    @PostMapping("/expense-schedules/run")
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> runDue(@Valid @RequestBody(required = false) RunRequest dto) {
        LocalDate asOf = dto == null || dto.asOf() == null ? LocalDate.now(BusinessZone.get())
                : LocalDate.parse(dto.asOf());
        return schedules.runDue(asOf);
    }
}
