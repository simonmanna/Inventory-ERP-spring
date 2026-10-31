package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.EmergencyPurchaseService;
import com.inventoryerp.procurement.application.IncomingInspectionService;
import com.inventoryerp.procurement.application.IncomingInspectionService.LineResult;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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
 * Receiving quality and emergency purchases (ADR-PROCUREMENT-001 wave 6; no Nest counterpart): incoming inspections of
 * goods receipts, and the emergency-purchase flag with its after-the-fact review.
 */
@RestController
@RequestMapping("/procurement")
public class ReceivingQualityController {

    public record LineResultRequest(@NotBlank(message = "lineId is required") String lineId,
            @DecimalMin(value = "0", message = "acceptedQuantity must not be less than 0") BigDecimal acceptedQuantity,
            @DecimalMin(value = "0", message = "rejectedQuantity must not be less than 0") BigDecimal rejectedQuantity,
            @OneOf({"pass", "fail", "partial"}) String result, String defectNotes) {}

    public record ResultsRequest(List<@Valid LineResultRequest> lines, String notes, List<String> fileIds) {}

    public record ReasonRequest(String reason) {}

    public record NoteRequest(String note) {}

    private final IncomingInspectionService inspections;
    private final EmergencyPurchaseService emergencies;

    public ReceivingQualityController(IncomingInspectionService inspections, EmergencyPurchaseService emergencies) {
        this.inspections = inspections;
        this.emergencies = emergencies;
    }

    @GetMapping("/inspections")
    @RequirePermissions(Permissions.INSPECTION__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String goodsReceiptId,
            @RequestParam(required = false) String purchaseOrderId) {
        return inspections.list(status, goodsReceiptId, purchaseOrderId);
    }

    @GetMapping("/inspections/{id}")
    @RequirePermissions(Permissions.INSPECTION__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return inspections.get(id);
    }

    @PostMapping("/goods-receipts/{id}/inspection")
    @RequirePermissions(Permissions.INSPECTION__MANAGE)
    public Map<String, Object> open(@PathVariable String id) {
        return inspections.open(id);
    }

    @PostMapping("/inspections/{id}/results")
    @RequirePermissions(Permissions.INSPECTION__MANAGE)
    public Map<String, Object> record(@PathVariable String id, @Valid @RequestBody ResultsRequest dto) {
        return inspections.record(id, dto.lines() == null ? null : dto.lines().stream().map(l -> new LineResult(
                l.lineId(), l.acceptedQuantity(), l.rejectedQuantity(), l.result(), l.defectNotes())).toList(),
                dto.notes(), dto.fileIds());
    }

    @PostMapping("/inspections/{id}/complete")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INSPECTION__MANAGE)
    public Map<String, Object> complete(@PathVariable String id) {
        return inspections.complete(id);
    }

    @PostMapping("/inspections/{id}/cancel")
    @RequirePermissions(Permissions.INSPECTION__MANAGE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return inspections.cancel(id);
    }

    @PostMapping("/purchase-orders/{id}/emergency")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__UPDATE)
    public Map<String, Object> markEmergency(@PathVariable String id, @RequestBody(required = false) ReasonRequest dto) {
        return emergencies.mark(id, dto == null ? null : dto.reason());
    }

    @PostMapping("/purchase-orders/{id}/emergency-review")
    @RequirePermissions(Permissions.EMERGENCY_PURCHASE__REVIEW)
    public Map<String, Object> reviewEmergency(@PathVariable String id, @RequestBody(required = false) NoteRequest dto) {
        return emergencies.review(id, dto == null ? null : dto.note());
    }

    @GetMapping("/reports/emergency")
    @RequirePermissions(Permissions.EMERGENCY_PURCHASE__REVIEW)
    public List<Map<String, Object>> emergencyReport(@RequestParam(required = false) Boolean reviewed) {
        return emergencies.report(reviewed);
    }
}
