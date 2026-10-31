package com.inventoryerp.invoicing.web;

import com.inventoryerp.invoicing.application.MobileMoneyService;
import com.inventoryerp.invoicing.application.MobileMoneyService.CollectionInput;
import com.inventoryerp.invoicing.application.MobileMoneyService.CollectionQuery;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mobile money collections (INT-1): request a payment from a customer's phone, follow it, and the provider callback.
 * The callback is public because a provider cannot present a JWT; its authenticity is the HMAC in
 * {@code X-Signature}.
 */
@RestController
@RequestMapping("/mobile-money")
@Validated
public class MobileMoneyController {

    private final MobileMoneyService collections;

    public MobileMoneyController(MobileMoneyService collections) {
        this.collections = collections;
    }

    @GetMapping("/settings")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public Map<String, Object> settings() {
        return collections.settings();
    }

    @GetMapping("/collections")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) @OneOf({"pending", "successful", "failed"}) String status,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return collections.list(new CollectionQuery(status, partnerId, page, pageSize));
    }

    @GetMapping("/collections/{id}")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return collections.get(id);
    }

    @PostMapping("/collections")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PAYMENT__CREATE)
    public Map<String, Object> collect(@Valid @RequestBody CollectionInput body) {
        return collections.collect(body);
    }

    @PostMapping("/collections/{id}/refresh")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PAYMENT__CREATE)
    public Map<String, Object> refresh(@PathVariable String id) {
        return collections.refresh(id);
    }

    /** Raw body: the signature covers the bytes as sent. */
    @PostMapping("/callbacks/{provider}/{organizationId}")
    @Public
    public Map<String, Object> callback(@PathVariable String provider, @PathVariable String organizationId,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String body) {
        collections.callback(provider, organizationId, body, signature);
        return Map.of("ok", true);
    }
}
