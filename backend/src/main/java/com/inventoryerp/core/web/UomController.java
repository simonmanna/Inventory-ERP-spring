package com.inventoryerp.core.web;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.core.application.UomService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
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

/** {@code /uoms} (port of {@code uom.controller.ts}). */
@RestController
@RequestMapping("/uoms")
public class UomController {

    /** {@code CreateUomDto}. */
    public record CreateUomRequest(
            @NotNull(message = "must be a string") String code,
            @NotNull(message = "must be a string") String name,
            String symbol,
            String categoryId,
            String category,
            BigDecimal factor,
            BigDecimal ratio,
            BigDecimal roundingPrecision,
            @OneOf({"bigger", "reference", "smaller"}) String uomType,
            Boolean isBase) {}

    /** {@code UpdateUomDto}. */
    public record UpdateUomRequest(
            String name,
            String symbol,
            String categoryId,
            String category,
            BigDecimal factor,
            BigDecimal ratio,
            BigDecimal roundingPrecision,
            @OneOf({"bigger", "reference", "smaller"}) String uomType,
            Boolean isBase,
            Boolean isActive) {}

    /** {@code ConvertUomDto}. */
    public record ConvertUomRequest(
            @NotNull(message = "must be a number conforming to the specified constraints") BigDecimal quantity,
            @NotBlank(message = "should not be empty") String fromUomId,
            @NotBlank(message = "should not be empty") String toUomId,
            String productId) {}

    private final UomService uoms;
    private final UomConversionService conversion;

    public UomController(UomService uoms, UomConversionService conversion) {
        this.uoms = uoms;
        this.conversion = conversion;
    }

    @PostMapping("/convert")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.UOM__READ)
    public Map<String, Object> convert(@Valid @RequestBody ConvertUomRequest dto) {
        BigDecimal result = conversion.convert(dto.quantity(), dto.fromUomId(), dto.toUomId(), dto.productId());
        return Map.of("result", result);
    }

    @GetMapping
    @RequirePermissions(Permissions.UOM__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return uoms.list(QueryParams.page(query));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.UOM__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return uoms.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.UOM__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateUomRequest dto) {
        return uoms.create(dto);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.UOM__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateUomRequest dto) {
        return uoms.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.UOM__DELETE)
    public void remove(@PathVariable String id) {
        uoms.remove(id);
    }
}
