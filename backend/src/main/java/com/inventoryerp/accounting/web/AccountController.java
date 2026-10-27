package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.AccountService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
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

/** {@code /accounts} (port of {@code account.controller.ts}). */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    /** {@code CreateAccountDto}. */
    public record CreateAccountRequest(
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String code,
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String name,
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String categoryId,
            String parentAccountId,
            Integer sortOrder,
            String currencyId,
            Boolean isPostable,
            Boolean isActive,
            String description,
            @OneOf({"operating", "investing", "financing", "none"}) String cashFlowCategory,
            Boolean allowReconciliation,
            Boolean allowManualPosting,
            Boolean allowBudgeting,
            @OneOf({"ar", "ap", "inventory", "fixed_assets", "payroll", "tax"}) String controlAccountType,
            Boolean isDefault,
            String bankName,
            String accountNumber) {}

    /** {@code UpdateAccountDto}. */
    public record UpdateAccountRequest(
            String code,
            String name,
            String categoryId,
            String parentAccountId,
            Integer sortOrder,
            String currencyId,
            Boolean isPostable,
            Boolean isActive,
            String description,
            @OneOf({"operating", "investing", "financing", "none"}) String cashFlowCategory,
            Boolean allowReconciliation,
            Boolean allowManualPosting,
            Boolean allowBudgeting,
            @OneOf({"ar", "ap", "inventory", "fixed_assets", "payroll", "tax"}) String controlAccountType,
            Boolean isDefault,
            String bankName,
            String accountNumber) {}

    /** {@code ReorderDto} (items keep explicit {@code parentAccountId: null}). */
    public record ReorderRequest(@NotNull(message = "must be an array") List<Map<String, Object>> items) {}

    /** {@code DeprecateDto}. */
    public record DeprecateRequest(Boolean deprecated) {}

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return accounts.list(QueryParams.page(query));
    }

    @GetMapping("/tree")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public Map<String, Object> tree(@RequestParam Map<String, String> query) {
        QueryParams.only(query, "includeInactive", "includeBalances", "asOf", "categoryKey", "classification");
        return accounts.tree("true".equals(query.get("includeInactive")), "true".equals(query.get("includeBalances")),
                query.get("asOf"), query.get("categoryKey"), query.get("classification"));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return accounts.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ACCOUNT__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateAccountRequest dto) {
        return accounts.create(dto);
    }

    @PatchMapping("/reorder")
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> reorder(@Valid @RequestBody ReorderRequest dto) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < dto.items().size(); i++) {
            Map<String, Object> item = dto.items().get(i);
            if (!(item.get("id") instanceof String)) {
                errors.add("items." + i + ".id must be a string");
            }
            if (item.get("sortOrder") != null && !(item.get("sortOrder") instanceof Integer)) {
                errors.add("items." + i + ".sortOrder must be an integer number");
            }
            if (item.get("parentAccountId") != null && !(item.get("parentAccountId") instanceof String)) {
                errors.add("items." + i + ".parentAccountId must be a string");
            }
            for (String k : item.keySet()) {
                if (!List.of("id", "sortOrder", "parentAccountId").contains(k)) {
                    errors.add("property items[" + i + "]." + k + " should not exist");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw QueryParams.validation(errors);
        }
        return accounts.reorder(dto.items());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateAccountRequest dto) {
        return accounts.update(id, dto);
    }

    @PatchMapping("/{id}/deprecate")
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> deprecate(@PathVariable String id,
            @Valid @RequestBody(required = false) DeprecateRequest dto) {
        return accounts.deprecate(id, dto == null || dto.deprecated() == null || dto.deprecated());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.ACCOUNT__DELETE)
    public void remove(@PathVariable String id) {
        accounts.remove(id);
    }
}
