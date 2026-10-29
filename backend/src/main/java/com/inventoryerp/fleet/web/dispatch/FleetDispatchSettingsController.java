package com.inventoryerp.fleet.web.dispatch;

import com.inventoryerp.fleet.application.DispatchCommands.ChargeRuleInput;
import com.inventoryerp.fleet.application.DispatchCommands.ChargeRulePatch;
import com.inventoryerp.fleet.application.DispatchCommands.LoadFactorInput;
import com.inventoryerp.fleet.application.DispatchCommands.TransporterRateInput;
import com.inventoryerp.fleet.application.DispatchCommands.TransporterRatePatch;
import com.inventoryerp.fleet.application.FleetDispatchSettingsService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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
 * Dispatch settings (fleet 1.3): transporter freight rates, delivery-charge rules (customer / zone / distance) and
 * per-product load factors. Read with {@code fleet_dispatch:read}, change with {@code fleet:settings}.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetDispatchSettingsController {

    private final FleetDispatchSettingsService settings;

    public FleetDispatchSettingsController(FleetDispatchSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/transporter-rates")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public List<Map<String, Object>> rates(@RequestParam(required = false) String partnerId,
            @RequestParam(required = false) Boolean includeInactive) {
        return settings.rates(partnerId, Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/transporter-rates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> createRate(@Valid @RequestBody TransporterRateInput dto) {
        return settings.createRate(dto);
    }

    @PatchMapping("/transporter-rates/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> updateRate(@PathVariable String id, @Valid @RequestBody TransporterRatePatch dto) {
        return settings.updateRate(id, dto);
    }

    @DeleteMapping("/transporter-rates/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> deleteRate(@PathVariable String id) {
        return settings.deleteRate(id);
    }

    @GetMapping("/delivery-charge-rules")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public List<Map<String, Object>> chargeRules(@RequestParam(required = false) Boolean includeInactive) {
        return settings.chargeRules(Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/delivery-charge-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> createChargeRule(@Valid @RequestBody ChargeRuleInput dto) {
        return settings.createChargeRule(dto);
    }

    @PatchMapping("/delivery-charge-rules/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> updateChargeRule(@PathVariable String id, @Valid @RequestBody ChargeRulePatch dto) {
        return settings.updateChargeRule(id, dto);
    }

    @DeleteMapping("/delivery-charge-rules/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> deleteChargeRule(@PathVariable String id) {
        return settings.deleteChargeRule(id);
    }

    @GetMapping("/product-load-factors")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public List<Map<String, Object>> loadFactors() {
        return settings.loadFactors();
    }

    /** The factor the planner uses for a product (fleet factor, product master or unit). */
    @GetMapping("/product-load-factors/{productId}")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public Map<String, Object> resolvedFactor(@PathVariable String productId) {
        return settings.resolvedFactor(productId);
    }

    @PutMapping("/product-load-factors/{productId}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> putLoadFactor(@PathVariable String productId, @Valid @RequestBody LoadFactorInput dto) {
        return settings.putLoadFactor(productId, dto);
    }

    @DeleteMapping("/product-load-factors/{productId}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> deleteLoadFactor(@PathVariable String productId) {
        return settings.deleteLoadFactor(productId);
    }
}
