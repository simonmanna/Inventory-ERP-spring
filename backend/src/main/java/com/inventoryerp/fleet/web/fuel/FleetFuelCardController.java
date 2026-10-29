package com.inventoryerp.fleet.web.fuel;

import com.inventoryerp.fleet.application.FleetFuelCardService;
import com.inventoryerp.fleet.application.FleetFuelSettingsService;
import com.inventoryerp.fleet.application.FuelCommands.BandInput;
import com.inventoryerp.fleet.application.FuelCommands.FuelCardInput;
import com.inventoryerp.fleet.application.FuelCommands.FuelCardPatch;
import com.inventoryerp.fleet.application.FuelCommands.SettingsInput;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * Fuel cards (masked numbers only) and the fuel thresholds — overfill tolerance, dip approval thresholds, consumption
 * bands per vehicle class (fleet 1.5, ADR-S009).
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetFuelCardController {

    private final FleetFuelCardService cards;
    private final FleetFuelSettingsService settings;

    public FleetFuelCardController(FleetFuelCardService cards, FleetFuelSettingsService settings) {
        this.cards = cards;
        this.settings = settings;
    }

    @GetMapping("/fuel-cards")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"active", "blocked", "expired", "cancelled"}) String status,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return cards.list(search, status, vehicleId, page, pageSize);
    }

    @GetMapping("/fuel-cards/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return cards.get(id);
    }

    @PostMapping("/fuel-cards")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_FUEL__CREATE)
    public Map<String, Object> create(@Valid @RequestBody FuelCardInput dto) {
        return cards.create(dto);
    }

    @PatchMapping("/fuel-cards/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody FuelCardPatch dto) {
        return cards.update(id, dto);
    }

    @DeleteMapping("/fuel-cards/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__UPDATE)
    public Map<String, Object> delete(@PathVariable String id) {
        cards.delete(id);
        return Map.of("id", id, "deleted", true);
    }

    @GetMapping("/fuel-settings")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public Map<String, Object> settings() {
        return settings.view();
    }

    @PutMapping("/fuel-settings")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> updateSettings(@Valid @RequestBody SettingsInput dto) {
        return settings.update(dto);
    }

    @PutMapping("/fuel-settings/bands/{categoryId}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> putBand(@PathVariable String categoryId, @Valid @RequestBody BandInput dto) {
        return settings.putBand(categoryId, dto);
    }
}
