package com.inventoryerp.core.web;

import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Product attributes &amp; variants (ADR-S058): attribute masters, a product's attribute lines and its variants. */
@RestController
public class ProductVariantController {

    private final ProductVariantService variants;

    public ProductVariantController(ProductVariantService variants) {
        this.variants = variants;
    }

    @GetMapping("/variant-attributes")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public List<Map<String, Object>> listAttributes(@RequestParam Map<String, String> query) {
        return variants.listAttributes(query);
    }

    @PostMapping("/variant-attributes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> createAttribute(@RequestBody Map<String, Object> dto) {
        return variants.createAttribute(dto);
    }

    @PatchMapping("/variant-attributes/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateAttribute(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return variants.updateAttribute(id, dto);
    }

    @DeleteMapping("/variant-attributes/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> deleteAttribute(@PathVariable String id) {
        return variants.deleteAttribute(id);
    }

    @GetMapping("/product-variants")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public List<Map<String, Object>> search(@RequestParam Map<String, String> query) {
        return variants.search(query);
    }

    @GetMapping("/products/{id}/variant-config")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public Map<String, Object> config(@PathVariable String id) {
        return variants.config(id);
    }

    @PutMapping("/products/{id}/attribute-lines")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> saveLines(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return variants.saveLines(id, dto);
    }

    @PatchMapping("/products/{id}/attribute-lines/{lineId}/values")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> saveLineValues(@PathVariable String id, @PathVariable String lineId,
            @RequestBody Map<String, Object> dto) {
        return variants.saveLineValues(id, lineId, dto);
    }

    @GetMapping("/products/{id}/variants")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public List<Map<String, Object>> listVariants(@PathVariable String id, @RequestParam Map<String, String> query) {
        return variants.listVariants(id, query);
    }

    @PatchMapping("/products/{id}/variants/{variantId}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateVariant(@PathVariable String id, @PathVariable String variantId,
            @RequestBody Map<String, Object> dto) {
        return variants.updateVariant(id, variantId, dto);
    }
}
