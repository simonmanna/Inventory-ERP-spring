package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.ARR;
import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosSplitService;
import com.inventoryerp.pos.web.PosOrderDtos.ReceivePaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Split bills (port of {@code split/pos-split.controller.ts}); not permission-gated except settle. */
@RestController
@RequestMapping("/pos")
public class PosSplitController {

    /** {@code SplitItemDto}. */
    public record SplitItemRequest(
            @NotNull(message = STR) String sourceItemId,
            @NotNull(message = NUM) @DecimalMin(value = "0.000001", message = "must not be less than 0.000001")
            Double quantity) {}

    /** {@code AddBillsDto}. */
    public record AddBillsRequest(@Min(value = 1, message = "must not be less than 1") Integer count) {}

    /** {@code AssignItemsDto}. */
    public record AssignItemsRequest(@NotNull(message = ARR) @Valid List<SplitItemRequest> items) {}

    private final PosSplitService svc;

    public PosSplitController(PosSplitService svc) {
        this.svc = svc;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(AssignItemsRequest dto) {
        return (List<Map<String, Object>>) PosDtos.js(dto.items());
    }

    @GetMapping("/tabs/{tableId}/split")
    public Map<String, Object> getState(@PathVariable String tableId) {
        return svc.getState(tableId);
    }

    @PostMapping("/tabs/{tableId}/split/bills")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addBills(@PathVariable String tableId, @Valid @RequestBody AddBillsRequest dto) {
        return svc.addBills(tableId, dto.count() == null ? 1 : dto.count());
    }

    @PostMapping("/tabs/{tableId}/split/equal")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> splitEqually(@PathVariable String tableId, @Valid @RequestBody AddBillsRequest dto) {
        return svc.splitEqually(tableId, dto.count() == null ? 2 : dto.count());
    }

    @PostMapping("/tabs/{tableId}/split/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> cancelSplit(@PathVariable String tableId) {
        return svc.cancelSplit(tableId);
    }

    @PostMapping("/split-bills/{billId}/assign")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assign(@PathVariable String billId, @Valid @RequestBody AssignItemsRequest dto) {
        return svc.assign(billId, items(dto));
    }

    @PostMapping("/split-bills/{billId}/unassign")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> unassign(@PathVariable String billId, @Valid @RequestBody AssignItemsRequest dto) {
        return svc.unassign(billId, items(dto));
    }

    @PostMapping("/split-bills/{billId}/move/{targetBillId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> move(@PathVariable String billId, @PathVariable String targetBillId,
            @Valid @RequestBody AssignItemsRequest dto) {
        return svc.move(billId, targetBillId, items(dto));
    }

    @PostMapping("/split-bills/{billId}/merge/{targetBillId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> merge(@PathVariable String billId, @PathVariable String targetBillId) {
        return svc.merge(targetBillId, billId);
    }

    @DeleteMapping("/split-bills/{billId}")
    public Map<String, Object> deleteBill(@PathVariable String billId) {
        return svc.deleteBill(billId);
    }

    @PostMapping("/split-bills/{billId}/settle")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> settle(@PathVariable String billId, @Valid @RequestBody ReceivePaymentRequest dto) {
        return svc.settleBill(billId, PosDtos.data(dto));
    }
}
