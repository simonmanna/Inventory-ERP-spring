package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.FxTreasuryService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /fx-treasury}: foreign-currency cash accounts, legacy designation and the FX summary (R-8, ADR-S076; Nest has
 * no counterpart).
 */
@RestController
@RequestMapping("/fx-treasury")
public class FxTreasuryController {

    /** Body of {@code POST /fx-treasury/designations}. */
    public record DesignationRequest(
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String accountId,
            @NotNull(message = "must be a number") BigDecimal foreignBalance,
            String date,
            String note) {}

    private final FxTreasuryService service;

    public FxTreasuryController(FxTreasuryService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    @RequirePermissions(Permissions.TREASURY__READ)
    public List<Map<String, Object>> accounts() {
        return service.accounts();
    }

    @PostMapping("/designations")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> designate(@Valid @RequestBody DesignationRequest dto) {
        return service.designate(new FxTreasuryService.DesignationInput(dto.accountId(), dto.foreignBalance(),
                dto.date(), dto.note()));
    }

    @GetMapping("/summary")
    @RequirePermissions(Permissions.CURRENCY__READ)
    public Map<String, Object> summary(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return service.summary(from, to);
    }
}
