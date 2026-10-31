package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.RequisitionToOrderService;
import com.inventoryerp.procurement.application.RequisitionToOrderService.OrderInput;
import com.inventoryerp.procurement.application.RequisitionToOrderService.OrderLine;
import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Requisition-to-order and the purchase-order lifecycle after approval (ADR-PROCUREMENT-001 wave 3; no Nest
 * counterpart): sourcing workbench, order from request lines, request cancel, send / acknowledge / short-close /
 * close, lifecycle view and the open-orders report.
 */
@RestController
@RequestMapping("/procurement")
public class RequisitionToOrderController {

    public record OrderLineRequest(@NotBlank(message = "requestLineId is required") String requestLineId,
            @Positive(message = "quantity must be a positive number") BigDecimal quantity,
            @DecimalMin(value = "0", message = "unitPrice must not be less than 0") BigDecimal unitPrice,
            @DecimalMin(value = "0", message = "taxRate must not be less than 0") BigDecimal taxRate, String taxId) {}

    public record OrderRequest(@NotBlank(message = "partnerId is required") String partnerId,
            @NotBlank(message = "warehouseId is required") String warehouseId, String branchId,
            @OneOf({"cash", "credit"}) String paymentType, String currencyCode,
            @Positive(message = "exchangeRate must be a positive number") BigDecimal exchangeRate,
            String expectedDeliveryDate, String notes, String terms,
            @NotNull(message = "lines must be an array") @ArrayMinSize(1) List<@Valid OrderLineRequest> lines) {}

    public record ReasonRequest(String reason) {}

    public record AcknowledgeRequest(String supplierReference, String promisedDate) {}

    private final RequisitionToOrderService svc;

    public RequisitionToOrderController(RequisitionToOrderService svc) {
        this.svc = svc;
    }

    @GetMapping("/sourcing/open-lines")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__CREATE)
    public List<Map<String, Object>> openLines(@RequestParam(required = false) String requestId,
            @RequestParam(required = false) String productId) {
        return svc.openLines(requestId, productId);
    }

    @PostMapping("/sourcing/orders")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__CREATE)
    public Map<String, Object> createOrder(@Valid @RequestBody OrderRequest dto) {
        return svc.createOrder(new OrderInput(dto.partnerId(), dto.warehouseId(), dto.branchId(), dto.paymentType(),
                dto.currencyCode(), dto.exchangeRate(), dto.expectedDeliveryDate(), dto.notes(), dto.terms(),
                dto.lines().stream().map(l -> new OrderLine(l.requestLineId(), l.quantity(), l.unitPrice(), l.taxRate(),
                        l.taxId())).toList()));
    }

    @PostMapping("/purchase-requests/{id}/cancel")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_REQUEST__UPDATE)
    public Map<String, Object> cancelRequest(@PathVariable String id, @RequestBody(required = false) ReasonRequest dto) {
        return svc.cancelRequest(id, dto == null ? null : dto.reason());
    }

    @GetMapping("/purchase-orders/{id}/lifecycle")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__READ)
    public Map<String, Object> lifecycle(@PathVariable String id) {
        return svc.lifecycle(id);
    }

    @PostMapping("/purchase-orders/{id}/send")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__SEND)
    public Map<String, Object> send(@PathVariable String id) {
        return svc.send(id);
    }

    @PostMapping("/purchase-orders/{id}/acknowledge")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__SEND)
    public Map<String, Object> acknowledge(@PathVariable String id, @RequestBody(required = false) AcknowledgeRequest dto) {
        return svc.acknowledge(id, dto == null ? null : dto.supplierReference(), dto == null ? null : dto.promisedDate());
    }

    @PostMapping("/purchase-orders/{id}/short-close")
    @Idempotent
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__CANCEL)
    public Map<String, Object> shortClose(@PathVariable String id, @RequestBody(required = false) ReasonRequest dto) {
        return svc.shortClose(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/purchase-orders/{id}/close")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__CANCEL)
    public Map<String, Object> close(@PathVariable String id) {
        return svc.close(id);
    }

    @GetMapping("/reports/open-orders")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__READ)
    public List<Map<String, Object>> openOrders(@RequestParam(required = false) String partnerId,
            @RequestParam(defaultValue = "false") boolean overdueOnly) {
        return svc.openOrders(partnerId, overdueOnly);
    }
}
