package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.ARR;
import static com.inventoryerp.pos.web.PosDtos.INT;
import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosTablesService;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** {@code /pos/tables} (port of {@code pos-tables.controller.ts}). */
@RestController
@RequestMapping("/pos/tables")
public class PosTablesController {

    static final String MIN0 = "must not be less than 0";
    static final String MIN20 = "must not be less than 20";
    static final String MIN_Q = "must not be less than 0.0001";

    /** {@code CreateTableBody}. */
    public record CreateTableRequest(
            @NotNull(message = STR) String name,
            @NotNull(message = INT) Integer number,
            @Min(value = 0, message = MIN0) Integer seats,
            String zone,
            @OneOf({"square", "rectangle", "circle"}) String shape,
            Integer posX,
            Integer posY,
            @Min(value = 20, message = MIN20) Integer width,
            @Min(value = 20, message = MIN20) Integer height,
            String notes,
            Boolean active,
            String assignedWaiterId,
            Integer sortOrder,
            String qrCodeUrl) {}

    /** {@code UpdateTableBody}. */
    public record UpdateTableRequest(
            String name,
            Integer number,
            @Min(value = 0, message = MIN0) Integer seats,
            String zone,
            @OneOf({"square", "rectangle", "circle"}) String shape,
            Integer posX,
            Integer posY,
            @Min(value = 20, message = MIN20) Integer width,
            @Min(value = 20, message = MIN20) Integer height,
            String notes,
            Boolean active,
            String assignedWaiterId,
            Integer sortOrder,
            String qrCodeUrl) {}

    /** {@code StatusBody}. */
    public record StatusRequest(
            @NotNull(message = "status must be one of the following values: available, occupied, reserved, out_of_service")
            @OneOf({"available", "occupied", "reserved", "out_of_service"}) String status,
            String reason) {}

    /** {@code AssignWaiterBody}. */
    public record AssignWaiterRequest(String waiterId) {}

    /** {@code SplitLineBody}. */
    public record SplitLineRequest(
            @NotNull(message = STR) String sourceItemId,
            @NotNull(message = NUM) @DecimalMin(value = "0.0001", message = MIN_Q) Double quantity) {}

    /** {@code SplitGroupBody}. */
    public record SplitGroupRequest(
            @NotNull(message = STR) String label,
            @NotNull(message = ARR) @Valid List<SplitLineRequest> lines) {}

    /** {@code SplitBillBody}. */
    public record SplitBillRequest(
            @NotNull(message = STR) String sourceOrderId,
            @NotNull(message = ARR) @Valid List<SplitGroupRequest> splits,
            String partnerId) {}

    /** {@code TransferItemBody}. */
    public record TransferItemRequest(
            @NotNull(message = STR) String lineId,
            @NotNull(message = NUM) @DecimalMin(value = "0.0001", message = MIN_Q) Double quantity) {}

    /** {@code TransferItemsBody}. */
    public record TransferItemsRequest(@NotNull(message = ARR) @Valid List<TransferItemRequest> items) {}

    private final PosTablesService svc;

    public PosTablesController(PosTablesService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.TABLES__VIEW)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String zone, @RequestParam(required = false) String active) {
        return svc.list(status, zone, PosDtos.jsBool(active));
    }

    @GetMapping("/stats")
    @RequirePermissions(Permissions.TABLES__VIEW)
    public Map<String, Object> stats() {
        return svc.stats();
    }

    @GetMapping("/stream")
    @RequirePermissions(Permissions.TABLES__VIEW)
    public SseEmitter stream() {
        return svc.stream();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.TABLES__VIEW)
    public Map<String, Object> get(@PathVariable String id) {
        return svc.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateTableRequest dto) {
        return svc.create(PosDtos.data(dto));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.TABLES__EDIT)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateTableRequest dto) {
        return svc.update(id, PosDtos.data(dto));
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.TABLES__DELETE)
    public Map<String, Object> archive(@PathVariable String id) {
        return svc.archive(id);
    }

    @PutMapping("/{id}/status")
    @RequirePermissions(Permissions.TABLES__EDIT)
    public Map<String, Object> setStatus(@PathVariable String id, @Valid @RequestBody StatusRequest dto) {
        return svc.setStatus(id, dto.status(), dto.reason());
    }

    @PostMapping("/{id}/assign-waiter")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__EDIT)
    public Map<String, Object> assignWaiter(@PathVariable String id, @Valid @RequestBody AssignWaiterRequest dto) {
        return svc.assignWaiter(id, dto.waiterId());
    }

    @PostMapping("/{id}/merge/{targetId}")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__MERGE)
    public Map<String, Object> merge(@PathVariable String id, @PathVariable String targetId) {
        return svc.merge(id, targetId);
    }

    @PostMapping("/{id}/unmerge")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__MERGE)
    public Map<String, Object> unmerge(@PathVariable String id) {
        return svc.unmerge(id);
    }

    @PostMapping("/{id}/transfer/{targetId}")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__TRANSFER)
    public Map<String, Object> transfer(@PathVariable String id, @PathVariable String targetId) {
        return svc.transfer(id, targetId);
    }

    @PostMapping("/{id}/transfer-items/{targetId}")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__TRANSFER)
    @SuppressWarnings("unchecked")
    public Map<String, Object> transferItems(@PathVariable String id, @PathVariable String targetId,
            @Valid @RequestBody TransferItemsRequest dto) {
        return svc.transferItems(id, targetId, (List<Map<String, Object>>) PosDtos.js(dto.items()));
    }

    @PostMapping("/{id}/split-bill")
    @ResponseStatus(HttpStatus.CREATED)
    @SuppressWarnings("unchecked")
    public Map<String, Object> splitBill(@PathVariable String id, @Valid @RequestBody SplitBillRequest dto) {
        return svc.splitBill(id, dto.sourceOrderId(), (List<Map<String, Object>>) PosDtos.js(dto.splits()),
                dto.partnerId());
    }

    /** Untyped Nest body {@code { orderId, customerName?, guestCount? }}. */
    @PostMapping("/{id}/attach-sale")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__EDIT)
    public Map<String, Object> attachSale(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return svc.attachSaleToTable(id, dto.get("orderId"), dto.get("customerName"), dto.get("guestCount"));
    }
}
