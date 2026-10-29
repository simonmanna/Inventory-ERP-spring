package com.inventoryerp.fleet.web.dispatch;

import com.inventoryerp.fleet.application.DispatchCommands.CloseOrderInput;
import com.inventoryerp.fleet.application.DispatchCommands.DeliveryOrderInput;
import com.inventoryerp.fleet.application.DispatchCommands.DeliveryOrderPatch;
import com.inventoryerp.fleet.application.DispatchCommands.DeliveryOrderQuery;
import com.inventoryerp.fleet.application.DispatchCommands.FromSourceInput;
import com.inventoryerp.fleet.application.DispatchCommands.InvoiceRequest;
import com.inventoryerp.fleet.application.DispatchCommands.LoadInput;
import com.inventoryerp.fleet.application.DispatchCommands.PlanInput;
import com.inventoryerp.fleet.application.DispatchCommands.ReasonInput;
import com.inventoryerp.fleet.application.FleetDeliveryInvoiceService;
import com.inventoryerp.fleet.application.FleetDeliveryOrderService;
import com.inventoryerp.fleet.application.FleetDispatchService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery orders and the dispatch board (fleet 1.3): manual / from a confirmed sales order / from a sales invoice,
 * load planning within payload and body volume, short-close, cancel, transport-charge preview and the customer invoice
 * (Idempotency-Key required), plus the board, source and form lookups.
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetDeliveryOrderController {

    private final FleetDeliveryOrderService orders;
    private final FleetDispatchService trips;
    private final FleetDeliveryInvoiceService invoicing;

    public FleetDeliveryOrderController(FleetDeliveryOrderService orders, FleetDispatchService trips,
            FleetDeliveryInvoiceService invoicing) {
        this.orders = orders;
        this.trips = trips;
        this.invoicing = invoicing;
    }

    /** {@code status}: a status or {@code active} (open / planned / in progress). */
    @GetMapping("/delivery-orders")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"active", "open", "planned", "in_progress", "delivered", "closed",
                "cancelled"}) String status,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) @OneOf({"manual", "sales_order", "invoice"}) String sourceType,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return orders.list(new DeliveryOrderQuery(search, status, partnerId, siteId, sourceType, dateFrom, dateTo, page,
                pageSize));
    }

    @GetMapping("/delivery-orders/{id}")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return orders.get(id);
    }

    @PostMapping("/delivery-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__CREATE)
    public Map<String, Object> create(@Valid @RequestBody DeliveryOrderInput dto) {
        return orders.create(dto);
    }

    /** From a confirmed back-office sales order (orders module); POS sales are refused. */
    @PostMapping("/delivery-orders/from-sales-order")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__CREATE)
    public Map<String, Object> fromSalesOrder(@Valid @RequestBody FromSourceInput dto) {
        return orders.fromSalesOrder(dto);
    }

    /** From a sales invoice (draft: fleet issues the stock; posted: already issued by the invoice). */
    @PostMapping("/delivery-orders/from-invoice")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__CREATE)
    public Map<String, Object> fromInvoice(@Valid @RequestBody FromSourceInput dto) {
        return orders.fromInvoice(dto);
    }

    @PatchMapping("/delivery-orders/{id}")
    @RequirePermissions(Permissions.FLEET_DISPATCH__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody DeliveryOrderPatch dto) {
        return orders.update(id, dto);
    }

    @PostMapping("/delivery-orders/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return orders.cancel(id, dto.reason());
    }

    /** Short-close: the remaining quantity will not be delivered. */
    @PostMapping("/delivery-orders/{id}/close")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__UPDATE)
    public Map<String, Object> close(@PathVariable String id, @Valid @RequestBody(required = false) CloseOrderInput dto) {
        return orders.close(id, dto);
    }

    /** Proposed split of the remaining quantity into loads (nothing saved). */
    @GetMapping("/delivery-orders/{id}/load-plan")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public Map<String, Object> loadPlan(@PathVariable String id,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String trailerId,
            @RequestParam(required = false) @DecimalMin(value = "0", inclusive = false) BigDecimal payloadT,
            @RequestParam(required = false) @DecimalMin(value = "0", inclusive = false) BigDecimal volumeM3,
            @RequestParam(required = false) @Min(1) @Max(200) Integer maxLoads) {
        return trips.previewPlan(id, new PlanInput(vehicleId, trailerId, null, payloadT, volumeM3, null, maxLoads));
    }

    @PostMapping("/delivery-orders/{id}/plan-loads")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__PLAN)
    public Map<String, Object> planLoads(@PathVariable String id, @Valid @RequestBody PlanInput dto) {
        return trips.plan(id, dto);
    }

    /** One load with explicit quantities. */
    @PostMapping("/delivery-orders/{id}/trips")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET_DISPATCH__PLAN)
    public Map<String, Object> addTrip(@PathVariable String id, @Valid @RequestBody LoadInput dto) {
        return trips.addLoad(id, dto);
    }

    @GetMapping("/delivery-orders/{id}/delivery-charge")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public Map<String, Object> deliveryCharge(@PathVariable String id) {
        return invoicing.preview(id);
    }

    /** Draft customer invoice: delivered goods (unless invoiced already) + the transport charge. Needs invoice:create. */
    @PostMapping("/delivery-orders/{id}/invoice")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.FLEET_DISPATCH__UPDATE)
    public Map<String, Object> invoice(@PathVariable String id, @Valid @RequestBody(required = false) InvoiceRequest dto) {
        return invoicing.invoice(id, dto);
    }

    @GetMapping("/dispatch-board")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public Map<String, Object> board(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String siteId) {
        return orders.board(from, to, partnerId, siteId);
    }

    /** Sales orders / invoices that can still become a delivery order. */
    @GetMapping("/dispatch/sources")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public List<Map<String, Object>> sources(
            @RequestParam(required = false) @OneOf({"sales_order", "invoice"}) String type,
            @RequestParam(required = false) String search) {
        return orders.sources(type, search);
    }

    @GetMapping("/dispatch/lookups")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public Map<String, Object> lookups() {
        return orders.lookups();
    }

    @GetMapping("/dispatch/lookups/customers")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public List<Map<String, Object>> customers(@RequestParam(required = false) String search) {
        return orders.customers(search);
    }

    @GetMapping("/dispatch/lookups/products")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public List<Map<String, Object>> products(@RequestParam(required = false) String search) {
        return orders.products(search);
    }
}
