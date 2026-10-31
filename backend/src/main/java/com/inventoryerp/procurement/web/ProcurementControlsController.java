package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.BillControls;
import com.inventoryerp.procurement.application.PoChangeService;
import com.inventoryerp.procurement.application.PurchaseCommitments;
import com.inventoryerp.procurement.application.PoChangeService.ChangeInput;
import com.inventoryerp.procurement.application.PoChangeService.LineChange;
import com.inventoryerp.procurement.application.SupplierClaimService;
import com.inventoryerp.procurement.application.SupplierClaimService.CreateInput;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Enterprise purchasing controls (ADR-PROCUREMENT-001 wave 5; no Nest counterpart): budget commitments, purchase-order
 * change requests and revisions, duplicate-bill lookup and supplier claims.
 */
@RestController
@RequestMapping("/procurement")
public class ProcurementControlsController {

    public record LineChangeRequest(@NotBlank(message = "lineId is required") String lineId,
            @Positive(message = "quantity must be a positive number") BigDecimal quantity,
            @DecimalMin(value = "0", message = "unitPrice must not be less than 0") BigDecimal unitPrice) {}

    public record ChangeRequest(@NotBlank(message = "reason is required") String reason, String expectedDeliveryDate,
            List<@Valid LineChangeRequest> lines) {}

    public record DecisionRequest(String note) {}

    public record ClaimRequest(@NotBlank(message = "partnerId is required") String partnerId,
            @NotNull(message = "type is required")
            @OneOf({"shortage", "damage", "quality", "price", "late_delivery", "other"}) String type,
            @NotNull(message = "amount is required") @DecimalMin(value = "0", message = "amount must not be less than 0")
            BigDecimal amount, @NotBlank(message = "description is required") String description,
            String purchaseOrderId, String goodsReceiptId, String vendorBillId) {}

    public record AcceptRequest(@DecimalMin(value = "0", message = "amount must not be less than 0") BigDecimal amount,
            String response) {}

    public record ResponseRequest(String response) {}

    private final PurchaseCommitments commitments;
    private final PoChangeService changes;
    private final SupplierClaimService claims;
    private final BillControls bills;

    public ProcurementControlsController(PurchaseCommitments commitments, PoChangeService changes,
            SupplierClaimService claims, BillControls bills) {
        this.commitments = commitments;
        this.changes = changes;
        this.claims = claims;
        this.bills = bills;
    }

    // ── budget commitments ──────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/commitments")
    @RequirePermissions(Permissions.BUDGET_CONTROL__COMMITMENTS)
    public PageResult<Map<String, Object>> commitments(@RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String sourceId, @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        return commitments.list(sourceType, sourceId, accountId, status, Math.max(1, page),
                Math.min(200, Math.max(1, limit)));
    }

    // ── purchase-order changes ──────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/purchase-orders/{id}/changes")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__READ)
    public Map<String, Object> history(@PathVariable String id) {
        return changes.history(id);
    }

    @PostMapping("/purchase-orders/{id}/changes")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PO_CHANGE__REQUEST)
    public Map<String, Object> requestChange(@PathVariable String id, @Valid @RequestBody ChangeRequest dto) {
        return changes.request(id, new ChangeInput(dto.reason(), dto.expectedDeliveryDate(), dto.lines() == null ? null
                : dto.lines().stream().map(l -> new LineChange(l.lineId(), l.quantity(), l.unitPrice())).toList()));
    }

    @GetMapping("/po-changes/pending")
    @RequirePermissions(Permissions.PO_CHANGE__APPROVE)
    public List<Map<String, Object>> pendingChanges() {
        return changes.pending();
    }

    @PostMapping("/po-changes/{changeId}/approve")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PO_CHANGE__APPROVE)
    public Map<String, Object> approveChange(@PathVariable String changeId, @RequestBody(required = false)
            DecisionRequest dto) {
        return changes.approve(changeId, dto == null ? null : dto.note());
    }

    @PostMapping("/po-changes/{changeId}/reject")
    @RequirePermissions(Permissions.PO_CHANGE__APPROVE)
    public Map<String, Object> rejectChange(@PathVariable String changeId, @RequestBody(required = false)
            DecisionRequest dto) {
        return changes.reject(changeId, dto == null ? null : dto.note());
    }

    @PostMapping("/po-changes/{changeId}/cancel")
    @RequirePermissions(Permissions.PO_CHANGE__REQUEST)
    public Map<String, Object> cancelChange(@PathVariable String changeId) {
        return changes.cancel(changeId);
    }

    // ── bills ───────────────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/bills/{billId}/duplicates")
    @RequirePermissions(Permissions.EXPENSE__READ)
    public List<Map<String, Object>> duplicates(@PathVariable String billId) {
        return bills.duplicates(billId);
    }

    // ── supplier claims ─────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/claims")
    @RequirePermissions(Permissions.SUPPLIER_CLAIM__READ)
    public PageResult<Map<String, Object>> listClaims(@RequestParam(required = false) String status,
            @RequestParam(required = false) String partnerId, @RequestParam(required = false) String purchaseOrderId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "25") int limit) {
        return claims.list(status, partnerId, purchaseOrderId, Math.max(1, page), Math.min(200, Math.max(1, limit)));
    }

    @GetMapping("/claims/{id}")
    @RequirePermissions(Permissions.SUPPLIER_CLAIM__READ)
    public Map<String, Object> getClaim(@PathVariable String id) {
        return claims.get(id);
    }

    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SUPPLIER_CLAIM__MANAGE)
    public Map<String, Object> createClaim(@Valid @RequestBody ClaimRequest dto) {
        return claims.create(new CreateInput(dto.partnerId(), dto.type(), dto.amount(), dto.description(),
                dto.purchaseOrderId(), dto.goodsReceiptId(), dto.vendorBillId()));
    }

    @PostMapping("/claims/{id}/submit")
    @RequirePermissions(Permissions.SUPPLIER_CLAIM__MANAGE)
    public Map<String, Object> submitClaim(@PathVariable String id) {
        return claims.submit(id);
    }

    @PostMapping("/claims/{id}/accept")
    @RequirePermissions(Permissions.SUPPLIER_CLAIM__MANAGE)
    public Map<String, Object> acceptClaim(@PathVariable String id, @Valid @RequestBody(required = false)
            AcceptRequest dto) {
        return claims.accept(id, dto == null ? null : dto.amount(), dto == null ? null : dto.response());
    }

    @PostMapping("/claims/{id}/reject")
    @RequirePermissions(Permissions.SUPPLIER_CLAIM__MANAGE)
    public Map<String, Object> rejectClaim(@PathVariable String id, @RequestBody(required = false) ResponseRequest dto) {
        return claims.reject(id, dto == null ? null : dto.response());
    }

    @PostMapping("/claims/{id}/cancel")
    @RequirePermissions(Permissions.SUPPLIER_CLAIM__MANAGE)
    public Map<String, Object> cancelClaim(@PathVariable String id) {
        return claims.cancel(id);
    }

    @PostMapping("/claims/{id}/settle")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.SUPPLIER_CLAIM__MANAGE)
    public Map<String, Object> settleClaim(@PathVariable String id) {
        return claims.settle(id);
    }
}
