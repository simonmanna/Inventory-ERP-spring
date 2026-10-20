package com.inventoryerp.expenses.web;

import com.inventoryerp.expenses.application.ExpenseCategoriesService;
import com.inventoryerp.expenses.application.ExpenseCategoriesService.CategoryInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
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

/** {@code /expense-categories} (port of {@code expense-categories.controller.ts}). */
@RestController
@RequestMapping("/expense-categories")
public class ExpenseCategoriesController {

    /** {@code CreateExpenseCategoryDto}. */
    public record CreateCategoryRequest(@NotNull(message = "must be a string") String name, String icon,
            String description, String ledgerAccountId) {}

    /** {@code UpdateExpenseCategoryDto}. */
    public record UpdateCategoryRequest(String name, String icon, String description, String ledgerAccountId,
            Boolean isActive) {}

    private final ExpenseCategoriesService categories;

    public ExpenseCategoriesController(ExpenseCategoriesService categories) {
        this.categories = categories;
    }

    @GetMapping
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> list() {
        return categories.list();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return categories.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.EXPENSE__UPDATE)
    public Map<String, Object> create(@Valid @RequestBody CreateCategoryRequest dto) {
        return categories.create(new CategoryInput(dto.name(), dto.icon(), dto.description(), dto.ledgerAccountId(), null));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.EXPENSE__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateCategoryRequest dto) {
        return categories.update(id, new CategoryInput(dto.name(), dto.icon(), dto.description(), dto.ledgerAccountId(),
                dto.isActive()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.EXPENSE__UPDATE)
    public void remove(@PathVariable String id) {
        categories.remove(id);
    }
}
