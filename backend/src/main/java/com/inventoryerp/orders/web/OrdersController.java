package com.inventoryerp.orders.web;

import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.orders.application.OrdersService;
import com.inventoryerp.orders.application.OrdersService.ListFilter;
import com.inventoryerp.orders.web.OrdersDtos.AddOrderItemsRequest;
import com.inventoryerp.orders.web.OrdersDtos.BillOrderRequest;
import com.inventoryerp.orders.web.OrdersDtos.CancelOrderRequest;
import com.inventoryerp.orders.web.OrdersDtos.CreateOrderRequest;
import com.inventoryerp.orders.web.OrdersDtos.SaveOrderItemsRequest;
import com.inventoryerp.orders.web.OrdersDtos.UpdateOrderHeaderRequest;
import com.inventoryerp.orders.web.OrdersDtos.UpdateOrderSettingsRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /orders} (port of {@code orders.controller.ts}): back-office orders over the shared {@code Order} aggregate.
 * Registered only with {@code ENABLE_ORDERS=true} (Nest imports {@code OrdersModule} conditionally); no
 * {@code @RequiresModule}. Cancel/reopen also pass the shared {@code order} workflow guards ({@code pos:checkout} /
 * {@code pos:override}).
 */
@RestController
@RequestMapping("/orders")
@ConditionalOnProperty(name = "app.modules.orders", havingValue = "true")
public class OrdersController {

    private final OrdersService orders;

    public OrdersController(OrdersService orders) {
        this.orders = orders;
    }

    @GetMapping("/settings")
    @RequirePermissions(Permissions.ORDERS__READ)
    public Map<String, Object> getSettings() {
        return orders.getOrderSettings();
    }

    @PatchMapping("/settings")
    @RequirePermissions(Permissions.ORDERS__UPDATE)
    public Map<String, Object> updateSettings(@Valid @RequestBody UpdateOrderSettingsRequest dto) {
        return orders.updateOrderSettings(EntityPatch.deepData(dto));
    }

    @GetMapping
    @RequirePermissions(Permissions.ORDERS__READ)
    public Map<String, Object> list(@RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize, @RequestParam(required = false) String search,
            @RequestParam(required = false) String status, @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String transactionKind, @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return orders.list(new ListFilter(parseInt(page, 1), parseInt(pageSize, 25), search, status, orderType,
                transactionKind, dateFrom, dateTo));
    }

    /** Nest {@code ParseIntPipe} after {@code DefaultValuePipe(def)}: {@link QueryParams#parseInt}. */
    private static int parseInt(String raw, int def) {
        return QueryParams.parseInt(raw, def);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.ORDERS__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return orders.getOrder(id);
    }

    @PostMapping
    @Idempotent
    @RequirePermissions(Permissions.ORDERS__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateOrderRequest dto) {
        return orders.createOrder(EntityPatch.deepData(dto));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.ORDERS__UPDATE)
    public Map<String, Object> updateHeader(@PathVariable String id, @Valid @RequestBody UpdateOrderHeaderRequest dto) {
        return orders.updateHeader(id, EntityPatch.deepData(dto));
    }

    @PutMapping("/{id}/items")
    @RequirePermissions(Permissions.ORDERS__UPDATE)
    public Map<String, Object> saveItems(@PathVariable String id, @Valid @RequestBody SaveOrderItemsRequest dto) {
        return orders.saveItems(id, EntityPatch.deepData(dto));
    }

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ORDERS__UPDATE)
    public Map<String, Object> addItems(@PathVariable String id, @Valid @RequestBody AddOrderItemsRequest dto) {
        return orders.addItems(id, EntityPatch.deepData(dto));
    }

    @PostMapping("/{id}/cancel")
    @RequirePermissions(Permissions.ORDERS__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody CancelOrderRequest dto) {
        return orders.cancelOrder(id, dto.reason());
    }

    @PostMapping("/{id}/reopen")
    @RequirePermissions(Permissions.ORDERS__UPDATE)
    public Map<String, Object> reopen(@PathVariable String id) {
        return orders.reopenOrder(id);
    }

    @PostMapping("/{id}/invoice")
    @Idempotent
    @RequirePermissions(Permissions.ORDERS__INVOICE)
    public Map<String, Object> generateInvoice(@PathVariable String id, @Valid @RequestBody BillOrderRequest dto) {
        return orders.generateInvoice(id, EntityPatch.deepData(dto));
    }
}
