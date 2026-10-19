package com.inventoryerp.core.web;

import com.inventoryerp.core.application.PartnerCategoryService;
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

/** {@code /partner-categories} (port of {@code partner-category.controller.ts}). */
@RestController
@RequestMapping("/partner-categories")
public class PartnerCategoryController {

    /** Create body. */
    public record CreateRequest(
            @NotNull(message = "must be a string") String name,
            String parentId) {}

    /** Update body (all optional). */
    public record UpdateRequest(
            String name,
            String parentId) {}

    private final PartnerCategoryService service;

    public PartnerCategoryController(PartnerCategoryService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.PARTNER_CATEGORY__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return service.list(QueryParams.page(query));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PARTNER_CATEGORY__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return service.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PARTNER_CATEGORY__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest dto) {
        return service.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.PARTNER_CATEGORY__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateRequest dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.PARTNER_CATEGORY__DELETE)
    public void remove(@PathVariable String id) {
        service.remove(id);
    }
}
