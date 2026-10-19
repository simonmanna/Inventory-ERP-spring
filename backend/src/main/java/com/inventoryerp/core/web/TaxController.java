package com.inventoryerp.core.web;

import com.inventoryerp.core.application.TaxService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
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

/** {@code /taxes} (port of {@code tax.controller.ts}). */
@RestController
@RequestMapping("/taxes")
public class TaxController {

    /** Create body. */
    public record CreateRequest(
            @NotNull(message = "must be a string") String name,
            String code,
            String type,
            BigDecimal rate,
            Boolean isInclusive,
            Boolean isCompound,
            @OneOf({"standard", "zero_rated", "exempt", "out_of_scope"}) String vatCategory,
            String accountId) {}

    /** Update body (all optional). */
    public record UpdateRequest(
            String name,
            String code,
            String type,
            BigDecimal rate,
            Boolean isInclusive,
            Boolean isCompound,
            @OneOf({"standard", "zero_rated", "exempt", "out_of_scope"}) String vatCategory,
            String accountId,
            Boolean isActive) {}

    private final TaxService service;

    public TaxController(TaxService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.TAX__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return service.list(QueryParams.page(query));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.TAX__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return service.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TAX__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest dto) {
        return service.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.TAX__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateRequest dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.TAX__DELETE)
    public void remove(@PathVariable String id) {
        service.remove(id);
    }
}
