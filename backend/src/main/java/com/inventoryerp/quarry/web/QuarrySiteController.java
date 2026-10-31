package com.inventoryerp.quarry.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.quarry.application.QuarryCommands.MaterialInput;
import com.inventoryerp.quarry.application.QuarryCommands.SiteInput;
import com.inventoryerp.quarry.application.QuarryCommands.StockpileInput;
import com.inventoryerp.quarry.application.QuarryCommands.ZoneInput;
import com.inventoryerp.quarry.application.QuarrySiteService;
import com.inventoryerp.quarry.application.QuarryStockpileService;
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
 * Quarry master data (ADR-S012): sites, the zone → bench → face hierarchy, material types / grades and stockpiles
 * (the ROM stockpile view with its stock and inventory movements). PATCH replaces the editable fields (whole form).
 */
@RestController
@RequestMapping("/quarry")
@RequiresModule("quarry")
@ConditionalOnProperty(name = "app.modules.quarry", havingValue = "true")
@Validated
public class QuarrySiteController {

    private final QuarrySiteService sites;
    private final QuarryStockpileService stockpiles;

    public QuarrySiteController(QuarrySiteService sites, QuarryStockpileService stockpiles) {
        this.sites = sites;
        this.stockpiles = stockpiles;
    }

    @GetMapping("/sites")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public PageResult<Map<String, Object>> listSites(
            @RequestParam(required = false) @OneOf({"active", "suspended", "closed"}) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return sites.listSites(status, search, page, pageSize);
    }

    @GetMapping("/sites/{id}")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public Map<String, Object> getSite(@PathVariable String id) {
        return sites.getSite(id);
    }

    @PostMapping("/sites")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_SITE__CREATE)
    public Map<String, Object> createSite(@Valid @RequestBody SiteInput dto) {
        return sites.createSite(dto);
    }

    @PatchMapping("/sites/{id}")
    @RequirePermissions(Permissions.QUARRY_SITE__UPDATE)
    public Map<String, Object> updateSite(@PathVariable String id, @Valid @RequestBody SiteInput dto) {
        return sites.updateSite(id, dto);
    }

    @DeleteMapping("/sites/{id}")
    @RequirePermissions(Permissions.QUARRY_SITE__DELETE)
    public Map<String, Object> deleteSite(@PathVariable String id) {
        sites.deleteSite(id);
        return Map.of("id", id, "deleted", true);
    }

    @GetMapping("/sites/{id}/zones")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public List<Map<String, Object>> zones(@PathVariable String id) {
        return sites.zones(id);
    }

    @PostMapping("/zones")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_SITE__CREATE)
    public Map<String, Object> createZone(@Valid @RequestBody ZoneInput dto) {
        return sites.createZone(dto);
    }

    @PatchMapping("/zones/{id}")
    @RequirePermissions(Permissions.QUARRY_SITE__UPDATE)
    public Map<String, Object> updateZone(@PathVariable String id, @Valid @RequestBody ZoneInput dto) {
        return sites.updateZone(id, dto);
    }

    @DeleteMapping("/zones/{id}")
    @RequirePermissions(Permissions.QUARRY_SITE__DELETE)
    public Map<String, Object> deleteZone(@PathVariable String id) {
        sites.deleteZone(id);
        return Map.of("id", id, "deleted", true);
    }

    @GetMapping("/materials")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public List<Map<String, Object>> materials(@RequestParam(required = false) Boolean includeInactive) {
        return sites.materials(Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/materials")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_SITE__CREATE)
    public Map<String, Object> createMaterial(@Valid @RequestBody MaterialInput dto) {
        return sites.createMaterial(dto);
    }

    @PatchMapping("/materials/{id}")
    @RequirePermissions(Permissions.QUARRY_SITE__UPDATE)
    public Map<String, Object> updateMaterial(@PathVariable String id, @Valid @RequestBody MaterialInput dto) {
        return sites.updateMaterial(id, dto);
    }

    @DeleteMapping("/materials/{id}")
    @RequirePermissions(Permissions.QUARRY_SITE__DELETE)
    public Map<String, Object> deleteMaterial(@PathVariable String id) {
        sites.deleteMaterial(id);
        return Map.of("id", id, "deleted", true);
    }

    @GetMapping("/stockpiles")
    @RequirePermissions(Permissions.QUARRY_SITE__READ)
    public List<Map<String, Object>> stockpiles(@RequestParam(required = false) String siteId,
            @RequestParam(required = false) @OneOf({"rom", "product"}) String kind) {
        return sites.stockpiles(siteId, kind);
    }

    /** Stockpile view: on-hand per product and the paged inventory movements of its location. */
    @GetMapping("/stockpiles/{id}")
    @RequirePermissions(Permissions.QUARRY_EXTRACTION__READ)
    public Map<String, Object> stockpile(@PathVariable String id,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return stockpiles.view(id, page, pageSize);
    }

    /** Without {@code locationId} a new inventory location is created for the stockpile. */
    @PostMapping("/stockpiles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUARRY_SITE__CREATE)
    public Map<String, Object> createStockpile(@Valid @RequestBody StockpileInput dto) {
        return sites.createStockpile(dto);
    }

    @PatchMapping("/stockpiles/{id}")
    @RequirePermissions(Permissions.QUARRY_SITE__UPDATE)
    public Map<String, Object> updateStockpile(@PathVariable String id, @Valid @RequestBody StockpileInput dto) {
        return sites.updateStockpile(id, dto);
    }
}
