package com.inventoryerp.customization.web;

import com.inventoryerp.customization.application.AutomationRuleService;
import com.inventoryerp.customization.application.AutomationRuleService.RuleInput;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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

/** {@code /automation-rules}: event-driven automation rules, their runs and dry-run tests (ADR-S029 §4, extension). */
@RestController
@RequestMapping("/automation-rules")
public class AutomationRulesController {

    public record RuleRequest(
            @NotBlank(message = "name should not be empty") @Size(max = 120, message = "name must be at most 120"
                    + " characters") String name,
            @Size(max = 500, message = "description must be at most 500 characters") String description,
            @NotBlank(message = "trigger should not be empty") String trigger,
            List<Map<String, Object>> conditions,
            @NotEmpty(message = "actions should not be empty") List<Map<String, Object>> actions,
            Boolean isActive) {}

    public record RuleUpdateRequest(
            @Size(min = 1, max = 120, message = "name must be 1 to 120 characters") String name,
            @Size(max = 500, message = "description must be at most 500 characters") String description,
            String trigger,
            List<Map<String, Object>> conditions,
            List<Map<String, Object>> actions,
            Boolean isActive) {}

    public record TestRequest(@NotBlank(message = "recordId should not be empty") String recordId) {}

    private final AutomationRuleService rules;

    public AutomationRulesController(AutomationRuleService rules) {
        this.rules = rules;
    }

    @GetMapping("/catalog")
    @RequirePermissions(Permissions.AUTOMATION__READ)
    public Map<String, Object> catalog() {
        return rules.catalog();
    }

    @GetMapping
    @RequirePermissions(Permissions.AUTOMATION__READ)
    public List<Map<String, Object>> list() {
        return rules.list();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.AUTOMATION__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return rules.get(id);
    }

    @GetMapping("/{id}/runs")
    @RequirePermissions(Permissions.AUTOMATION__READ)
    public PageResult<Map<String, Object>> runs(@PathVariable String id, @RequestParam Map<String, String> query) {
        return rules.runs(id, QueryParams.page(query));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.AUTOMATION__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody RuleRequest dto) {
        return rules.create(new RuleInput(dto.name(), dto.description(), dto.trigger(), dto.conditions(), dto.actions(),
                dto.isActive()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.AUTOMATION__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody RuleUpdateRequest dto) {
        return rules.update(id, new RuleInput(dto.name(), dto.description(), dto.trigger(), dto.conditions(),
                dto.actions(), dto.isActive()));
    }

    @PostMapping("/{id}/test")
    @RequirePermissions(Permissions.AUTOMATION__MANAGE)
    public Map<String, Object> test(@PathVariable String id, @Valid @RequestBody TestRequest dto) {
        return rules.test(id, dto.recordId());
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.AUTOMATION__MANAGE)
    public Map<String, Object> delete(@PathVariable String id) {
        return rules.delete(id);
    }
}
