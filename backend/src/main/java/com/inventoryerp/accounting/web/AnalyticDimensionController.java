package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.AnalyticDimensionService;
import com.inventoryerp.accounting.application.AnalyticReportService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /analytic-dimensions}: generic analytic dimensions, their values, account rules and dimension-filtered
 * reports (roadmap 2.4, ADR-S025, extension). Literal segments ({@code /account-rules}, {@code /reports/...}) are
 * declared before {@code /{id}} and never collide with a UUID.
 */
@RestController
@RequestMapping("/analytic-dimensions")
public class AnalyticDimensionController {

    private static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";
    private static final String DATE_MSG = "must be a valid ISO 8601 date string";

    public record CreateDimensionRequest(
            @NotBlank(message = "code should not be empty") @Size(max = 40, message = "code must be at most 40 characters")
            String code,
            @NotBlank(message = "name should not be empty") String name,
            String description,
            Integer sortOrder) {}

    public record UpdateDimensionRequest(String name, String description, Boolean isActive, Integer sortOrder) {}

    public record CreateValueRequest(
            @NotBlank(message = "code should not be empty") String code,
            @NotBlank(message = "name should not be empty") String name,
            String parentId,
            @Pattern(regexp = DATE, message = DATE_MSG) String validFrom,
            @Pattern(regexp = DATE, message = DATE_MSG) String validTo) {}

    public record UpdateValueRequest(String code, String name, String parentId, Boolean clearParent, Boolean isActive,
            @Pattern(regexp = DATE, message = DATE_MSG) String validFrom,
            @Pattern(regexp = DATE, message = DATE_MSG) String validTo,
            Boolean clearValidity) {}

    public record AccountRuleRequest(
            @NotBlank(message = "dimension should not be empty") String dimension,
            @NotNull(message = "rule must be one of the following values: required, optional, forbidden")
            @OneOf({"required", "optional", "forbidden"}) String rule) {}

    private final AnalyticDimensionService dimensions;
    private final AnalyticReportService reports;

    public AnalyticDimensionController(AnalyticDimensionService dimensions, AnalyticReportService reports) {
        this.dimensions = dimensions;
        this.reports = reports;
    }

    private static LocalDate date(String v, String name) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(name + " " + DATE_MSG);
        }
    }

    // ------------------------------------------------------------------ account rules

    @GetMapping("/account-rules")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__READ)
    public List<Map<String, Object>> accountRules(@RequestParam(required = false) String dimension) {
        return dimensions.listAccountRules(dimension);
    }

    @PutMapping("/account-rules/{accountId}")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__MANAGE)
    public Map<String, Object> setAccountRule(@PathVariable String accountId, @Valid @RequestBody AccountRuleRequest dto) {
        return dimensions.setAccountRule(accountId, dto.dimension(), dto.rule());
    }

    // ------------------------------------------------------------------ reports

    @GetMapping("/reports/summary")
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public Map<String, Object> summary(@RequestParam(required = false) String dimension,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String filter) {
        return reports.summary(dimension, date(from, "from"), date(to, "to"), filter);
    }

    @GetMapping("/reports/trial-balance")
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public Map<String, Object> trialBalance(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String filter) {
        return reports.trialBalance(date(from, "from"), date(to, "to"), filter);
    }

    @GetMapping("/reports/lines")
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public Map<String, Object> lines(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String filter,
            @RequestParam(required = false) String accountId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return reports.lines(date(from, "from"), date(to, "to"), filter, accountId, limit, offset);
    }

    // ------------------------------------------------------------------ dimensions

    @GetMapping
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String includeInactive) {
        return dimensions.list("true".equals(includeInactive));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody CreateDimensionRequest dto) {
        return dimensions.create(dto.code(), dto.name(), dto.description(), dto.sortOrder());
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return dimensions.get(id);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateDimensionRequest dto) {
        return dimensions.update(id, dto.name(), dto.description(), dto.isActive(), dto.sortOrder());
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__MANAGE)
    public Map<String, Object> delete(@PathVariable String id) {
        return dimensions.delete(id);
    }

    // ------------------------------------------------------------------ values

    @GetMapping("/{id}/values")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__READ)
    public List<Map<String, Object>> values(@PathVariable String id) {
        return dimensions.listValues(id);
    }

    @PostMapping("/{id}/values")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__MANAGE)
    public Map<String, Object> createValue(@PathVariable String id, @Valid @RequestBody CreateValueRequest dto) {
        return dimensions.createValue(id, dto.code(), dto.name(), dto.parentId(), date(dto.validFrom(), "validFrom"),
                date(dto.validTo(), "validTo"));
    }

    @PatchMapping("/{id}/values/{valueId}")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__MANAGE)
    public Map<String, Object> updateValue(@PathVariable String id, @PathVariable String valueId,
            @Valid @RequestBody UpdateValueRequest dto) {
        return dimensions.updateValue(id, valueId, dto.code(), dto.name(), dto.parentId(), dto.clearParent(),
                dto.isActive(), date(dto.validFrom(), "validFrom"), date(dto.validTo(), "validTo"), dto.clearValidity());
    }

    @DeleteMapping("/{id}/values/{valueId}")
    @RequirePermissions(Permissions.ANALYTIC_DIMENSION__MANAGE)
    public Map<String, Object> deleteValue(@PathVariable String id, @PathVariable String valueId) {
        return dimensions.deleteValue(id, valueId);
    }
}
