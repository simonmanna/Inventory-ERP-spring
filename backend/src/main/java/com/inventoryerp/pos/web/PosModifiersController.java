package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.ARR;
import static com.inventoryerp.pos.web.PosDtos.MIN1;
import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.STR;
import static com.inventoryerp.pos.web.PosMenuController.MAX999;
import static com.inventoryerp.pos.web.PosMenuController.MAX9999;
import static com.inventoryerp.pos.web.PosMenuController.MAX_PRICE;
import static com.inventoryerp.pos.web.PosMenuController.MIN0;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosModifiersService;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/** {@code /pos/modifiers} (port of {@code pos-modifiers.controller.ts}). */
@RestController
@RequestMapping("/pos/modifiers")
public class PosModifiersController {

    public record CreateGroupRequest(
            @NotNull(message = STR) String name,
            String category,
            String description,
            String color,
            String icon,
            @OneOf({"ADD_ON", "MODIFIER"}) String groupType,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "999", message = MAX999) Double minSelect,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "999", message = MAX999) Double maxSelect,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder) {}

    public record CreateModifierRequest(
            @NotNull(message = STR) String groupId,
            @NotNull(message = STR) String name,
            String kitchenPrintName,
            String description,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "99999999", message = MAX_PRICE) Double priceDelta,
            Object isDefault,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder,
            String inventoryItemId,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "99999999", message = MAX_PRICE)
            Double consumptionQty,
            String consumptionUomId) {}

    public record ComboItemRequest(
            @NotNull(message = STR) String productId,
            @NotNull(message = NUM) @DecimalMin(value = "1", message = "must not be less than 1")
            @DecimalMax(value = "9999", message = MAX9999) Double quantity) {}

    public record CreateComboRequest(
            @NotNull(message = STR) String name,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0)
            @DecimalMax(value = "99999999", message = MAX_PRICE) Double price,
            String description,
            String imageUrl,
            @NotNull(message = ARR) @Size(min = 1, message = MIN1) List<@Valid ComboItemRequest> items) {}

    public record UpdateComboRequest(
            String name,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "99999999", message = MAX_PRICE) Double price,
            String description,
            String imageUrl,
            @Size(min = 1, message = MIN1) List<@Valid ComboItemRequest> items) {}

    public record AssignGroupRequest(@NotNull(message = STR) String modifierGroupId, Double sortOrder) {}

    public record UpdateGroupRequest(
            String name,
            String category,
            String description,
            String color,
            String icon,
            @OneOf({"ADD_ON", "MODIFIER"}) String groupType,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "999", message = MAX999) Double minSelect,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "999", message = MAX999) Double maxSelect,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder,
            Object isActive,
            Double expectedVersion,
            String ipAddress,
            String userAgent) {}

    public record UpdateModifierRequest(
            String name,
            String kitchenPrintName,
            String description,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "99999999", message = MAX_PRICE) Double priceDelta,
            Object isDefault,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9999", message = MAX9999) Double sortOrder,
            Object isActive,
            String inventoryItemId,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "99999999", message = MAX_PRICE)
            Double consumptionQty,
            String consumptionUomId,
            String expectedUpdatedAt,
            String ipAddress,
            String userAgent) {}

    private final PosModifiersService svc;

    public PosModifiersController(PosModifiersService svc) {
        this.svc = svc;
    }

    @GetMapping("/groups")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> listGroups(@RequestParam(required = false) String search,
            @RequestParam(required = false) String isActive, @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize) {
        return svc.listGroups(search, PosDtos.jsBool(isActive), PosDtos.jsParseInt(page), PosDtos.jsParseInt(pageSize));
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public Map<String, Object> createGroup(@Valid @RequestBody CreateGroupRequest dto) {
        return svc.createGroup(PosDtos.data(dto));
    }

    @PostMapping("/modifiers")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public Map<String, Object> createModifier(@Valid @RequestBody CreateModifierRequest dto) {
        return svc.createModifier(PosDtos.data(dto));
    }

    @GetMapping("/combos")
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> listCombos() {
        return svc.listCombos();
    }

    @GetMapping("/combos/{id}")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> getCombo(@PathVariable String id) {
        return svc.getCombo(id);
    }

    @PostMapping("/combos")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public Map<String, Object> createCombo(@Valid @RequestBody CreateComboRequest dto) {
        return svc.createCombo(PosDtos.data(dto));
    }

    @PatchMapping("/combos/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateCombo(@PathVariable String id, @Valid @RequestBody UpdateComboRequest dto,
            HttpServletRequest req) {
        Map<String, Object> d = PosDtos.data(dto);
        d.put("ipAddress", req.getRemoteAddr());
        return svc.updateCombo(id, d);
    }

    @DeleteMapping("/combos/{id}")
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void deleteCombo(@PathVariable String id) {
        svc.deleteCombo(id);
    }

    @GetMapping("/products/{productId}/bundle")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> getProductBundle(@PathVariable String productId) {
        return svc.getProductBundle(productId);
    }

    @PostMapping("/products/{productId}/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public void assignGroup(@PathVariable String productId, @Valid @RequestBody AssignGroupRequest dto) {
        svc.assignGroupToProduct(productId, dto.modifierGroupId(), dto.sortOrder());
    }

    @PatchMapping("/groups/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateGroup(@PathVariable String id, @Valid @RequestBody UpdateGroupRequest dto,
            HttpServletRequest req) {
        Map<String, Object> d = PosDtos.data(dto);
        d.put("ipAddress", req.getRemoteAddr());
        return svc.updateGroup(id, d);
    }

    @DeleteMapping("/groups/{id}")
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void deleteGroup(@PathVariable String id) {
        svc.deleteGroup(id);
    }

    @PatchMapping("/modifiers/{id}")
    @RequirePermissions(Permissions.PRODUCT__UPDATE)
    public Map<String, Object> updateModifier(@PathVariable String id, @Valid @RequestBody UpdateModifierRequest dto,
            HttpServletRequest req) {
        Map<String, Object> d = PosDtos.data(dto);
        d.put("ipAddress", req.getRemoteAddr());
        return svc.updateModifier(id, d);
    }

    @DeleteMapping("/modifiers/{id}")
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void deleteModifier(@PathVariable String id) {
        svc.deleteModifier(id);
    }

    @DeleteMapping("/products/{productId}/groups/{groupId}")
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void unassignGroup(@PathVariable String productId, @PathVariable String groupId) {
        svc.unassignGroupFromProduct(productId, groupId);
    }

    @GetMapping("/report/sales")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> modifierSalesReport(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return svc.modifierSalesReport(from, to);
    }

    @GetMapping("/groups/{id}/menu-items")
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> getGroupMenuItems(@PathVariable String id) {
        return svc.getGroupMenuItems(id);
    }

    @GetMapping("/menu-items/{menuItemId}/bundle")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> getMenuItemBundle(@PathVariable String menuItemId) {
        return svc.getMenuItemBundle(menuItemId);
    }

    @PostMapping("/menu-items/{menuItemId}/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRODUCT__CREATE)
    public void assignGroupToMenuItem(@PathVariable String menuItemId, @Valid @RequestBody AssignGroupRequest dto) {
        svc.assignGroupToMenuItem(menuItemId, dto.modifierGroupId(), dto.sortOrder());
    }

    @DeleteMapping("/menu-items/{menuItemId}/groups/{groupId}")
    @RequirePermissions(Permissions.PRODUCT__DELETE)
    public void unassignGroupFromMenuItem(@PathVariable String menuItemId, @PathVariable String groupId) {
        svc.unassignGroupFromMenuItem(menuItemId, groupId);
    }
}
