package com.inventoryerp.core.web;

import com.inventoryerp.core.application.ProductSpecService;
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

/** {@code /product-grades}, {@code /product-attributes}, {@code /product-specifications} (INV-2). */
@RestController
public class ProductSpecController {

    private final ProductSpecService specs;

    public ProductSpecController(ProductSpecService specs) {
        this.specs = specs;
    }

    @GetMapping("/product-grades")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public List<Map<String, Object>> listGrades(@RequestParam Map<String, String> query) {
        return specs.listGrades(query);
    }

    @PostMapping("/product-grades")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> createGrade(@RequestBody Map<String, Object> dto) {
        return specs.createGrade(dto);
    }

    @PatchMapping("/product-grades/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateGrade(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return specs.updateGrade(id, dto);
    }

    @DeleteMapping("/product-grades/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> deleteGrade(@PathVariable String id) {
        return specs.deleteGrade(id);
    }

    @GetMapping("/product-attributes")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public List<Map<String, Object>> listAttributes(@RequestParam Map<String, String> query) {
        return specs.listAttributes(query);
    }

    @PostMapping("/product-attributes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> createAttribute(@RequestBody Map<String, Object> dto) {
        return specs.createAttribute(dto);
    }

    @PatchMapping("/product-attributes/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateAttribute(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return specs.updateAttribute(id, dto);
    }

    @DeleteMapping("/product-attributes/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> deleteAttribute(@PathVariable String id) {
        return specs.deleteAttribute(id);
    }

    @GetMapping("/product-specifications")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public List<Map<String, Object>> listSpecifications(@RequestParam Map<String, String> query) {
        return specs.listSpecifications(query);
    }

    @GetMapping("/product-specifications/{productId}")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public Map<String, Object> getSpecification(@PathVariable String productId) {
        return specs.getSpecification(productId);
    }

    @PutMapping("/product-specifications/{productId}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> saveSpecification(@PathVariable String productId, @RequestBody Map<String, Object> dto) {
        return specs.saveSpecification(productId, dto);
    }
}
