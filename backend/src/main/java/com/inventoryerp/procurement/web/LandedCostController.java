package com.inventoryerp.procurement.web;


import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.LandedCostService;
import com.inventoryerp.procurement.application.LandedCostVoucherService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /procurement/landed-costs} (port of {@code landed-cost.controller.ts}). */
@RestController
@RequestMapping("/procurement/landed-costs")
public class LandedCostController {

    private final LandedCostService landedCosts;
    private final LandedCostVoucherService vouchers;

    public LandedCostController(LandedCostService landedCosts, LandedCostVoucherService vouchers) {
        this.landedCosts = landedCosts;
        this.vouchers = vouchers;
    }

    @GetMapping
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String goodsReceiptId) {
        return landedCosts.list(goodsReceiptId);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return landedCosts.get(id);
    }

    /**
     * Purchase readiness B4 (owner-approved 2026-11-27): retired — always 400, pointing to landed cost vouchers. The
     * body is not read.
     */
    @PostMapping
    @Idempotent
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__CREATE)
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> ignored) {
        return landedCosts.create(null);
    }

    /** Purchase readiness B4: retired — always 400 (a draft can still be cancelled). */
    @PostMapping("/{id}/post")
    @Idempotent
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return landedCosts.post(id);
    }

    @PostMapping("/{id}/cancel")
    @Idempotent
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__CANCEL)
    public Map<String, Object> cancel(@PathVariable String id) {
        return landedCosts.cancel(id);
    }

    /** Optional body of a reversal (readiness gap h-06 F13): why the landed cost is reversed, kept in the audit trail. */
    public record ReverseLandedCostRequest(@Size(max = 500, message = "reason must be shorter than or equal to 500"
            + " characters") String reason) {}

    /**
     * Extension (Cost Management CM-6, ADR-S036; no Nest counterpart): reverse a posted per-receipt landed cost through
     * the voucher engine — capitalisation out of stock, entry reversed, the share sold since back to COGS.
     */
    @PostMapping("/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.LANDED_COST__REVERSE)
    public Map<String, Object> reverse(@PathVariable String id,
            @Valid @RequestBody(required = false) ReverseLandedCostRequest dto) {
        vouchers.reverseReceipt(id, dto == null ? null : dto.reason());
        return landedCosts.get(id);
    }
}
