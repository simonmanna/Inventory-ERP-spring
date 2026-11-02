package com.inventoryerp.expenses.web;

import com.inventoryerp.expenses.application.PettyCashService;
import com.inventoryerp.expenses.application.PettyCashService.CountInput;
import com.inventoryerp.expenses.application.PettyCashService.FundInput;
import com.inventoryerp.expenses.application.PettyCashService.ReplenishInput;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** EX-4 (ADR-S052): {@code /petty-cash-funds}. */
@RestController
@RequestMapping("/petty-cash-funds")
public class PettyCashController {

    private static final String DAY = "^\\d{4}-\\d{2}-\\d{2}$";

    /** Create / update body. */
    public record FundRequest(String name, String accountId, String custodianId, String branchId,
            @Positive BigDecimal floatAmount, @Positive BigDecimal maxPayment, Boolean isActive, String notes) {

        FundInput input() {
            return new FundInput(name, accountId, custodianId, branchId, floatAmount, maxPayment, isActive, notes);
        }
    }

    /** Replenishment body. */
    public record ReplenishRequest(@NotNull(message = "must be a string") String sourceAccountId,
            @Positive BigDecimal amount, String paymentMethod, String reference) {}

    /** Cash count body. */
    public record CountRequest(@NotNull @PositiveOrZero BigDecimal countedAmount, Map<String, Object> denominations,
            String notes) {}

    private final PettyCashService funds;

    public PettyCashController(PettyCashService funds) {
        this.funds = funds;
    }

    @GetMapping
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> list() {
        return funds.list();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return funds.findOne(id);
    }

    @GetMapping("/{id}/statement")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> statement(@PathVariable String id,
            @RequestParam(required = false) @Pattern(regexp = DAY) String from,
            @RequestParam(required = false) @Pattern(regexp = DAY) String to) {
        return funds.statement(id, from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> create(@Valid @RequestBody FundRequest dto) {
        return funds.create(dto.input());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.EXPENSE__APPROVE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody FundRequest dto) {
        return funds.update(id, dto.input());
    }

    @PostMapping("/{id}/replenish")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__POST)
    public Map<String, Object> replenish(@PathVariable String id, @Valid @RequestBody ReplenishRequest dto) {
        return funds.replenish(id, new ReplenishInput(dto.sourceAccountId(), dto.amount(), dto.paymentMethod(),
                dto.reference()));
    }

    @PostMapping("/{id}/counts")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.EXPENSE__POST)
    public Map<String, Object> count(@PathVariable String id, @Valid @RequestBody CountRequest dto) {
        return funds.count(id, new CountInput(dto.countedAmount(), dto.denominations(), dto.notes()));
    }
}
