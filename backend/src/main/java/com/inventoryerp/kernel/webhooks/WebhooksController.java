package com.inventoryerp.kernel.webhooks;

import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
 * {@code /webhooks/*} (port of {@code webhooks.controller.ts}). Deviation (security, owner-approved 2026-10-31): Nest
 * needed only a session, so any user could send the organization's events to any URL; routes now need the
 * {@code webhooks:*} permissions and targets must be public addresses ({@link WebhookUrlGuard}).
 */
@RestController
// Outbound webhooks are a paid add-on with no module row of its own to switch off.
@RequiresFeature(LicenseFeatures.WEBHOOKS)
@RequestMapping("/webhooks")
public class WebhooksController {

    /** {@code CreateEndpointDto} ({@code @IsUrl({ require_tld: false })}). */
    public record CreateEndpointRequest(
            @NotNull(message = "must be a URL address")
            @Pattern(regexp = "^(https?|ftp)://[^\\s/$.?#][^\\s]*$", message = "must be a URL address") String url,
            List<String> events,
            String description) {}

    /** {@code UpdateEndpointDto}. */
    public record UpdateEndpointRequest(String url, List<String> events, String description, Boolean isActive) {}

    private final WebhooksService svc;

    public WebhooksController(WebhooksService svc) {
        this.svc = svc;
    }

    @GetMapping("/endpoints")
    @RequirePermissions(Permissions.WEBHOOKS__READ)
    public List<Map<String, Object>> list() {
        return svc.listEndpoints();
    }

    @PostMapping("/endpoints")
    @RequirePermissions(Permissions.WEBHOOKS__WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateEndpointRequest dto) {
        return svc.createEndpoint(dto.url(), dto.events(), dto.description());
    }

    /** Only a hint, as in Nest: rotation has its own route. */
    @PatchMapping("/endpoints/{id}")
    @RequirePermissions(Permissions.WEBHOOKS__WRITE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateEndpointRequest dto) {
        return Map.of("ok", true, "note", "Use POST /webhooks/endpoints/:id/rotate to rotate secret");
    }

    @PostMapping("/endpoints/{id}/rotate")
    @RequirePermissions(Permissions.WEBHOOKS__WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> rotate(@PathVariable String id) {
        return svc.rotateSecret(id);
    }

    @DeleteMapping("/endpoints/{id}")
    @RequirePermissions(Permissions.WEBHOOKS__DELETE)
    public Map<String, Object> remove(@PathVariable String id) {
        svc.deleteEndpoint(id);
        return Map.of("ok", true);
    }

    @GetMapping("/deliveries")
    @RequirePermissions(Permissions.WEBHOOKS__READ)
    public List<Map<String, Object>> deliveries(@RequestParam(required = false) String endpointId) {
        return svc.listDeliveries(endpointId);
    }
}
