package com.inventoryerp.procurement.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import static com.inventoryerp.procurement.web.ProcurementDtos.ARR;
import static com.inventoryerp.procurement.web.ProcurementDtos.DATE_MSG;
import static com.inventoryerp.procurement.web.ProcurementDtos.ISO_DATE;
import static com.inventoryerp.procurement.web.ProcurementDtos.MIN1;
import static com.inventoryerp.procurement.web.ProcurementDtos.NOT_EMPTY;
import static com.inventoryerp.procurement.web.ProcurementDtos.POSITIVE;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.LandedCostService;
import com.inventoryerp.procurement.application.LandedCostService.ChargeInput;
import com.inventoryerp.procurement.application.LandedCostService.CreateInput;
import com.inventoryerp.procurement.application.LandedCostVoucherService;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/** {@code /procurement/landed-costs} (port of {@code landed-cost.controller.ts}). */
@RestController
@RequestMapping("/procurement/landed-costs")
public class LandedCostController {

    /** {@code LandedCostChargeDto}. */
    public record LandedCostChargeRequest(
            @NotNull(message = "must be one of the following values: freight, duty, insurance, handling, other")
            @OneOf({"freight", "duty", "insurance", "handling", "other"}) String kind,
            String description,
            @NotNull(message = POSITIVE) @Positive(message = POSITIVE) BigDecimal amount) {}

    /** {@code CreateLandedCostDto}. */
    public record CreateLandedCostRequest(
            @NotNull(message = "must be a string") @NotEmpty(message = NOT_EMPTY) String goodsReceiptId,
            @NotNull(message = "must be a string") @NotEmpty(message = NOT_EMPTY) String creditAccountId,
            @OneOf({"value", "quantity", "equal"}) String allocationMethod,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String date,
            String notes,
            @NotNull(message = ARR) @ArrayMinSize(1) List<@Valid LandedCostChargeRequest> charges) {}

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

    @PostMapping
    @Idempotent
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateLandedCostRequest dto) {
        return landedCosts.create(new CreateInput(dto.goodsReceiptId(), dto.creditAccountId(), dto.allocationMethod(),
                dto.date(), dto.notes(), dto.charges().stream().map(c -> new ChargeInput(c.kind(), c.description(),
                        c.amount())).toList()));
    }

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

    /**
     * Extension (Cost Management CM-6, ADR-S036; no Nest counterpart): reverse a posted per-receipt landed cost through
     * the voucher engine — capitalisation out of stock, entry reversed, the share sold since back to COGS.
     */
    @PostMapping("/{id}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.LANDED_COST__REVERSE)
    public Map<String, Object> reverse(@PathVariable String id) {
        vouchers.reverseReceipt(id);
        return landedCosts.get(id);
    }
}
