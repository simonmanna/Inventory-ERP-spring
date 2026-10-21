package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.STR;
import static com.inventoryerp.pos.web.PosDtos.UUID_MSG;
import static com.inventoryerp.pos.web.PosDtos.UUID_RE;

import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosAccompanimentService;
import com.inventoryerp.pos.application.PosMenuService;
import com.inventoryerp.pos.application.PosVariantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/** {@code /pos/menu} (port of {@code pos-menu.controller.ts}). */
@RestController
@RequestMapping("/pos/menu")
public class PosMenuController {

    static final String MIN0 = "must not be less than 0";
    static final String MAX999 = "must not be greater than 999";
    static final String MAX9999 = "must not be greater than 9999";
    static final String MAX_PRICE = "must not be greater than 99999999";

    public record IngredientRequest(
            @NotNull(message = UUID_MSG) @Pattern(regexp = UUID_RE, message = UUID_MSG) String productId,
            @DecimalMin(value = "0.000001", message = "must not be less than 0.000001") Double quantity) {}

    public record CreateMenuItemRequest(
            String code,
            @NotNull(message = STR) String name,
            String description,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String categoryId,
            Double basePrice,
            Boolean isInventoryTracked,
            String image,
            Double preparationTime,
            String stationCode,
            Boolean isAvailable,
            Double displayOrder,
            List<@Valid IngredientRequest> ingredients) {}

    public record UpdateMenuItemRequest(
            String code,
            String name,
            String description,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String categoryId,
            Double basePrice,
            Boolean isInventoryTracked,
            String image,
            Double preparationTime,
            String stationCode,
            Boolean isAvailable,
            Double displayOrder,
            List<@Valid IngredientRequest> ingredients,
            String expectedUpdatedAt) {}

    public record CreateCategoryRequest(
            @NotNull(message = STR) String name,
            @Pattern(regexp = UUID_RE, message = UUID_MSG) String parentId,
            String image,
            String icon,
            Double displayOrder) {}

    public record CreateVariantRequest(
            @NotNull(message = STR) String name,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) Double price,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double qtyMultiplier) {}

    public record UpdateVariantRequest(
            String name,
            @DecimalMin(value = "0", message = MIN0) Double price,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder,
            Boolean isActive,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double qtyMultiplier,
            String expectedUpdatedAt,
            String ipAddress,
            String userAgent) {}

    public record CreateAccompanimentGroupRequest(
            @NotNull(message = STR) String name,
            String category,
            Boolean isRequired,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "999", message = MAX999) Double minSelect,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "999", message = MAX999) Double maxSelect,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder) {}

    public record UpdateAccompanimentGroupRequest(
            String name,
            String category,
            Boolean isRequired,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "999", message = MAX999) Double minSelect,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "999", message = MAX999) Double maxSelect,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder,
            Boolean isActive,
            String expectedUpdatedAt,
            String ipAddress,
            String userAgent) {}

    public record CreateAccompanimentOptionRequest(
            @NotNull(message = STR) String name,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "99999999", message = MAX_PRICE) Double priceImpact,
            Boolean isDefault,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder,
            String inventoryItemId,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "99999999", message = MAX_PRICE)
            Double consumptionQty,
            String consumptionUomId) {}

    public record UpdateAccompanimentOptionRequest(
            String name,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "99999999", message = MAX_PRICE) Double priceImpact,
            Boolean isDefault,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder,
            Boolean isActive,
            String inventoryItemId,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "99999999", message = MAX_PRICE)
            Double consumptionQty,
            String consumptionUomId,
            String expectedUpdatedAt,
            String ipAddress,
            String userAgent) {}

    private final PosMenuService svc;
    private final PosVariantService variantSvc;
    private final PosAccompanimentService accompanimentSvc;

    public PosMenuController(PosMenuService svc, PosVariantService variantSvc,
            PosAccompanimentService accompanimentSvc) {
        this.svc = svc;
        this.variantSvc = variantSvc;
        this.accompanimentSvc = accompanimentSvc;
    }

    static Map<String, Object> withClient(Map<String, Object> dto, HttpServletRequest req) {
        dto.put("ipAddress", req.getRemoteAddr());
        dto.put("userAgent", req.getHeader("user-agent"));
        return dto;
    }

    // ─── Categories ───

    @GetMapping("/categories")
    public List<Map<String, Object>> listCategories() {
        return svc.listCategories();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.MENU_CATEGORIES__CREATE)
    public Map<String, Object> createCategory(@Valid @RequestBody CreateCategoryRequest body) {
        return svc.createCategory(PosDtos.data(body));
    }

    /** {@code Partial<CreateCategoryDto>} is erased at runtime: Nest does not validate this body. */
    @PatchMapping("/categories/{id}")
    @RequirePermissions(Permissions.MENU_CATEGORIES__EDIT)
    public Map<String, Object> updateCategory(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return svc.updateCategory(id, body);
    }

    @DeleteMapping("/categories/{id}")
    @RequirePermissions(Permissions.MENU_CATEGORIES__DELETE)
    public Map<String, Object> deleteCategory(@PathVariable String id) {
        return svc.deleteCategory(id);
    }

    @GetMapping("/categories/deleted")
    @RequirePermissions(Permissions.MENU_CATEGORIES__VIEW)
    public List<Map<String, Object>> listDeletedCategories() {
        return svc.listDeletedCategories();
    }

    @PatchMapping("/categories/{id}/restore")
    @RequirePermissions(Permissions.MENU_CATEGORIES__EDIT)
    public Map<String, Object> restoreCategory(@PathVariable String id) {
        return svc.restoreCategory(id);
    }

    // ─── Items ───

    @GetMapping("/items/available")
    public Map<String, Object> available() {
        return svc.listAvailable();
    }

    @GetMapping("/items")
    public Map<String, Object> list(@RequestParam Map<String, String> query) {
        var page = QueryParams.page(query, "categoryId");
        String categoryId = query.get("categoryId");
        if (categoryId != null && !categoryId.matches(UUID_RE)) {
            throw QueryParams.validation(List.of("categoryId must be a UUID"));
        }
        return svc.listAll(page, categoryId);
    }

    /**
     * Express route shadowing: {@code GET items/:id} is registered before {@code GET items/deleted}, so Nest answers
     * {@code /items/deleted} with {@code getOne('deleted')} (no permission, 404).
     */
    @GetMapping({"/items/{id}", "/items/deleted"})
    public Map<String, Object> getOne(@PathVariable(required = false) String id) {
        return svc.getOne(id == null ? "deleted" : id);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.MENU__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateMenuItemRequest body) {
        return svc.create(PosDtos.data(body));
    }

    @PatchMapping("/items/{id}")
    @RequirePermissions(Permissions.MENU__EDIT)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateMenuItemRequest body) {
        return svc.update(id, PosDtos.data(body));
    }

    /** Untyped body {@code { isAvailable }}. */
    @PatchMapping("/items/{id}/availability")
    @RequirePermissions(Permissions.MENU__EDIT)
    public Map<String, Object> setAvail(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            throw new IllegalStateException("Cannot read properties of undefined (reading 'isAvailable')");
        }
        return svc.setAvailability(id, body);
    }

    @DeleteMapping("/items/{id}")
    @RequirePermissions(Permissions.MENU__DELETE)
    public Map<String, Object> remove(@PathVariable String id) {
        return svc.disable(id);
    }

    @DeleteMapping("/items/{id}/hard")
    @RequirePermissions(Permissions.MENU__DELETE)
    public Map<String, Object> hardDelete(@PathVariable String id) {
        return svc.deleteItem(id);
    }

    @PatchMapping("/items/{id}/restore")
    @RequirePermissions(Permissions.MENU__EDIT)
    public Map<String, Object> restoreItem(@PathVariable String id) {
        return svc.restoreItem(id);
    }

    @GetMapping("/items/{id}/bundle")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> getBundle(@PathVariable String id) {
        return svc.getFullBundle(id);
    }

    // ─── Variants ───

    @GetMapping("/items/{menuItemId}/variants")
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> listVariants(@PathVariable String menuItemId) {
        return variantSvc.listVariants(menuItemId);
    }

    @PostMapping("/items/{menuItemId}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public Map<String, Object> createVariant(@PathVariable String menuItemId,
            @Valid @RequestBody CreateVariantRequest dto) {
        return variantSvc.createVariant(menuItemId, PosDtos.data(dto));
    }

    @PatchMapping("/items/{menuItemId}/variants/{variantId}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateVariant(@PathVariable String variantId, @Valid @RequestBody UpdateVariantRequest dto,
            HttpServletRequest req) {
        return variantSvc.updateVariant(variantId, withClient(PosDtos.data(dto), req));
    }

    @DeleteMapping("/items/{menuItemId}/variants/{variantId}")
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void deleteVariant(@PathVariable String variantId) {
        variantSvc.deleteVariant(variantId);
    }

    // ─── Accompaniment groups ───

    @GetMapping("/accompaniments/groups")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> listAllAccompanimentGroups(@RequestParam(required = false) String search,
            @RequestParam(required = false) String isActive, @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize) {
        return accompanimentSvc.listAllGroups(search, PosDtos.jsBool(isActive), PosDtos.jsParseInt(page),
                PosDtos.jsParseInt(pageSize));
    }

    @GetMapping("/accompaniments/report/sales")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> accompanimentSalesReport(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return accompanimentSvc.accompanimentSalesReport(from, to);
    }

    @PostMapping("/accompaniments/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public Map<String, Object> createAccompanimentGroup(@Valid @RequestBody CreateAccompanimentGroupRequest dto) {
        return accompanimentSvc.createGroup(PosDtos.data(dto));
    }

    @PatchMapping("/accompaniments/groups/{groupId}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateAccompanimentGroup(@PathVariable String groupId,
            @Valid @RequestBody UpdateAccompanimentGroupRequest dto, HttpServletRequest req) {
        return accompanimentSvc.updateGroup(groupId, withClient(PosDtos.data(dto), req));
    }

    @DeleteMapping("/accompaniments/groups/{groupId}")
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void deleteAccompanimentGroup(@PathVariable String groupId) {
        accompanimentSvc.deleteGroup(groupId);
    }

    @PostMapping("/accompaniments/groups/{groupId}/options")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public Map<String, Object> createAccompanimentOption(@PathVariable String groupId,
            @Valid @RequestBody CreateAccompanimentOptionRequest dto) {
        return accompanimentSvc.createOption(groupId, PosDtos.data(dto));
    }

    @PatchMapping("/accompaniments/options/{optionId}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateAccompanimentOption(@PathVariable String optionId,
            @Valid @RequestBody UpdateAccompanimentOptionRequest dto, HttpServletRequest req) {
        return accompanimentSvc.updateOption(optionId, withClient(PosDtos.data(dto), req));
    }

    @DeleteMapping("/accompaniments/options/{optionId}")
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void deleteAccompanimentOption(@PathVariable String optionId) {
        accompanimentSvc.deleteOption(optionId);
    }

    // ─── Per-menu-item assignment ───

    @GetMapping("/items/{menuItemId}/accompaniments")
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> listMenuItemAccompaniments(@PathVariable String menuItemId) {
        return accompanimentSvc.listGroupsForMenuItem(menuItemId);
    }

    /** Untyped body {@code { accompanimentGroupId, sortOrder? }}. */
    @PostMapping("/items/{menuItemId}/accompaniments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public void assignAccompanimentGroup(@PathVariable String menuItemId,
            @RequestBody(required = false) Map<String, Object> dto) {
        if (dto == null) {
            throw new IllegalStateException("Cannot read properties of undefined (reading 'accompanimentGroupId')");
        }
        accompanimentSvc.assignGroupToMenuItem(menuItemId, dto.get("accompanimentGroupId"), dto.get("sortOrder"));
    }

    @DeleteMapping("/items/{menuItemId}/accompaniments/{groupId}")
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void unassignAccompanimentGroup(@PathVariable String menuItemId, @PathVariable String groupId) {
        accompanimentSvc.unassignGroupFromMenuItem(menuItemId, groupId);
    }
}
