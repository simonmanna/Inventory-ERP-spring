package com.inventoryerp.kernel.settings;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /settings} generic key/value routes (port of {@code settings.controller.ts}). */
@RestController
@RequestMapping("/settings")
@Validated
public class SettingsController {

    /** {@code SetSettingDto}. */
    public record SetSettingRequest(
            @NotNull(message = "should not be null or undefined") Object value,
            @OneOf({"organization", "warehouse", "category", "product"}) String scopeType,
            String scopeId) {}

    /** {@code InventoryDefaultsDto} (partial update). */
    public record InventoryDefaultsRequest(
            @OneOf({"AVCO", "FIFO", "STANDARD", "SPECIFIC"}) String costingMethod,
            @OneOf({"FEFO", "FIFO", "MANUAL", "SERIAL"}) String pickingStrategy,
            Boolean batchTracking,
            Boolean expiryTracking,
            Boolean serialTracking) {}

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @RequirePermissions(Permissions.SETTING__READ)
    public List<Map<String, Object>> list() {
        return settings.listForOrganization();
    }

    @GetMapping("/inventory-defaults")
    @RequirePermissions(Permissions.SETTING__READ)
    public Map<String, Object> getInventoryDefaults() {
        return settings.getInventoryDefaults();
    }

    @PutMapping("/inventory-defaults")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> setInventoryDefaults(@Valid @RequestBody InventoryDefaultsRequest dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("costingMethod", dto.costingMethod());
        m.put("pickingStrategy", dto.pickingStrategy());
        m.put("batchTracking", dto.batchTracking());
        m.put("expiryTracking", dto.expiryTracking());
        m.put("serialTracking", dto.serialTracking());
        return settings.setInventoryDefaults(m);
    }

    @GetMapping("/effective")
    @RequirePermissions(Permissions.SETTING__READ)
    public List<Map<String, Object>> effective(
            @RequestParam(required = false) String group,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String productId) {
        String resolved = "accounting".equals(group) || "purchasing".equals(group) ? group : "inventory";
        return settings.listEffective(resolved, new SettingContext(productId, categoryId, warehouseId), false);
    }

    @PutMapping("/{key}")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> set(@PathVariable String key, @Valid @RequestBody SetSettingRequest dto) {
        return settings.set(key, dto.value(), dto.scopeType(), dto.scopeId());
    }

    @DeleteMapping("/{key}")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public void unset(@PathVariable String key,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String scopeId) {
        settings.unset(key, scopeType, scopeId);
    }
}
