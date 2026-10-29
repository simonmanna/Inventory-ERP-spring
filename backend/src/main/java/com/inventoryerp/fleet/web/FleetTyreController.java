package com.inventoryerp.fleet.web;

import com.inventoryerp.fleet.application.FleetTyreCommands.FitInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.RemoveInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.RetreadInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.RotateInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.ScrapInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.TreadReadingInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.TyreInput;
import com.inventoryerp.fleet.application.FleetTyreCommands.TyrePatch;
import com.inventoryerp.fleet.application.FleetTyreCommands.TyreQuery;
import com.inventoryerp.fleet.application.FleetTyreService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
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
 * Tyres (fleet 1.7, ADR-S009): register by serial number, wheel positions per vehicle, fitment / rotation / removal
 * with the meter reading, retreads, scrapping, tread-depth readings, cost per km. Fitment of a stocked tyre issues it
 * from stock (GL), so that route requires an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetTyreController {

    private final FleetTyreService tyres;

    public FleetTyreController(FleetTyreService tyres) {
        this.tyres = tyres;
    }

    @GetMapping("/tyres")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"in_stock", "fitted", "removed", "retreaded", "scrapped"})
            String status,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String tyreType,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) @OneOf({"asc", "desc"}) String sortOrder) {
        return tyres.list(new TyreQuery(search, status, vehicleId, tyreType, page, pageSize, sortBy, sortOrder));
    }

    @GetMapping("/tyres/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return tyres.get(id);
    }

    @PostMapping("/tyres")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__TYRE)
    public Map<String, Object> create(@Valid @RequestBody TyreInput dto) {
        return tyres.create(dto);
    }

    @PatchMapping("/tyres/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__TYRE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody TyrePatch dto) {
        return tyres.update(id, dto);
    }

    @DeleteMapping("/tyres/{id}")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__TYRE)
    public Map<String, Object> delete(@PathVariable String id) {
        tyres.delete(id);
        return Map.of("ok", true);
    }

    /** Fit to a position; the first fitment of a stocked tyre issues it from stock (money route). */
    @PostMapping("/tyres/{id}/fit")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__TYRE)
    public Map<String, Object> fit(@PathVariable String id, @Valid @RequestBody FitInput dto) {
        return tyres.fit(id, dto);
    }

    @PostMapping("/tyres/{id}/rotate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__TYRE)
    public Map<String, Object> rotate(@PathVariable String id, @Valid @RequestBody RotateInput dto) {
        return tyres.rotate(id, dto);
    }

    @PostMapping("/tyres/{id}/remove")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__TYRE)
    public Map<String, Object> remove(@PathVariable String id, @Valid @RequestBody RemoveInput dto) {
        return tyres.remove(id, dto);
    }

    @PostMapping("/tyres/{id}/retread")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__TYRE)
    public Map<String, Object> retread(@PathVariable String id, @Valid @RequestBody RetreadInput dto) {
        return tyres.retread(id, dto);
    }

    @PostMapping("/tyres/{id}/scrap")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__TYRE)
    public Map<String, Object> scrap(@PathVariable String id, @Valid @RequestBody ScrapInput dto) {
        return tyres.scrap(id, dto);
    }

    @PostMapping("/tyres/{id}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__TYRE)
    public Map<String, Object> reading(@PathVariable String id, @Valid @RequestBody TreadReadingInput dto) {
        return tyres.addReading(id, dto);
    }

    /** Wheel positions of the vehicle (axle layout) with the tyre fitted at each. */
    @GetMapping("/vehicles/{id}/tyres")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public Map<String, Object> vehicleTyres(@PathVariable String id) {
        return tyres.vehicleTyres(id);
    }

    @GetMapping("/tyre-lookups/products")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__READ)
    public List<Map<String, Object>> products(@RequestParam(required = false) String search) {
        return tyres.products(search);
    }
}
