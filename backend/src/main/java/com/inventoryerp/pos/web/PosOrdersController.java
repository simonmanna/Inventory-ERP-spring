package com.inventoryerp.pos.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.pos.application.PosOrdersService;
import com.inventoryerp.pos.web.PosOrderDtos.AddOrderItemsRequest;
import com.inventoryerp.pos.web.PosOrderDtos.CancelOrderRequest;
import com.inventoryerp.pos.web.PosOrderDtos.CreateOrderRequest;
import com.inventoryerp.pos.web.PosOrderDtos.FireKitchenRequest;
import com.inventoryerp.pos.web.PosOrderDtos.GenerateInvoiceRequest;
import com.inventoryerp.pos.web.PosOrderDtos.MergeOrderRequest;
import com.inventoryerp.pos.web.PosOrderDtos.MoveTableRequest;
import com.inventoryerp.pos.web.PosOrderDtos.QuoteOrderRequest;
import com.inventoryerp.pos.web.PosOrderDtos.SaveOrderItemsRequest;
import com.inventoryerp.pos.web.PosOrderDtos.VoidOrderItemRequest;
import jakarta.validation.Valid;
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

/** {@code /pos/orders} (port of {@code PosOrdersController} in {@code order/pos-orders.controller.ts}). */
@RestController
@RequestMapping("/pos/orders")
public class PosOrdersController {

    private final PosOrdersService orders;
    private final PosInvoiceService billing;

    public PosOrdersController(PosOrdersService orders, PosInvoiceService billing) {
        this.orders = orders;
        this.billing = billing;
    }

    @GetMapping
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String tableId, @RequestParam(required = false) String cashSessionId) {
        return orders.list(status, tableId, cashSessionId);
    }

    @GetMapping("/by-table/{tableId}")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> byTable(@PathVariable String tableId) {
        return orders.getOpenOrderForTable(tableId);
    }

    @GetMapping("/open")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> listOpen(@RequestParam(required = false) String orderType,
            @RequestParam(required = false) String cashSessionId, @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String search) {
        return orders.listOpenOrders(orderType, cashSessionId, branchId, search);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return orders.getOrder(id);
    }

    @GetMapping("/{id}/milestones")
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> milestones(@PathVariable String id) {
        return orders.getMilestones(id);
    }

    @PostMapping("/quote")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> quote(@Valid @RequestBody QuoteOrderRequest dto) {
        return orders.quote(PosDtos.data(dto));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> create(@Valid @RequestBody CreateOrderRequest dto) {
        Map<String, Object> order = orders.createOrder(PosDtos.data(dto));
        orders.autoSendRoutedLines((String) order.get("id"));
        return order;
    }

    @PutMapping("/{id}/items")
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> saveItems(@PathVariable String id, @Valid @RequestBody SaveOrderItemsRequest dto) {
        return orders.saveItems(id, PosDtos.data(dto));
    }

    @PostMapping("/{id}/items")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> addItems(@PathVariable String id, @Valid @RequestBody AddOrderItemsRequest dto) {
        return orders.addItems(id, PosDtos.data(dto));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__VOID)
    public Map<String, Object> voidItem(@PathVariable String id, @PathVariable String itemId,
            @Valid @RequestBody VoidOrderItemRequest dto) {
        return orders.voidItem(id, itemId, PosDtos.data(dto));
    }

    @PostMapping("/{id}/fire-kitchen")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> fireKitchen(@PathVariable String id,
            @Valid @RequestBody(required = false) FireKitchenRequest body) {
        return orders.fireKitchen(id, body == null ? null : body.course(), false);
    }

    @PostMapping("/{id}/move")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__TRANSFER)
    public Map<String, Object> move(@PathVariable String id, @Valid @RequestBody MoveTableRequest dto) {
        return orders.moveTable(id, dto.targetTableId());
    }

    @PostMapping("/{id}/merge")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__MERGE)
    public Map<String, Object> merge(@PathVariable String id, @Valid @RequestBody MergeOrderRequest dto) {
        return orders.mergeOrders(id, dto.sourceOrderId());
    }

    @PostMapping("/{id}/cancel")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody CancelOrderRequest dto) {
        return orders.cancelOrder(id, dto.reason(), null, dto.overrideById(), dto.overridePin());
    }

    @PostMapping("/{id}/reopen")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> reopen(@PathVariable String id) {
        return orders.reopenOrder(id);
    }

    @PostMapping("/{id}/invoice")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> generateInvoice(@PathVariable String id, @Valid @RequestBody GenerateInvoiceRequest dto) {
        return billing.generateInvoice(id, PosDtos.data(dto));
    }
}
