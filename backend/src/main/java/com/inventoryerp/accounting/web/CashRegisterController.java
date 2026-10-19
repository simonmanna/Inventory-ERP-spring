package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.CashRegisterService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

/** {@code /cash-registers} (port of {@code cash-register.controller.ts}). */
@RestController
@RequestMapping("/cash-registers")
public class CashRegisterController {

    /** {@code CreateCashRegisterDto}. */
    public record CreateCashRegisterRequest(
            @NotNull(message = "must be a string") String code,
            @NotNull(message = "must be a string") String name,
            String defaultAccountId,
            String locationId,
            String branchId) {}

    /** {@code UpdateCashRegisterDto}. */
    public record UpdateCashRegisterRequest(String name, String defaultAccountId, String locationId, String branchId,
            Boolean isActive) {}

    private final CashRegisterService registers;

    public CashRegisterController(CashRegisterService registers) {
        this.registers = registers;
    }

    @GetMapping
    @RequirePermissions(Permissions.CASH_REGISTER__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return registers.list(QueryParams.page(query));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.CASH_REGISTER__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return registers.findOne(id);
    }

    @GetMapping("/{id}/drawer-balance")
    @RequirePermissions(Permissions.CASH_SESSION__OPEN)
    public Map<String, Object> drawerBalance(@PathVariable String id) {
        return registers.drawerBalance(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CASH_REGISTER__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateCashRegisterRequest dto) {
        return registers.create(EntityPatch.data(dto));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.CASH_REGISTER__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateCashRegisterRequest dto) {
        return registers.update(id, EntityPatch.data(dto));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.CASH_REGISTER__DELETE)
    public void remove(@PathVariable String id) {
        registers.remove(id);
    }
}
