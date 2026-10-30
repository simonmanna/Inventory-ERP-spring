package com.inventoryerp.quarry.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quarry.application.QuarryCommands.EquipmentRateInput;
import com.inventoryerp.quarry.application.QuarryCommands.RoyaltyRateInput;
import com.inventoryerp.quarry.application.QuarryCommands.SettingsInput;
import com.inventoryerp.quarry.application.QuarryLookupService;
import com.inventoryerp.quarry.application.QuarrySettingsService;
import com.inventoryerp.quarry.application.QuarryStockpileService;
import com.inventoryerp.quarry.application.QuarryStockpileService.RomStock;
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
 * Quarry overview, form lookups, ROM stock summary, settings (cost-allocation rule, fallback rates, explosive
 * segregation of duties), mobile-plant hourly rates and royalty / levy rates (ADR-S012).
 */
@RestController
@RequestMapping("/quarry")
@RequiresModule("quarry")
@ConditionalOnProperty(name = "app.modules.quarry", havingValue = "true")
@Validated
public class QuarrySetupController {

    private final QuarryLookupService lookups;
    private final QuarrySettingsService settings;
    private final QuarryStockpileService stockpiles;

    public QuarrySetupController(QuarryLookupService lookups, QuarrySettingsService settings,
            QuarryStockpileService stockpiles) {
        this.lookups = lookups;
        this.settings = settings;
        this.stockpiles = stockpiles;
    }

    @GetMapping("/overview")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> overview() {
        return lookups.overview();
    }

    @GetMapping("/lookups")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public Map<String, Object> lookups() {
        return lookups.lookups();
    }

    @GetMapping("/lookups/employees")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public List<Map<String, Object>> employees(@RequestParam(required = false) String search) {
        return lookups.employees(search);
    }

    @GetMapping("/lookups/products")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public List<Map<String, Object>> products(@RequestParam(required = false) String search) {
        return lookups.products(search);
    }

    @GetMapping("/lookups/partners")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public List<Map<String, Object>> partners(@RequestParam(required = false) String search) {
        return lookups.partners(search);
    }

    /** Completed internal-haulage weighbridge tickets not yet used by a haulage record or an extraction log. */
    @GetMapping("/lookups/weighbridge-tickets")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public List<Map<String, Object>> tickets(@RequestParam(required = false) String search) {
        return lookups.tickets(search);
    }

    /** ROM / product stockpile stock per product (the stone 2.3 crushing consumes). */
    @GetMapping("/rom-stock")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public List<RomStock> romStock(@RequestParam(required = false) String siteId,
            @RequestParam(required = false) String kind) {
        return stockpiles.romStock(siteId == null || siteId.isBlank() ? null : siteId,
                kind == null || kind.isBlank() ? null : kind);
    }

    @GetMapping("/settings")
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> getSettings() {
        return settings.current();
    }

    @PutMapping("/settings")
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> putSettings(@Valid @RequestBody SettingsInput dto) {
        return settings.update(dto);
    }

    @GetMapping("/equipment-rates")
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public List<Map<String, Object>> equipmentRates() {
        return settings.equipmentRates();
    }

    @PostMapping("/equipment-rates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> createEquipmentRate(@Valid @RequestBody EquipmentRateInput dto) {
        return settings.createEquipmentRate(dto);
    }

    @PatchMapping("/equipment-rates/{id}")
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> updateEquipmentRate(@PathVariable String id, @Valid @RequestBody EquipmentRateInput dto) {
        return settings.updateEquipmentRate(id, dto);
    }

    @DeleteMapping("/equipment-rates/{id}")
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> deleteEquipmentRate(@PathVariable String id) {
        settings.deleteEquipmentRate(id);
        return Map.of("id", id, "deleted", true);
    }

    @GetMapping("/royalty-rates")
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public List<Map<String, Object>> royaltyRates() {
        return settings.royaltyRates();
    }

    @PostMapping("/royalty-rates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> createRoyaltyRate(@Valid @RequestBody RoyaltyRateInput dto) {
        return settings.createRoyaltyRate(dto);
    }

    @PatchMapping("/royalty-rates/{id}")
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> updateRoyaltyRate(@PathVariable String id, @Valid @RequestBody RoyaltyRateInput dto) {
        return settings.updateRoyaltyRate(id, dto);
    }

    @DeleteMapping("/royalty-rates/{id}")
    @RequirePermissions(Permissions.QUARRY__SETTINGS)
    public Map<String, Object> deleteRoyaltyRate(@PathVariable String id) {
        settings.deleteRoyaltyRate(id);
        return Map.of("id", id, "deleted", true);
    }
}
