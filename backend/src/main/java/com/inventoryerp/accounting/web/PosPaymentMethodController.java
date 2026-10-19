package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.PosPaymentMethodService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /accounts/pos-payment-methods} (port of {@code pos-payment-method.controller.ts}). */
@RestController
@RequestMapping("/accounts/pos-payment-methods")
public class PosPaymentMethodController {

    /** {@code CreatePosPaymentMethodDto}. */
    public record CreateRequest(
            @NotBlank(message = "should not be empty") String code,
            @NotBlank(message = "should not be empty") String label,
            @NotNull(message = "kind must be one of the following values: cash, bank, card, mobile_money, store_credit")
            @OneOf({"cash", "bank", "card", "mobile_money", "store_credit"}) String kind,
            String provider,
            String accountId,
            String icon,
            Integer sortOrder,
            Boolean isActive,
            Boolean requiresReference,
            Boolean trackInShift) {}

    /** {@code UpdatePosPaymentMethodDto}. */
    public record UpdateRequest(
            String code,
            String label,
            @OneOf({"cash", "bank", "card", "mobile_money", "store_credit"}) String kind,
            String provider,
            String accountId,
            String icon,
            Integer sortOrder,
            Boolean isActive,
            Boolean requiresReference,
            Boolean trackInShift) {}

    private final PosPaymentMethodService methods;

    public PosPaymentMethodController(PosPaymentMethodService methods) {
        this.methods = methods;
    }

    @GetMapping
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public List<Map<String, Object>> list() {
        return methods.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ACCOUNT__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest dto) {
        return methods.create(new PosPaymentMethodService.Upsert(dto.code(), dto.label(), dto.kind(), dto.provider(),
                dto.accountId(), dto.icon(), dto.sortOrder(), dto.isActive(), dto.requiresReference(), dto.trackInShift(),
                EntityPatch.presentKeys()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateRequest dto) {
        if (dto.code() != null && dto.code().isEmpty() || dto.label() != null && dto.label().isEmpty()) {
            throw com.inventoryerp.kernel.crud.QueryParams.validation(List.of(
                    dto.code() != null && dto.code().isEmpty() ? "code should not be empty" : "label should not be empty"));
        }
        return methods.update(id, new PosPaymentMethodService.Upsert(dto.code(), dto.label(), dto.kind(), dto.provider(),
                dto.accountId(), dto.icon(), dto.sortOrder(), dto.isActive(), dto.requiresReference(), dto.trackInShift(),
                EntityPatch.presentKeys()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.ACCOUNT__DELETE)
    public void remove(@PathVariable String id) {
        methods.remove(id);
    }
}
