package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.AccountMappingService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** {@code /account-mappings} (port of {@code account-mapping.controller.ts}). */
@RestController
@RequestMapping("/account-mappings")
public class AccountMappingController {

    /** {@code SetMappingDto}. */
    public record SetMappingRequest(@NotBlank(message = "should not be empty") String accountId, Boolean force) {}

    private final AccountMappingService mappings;

    public AccountMappingController(AccountMappingService mappings) {
        this.mappings = mappings;
    }

    @GetMapping
    @RequirePermissions(Permissions.ACCOUNT_MAPPING__READ)
    public List<Map<String, Object>> list() {
        return mappings.list();
    }

    @GetMapping("/registry")
    @RequirePermissions(Permissions.ACCOUNT_MAPPING__READ)
    public JsonNode registry() {
        return mappings.registry();
    }

    @GetMapping("/missing")
    @RequirePermissions(Permissions.ACCOUNT_MAPPING__READ)
    public List<String> missing() {
        return mappings.missingRequired();
    }

    @PutMapping("/{key}")
    @RequirePermissions(Permissions.ACCOUNT_MAPPING__UPDATE)
    public Map<String, Object> set(@PathVariable String key, @Valid @RequestBody SetMappingRequest dto) {
        return mappings.set(key, dto.accountId(), Boolean.TRUE.equals(dto.force()));
    }
}
