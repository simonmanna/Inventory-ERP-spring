package com.inventoryerp.fleet.web.fuel;

import com.inventoryerp.fleet.application.FleetFuelTankService;
import com.inventoryerp.fleet.application.FuelCommands.DipInput;
import com.inventoryerp.fleet.application.FuelCommands.ReconcileInput;
import com.inventoryerp.fleet.application.FuelCommands.TankInput;
import com.inventoryerp.fleet.application.FuelCommands.TankPatch;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site fuel tanks and bowsers (fleet 1.5, ADR-S009): register, level and stock movements (fuel arrives through a
 * procurement GRN into the tank's location; issues are fuel logs with {@code source = tank}), dip readings and their
 * reconciliation (stock adjustment, approval above the thresholds; Idempotency-Key required).
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetFuelTankController {

    private final FleetFuelTankService tanks;

    public FleetFuelTankController(FleetFuelTankService tanks) {
        this.tanks = tanks;
    }

    @GetMapping("/fuel-tanks")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) Boolean includeInactive) {
        return tanks.list(Boolean.TRUE.equals(includeInactive));
    }

    @GetMapping("/fuel-tanks/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return tanks.get(id);
    }

    @PostMapping("/fuel-tanks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_FUEL__TANK)
    public Map<String, Object> create(@Valid @RequestBody TankInput dto) {
        return tanks.create(dto);
    }

    @PatchMapping("/fuel-tanks/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__TANK)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody TankPatch dto) {
        return tanks.update(id, dto);
    }

    @DeleteMapping("/fuel-tanks/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__TANK)
    public Map<String, Object> delete(@PathVariable String id) {
        tanks.delete(id);
        return Map.of("id", id, "deleted", true);
    }

    @GetMapping("/fuel-tanks/{id}/movements")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public PageResult<Map<String, Object>> movements(@PathVariable String id,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return tanks.movements(id, page, pageSize);
    }

    @GetMapping("/fuel-tanks/{id}/dips")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public List<Map<String, Object>> dips(@PathVariable String id,
            @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
        return tanks.dips(id, limit == null ? 100 : limit);
    }

    @PostMapping("/fuel-tanks/{id}/dips")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_FUEL__TANK)
    public Map<String, Object> recordDip(@PathVariable String id, @Valid @RequestBody DipInput dto) {
        return tanks.recordDip(id, dto);
    }

    @GetMapping("/fuel-dips/{id}")
    @RequirePermissions(Permissions.FLEET_FUEL__READ)
    public Map<String, Object> dip(@PathVariable String id) {
        return tanks.dip(id);
    }

    /** Post the dip variance as a stock adjustment (or request approval above the thresholds). */
    @PostMapping("/fuel-dips/{id}/reconcile")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_FUEL__TANK)
    public Map<String, Object> reconcile(@PathVariable String id, @Valid @RequestBody ReconcileInput dto) {
        return tanks.reconcile(id, dto);
    }

    /** Apply the decided approval request now (normally automatic once it is decided). */
    @PostMapping("/fuel-dips/{id}/sync")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_FUEL__TANK)
    public Map<String, Object> sync(@PathVariable String id) {
        return tanks.sync(id);
    }
}
