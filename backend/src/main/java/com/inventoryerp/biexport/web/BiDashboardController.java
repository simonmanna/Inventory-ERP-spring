package com.inventoryerp.biexport.web;

import com.inventoryerp.biexport.application.SupersetEmbedService;
import com.inventoryerp.biexport.application.SupersetEmbedService.GuestToken;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /bi/dashboards}: embedded Superset dashboards, one per module (ADR-S078). Every route needs
 * {@code analytics:view}; each dashboard also needs its module's permission ({@code analytics:sales}, {@code pos:reports}, ...), checked by
 * the service.
 */
@RestController
@RequestMapping("/bi/dashboards")
public class BiDashboardController {

    private final SupersetEmbedService embeds;

    public BiDashboardController(SupersetEmbedService embeds) {
        this.embeds = embeds;
    }

    /** The dashboards this user may open; empty when Superset is not configured. */
    @GetMapping
    @RequirePermissions(Permissions.ANALYTICS__VIEW)
    public List<Map<String, Object>> dashboards() {
        return embeds.dashboards();
    }

    /** A short-lived guest token for one module's dashboard, limited to the caller's organization. */
    @GetMapping("/{module}/guest-token")
    @RequirePermissions(Permissions.ANALYTICS__VIEW)
    public ResponseEntity<GuestToken> guestToken(@PathVariable String module) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(embeds.guestToken(module));
    }
}
