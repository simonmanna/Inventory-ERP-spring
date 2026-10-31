package com.inventoryerp.procurement.web;

import static com.inventoryerp.procurement.web.ProcurementDtos.DATE_MSG;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.ProcurementBudgetService;
import com.inventoryerp.procurement.application.ProcurementBudgetService.BudgetInput;
import com.inventoryerp.procurement.application.ProcurementBudgetService.Doc;
import com.inventoryerp.procurement.application.ProcurementBudgetService.DocLine;
import com.inventoryerp.procurement.application.ProcurementDelegationService;
import com.inventoryerp.procurement.application.ProcurementDelegationService.DelegationInput;
import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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

/** {@code /procurement/budgets} and {@code /procurement/delegations} (PROC-3). */
@RestController
@RequestMapping("/procurement")
@Validated
public class ProcurementControlController {

    /** Create or update a budget. */
    public record BudgetRequest(
            @NotBlank(message = "name should not be empty") String name,
            @NotNull(message = "startDate must be a valid ISO 8601 date string")
            @Pattern(regexp = DATE, message = DATE_MSG) String startDate,
            @NotNull(message = "endDate must be a valid ISO 8601 date string")
            @Pattern(regexp = DATE, message = DATE_MSG) String endDate,
            String branchId,
            String departmentId,
            String categoryId,
            @NotNull(message = "amount must be a number") @DecimalMin(value = "0", message = "must not be less than 0")
            BigDecimal amount,
            @DecimalMin(value = "1", message = "must not be less than 1")
            @DecimalMax(value = "100", message = "must not be greater than 100") BigDecimal warnPercent,
            @OneOf({"warn", "block"}) String controlMode,
            String notes) {

        BudgetInput input() {
            return new BudgetInput(name, startDate, endDate, branchId, departmentId, categoryId, amount, warnPercent,
                    controlMode, notes);
        }
    }

    /** Change the amount of an active budget. */
    public record ReviseRequest(
            @NotNull(message = "amount must be a number") @DecimalMin(value = "0", message = "must not be less than 0")
            BigDecimal amount,
            @NotBlank(message = "reason should not be empty") String reason) {}

    /** One line of a preview. */
    public record CheckLine(String productId, @NotNull(message = "amount must be a number") BigDecimal amount) {}

    /** What a form asks before saving. */
    public record CheckRequest(
            @NotNull(message = "kind must be a string") @OneOf({"purchase_request", "purchase_order"}) String kind,
            String branchId,
            String departmentId,
            String fromRequestId,
            @NotNull(message = "lines must be an array") @ArrayMinSize(1) List<@Valid CheckLine> lines) {}

    /** Create or update a delegation rule. */
    public record DelegationRequest(
            @NotBlank(message = "branchId should not be empty") String branchId,
            @NotNull(message = "mode must be one of the following values: central, delegated")
            @OneOf({"central", "delegated"}) String mode,
            String delegateUserId,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal maxOrderAmount,
            @Pattern(regexp = DATE, message = DATE_MSG) String validFrom,
            @Pattern(regexp = DATE, message = DATE_MSG) String validTo,
            Boolean isActive,
            String notes) {

        DelegationInput input() {
            return new DelegationInput(branchId, mode, delegateUserId, maxOrderAmount, validFrom, validTo, isActive,
                    notes);
        }
    }

    private static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    private final ProcurementBudgetService budgets;
    private final ProcurementDelegationService delegations;

    public ProcurementControlController(ProcurementBudgetService budgets, ProcurementDelegationService delegations) {
        this.budgets = budgets;
        this.delegations = delegations;
    }

    @GetMapping("/budgets")
    @RequirePermissions(Permissions.PROCUREMENT__BUDGET__READ)
    public PageResult<Map<String, Object>> listBudgets(@RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) @OneOf({"draft", "active", "closed"}) String status,
            @RequestParam(required = false) String branchId) {
        int p = Math.max(1, ProcurementDtos.parseInt(page, 1));
        int ps = Math.min(200, Math.max(1, ProcurementDtos.parseInt(pageSize, 25)));
        return budgets.list(p, ps, status, branchId);
    }

    @GetMapping("/budgets/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__BUDGET__READ)
    public Map<String, Object> findBudget(@PathVariable String id) {
        return budgets.findOne(id);
    }

    @PostMapping("/budgets")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT__BUDGET__MANAGE)
    public Map<String, Object> createBudget(@Valid @RequestBody BudgetRequest dto) {
        return budgets.create(dto.input());
    }

    @PatchMapping("/budgets/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__BUDGET__MANAGE)
    public Map<String, Object> updateBudget(@PathVariable String id, @Valid @RequestBody BudgetRequest dto) {
        return budgets.update(id, dto.input());
    }

    @PostMapping("/budgets/{id}/activate")
    @RequirePermissions(Permissions.PROCUREMENT__BUDGET__MANAGE)
    public Map<String, Object> activateBudget(@PathVariable String id) {
        return budgets.activate(id);
    }

    @PostMapping("/budgets/{id}/close")
    @RequirePermissions(Permissions.PROCUREMENT__BUDGET__MANAGE)
    public Map<String, Object> closeBudget(@PathVariable String id) {
        return budgets.close(id);
    }

    @PostMapping("/budgets/{id}/revise")
    @RequirePermissions(Permissions.PROCUREMENT__BUDGET__MANAGE)
    public Map<String, Object> reviseBudget(@PathVariable String id, @Valid @RequestBody ReviseRequest dto) {
        return budgets.revise(id, dto.amount(), dto.reason());
    }

    @DeleteMapping("/budgets/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__BUDGET__MANAGE)
    public Map<String, Object> removeBudget(@PathVariable String id) {
        budgets.remove(id);
        return Map.of("ok", true);
    }

    /** Which budgets a document would touch, for the request and order forms. */
    @PostMapping("/budget-check")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_REQUEST__READ)
    public List<Map<String, Object>> checkBudget(@Valid @RequestBody CheckRequest dto) {
        return budgets.preview(new Doc(dto.kind(), null, dto.branchId(), dto.departmentId(), dto.lines().stream()
                .map(l -> new DocLine(l.productId(), l.amount())).toList(), dto.fromRequestId()));
    }

    @GetMapping("/delegations")
    @RequirePermissions(Permissions.PROCUREMENT__DELEGATION__READ)
    public List<Map<String, Object>> listDelegations(@RequestParam(required = false) String branchId) {
        return delegations.list(branchId);
    }

    @PostMapping("/delegations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT__DELEGATION__MANAGE)
    public Map<String, Object> createDelegation(@Valid @RequestBody DelegationRequest dto) {
        return delegations.create(dto.input());
    }

    @PatchMapping("/delegations/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__DELEGATION__MANAGE)
    public Map<String, Object> updateDelegation(@PathVariable String id, @Valid @RequestBody DelegationRequest dto) {
        return delegations.update(id, dto.input());
    }

    @DeleteMapping("/delegations/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__DELEGATION__MANAGE)
    public Map<String, Object> removeDelegation(@PathVariable String id) {
        delegations.remove(id);
        return Map.of("ok", true);
    }
}
