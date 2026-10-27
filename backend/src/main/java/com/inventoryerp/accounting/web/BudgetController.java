package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.BudgetService;
import com.inventoryerp.accounting.application.BudgetService.BudgetInput;
import com.inventoryerp.accounting.application.BudgetService.LineInput;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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

/** {@code /budgets} — budgets and budget-vs-actual (ADR-S005; no Nest counterpart). */
@RestController
@RequestMapping("/budgets")
public class BudgetController {

    static final String DATE = "^\\d{4}-\\d{2}-\\d{2}.*$";
    static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

    /** One budget line. */
    public record LineRequest(
            @NotNull(message = "must be a string") String accountId,
            @NotNull(message = "must be a string") @Pattern(regexp = MONTH, message = "must be YYYY-MM") String period,
            @NotNull(message = "must be a number conforming to the specified constraints")
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal amount,
            String notes) {}

    /** Create body. */
    public record CreateRequest(
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty")
            @Size(max = 120, message = "must be shorter than or equal to 120 characters") String name,
            @NotNull(message = "must be an integer number") @Min(value = 2000, message = "must not be less than 2000")
            @Max(value = 2100, message = "must not be greater than 2100") Integer fiscalYear,
            @Pattern(regexp = DATE, message = "must be a valid ISO 8601 date string") String startDate,
            @Pattern(regexp = DATE, message = "must be a valid ISO 8601 date string") String endDate,
            String branchId,
            String costCenterId,
            String notes,
            @Valid List<LineRequest> lines) {}

    /** Update body (all optional). */
    public record UpdateRequest(
            @Size(max = 120, message = "must be shorter than or equal to 120 characters") String name,
            @Min(value = 2000, message = "must not be less than 2000")
            @Max(value = 2100, message = "must not be greater than 2100") Integer fiscalYear,
            @Pattern(regexp = DATE, message = "must be a valid ISO 8601 date string") String startDate,
            @Pattern(regexp = DATE, message = "must be a valid ISO 8601 date string") String endDate,
            String branchId,
            String costCenterId,
            String notes,
            @Valid List<LineRequest> lines) {}

    /** Replace-lines body. */
    public record LinesRequest(@NotNull(message = "must be an array") @Valid List<LineRequest> lines) {}

    private final BudgetService service;

    public BudgetController(BudgetService service) {
        this.service = service;
    }

    private static List<LineInput> lines(List<LineRequest> in) {
        return in == null ? null : in.stream()
                .map(l -> new LineInput(l.accountId(), l.period(), l.amount(), l.notes())).toList();
    }

    @GetMapping
    @RequirePermissions(Permissions.BUDGET__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        PageQuery p = QueryParams.page(query, "status", "fiscalYear");
        String status = query.get("status");
        if (status != null && !List.of("draft", "approved").contains(status)) {
            throw QueryParams.validation(List.of("status must be one of the following values: draft, approved"));
        }
        Integer year = query.get("fiscalYear") == null ? null : QueryParams.parseInt(query.get("fiscalYear"), 0);
        return service.list(p.page() == null ? 1 : p.page(), p.pageSize() == null ? 25 : p.pageSize(), p.search(),
                status, year);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.BUDGET__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return service.findOne(id);
    }

    @GetMapping("/{id}/vs-actual")
    @RequirePermissions(Permissions.BUDGET__READ)
    public Map<String, Object> vsActual(@PathVariable String id, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return service.vsActual(id, from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BUDGET__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest dto) {
        return service.create(new BudgetInput(dto.name(), dto.fiscalYear(), dto.startDate(), dto.endDate(),
                dto.branchId(), dto.costCenterId(), dto.notes(), lines(dto.lines())));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.BUDGET__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateRequest dto) {
        return service.update(id, new BudgetInput(dto.name(), dto.fiscalYear(), dto.startDate(), dto.endDate(),
                dto.branchId(), dto.costCenterId(), dto.notes(), lines(dto.lines())));
    }

    @PutMapping("/{id}/lines")
    @RequirePermissions(Permissions.BUDGET__UPDATE)
    public Map<String, Object> replaceLines(@PathVariable String id, @Valid @RequestBody LinesRequest dto) {
        return service.replaceLines(id, lines(dto.lines()));
    }

    @PostMapping("/{id}/approve")
    @RequirePermissions(Permissions.BUDGET__APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/reopen")
    @RequirePermissions(Permissions.BUDGET__APPROVE)
    public Map<String, Object> reopen(@PathVariable String id) {
        return service.reopen(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.BUDGET__DELETE)
    public void remove(@PathVariable String id) {
        service.remove(id);
    }
}
