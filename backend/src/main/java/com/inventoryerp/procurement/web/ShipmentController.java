package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.CostComponentService;
import com.inventoryerp.procurement.application.CostComponentService.ComponentInput;
import com.inventoryerp.procurement.application.ShipmentService;
import com.inventoryerp.procurement.application.ShipmentService.ShipmentInput;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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

/** {@code /procurement/shipments} and {@code /procurement/cost-components} (ADR-S007; no Nest counterpart). */
@RestController
@RequestMapping("/procurement")
public class ShipmentController {

    static final String DAY = "^\\d{4}-\\d{2}-\\d{2}$";
    static final String DAY_MSG = "must be a date (YYYY-MM-DD)";

    /** Shipment body. */
    public record ShipmentRequest(String reference, String supplierId, String transportMode, String billOfLading,
            String origin, @Pattern(regexp = DAY, message = DAY_MSG) String etd,
            @Pattern(regexp = DAY, message = DAY_MSG) String eta,
            @Pattern(regexp = DAY, message = DAY_MSG) String arrivedAt,
            @OneOf({"open", "in_transit", "arrived", "closed"}) String status, String costCenterId, String notes,
            String incoterm) {}

    /** Attach-receipt body. */
    public record ReceiptRequest(
            @NotNull(message = "goodsReceiptId must be a string") @NotEmpty(message = "goodsReceiptId should not be empty")
            String goodsReceiptId) {}

    /** Component body. */
    public record ComponentRequest(String code, String name,
            @OneOf({"freight", "duty", "insurance", "handling", "other"}) String kind,
            @OneOf({"capitalize", "expense", "recoverable_tax"}) String treatment,
            @OneOf({"value", "quantity", "equal", "weight", "volume", "manual"}) String defaultAllocationMethod,
            String expenseAccountId, String description, Boolean isActive) {}

    private final ShipmentService shipments;
    private final CostComponentService components;

    public ShipmentController(ShipmentService shipments, CostComponentService components) {
        this.shipments = shipments;
        this.components = components;
    }

    private static ShipmentInput input(ShipmentRequest d) {
        return new ShipmentInput(d.reference(), d.supplierId(), d.transportMode(), d.billOfLading(), d.origin(), d.etd(),
                d.eta(), d.arrivedAt(), d.status(), d.costCenterId(), d.notes(), d.incoterm());
    }

    // ------------------------------------------------------------------ shipments

    @GetMapping("/shipments")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return shipments.list(status);
    }

    @GetMapping("/shipments/{id}")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return shipments.get(id);
    }

    @PostMapping("/shipments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> create(@Valid @RequestBody ShipmentRequest dto) {
        return shipments.create(input(dto));
    }

    @PatchMapping("/shipments/{id}")
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody ShipmentRequest dto) {
        return shipments.update(id, input(dto));
    }

    @PostMapping("/shipments/{id}/receipts")
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> addReceipt(@PathVariable String id, @Valid @RequestBody ReceiptRequest dto) {
        return shipments.addReceipt(id, dto.goodsReceiptId());
    }

    @DeleteMapping("/shipments/{id}/receipts/{goodsReceiptId}")
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> removeReceipt(@PathVariable String id, @PathVariable String goodsReceiptId) {
        return shipments.removeReceipt(id, goodsReceiptId);
    }

    // ------------------------------------------------------------------ cost components

    @GetMapping("/cost-components")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> components() {
        return components.list();
    }

    @PostMapping("/cost-components")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LANDED_COST__CONFIGURE)
    public Map<String, Object> createComponent(@Valid @RequestBody ComponentRequest dto) {
        return components.create(new ComponentInput(dto.code(), dto.name(), dto.kind(), dto.treatment(),
                dto.defaultAllocationMethod(), dto.expenseAccountId(), dto.description(), dto.isActive()));
    }

    @PatchMapping("/cost-components/{id}")
    @RequirePermissions(Permissions.LANDED_COST__CONFIGURE)
    public Map<String, Object> updateComponent(@PathVariable String id, @Valid @RequestBody ComponentRequest dto) {
        return components.update(id, new ComponentInput(dto.code(), dto.name(), dto.kind(), dto.treatment(),
                dto.defaultAllocationMethod(), dto.expenseAccountId(), dto.description(), dto.isActive()));
    }
}
