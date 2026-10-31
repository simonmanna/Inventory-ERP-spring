package com.inventoryerp.fleet.web.plant;

import com.inventoryerp.fleet.application.FleetCommands.VehicleInput;
import com.inventoryerp.fleet.application.FleetPlantService;
import com.inventoryerp.fleet.application.FleetPlantService.PlantQuery;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixed-plant register (CMMS-1): classes, register by location, registration, cost per equipment and the link from a
 * fixed asset. Schedules, work orders, downtime and inspections are the fleet 1.6 routes, keyed on the same unit id.
 */
@RestController
@RequestMapping("/fleet/plant")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetPlantController {

    private final FleetPlantService plant;

    public FleetPlantController(FleetPlantService plant) {
        this.plant = plant;
    }

    @GetMapping
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false)
            @OneOf({"active", "in_workshop", "breakdown", "idle", "disposed"}) String status,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String locationId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return plant.list(new PlantQuery(search, categoryId, status, branchId, locationId, page, pageSize));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_VEHICLE__CREATE)
    public Map<String, Object> register(@Valid @RequestBody VehicleInput body) {
        return plant.register(body);
    }

    @GetMapping("/classes")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public List<Map<String, Object>> classes() {
        return plant.classes();
    }

    @GetMapping("/by-asset/{assetId}")
    @RequirePermissions(Permissions.FLEET_VEHICLE__READ)
    public Map<String, Object> byAsset(@PathVariable String assetId) {
        return plant.byAsset(assetId);
    }

    @GetMapping("/costs")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> costs(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String locationId) {
        return plant.costs(from, to, categoryId, branchId, locationId);
    }
}
