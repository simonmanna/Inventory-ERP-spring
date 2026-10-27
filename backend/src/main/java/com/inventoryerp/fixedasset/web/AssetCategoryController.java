package com.inventoryerp.fixedasset.web;

import com.inventoryerp.fixedasset.application.AssetCategoryService;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.CreateCategoryRequest;
import com.inventoryerp.fixedasset.web.FixedAssetDtos.UpdateCategoryRequest;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
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

/** {@code /asset-categories} (port of {@code asset-category.controller.ts}). */
@RestController
@RequestMapping("/asset-categories")
@RequiresModule("fixed-asset")
@ConditionalOnProperty(name = "app.modules.assets", havingValue = "true")
public class AssetCategoryController {

    private final AssetCategoryService service;

    public AssetCategoryController(AssetCategoryService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermissions(Permissions.ASSET_CATEGORY__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return service.list(QueryParams.page(query));
    }

    @GetMapping("/all")
    @RequirePermissions(Permissions.ASSET_CATEGORY__READ)
    public List<Map<String, Object>> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.ASSET_CATEGORY__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return service.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ASSET_CATEGORY__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateCategoryRequest dto) {
        return service.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.ASSET_CATEGORY__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateCategoryRequest dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.ASSET_CATEGORY__DELETE)
    public void remove(@PathVariable String id) {
        service.remove(id);
    }
}
