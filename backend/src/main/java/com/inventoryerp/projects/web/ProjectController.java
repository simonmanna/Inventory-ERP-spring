package com.inventoryerp.projects.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.application.ProjectCostService;
import com.inventoryerp.projects.application.ProjectCostService.EquipmentInput;
import com.inventoryerp.projects.application.ProjectCostService.IssueInput;
import com.inventoryerp.projects.application.ProjectCostService.ReclassInput;
import com.inventoryerp.projects.application.ProjectCostService.TimesheetInput;
import com.inventoryerp.projects.application.ProjectService;
import com.inventoryerp.projects.application.ProjectService.BudgetLineInput;
import com.inventoryerp.projects.application.ProjectService.ForecastLineInput;
import com.inventoryerp.projects.application.ProjectService.ProjectInput;
import com.inventoryerp.projects.application.ProjectService.SiteInput;
import com.inventoryerp.projects.application.ProjectService.WbsInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
 * {@code /projects} (Cost Management CM-8a, ADR-S038; extension): projects, sites, WBS, budgets, cost-to-complete
 * forecasts, cost capture and the budget / committed / actual / EAC report.
 */
@RestController
@RequestMapping("/projects")
public class ProjectController {

    public record ProjectRequest(
            @Size(max = 40, message = "code must be at most 40 characters") String code,
            @Size(max = 200, message = "name must be at most 200 characters") String name,
            String customerId,
            String contractRef,
            BigDecimal contractValue,
            String branchId,
            String costCenterId,
            String startDate,
            String endDate,
            @Size(max = 2000, message = "notes must be at most 2000 characters") String notes) {

        ProjectInput input() {
            return new ProjectInput(code, name, customerId, contractRef, contractValue, branchId, costCenterId,
                    startDate, endDate, notes);
        }
    }

    public record StatusRequest(@NotBlank(message = "status should not be empty") String status) {}

    public record SiteRequest(
            @NotBlank(message = "code should not be empty") String code,
            @NotBlank(message = "name should not be empty") String name,
            String address,
            String deliveryLocationId) {}

    public record WbsRequest(
            @NotBlank(message = "code should not be empty") String code,
            @NotBlank(message = "name should not be empty") String name,
            String parentId,
            Boolean isBillable,
            Boolean isCostable) {}

    public record BudgetLine(String wbsId, String costElement, BigDecimal amount, BigDecimal revenue) {}

    public record BudgetRequest(List<BudgetLine> lines, String notes) {}

    public record ForecastLine(String wbsId, String costElement, BigDecimal costToComplete, String fulfilmentClass) {}

    public record ForecastRequest(String asOf, List<ForecastLine> lines) {}

    public record IssueRequest(
            @NotBlank(message = "wbsId should not be empty") String wbsId,
            String siteId,
            @NotBlank(message = "productId should not be empty") String productId,
            String locationId,
            BigDecimal quantity,
            String date,
            Boolean excludeFromProgress,
            String notes) {}

    public record TimesheetRequest(
            @NotBlank(message = "wbsId should not be empty") String wbsId,
            @NotBlank(message = "from should not be empty") String from,
            @NotBlank(message = "to should not be empty") String to,
            BigDecimal ratePerHour) {}

    public record EquipmentRequest(
            @NotBlank(message = "wbsId should not be empty") String wbsId,
            String siteId,
            String equipmentRef,
            BigDecimal hours,
            BigDecimal ratePerHour,
            String date,
            String notes) {}

    public record ReclassRequest(
            @NotBlank(message = "wbsId should not be empty") String wbsId,
            String siteId,
            @NotBlank(message = "journalLineId should not be empty") String journalLineId,
            BigDecimal amount,
            String costElement,
            Boolean excludeFromProgress,
            String date,
            String notes) {}

    private final ProjectService projects;
    private final ProjectCostService costs;

    public ProjectController(ProjectService projects, ProjectCostService costs) {
        this.projects = projects;
        this.costs = costs;
    }

    @GetMapping
    @RequirePermissions(Permissions.PROJECT__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return projects.list(status, search);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody ProjectRequest r) {
        return projects.create(r.input());
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PROJECT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return projects.get(id);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody ProjectRequest r) {
        return projects.update(id, r.input());
    }

    @PostMapping("/{id}/status")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> status(@PathVariable String id, @Valid @RequestBody StatusRequest r) {
        return projects.setStatus(id, r.status());
    }

    @PostMapping("/{id}/sites")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> site(@PathVariable String id, @Valid @RequestBody SiteRequest r) {
        return projects.addSite(id, new SiteInput(r.code(), r.name(), r.address(), r.deliveryLocationId()));
    }

    @PostMapping("/{id}/wbs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> wbs(@PathVariable String id, @Valid @RequestBody WbsRequest r) {
        return projects.addWbs(id, new WbsInput(r.code(), r.name(), r.parentId(), r.isBillable(), r.isCostable()));
    }

    @GetMapping("/{id}/budgets")
    @RequirePermissions(Permissions.PROJECT__READ)
    public List<Map<String, Object>> budgets(@PathVariable String id) {
        return projects.budgets(id);
    }

    @PostMapping("/{id}/budgets")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> budget(@PathVariable String id, @RequestBody BudgetRequest r) {
        return projects.createBudget(id, r.lines() == null ? null : r.lines().stream().map(l -> new BudgetLineInput(
                l.wbsId(), l.costElement(), l.amount(), l.revenue())).toList(), r.notes());
    }

    @GetMapping("/budgets/{budgetId}")
    @RequirePermissions(Permissions.PROJECT__READ)
    public Map<String, Object> getBudget(@PathVariable String budgetId) {
        return projects.budget(budgetId);
    }

    @PostMapping("/budgets/{budgetId}/approve")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> approveBudget(@PathVariable String budgetId) {
        return projects.approveBudget(budgetId);
    }

    @GetMapping("/{id}/forecast")
    @RequirePermissions(Permissions.PROJECT__READ)
    public List<Map<String, Object>> forecast(@PathVariable String id) {
        projects.project(id, false);
        return projects.forecast(id);
    }

    @PutMapping("/{id}/forecast")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public List<Map<String, Object>> setForecast(@PathVariable String id, @RequestBody ForecastRequest r) {
        return projects.setForecast(id, r.asOf(), r.lines() == null ? null : r.lines().stream().map(l ->
                new ForecastLineInput(l.wbsId(), l.costElement(), l.costToComplete(), l.fulfilmentClass())).toList());
    }

    @GetMapping("/{id}/costs")
    @RequirePermissions(Permissions.PROJECT__READ)
    public List<Map<String, Object>> costs(@PathVariable String id) {
        return costs.costs(id);
    }

    @PostMapping("/{id}/costs/issue")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> issue(@PathVariable String id, @Valid @RequestBody IssueRequest r) {
        return costs.issue(id, new IssueInput(r.wbsId(), r.siteId(), r.productId(), r.locationId(), r.quantity(),
                r.date(), r.excludeFromProgress(), r.notes()));
    }

    @PostMapping("/{id}/costs/timesheets")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> timesheets(@PathVariable String id, @Valid @RequestBody TimesheetRequest r) {
        return costs.captureTimesheets(id, new TimesheetInput(r.wbsId(), r.from(), r.to(), r.ratePerHour()));
    }

    @PostMapping("/{id}/costs/equipment")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> equipment(@PathVariable String id, @Valid @RequestBody EquipmentRequest r) {
        return costs.equipment(id, new EquipmentInput(r.wbsId(), r.siteId(), r.equipmentRef(), r.hours(),
                r.ratePerHour(), r.date(), r.notes()));
    }

    @PostMapping("/{id}/costs/reclass")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> reclass(@PathVariable String id, @Valid @RequestBody ReclassRequest r) {
        return costs.reclass(id, new ReclassInput(r.wbsId(), r.siteId(), r.journalLineId(), r.amount(),
                r.costElement(), r.excludeFromProgress(), r.date(), r.notes()));
    }

    @PostMapping("/costs/{costLineId}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> reverse(@PathVariable String costLineId) {
        return costs.reverse(costLineId);
    }

    @GetMapping("/{id}/report")
    @RequirePermissions(Permissions.PROJECT__READ)
    public Map<String, Object> report(@PathVariable String id) {
        return costs.report(id);
    }
}
