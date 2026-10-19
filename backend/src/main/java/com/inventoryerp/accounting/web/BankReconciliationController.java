package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.BankReconciliationService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /bank-reconciliation} (port of {@code bank-reconciliation.controller.ts}). */
@RestController
@RequestMapping("/bank-reconciliation")
public class BankReconciliationController {

    /** {@code StatementLineDto}. */
    public record StatementLineRequest(
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String postedAt,
            String externalRef,
            @NotNull(message = "must be a string") String description,
            @NotNull(message = "must be a number conforming to the specified constraints") BigDecimal amount,
            String currencyCode) {}

    /** {@code ImportStatementDto}. */
    public record ImportStatementRequest(
            @NotNull(message = "must be a string") String bankAccountId,
            @NotNull(message = "must be a string") String currencyCode,
            @NotNull(message = "must be an array") List<@Valid StatementLineRequest> lines) {}

    /** {@code MatchDto}. */
    public record MatchRequest(Double dateToleranceDays, String notes) {}

    private final BankReconciliationService svc;

    public BankReconciliationController(BankReconciliationService svc) {
        this.svc = svc;
    }

    @PostMapping("/import")
    @Idempotent
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> importStatement(@Valid @RequestBody ImportStatementRequest dto) {
        return svc.importStatement(dto.bankAccountId(), dto.lines().stream()
                .map(l -> new BankReconciliationService.StatementLine(l.postedAt(), l.externalRef(), l.description(), l.amount(),
                        l.currencyCode() != null ? l.currencyCode() : dto.currencyCode()))
                .toList(), dto.currencyCode());
    }

    @PostMapping("/match")
    @Idempotent
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> match(@RequestParam(required = false) String bankAccountId,
            @Valid @RequestBody(required = false) MatchRequest dto) {
        return svc.match(bankAccountId, dto == null ? null : dto.dateToleranceDays(), dto == null ? null : dto.notes());
    }

    @PostMapping("/unmatch/{lineId}")
    @Idempotent
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> unmatch(@PathVariable String lineId) {
        return svc.unmatch(lineId);
    }

    @GetMapping("/status")
    @RequirePermissions(Permissions.BANK_ACCOUNT__READ)
    public Map<String, Object> status(@RequestParam(required = false) String bankAccountId) {
        return svc.status(bankAccountId);
    }
}
