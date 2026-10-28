package com.inventoryerp.core.web;

import com.inventoryerp.core.application.ProductService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
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

/** {@code /products} (port of {@code product.controller.ts}). */
@RestController
@RequestMapping("/products")
public class ProductController {

    /** {@code PackagingDto}. */
    public record PackagingRequest(
            String id,
            @NotNull(message = "must be a string") String name,
            @NotNull(message = "must be a number conforming to the specified constraints")
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal quantity,
            String barcode,
            Boolean isActive) {}

    /** One extra product unit (ADR-S003): {@code factor} base units per 1 of {@code uomId}. */
    public record ProductUnitRequest(
            @NotBlank(message = "should not be empty") String uomId,
            @NotNull(message = "must be a number conforming to the specified constraints")
            @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0") BigDecimal factor,
            Boolean isPurchase,
            Boolean isSales,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal salePrice,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal purchasePrice,
            Boolean isActive) {}

    /** {@code CreateProductDto}. */
    public record CreateProductRequest(
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String code,
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String name,
            String sku,
            String description,
            @OneOf({"stockable", "consumable", "service", "fee", "subscription", "asset"}) String productType,
            String categoryId,
            String uomId,
            String purchaseUomId,
            String salesUomId,
            String recipeUomId,
            String productionUomId,
            BigDecimal uomConversion,
            BigDecimal reorderQty,
            String station,
            Boolean allowFractionalSale,
            BigDecimal minSaleQty,
            BigDecimal maxSaleQty,
            List<@Valid PackagingRequest> packagings,
            List<@Valid ProductUnitRequest> units,
            String taxId,
            BigDecimal salesPrice,
            BigDecimal costPrice,
            Boolean trackInventory,
            Boolean isActive,
            @OneOf({"AVCO", "FIFO", "STANDARD", "SPECIFIC"}) String costingMethod,
            Boolean batchTracking,
            Boolean expiryTracking,
            Boolean serialTracking,
            @OneOf({"FEFO", "FIFO", "MANUAL", "SERIAL"}) String pickingStrategy,
            String image,
            Map<String, Object> customFields,
            @OneOf({"count", "manual_volume", "digital_weight"}) String measurementMethod,
            BigDecimal containerVolumeMl,
            @DecimalMin(value = "0", message = "weight must not be less than 0") BigDecimal weight,
            @DecimalMin(value = "0", message = "volume must not be less than 0") BigDecimal volume,
            BigDecimal emptyBottleWeightG,
            BigDecimal actualEmptyWeightG,
            BigDecimal fullBottleWeightG,
            BigDecimal standardPourMl,
            @DecimalMin(value = "0", inclusive = false, message = "must be a positive number") BigDecimal doublePourMl,
            Boolean allowPartialBottle,
            BigDecimal varianceToleranceG) {}

    /** {@code UpdateProductDto}. */
    public record UpdateProductRequest(
            String code,
            String name,
            String sku,
            String description,
            @OneOf({"stockable", "consumable", "service", "fee", "subscription", "asset"}) String productType,
            String categoryId,
            String uomId,
            String purchaseUomId,
            String salesUomId,
            String recipeUomId,
            String productionUomId,
            BigDecimal uomConversion,
            BigDecimal reorderQty,
            String station,
            Boolean allowFractionalSale,
            BigDecimal minSaleQty,
            BigDecimal maxSaleQty,
            List<@Valid PackagingRequest> packagings,
            List<@Valid ProductUnitRequest> units,
            String taxId,
            BigDecimal salesPrice,
            BigDecimal costPrice,
            Boolean trackInventory,
            Boolean isActive,
            @OneOf({"AVCO", "FIFO", "STANDARD", "SPECIFIC"}) String costingMethod,
            Boolean batchTracking,
            Boolean expiryTracking,
            Boolean serialTracking,
            @OneOf({"FEFO", "FIFO", "MANUAL", "SERIAL"}) String pickingStrategy,
            String image,
            Map<String, Object> customFields,
            @OneOf({"count", "manual_volume", "digital_weight"}) String measurementMethod,
            BigDecimal containerVolumeMl,
            @DecimalMin(value = "0", message = "weight must not be less than 0") BigDecimal weight,
            @DecimalMin(value = "0", message = "volume must not be less than 0") BigDecimal volume,
            BigDecimal emptyBottleWeightG,
            BigDecimal actualEmptyWeightG,
            BigDecimal fullBottleWeightG,
            BigDecimal standardPourMl,
            @DecimalMin(value = "0", inclusive = false, message = "must be a positive number") BigDecimal doublePourMl,
            Boolean allowPartialBottle,
            BigDecimal varianceToleranceG) {}

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    @RequirePermissions(Permissions.PRODUCT__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return products.list(QueryParams.page(query, "categoryId", "productType"), query.get("categoryId"),
                query.get("productType"));
    }

    @GetMapping("/search")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public List<Map<String, Object>> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String pageSize) {
        int size = pageSize == null || pageSize.isEmpty() ? 20 : (int) JsNumbers.number(pageSize);
        return products.search(q, size);
    }

    /** Resolve a scanned barcode to a product + base-unit quantity; {@code null} when not found. */
    @GetMapping("/resolve-barcode")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public Map<String, Object> resolveBarcode(@RequestParam(required = false) String code) {
        return products.resolveBarcode(code);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return products.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateProductRequest dto) {
        return products.create(dto, packagings(dto.packagings()), units(dto.units()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateProductRequest dto) {
        return products.update(id, dto, packagings(dto.packagings()), units(dto.units()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void remove(@PathVariable String id) {
        products.remove(id);
    }

    @PatchMapping("/{id}/restore")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> restore(@PathVariable String id) {
        return products.restore(id);
    }

    private static List<ProductService.Unit> units(List<ProductUnitRequest> rows) {
        return rows == null ? null : rows.stream()
                .map(r -> new ProductService.Unit(r.uomId(), r.factor(), r.isPurchase(), r.isSales(), r.salePrice(),
                        r.purchasePrice(), r.isActive()))
                .toList();
    }

    private static List<ProductService.Packaging> packagings(List<PackagingRequest> rows) {
        return rows == null ? null : rows.stream()
                .map(r -> new ProductService.Packaging(r.id(), r.name(), r.quantity(), r.barcode(), r.isActive()))
                .toList();
    }
}
