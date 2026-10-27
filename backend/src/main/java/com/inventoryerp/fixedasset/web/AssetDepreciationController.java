package com.inventoryerp.fixedasset.web;

import com.inventoryerp.fixedasset.application.AssetDepreciationService;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.RunDepreciationRequest;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import java.util.List;
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

/**
 * {@code /fixed-assets/:assetId/depreciation} (port of {@code asset-depreciation.controller.ts}). As in Nest,
 * {@code period/:period} and {@code run} ignore {@code :assetId} (a run targets one asset only via the body).
 */
@RestController
@RequestMapping("/fixed-assets/{assetId}/depreciation")
@RequiresModule("fixed-asset")
@ConditionalOnProperty(name = "app.modules.assets", havingValue = "true")
public class AssetDepreciationController {

    private final AssetDepreciationService service;

    public AssetDepreciationController(AssetDepreciationService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.ASSET_DEPRECIATION__READ)
    public List<Map<String, Object>> list(@PathVariable String assetId) {
        return service.findByAsset(assetId);
    }

    @GetMapping("/period/{period}")
    @RequirePermissions(Permissions.ASSET_DEPRECIATION__READ)
    public List<Map<String, Object>> byPeriod(@PathVariable String period) {
        return service.findByPeriod(period);
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ASSET_DEPRECIATION__RUN)
    public List<Map<String, Object>> run(@Valid @RequestBody RunDepreciationRequest dto) {
        return service.run(dto.period(), dto.postEntries(), dto.assetId());
    }

    @PostMapping("/{id}/post")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ASSET_DEPRECIATION__RUN)
    public Map<String, Object> post(@PathVariable String id) {
        return service.postEntry(id);
    }
}
