package com.inventoryerp.procurement.web;

import static com.inventoryerp.procurement.web.ProcurementDtos.ARR;
import static com.inventoryerp.procurement.web.ProcurementDtos.DATE_MSG;
import static com.inventoryerp.procurement.web.ProcurementDtos.ISO_DATE;
import static com.inventoryerp.procurement.web.ProcurementDtos.MIN1;
import static com.inventoryerp.procurement.web.ProcurementDtos.NUM;
import static com.inventoryerp.procurement.web.ProcurementDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.PurchaseRequestsService;
import com.inventoryerp.procurement.application.PurchaseRequestsService.CreateInput;
import com.inventoryerp.procurement.application.PurchaseRequestsService.LineInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

/** {@code /procurement/purchase-requests} (port of {@code purchase-requests.controller.ts}). */
@RestController
@RequestMapping("/procurement/purchase-requests")
public class PurchaseRequestsController {

    /** {@code PRLineDto}. */
    public record PRLineRequest(
            String productId,
            @NotNull(message = STR) String description,
            @NotNull(message = NUM) BigDecimal quantity,
            String unitOfMeasureId,
            BigDecimal estimatedUnitPrice,
            String notes) {}

    /** {@code CreatePRDto}. */
    public record CreatePRRequest(
            String partnerId,
            String branchId,
            String description,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String neededBy,
            @NotNull(message = ARR) @Size(min = 1, message = MIN1) List<@Valid PRLineRequest> lines) {}

    private final PurchaseRequestsService svc;

    public PurchaseRequestsController(PurchaseRequestsService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_REQUEST__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return svc.list(status);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_REQUEST__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return svc.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_REQUEST__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreatePRRequest dto) {
        return svc.create(new CreateInput(dto.partnerId(), dto.branchId(), dto.description(), dto.neededBy(),
                dto.lines().stream().map(l -> new LineInput(l.productId(), l.description(), l.quantity(),
                        l.unitOfMeasureId(), l.estimatedUnitPrice(), l.notes())).toList()));
    }

    @PatchMapping("/{id}/submit")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_REQUEST__SUBMIT)
    public Map<String, Object> submit(@PathVariable String id) {
        return svc.submit(id);
    }

    @PatchMapping("/{id}/approve")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_REQUEST__APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return svc.approve(id);
    }

    @PatchMapping("/{id}/reject")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_REQUEST__APPROVE)
    public Map<String, Object> reject(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        if (reason != null && !(reason instanceof String)) {
            throw new IllegalStateException("Invalid value for PurchaseRequest.rejectedReason");
        }
        return svc.reject(id, reason == null ? "" : (String) reason);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_REQUEST__DELETE)
    public Map<String, Object> remove(@PathVariable String id) {
        return svc.remove(id);
    }
}
