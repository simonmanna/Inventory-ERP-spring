package com.inventoryerp.core.web;

import com.inventoryerp.core.application.FiscalPeriodService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/** {@code /fiscal-periods} (port of {@code fiscal-period.controller.ts}). */
@RestController
@RequestMapping("/fiscal-periods")
public class FiscalPeriodController {

    static final String ISO_DATE = CoreDtos.ISO_DATE;

    /** Create body. */
    public record CreateRequest(
            @NotNull(message = "must be a string") String name,
            @NotNull(message = "must be a valid ISO 8601 date string") @Pattern(regexp = ISO_DATE, message = "must be a valid ISO 8601 date string") String startDate,
            @NotNull(message = "must be a valid ISO 8601 date string") @Pattern(regexp = ISO_DATE, message = "must be a valid ISO 8601 date string") String endDate) {}

    /** Update body (all optional). */
    public record UpdateRequest(
            String name,
            @Pattern(regexp = ISO_DATE, message = "must be a valid ISO 8601 date string") String startDate,
            @Pattern(regexp = ISO_DATE, message = "must be a valid ISO 8601 date string") String endDate,
            String status) {}

    private final FiscalPeriodService service;

    public FiscalPeriodController(FiscalPeriodService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.FISCAL_PERIOD__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return service.list(QueryParams.page(query));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.FISCAL_PERIOD__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return service.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FISCAL_PERIOD__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest dto) {
        return service.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.FISCAL_PERIOD__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateRequest dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.FISCAL_PERIOD__DELETE)
    public void remove(@PathVariable String id) {
        service.remove(id);
    }
}
