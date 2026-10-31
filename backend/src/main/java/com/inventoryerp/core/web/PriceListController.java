package com.inventoryerp.core.web;

import com.inventoryerp.core.application.PriceListService;
import com.inventoryerp.core.application.PriceListService.ItemInput;
import com.inventoryerp.core.application.PriceListService.ListInput;
import com.inventoryerp.core.application.PriceListService.ListPrice;
import com.inventoryerp.core.application.PriceListService.PriceQuery;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /price-lists} (SALES-3): named lists, their lines and customers, and a price lookup. */
@RestController
@RequestMapping("/price-lists")
public class PriceListController {

    public record ItemRequest(
            String productId,
            String productCategoryId,
            String uomId,
            String siteId,
            String zone,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal minQuantity,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal unitPrice,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal discountPercent) {}

    public record SaveRequest(
            @NotBlank(message = "should not be empty") String code,
            @NotBlank(message = "should not be empty") String name,
            @OneOf({"retail", "wholesale", "contract", "negotiated"}) String tier,
            Boolean isDefault,
            String branchId,
            LocalDate validFrom,
            LocalDate validTo,
            Integer priority,
            Boolean isActive,
            String notes,
            List<@Valid ItemRequest> items,
            List<String> partnerIds,
            List<String> partnerCategoryIds) {

        ListInput toInput() {
            List<ItemInput> lines = items == null ? List.of() : items.stream().map(i -> new ItemInput(i.productId(),
                    i.productCategoryId(), i.uomId(), i.siteId(), i.zone(), i.minQuantity(), i.unitPrice(),
                    i.discountPercent())).toList();
            return new ListInput(code, name, tier, isDefault, branchId, validFrom, validTo, priority, isActive, notes,
                    lines, partnerIds, partnerCategoryIds);
        }
    }

    public record ResolveRequest(
            String partnerId,
            @NotBlank(message = "should not be empty") String productId,
            BigDecimal quantity,
            String uomId,
            String branchId,
            String siteId,
            LocalDate date) {}

    private final PriceListService lists;

    public PriceListController(PriceListService lists) {
        this.lists = lists;
    }

    @GetMapping
    @RequirePermissions(Permissions.PRICE_LIST__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String tier, @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String page, @RequestParam(required = false) String pageSize) {
        return lists.list(search, tier, active, QueryParams.parseInt(page, 1), QueryParams.parseInt(pageSize, 25));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PRICE_LIST__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return lists.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRICE_LIST__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody SaveRequest dto) {
        return lists.create(dto.toInput());
    }

    @PutMapping("/{id}")
    @RequirePermissions(Permissions.PRICE_LIST__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody SaveRequest dto) {
        return lists.update(id, dto.toInput());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.PRICE_LIST__MANAGE)
    public void delete(@PathVariable String id) {
        lists.delete(id);
    }

    @PostMapping("/resolve")
    @RequirePermissions(Permissions.PRICE_LIST__READ)
    public Map<String, Object> resolve(@Valid @RequestBody ResolveRequest dto) {
        ListPrice p = lists.resolve(new PriceQuery(dto.partnerId(), dto.productId(), dto.quantity(), dto.uomId(),
                dto.branchId(), dto.siteId(), dto.date()));
        if (p == null) {
            return Obj.of("matched", false);
        }
        return Obj.of("matched", true, "unitPrice", p.unitPrice() == null ? null : JsNumbers.js(p.unitPrice()),
                "discountPercent", JsNumbers.js(p.discountPercent()), "priceListId", p.priceListId(), "priceListName",
                p.priceListName());
    }
}
