package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.AccountCategoryService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /account-categories}: read-only global catalog (port of {@code account-category.controller.ts}). */
@RestController
@RequestMapping("/account-categories")
public class AccountCategoryController {

    private final AccountCategoryService categories;

    public AccountCategoryController(AccountCategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @RequirePermissions(Permissions.ACCOUNT_CATEGORY__READ)
    public List<Map<String, Object>> list() {
        return categories.list();
    }

    @GetMapping("/usage")
    @RequirePermissions(Permissions.ACCOUNT_CATEGORY__READ)
    public List<Map<String, Object>> usage() {
        return categories.listWithUsage();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT_CATEGORY__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return categories.findOne(id);
    }
}
