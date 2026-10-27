package com.inventoryerp.fixedasset.web;

import com.inventoryerp.fixedasset.application.AssetService;
import com.inventoryerp.fixedasset.application.AssetService.AssetQuery;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.CreateAssetRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.UpdateAssetRequest;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/** {@code /fixed-assets} (port of {@code asset.controller.ts}). */
@RestController
@RequestMapping("/fixed-assets")
@RequiresModule("fixed-asset")
@ConditionalOnProperty(name = "app.modules.assets", havingValue = "true")
public class AssetController {

    private final AssetService service;

    public AssetController(AssetService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        PageQuery p = QueryParams.page(query, "categoryId", "status", "branchId", "department", "supplierId",
                "location");
        String status = query.get("status");
        if (status != null && !Arrays.asList(FixedAssetDtos.ASSET_STATUSES).contains(status)) {
            throw QueryParams.validation(List.of("status must be one of the following values: "
                    + String.join(", ", FixedAssetDtos.ASSET_STATUSES)));
        }
        return service.list(new AssetQuery(p.page() == null ? 1 : p.page(), p.pageSize() == null ? 25 : p.pageSize(),
                p.search(), p.sortBy(), p.sortOrder(), query.get("categoryId"), status, query.get("branchId"),
                query.get("department"), query.get("supplierId"), query.get("location")));
    }

    @GetMapping("/search")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public List<Map<String, Object>> search(@RequestParam(required = false) String q) {
        return service.search(q);
    }

    @GetMapping("/by-code/{code}")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public Map<String, Object> findByCode(@PathVariable String code) {
        return service.findByCode(code);
    }

    @GetMapping("/by-barcode/{barcode}")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public Map<String, Object> findByBarcode(@PathVariable String barcode) {
        return service.findByBarcode(barcode);
    }

    @GetMapping("/dashboard")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public Map<String, Object> dashboard() {
        return service.getDashboard();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.FIXED_ASSET__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return service.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FIXED_ASSET__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateAssetRequest dto) {
        return service.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.FIXED_ASSET__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateAssetRequest dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.FIXED_ASSET__DELETE)
    public void remove(@PathVariable String id) {
        service.remove(id);
    }

    @PatchMapping("/{id}/restore")
    @RequirePermissions(Permissions.FIXED_ASSET__UPDATE)
    public Map<String, Object> restore(@PathVariable String id) {
        return service.restore(id);
    }
}
