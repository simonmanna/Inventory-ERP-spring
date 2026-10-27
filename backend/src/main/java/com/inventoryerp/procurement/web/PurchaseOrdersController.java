package com.inventoryerp.procurement.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import static com.inventoryerp.procurement.web.ProcurementDtos.ARR;
import static com.inventoryerp.procurement.web.ProcurementDtos.DATE_MSG;
import static com.inventoryerp.procurement.web.ProcurementDtos.ISO_DATE;
import static com.inventoryerp.procurement.web.ProcurementDtos.MIN1;
import static com.inventoryerp.procurement.web.ProcurementDtos.NUM;
import static com.inventoryerp.procurement.web.ProcurementDtos.STR;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.PurchaseOrdersService;
import com.inventoryerp.procurement.application.PurchaseOrdersService.CreateInput;
import com.inventoryerp.procurement.application.PurchaseOrdersService.LineInput;
import com.inventoryerp.procurement.application.PurchaseOrdersService.ListQuery;
import com.inventoryerp.procurement.application.PurchaseOrdersService.PayInput;
import com.inventoryerp.procurement.application.PurchaseOrdersService.ReceiveInput;
import com.inventoryerp.procurement.application.PurchaseOrdersService.ReceiveLineInput;
import com.inventoryerp.procurement.application.PurchaseOrdersService.UpdateInput;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/** {@code /procurement/purchase-orders} (port of {@code purchase-orders.controller.ts}). */
@RestController
@RequestMapping("/procurement/purchase-orders")
public class PurchaseOrdersController {

    /** {@code POLineDto}. */
    public record POLineRequest(
            String productId,
            @NotNull(message = STR) String description,
            @NotNull(message = NUM) @DecimalMin(value = "0.001", message = "must not be less than 0.001")
            BigDecimal quantity,
            String unitOfMeasureId,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal unitPrice,
            String taxId,
            BigDecimal taxRate,
            String notes) {}

    /** {@code CreatePODto}. */
    public record CreatePORequest(
            @NotNull(message = STR) String partnerId,
            String branchId,
            String description,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expectedDeliveryDate,
            String currencyCode,
            BigDecimal exchangeRate,
            @OneOf({"cash", "credit"}) String paymentType,
            String notes,
            String terms,
            String warehouseId,
            String requestId,
            @NotNull(message = ARR) @ArrayMinSize(1) List<@Valid POLineRequest> lines) {}

    /** {@code ReceiveLineDto}. */
    public record ReceiveLineRequest(
            String purchaseOrderLineId,
            String productId,
            @NotNull(message = STR) String description,
            @NotNull(message = NUM) @DecimalMin(value = "0.001", message = "must not be less than 0.001")
            BigDecimal quantity,
            BigDecimal unitCost,
            String batchNumber,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expiryDate,
            String notes) {}

    /** {@code ReceivePODto}. */
    public record ReceivePORequest(
            @NotNull(message = STR) String warehouseId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String receivedAt,
            String notes,
            @NotNull(message = ARR) @ArrayMinSize(1) List<@Valid ReceiveLineRequest> lines) {}

    /** {@code PayPODto}. */
    public record PayPORequest(
            @DecimalMin(value = "0.01", message = "must not be less than 0.01") BigDecimal amount,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String paidAt,
            String reference,
            String notes,
            @OneOf({"cash", "bank"}) String method,
            String cashRegisterId) {}

    /** {@code UpdatePODto}. */
    public record UpdatePORequest(
            String description,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expectedDeliveryDate,
            String notes,
            String terms) {}

    private final PurchaseOrdersService svc;

    public PurchaseOrdersController(PurchaseOrdersService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__READ)
    public Map<String, Object> list(@RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize, @RequestParam(required = false) String search,
            @RequestParam(required = false) String status, @RequestParam(required = false) String paymentType,
            @RequestParam(required = false) String paymentStatus, @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        int p = ProcurementDtos.parseInt(page, 1);
        int ps = ProcurementDtos.parseInt(pageSize, 25);
        return svc.list(new ListQuery(status, paymentType, paymentStatus, partnerId, search, p, ps, dateFrom, dateTo));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return svc.findOne(id);
    }

    /** Step 1: register a purchase order. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreatePORequest dto) {
        return svc.create(new CreateInput(dto.partnerId(), dto.branchId(), dto.description(),
                dto.expectedDeliveryDate(), dto.currencyCode(), dto.exchangeRate(), dto.paymentType(), dto.notes(),
                dto.terms(), dto.warehouseId(), dto.requestId(), dto.lines().stream().map(l -> new LineInput(
                        l.productId(), l.description(), l.quantity(), l.unitOfMeasureId(), l.unitPrice(), l.taxId(),
                        l.taxRate(), l.notes())).toList()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdatePORequest dto) {
        return svc.update(id, new UpdateInput(dto.description(), dto.expectedDeliveryDate(), dto.notes(), dto.terms()));
    }

    /** Step 2: receive products against an active PO (idempotent: a retry must not receive twice). */
    @PostMapping("/{id}/receive")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__RECEIVE)
    public Map<String, Object> receive(@PathVariable String id, @Valid @RequestBody ReceivePORequest dto) {
        return svc.receive(id, new ReceiveInput(dto.warehouseId(), dto.receivedAt(), dto.notes(),
                dto.lines().stream().map(l -> new ReceiveLineInput(l.purchaseOrderLineId(), l.productId(),
                        l.description(), l.quantity(), l.unitCost(), l.batchNumber(), l.expiryDate(), l.notes()))
                        .toList()));
    }

    /** Step 3: register a payment (credit purchases only). */
    @PostMapping("/{id}/pay")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__PAY)
    public Map<String, Object> pay(@PathVariable String id, @Valid @RequestBody PayPORequest dto) {
        return svc.pay(id, new PayInput(dto.amount(), dto.paidAt(), dto.reference(), dto.notes(), dto.method(),
                dto.cashRegisterId()));
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        if (reason != null && !(reason instanceof String)) {
            throw new IllegalStateException("Invalid value for PurchaseOrder.notes");
        }
        return svc.cancel(id, (String) reason);
    }

    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__APPROVE)
    public Map<String, Object> activate(@PathVariable String id) {
        return svc.activate(id);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__DELETE)
    public Map<String, Object> remove(@PathVariable String id) {
        return svc.remove(id);
    }
}
