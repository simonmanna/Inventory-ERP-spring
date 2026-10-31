package com.inventoryerp.core.web;

import com.inventoryerp.core.application.ProductUnitProfileService;
import com.inventoryerp.core.application.ProductUnitProfileService.ProfileInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /products/:id/unit-profile}, {@code /unit-equivalents} (UOM-1): density, weights and unit equivalents. */
@RestController
@RequestMapping("/products/{id}")
public class ProductUnitProfileController {

    /** Null leaves a fact unchanged, 0 removes it. */
    public record UnitProfileRequest(
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal densityTonnesPerM3,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal kgPerPiece,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal kgPerBag,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal tonnesPerTruckload) {}

    private final ProductUnitProfileService profiles;

    public ProductUnitProfileController(ProductUnitProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/unit-profile")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return profiles.get(id);
    }

    @PutMapping("/unit-profile")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> save(@PathVariable String id, @Valid @RequestBody UnitProfileRequest dto) {
        return profiles.save(id, new ProfileInput(dto.densityTonnesPerM3(), dto.kgPerPiece(), dto.kgPerBag(),
                dto.tonnesPerTruckload()));
    }

    @GetMapping("/unit-equivalents")
    @RequirePermissions(Permissions.PRODUCT__READ)
    public Map<String, Object> equivalents(@PathVariable String id,
            @RequestParam(required = false) BigDecimal quantity, @RequestParam(required = false) String uomId) {
        return profiles.equivalents(id, quantity, uomId);
    }
}
