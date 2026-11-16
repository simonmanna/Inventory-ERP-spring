package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.BankMatchingService;
import com.inventoryerp.accounting.application.GlAutomationCommands.BankRuleInput;
import com.inventoryerp.accounting.application.GlAutomationCommands.LineAllocationInput;
import com.inventoryerp.accounting.application.GlAutomationCommands.PaymentAllocationInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
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

/**
 * {@code /bank-reconciliation} matching rules and partial / grouped matches (ADR-S073, ACC-6 extension; Nest has no
 * counterpart). {@code bankAccountId} is the GL account id, as on the other bank-reconciliation routes.
 */
@RestController
@RequestMapping("/bank-reconciliation")
public class BankMatchingController {

    private final BankMatchingService service;

    public BankMatchingController(BankMatchingService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    @RequirePermissions(Permissions.BANK_ACCOUNT__READ)
    public List<Map<String, Object>> rules(@RequestParam(required = false) String bankAccountId) {
        return service.rules(bankAccountId);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> createRule(@Valid @RequestBody BankRuleInput dto) {
        return service.createRule(dto);
    }

    @PatchMapping("/rules/{id}")
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> updateRule(@PathVariable String id, @Valid @RequestBody BankRuleInput dto) {
        return service.updateRule(id, dto);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public void deleteRule(@PathVariable String id) {
        service.deleteRule(id);
    }

    /** Run the account's rules over its unmatched lines now (they also run after every import). */
    @PostMapping("/rules/apply")
    @Idempotent
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> apply(@RequestParam(required = false) String bankAccountId) {
        return service.applyRules(bankAccountId);
    }

    @GetMapping("/lines/{lineId}/open-payments")
    @RequirePermissions(Permissions.BANK_ACCOUNT__READ)
    public List<Map<String, Object>> openPayments(@PathVariable String lineId) {
        return service.openPayments(lineId);
    }

    @GetMapping("/lines/{lineId}/allocations")
    @RequirePermissions(Permissions.BANK_ACCOUNT__READ)
    public List<Map<String, Object>> allocations(@PathVariable String lineId) {
        return service.allocations(lineId);
    }

    /** One statement line to one or several payments (partial allowed). */
    @PostMapping("/lines/{lineId}/allocate")
    @Idempotent
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> allocateLine(@PathVariable String lineId,
            @Valid @RequestBody LineAllocationInput dto) {
        return service.allocateLine(lineId, dto.allocations());
    }

    /** Several statement lines to one payment. */
    @PostMapping("/payments/{paymentId}/allocate")
    @Idempotent
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> allocatePayment(@PathVariable String paymentId,
            @Valid @RequestBody PaymentAllocationInput dto) {
        return service.allocatePayment(paymentId, dto.lineIds());
    }
}
