package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.inventoryerp.lossprevention.application.LpCommands.RuleInput;
import com.inventoryerp.lossprevention.application.LpCommands.SettingsInput;
import com.inventoryerp.lossprevention.application.LpRuleService;
import com.inventoryerp.lossprevention.application.LpSettingsService;

/** {@code /loss-prevention/rules} and {@code /settings} (ADR-S077): what is looked for, and when a window is run. */
@RestController
@RequestMapping("/loss-prevention")
@RequiresModule("loss-prevention")
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionRuleController {

    private final LpRuleService rules;
    private final LpSettingsService settings;

    public LossPreventionRuleController(LpRuleService rules, LpSettingsService settings) {
        this.rules = rules;
        this.settings = settings;
    }

    @GetMapping("/rules")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public List<Map<String, Object>> rules() {
        return rules.list();
    }

    @GetMapping("/rules/{code}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> rule(@PathVariable String code) {
        return rules.get(code);
    }

    @PutMapping("/rules/{code}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> saveRule(@PathVariable String code, @Valid @RequestBody RuleInput dto) {
        return rules.save(code, dto);
    }

    @GetMapping("/rules/{code}/versions")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public List<Map<String, Object>> versions(@PathVariable String code) {
        return rules.versions(code);
    }

    @GetMapping("/settings")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> settings() {
        return settings.get();
    }

    @PutMapping("/settings")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> saveSettings(@Valid @RequestBody SettingsInput dto) {
        return settings.save(dto);
    }
}
