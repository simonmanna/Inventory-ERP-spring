package com.inventoryerp.fixedasset.web;

import com.inventoryerp.fixedasset.application.AssetImpairmentService;
import com.inventoryerp.fixedasset.application.AssetImpairmentService.ImpairmentInput;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /fixed-assets/:assetId/impairments}: IAS 36 impairment and reversal (ADR-S073; Nest has no counterpart). */
@RestController
@RequestMapping("/fixed-assets/{assetId}/impairments")
@RequiresModule("fixed-asset")
@ConditionalOnProperty(name = "app.modules.assets", havingValue = "true")
public class AssetImpairmentController {

    private final AssetImpairmentService service;

    public AssetImpairmentController(AssetImpairmentService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public Map<String, Object> summary(@PathVariable String assetId) {
        return service.summary(assetId);
    }

    @PostMapping
    @Idempotent
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__IMPAIR)
    public Map<String, Object> impair(@PathVariable String assetId, @Valid @RequestBody ImpairmentInput dto) {
        return service.impair(assetId, dto);
    }

    @PostMapping("/reversal")
    @Idempotent
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__IMPAIR)
    public Map<String, Object> reverse(@PathVariable String assetId, @Valid @RequestBody ImpairmentInput dto) {
        return service.reverse(assetId, dto);
    }
}
