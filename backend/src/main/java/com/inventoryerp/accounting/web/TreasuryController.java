package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.BankAccountService;
import com.inventoryerp.accounting.application.TreasuryService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /bank-accounts} + {@code /treasury/transfer} (port of {@code treasury.controller.ts}). */
@RestController
public class TreasuryController {

    /** {@code CreateBankAccountDto}. */
    public record CreateBankAccountRequest(
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String accountId,
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String name,
            String bankName,
            String accountNumber,
            String currencyId,
            Boolean isActive) {}

    /** {@code UpdateBankAccountDto}. */
    public record UpdateBankAccountRequest(String name, String bankName, String accountNumber, String currencyId,
            Boolean isActive) {}

    /** {@code TransferDto}. */
    public record TransferRequest(
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String fromAccountId,
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String toAccountId,
            @NotNull(message = "must be a positive number") @Positive(message = "must be a positive number") BigDecimal amount,
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String date,
            String reference) {}

    private final BankAccountService bankAccounts;
    private final TreasuryService treasury;

    public TreasuryController(BankAccountService bankAccounts, TreasuryService treasury) {
        this.bankAccounts = bankAccounts;
        this.treasury = treasury;
    }

    @GetMapping("/bank-accounts")
    @RequirePermissions(Permissions.BANK_ACCOUNT__READ)
    public PageResult<Map<String, Object>> listBankAccounts(@RequestParam Map<String, String> query) {
        return bankAccounts.list(QueryParams.page(query));
    }

    @PostMapping("/bank-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BANK_ACCOUNT__CREATE)
    public Map<String, Object> createBankAccount(@Valid @RequestBody CreateBankAccountRequest dto) {
        return bankAccounts.create(dto);
    }

    @PatchMapping("/bank-accounts/{id}")
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> updateBankAccount(@PathVariable String id, @Valid @RequestBody UpdateBankAccountRequest dto) {
        return bankAccounts.update(id, dto);
    }

    @DeleteMapping("/bank-accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.BANK_ACCOUNT__DELETE)
    public void removeBankAccount(@PathVariable String id) {
        bankAccounts.remove(id);
    }

    @PostMapping("/treasury/transfer")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.TREASURY__TRANSFER)
    public Map<String, Object> transfer(@Valid @RequestBody TransferRequest dto) {
        return treasury.transfer(dto.fromAccountId(), dto.toAccountId(), dto.amount(), dto.date(), dto.reference());
    }
}
