package com.inventoryerp.quarry.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quarry.application.QuarryCommands.HaulageInput;
import com.inventoryerp.quarry.application.QuarryCommands.HaulageQuery;
import com.inventoryerp.quarry.application.QuarryHaulageService;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
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

/** Haulage face → ROM stockpile / crusher (ADR-S012): loads × capacity or weighbridge tickets (read-only). */
@RestController
@RequestMapping("/quarry")
@RequiresModule("quarry")
@ConditionalOnProperty(name = "app.modules.quarry", havingValue = "true")
@Validated
public class QuarryHaulageController {

    private final QuarryHaulageService haulage;

    public QuarryHaulageController(QuarryHaulageService haulage) {
        this.haulage = haulage;
    }

    /** {@code {data, meta, summary}}; {@code unassigned=true}: not yet on an extraction log. */
    @GetMapping("/haulage-trips")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> list(@RequestParam(required = false) String siteId,
            @RequestParam(required = false) String truckVehicleId,
            @RequestParam(required = false) @OneOf({"rom_stockpile", "crusher", "waste_dump", "other"}) String destination,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return haulage.list(new HaulageQuery(siteId, truckVehicleId, destination, dateFrom, dateTo, unassigned, search,
                page, pageSize));
    }

    @GetMapping("/haulage-trips/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return haulage.get(id);
    }

    @PostMapping("/haulage-trips")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__HAUL)
    public Map<String, Object> create(@Valid @RequestBody HaulageInput dto) {
        return haulage.create(dto);
    }

    @PatchMapping("/haulage-trips/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__HAUL)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody HaulageInput dto) {
        return haulage.update(id, dto);
    }

    @DeleteMapping("/haulage-trips/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__HAUL)
    public Map<String, Object> delete(@PathVariable String id) {
        haulage.delete(id);
        return Map.of("id", id, "deleted", true);
    }
}
