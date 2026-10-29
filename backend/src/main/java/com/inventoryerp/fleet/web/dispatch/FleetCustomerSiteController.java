package com.inventoryerp.fleet.web.dispatch;

import com.inventoryerp.fleet.application.DispatchCommands.SiteInput;
import com.inventoryerp.fleet.application.DispatchCommands.SitePatch;
import com.inventoryerp.fleet.application.FleetCustomerSiteService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer construction sites / delivery addresses (fleet 1.3): GPS, contact, access notes, charge zone. */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetCustomerSiteController {

    private final FleetCustomerSiteService sites;

    public FleetCustomerSiteController(FleetCustomerSiteService sites) {
        this.sites = sites;
    }

    @GetMapping("/customer-sites")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) Boolean includeInactive,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return sites.list(search, partnerId, zone, Boolean.TRUE.equals(includeInactive), page, pageSize);
    }

    @GetMapping("/customer-sites/{id}")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return sites.get(id);
    }

    @PostMapping("/customer-sites")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__CREATE)
    public Map<String, Object> create(@Valid @RequestBody SiteInput dto) {
        return sites.create(dto);
    }

    @PatchMapping("/customer-sites/{id}")
    @RequirePermissions(Permissions.FLEET_DISPATCH__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody SitePatch dto) {
        return sites.update(id, dto);
    }

    /** Deletes an unused site; a site with delivery orders is deactivated. */
    @DeleteMapping("/customer-sites/{id}")
    @RequirePermissions(Permissions.FLEET_DISPATCH__UPDATE)
    public Map<String, Object> delete(@PathVariable String id) {
        return sites.delete(id);
    }
}
