package com.inventoryerp.invoicing.web;

import com.inventoryerp.invoicing.application.CreditControlService;
import com.inventoryerp.invoicing.application.CreditControlService.OverrideInput;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /credit-control} (SALES-1): customer exposure, limit and hold, temporary overrides. */
@RestController
@RequestMapping("/credit-control")
public class CreditControlController {

    /** Null leaves a value unchanged. */
    public record CreditPolicyRequest(
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal creditLimit,
            Boolean creditHold,
            String reason) {}

    public record CreditOverrideRequest(
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal amount,
            Boolean coversHold,
            @NotBlank(message = "should not be empty") String reason,
            @NotNull(message = "must be a valid ISO 8601 date string") Instant expiresAt) {}

    private final CreditControlService credit;

    public CreditControlController(CreditControlService credit) {
        this.credit = credit;
    }

    @GetMapping("/customers")
    @RequirePermissions(Permissions.CREDIT_CONTROL__READ)
    public PageResult<Map<String, Object>> customers(@RequestParam(required = false) String search,
            @RequestParam(required = false) String status, @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize) {
        return credit.customers(search, status, QueryParams.parseInt(page, 1), QueryParams.parseInt(pageSize, 25));
    }

    @GetMapping("/customers/{partnerId}")
    @RequirePermissions(Permissions.CREDIT_CONTROL__READ)
    public Map<String, Object> customer(@PathVariable String partnerId) {
        return credit.customer(partnerId);
    }

    @PutMapping("/customers/{partnerId}/policy")
    @RequirePermissions(Permissions.CREDIT_CONTROL__MANAGE)
    public Map<String, Object> savePolicy(@PathVariable String partnerId, @Valid @RequestBody CreditPolicyRequest dto) {
        return credit.savePolicy(partnerId, dto.creditLimit(), dto.creditHold(), dto.reason());
    }

    @PostMapping("/customers/{partnerId}/overrides")
    @RequirePermissions(Permissions.CREDIT_CONTROL__OVERRIDE)
    public Map<String, Object> grantOverride(@PathVariable String partnerId,
            @Valid @RequestBody CreditOverrideRequest dto) {
        return credit.grantOverride(partnerId, new OverrideInput(dto.amount(), dto.coversHold(), dto.reason(),
                dto.expiresAt()));
    }

    @PostMapping("/overrides/{id}/revoke")
    @RequirePermissions(Permissions.CREDIT_CONTROL__OVERRIDE)
    public Map<String, Object> revokeOverride(@PathVariable String id) {
        return credit.revokeOverride(id);
    }
}
