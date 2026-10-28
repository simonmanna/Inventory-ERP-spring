package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.CostAllocationService;
import com.inventoryerp.accounting.application.CostAllocationService.RuleInput;
import com.inventoryerp.accounting.application.CostAllocationService.TargetInput;
import com.inventoryerp.accounting.application.CostCenterReportService;
import com.inventoryerp.accounting.application.CostCenterRulesService;
import com.inventoryerp.accounting.application.CostCenterService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
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
 * Cost-centre accounting beyond the Nest CRUD (ADR-S007 §4): hierarchy, defaults, per-account rules, allocation rules
 * and runs, and reports. All under {@code /cost-centers/...}; literal segments never collide with {@code /{id}}.
 */
@RestController
@RequestMapping("/cost-centers")
public class CostCenterAccountingController {

    /** Default mapping body. */
    public record DefaultRequest(
            @NotNull(message = "scopeType must be one of the following values: branch, partner, user, location, employee")
            @OneOf({"branch", "partner", "user", "location", "employee"}) String scopeType,
            @NotNull(message = "scopeId must be a string") @NotEmpty(message = "scopeId should not be empty") String scopeId,
            @NotNull(message = "costCenterId must be a string") @NotEmpty(message = "costCenterId should not be empty")
            String costCenterId) {}

    /** Account rule body. */
    public record AccountRuleRequest(
            @NotNull(message = "rule must be one of the following values: required, optional, forbidden")
            @OneOf({"required", "optional", "forbidden"}) String rule) {}

    /** One weighted target. */
    public record TargetRequest(
            @NotNull(message = "costCenterId must be a string") @NotEmpty(message = "costCenterId should not be empty")
            String costCenterId,
            @NotNull(message = "weight must be a positive number") @Positive(message = "weight must be a positive number")
            BigDecimal weight) {}

    /** Create body. */
    public record CreateRuleRequest(
            @NotNull(message = "code must be a string") @NotEmpty(message = "code should not be empty") String code,
            @NotNull(message = "name must be a string") @NotEmpty(message = "name should not be empty") String name,
            @NotNull(message = "kind must be one of the following values: split, periodic")
            @OneOf({"split", "periodic"}) String kind,
            String driver,
            String sourceCostCenterId,
            List<String> accountIds,
            Boolean isActive,
            String notes,
            @NotNull(message = "targets must be an array") List<@Valid TargetRequest> targets) {}

    /** Patch body. */
    public record UpdateRuleRequest(String code, String name, String driver, String sourceCostCenterId,
            List<String> accountIds, Boolean isActive, String notes, List<@Valid TargetRequest> targets) {}

    /** Run body. */
    public record RunRequest(
            @NotNull(message = "periodFrom must be a date (YYYY-MM-DD)") String periodFrom,
            @NotNull(message = "periodTo must be a date (YYYY-MM-DD)") String periodTo) {}

    private final CostCenterService centers;
    private final CostCenterRulesService rules;
    private final CostAllocationService allocations;
    private final CostCenterReportService reports;

    public CostCenterAccountingController(CostCenterService centers, CostCenterRulesService rules,
            CostAllocationService allocations, CostCenterReportService reports) {
        this.centers = centers;
        this.rules = rules;
        this.allocations = allocations;
        this.reports = reports;
    }

    // ------------------------------------------------------------------ hierarchy

    @GetMapping("/tree")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public List<Map<String, Object>> tree() {
        return centers.tree();
    }

    // ------------------------------------------------------------------ defaults

    @GetMapping("/defaults")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public List<Map<String, Object>> defaults() {
        return rules.listDefaults();
    }

    @PostMapping("/defaults")
    @RequirePermissions(Permissions.COST_CENTER__UPDATE)
    public Map<String, Object> upsertDefault(@Valid @RequestBody DefaultRequest dto) {
        return rules.upsertDefault(dto.scopeType(), dto.scopeId(), dto.costCenterId());
    }

    @DeleteMapping("/defaults/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.COST_CENTER__UPDATE)
    public void deleteDefault(@PathVariable String id) {
        rules.deleteDefault(id);
    }

    // ------------------------------------------------------------------ account rules

    @GetMapping("/account-rules")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public List<Map<String, Object>> accountRules() {
        return rules.listAccountRules();
    }

    @PutMapping("/account-rules/{accountId}")
    @RequirePermissions(Permissions.COST_CENTER__UPDATE)
    public Map<String, Object> setAccountRule(@PathVariable String accountId, @Valid @RequestBody AccountRuleRequest dto) {
        return rules.setAccountRule(accountId, dto.rule());
    }

    // ------------------------------------------------------------------ allocation rules + runs

    @GetMapping("/allocation-rules")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public List<Map<String, Object>> listRules(@RequestParam(required = false) String kind) {
        return allocations.list(kind);
    }

    @GetMapping("/allocation-rules/{id}")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public Map<String, Object> getRule(@PathVariable String id) {
        return allocations.get(id);
    }

    @PostMapping("/allocation-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COST_CENTER__ALLOCATE)
    public Map<String, Object> createRule(@Valid @RequestBody CreateRuleRequest dto) {
        return allocations.create(new RuleInput(dto.code(), dto.name(), dto.kind(), dto.driver(),
                dto.sourceCostCenterId(), dto.accountIds(), dto.isActive(), dto.notes(), targets(dto.targets())));
    }

    @PatchMapping("/allocation-rules/{id}")
    @RequirePermissions(Permissions.COST_CENTER__ALLOCATE)
    public Map<String, Object> updateRule(@PathVariable String id, @Valid @RequestBody UpdateRuleRequest dto) {
        return allocations.update(id, new RuleInput(dto.code(), dto.name(), null, dto.driver(), dto.sourceCostCenterId(),
                dto.accountIds(), dto.isActive(), dto.notes(), dto.targets() == null ? null : targets(dto.targets())));
    }

    @DeleteMapping("/allocation-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.COST_CENTER__ALLOCATE)
    public void deleteRule(@PathVariable String id) {
        allocations.remove(id);
    }

    @GetMapping("/allocation-rules/{id}/preview")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public Map<String, Object> preview(@PathVariable String id, @RequestParam String periodFrom,
            @RequestParam String periodTo) {
        return allocations.preview(id, day(periodFrom, "periodFrom"), day(periodTo, "periodTo"));
    }

    @PostMapping("/allocation-rules/{id}/run")
    @Idempotent
    @RequirePermissions(Permissions.COST_CENTER__ALLOCATE)
    public Map<String, Object> run(@PathVariable String id, @Valid @RequestBody RunRequest dto) {
        LocalDate from = day(dto.periodFrom(), "periodFrom");
        LocalDate to = day(dto.periodTo(), "periodTo");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("periodTo must not be before periodFrom");
        }
        return allocations.run(id, from, to);
    }

    @GetMapping("/allocation-runs")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public List<Map<String, Object>> runs(@RequestParam(required = false) String ruleId) {
        return allocations.runs(ruleId);
    }

    @PostMapping("/allocation-runs/{id}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.COST_CENTER__ALLOCATE)
    public Map<String, Object> reverseRun(@PathVariable String id) {
        return allocations.reverseRun(id);
    }

    // ------------------------------------------------------------------ reports

    @GetMapping("/reports/profit-and-loss")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public Map<String, Object> profitAndLoss(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String costCenterId) {
        return reports.profitAndLoss(day(from, "from"), day(to, "to"),
                costCenterId == null || costCenterId.isEmpty() ? null : costCenterId);
    }

    @GetMapping("/reports/matrix")
    @RequirePermissions(Permissions.COST_CENTER__READ)
    public Map<String, Object> matrix(@RequestParam String from, @RequestParam String to) {
        return reports.matrix(day(from, "from"), day(to, "to"));
    }

    // ------------------------------------------------------------------ helpers

    private static List<TargetInput> targets(List<TargetRequest> in) {
        return in.stream().map(t -> new TargetInput(t.costCenterId(), t.weight())).toList();
    }

    static LocalDate day(String v, String field) {
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest(field + " must be a date (YYYY-MM-DD)");
        }
    }
}
