package com.inventoryerp.procurement.web;

import static com.inventoryerp.procurement.web.ProcurementDtos.DATE_MSG;
import static com.inventoryerp.procurement.web.ProcurementDtos.ISO_DATE;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.LandedCostReportService;
import com.inventoryerp.procurement.application.LandedCostVoucherService;
import com.inventoryerp.procurement.application.LandedCostVoucherService.ChargeInput;
import com.inventoryerp.procurement.application.LandedCostVoucherService.VoucherInput;
import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /procurement/landed-cost-vouchers} and {@code /procurement/landed-cost-reports} — multi-receipt,
 * multi-supplier landed cost vouchers and their reports (ADR-S007; no Nest counterpart). The legacy single-receipt
 * {@code /procurement/landed-costs} routes are unchanged.
 */
@RestController
@RequestMapping("/procurement")
public class LandedCostVoucherController {

    static final String METHODS_MSG = "must be one of the following values: value, quantity, equal, weight, volume, manual";

    /** One charge line. */
    public record ChargeRequest(
            String costComponentId,
            @OneOf({"freight", "duty", "insurance", "handling", "other"}) String kind,
            String description,
            String supplierId,
            String supplierInvoiceRef,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String invoiceDate,
            String currencyCode,
            @Positive(message = "exchangeRate must be a positive number") BigDecimal exchangeRate,
            @NotNull(message = "amount must be a positive number") @Positive(message = "amount must be a positive number")
            BigDecimal amount,
            @DecimalMin(value = "0", message = "taxAmount must not be less than 0") BigDecimal taxAmount,
            @OneOf({"capitalize", "expense", "recoverable_tax"}) String treatment,
            @OneOf({"value", "quantity", "equal", "weight", "volume", "manual"}) String allocationMethod,
            String expenseAccountId,
            String creditAccountId,
            String costCenterId,
            List<String> appliesToLineIds,
            Map<String, BigDecimal> manualAllocations,
            @OneOf({"generate", "link", "none"}) String billMode,
            String vendorBillId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dueDate,
            String taxId,
            Boolean taxRecoverable) {}

    /** Create / replace body. */
    public record VoucherRequest(
            String shipmentId,
            List<String> goodsReceiptIds,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String date,
            String notes,
            String costCenterId,
            @NotNull(message = "charges must be an array") @ArrayMinSize(1) List<@Valid ChargeRequest> charges) {}

    private final LandedCostVoucherService vouchers;
    private final LandedCostReportService reports;

    public LandedCostVoucherController(LandedCostVoucherService vouchers, LandedCostReportService reports) {
        this.vouchers = vouchers;
        this.reports = reports;
    }

    private static VoucherInput input(VoucherRequest dto) {
        return new VoucherInput(dto.shipmentId(), dto.goodsReceiptIds(), dto.date(), dto.notes(), dto.costCenterId(),
                dto.charges().stream().map(c -> new ChargeInput(c.costComponentId(), c.kind(), c.description(),
                        c.supplierId(), c.supplierInvoiceRef(), c.invoiceDate(), c.currencyCode(), c.exchangeRate(),
                        c.amount(), c.taxAmount(), c.treatment(), c.allocationMethod(), c.expenseAccountId(),
                        c.creditAccountId(), c.costCenterId(), c.appliesToLineIds(), c.manualAllocations(), c.billMode(),
                        c.vendorBillId(), c.dueDate(), c.taxId(), c.taxRecoverable())).toList());
    }

    // ------------------------------------------------------------------ vouchers

    @GetMapping("/landed-cost-vouchers")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String shipmentId) {
        return vouchers.list(status, shipmentId);
    }

    @GetMapping("/landed-cost-vouchers/{id}")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return vouchers.get(id);
    }

    @GetMapping("/landed-cost-vouchers/{id}/preview")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public Map<String, Object> preview(@PathVariable String id) {
        return vouchers.preview(id);
    }

    @PostMapping("/landed-cost-vouchers")
    @Idempotent
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> create(@Valid @RequestBody VoucherRequest dto) {
        return vouchers.create(input(dto));
    }

    @PutMapping("/landed-cost-vouchers/{id}")
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody VoucherRequest dto) {
        return vouchers.update(id, input(dto));
    }

    @PostMapping("/landed-cost-vouchers/{id}/submit")
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> submit(@PathVariable String id) {
        return vouchers.submit(id);
    }

    @PostMapping("/landed-cost-vouchers/{id}/approve")
    @RequirePermissions(Permissions.LANDED_COST__APPROVE)
    public Map<String, Object> approve(@PathVariable String id) {
        return vouchers.approve(id);
    }

    @PostMapping("/landed-cost-vouchers/{id}/post")
    @Idempotent
    @RequirePermissions(Permissions.LANDED_COST__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return vouchers.post(id);
    }

    @PostMapping("/landed-cost-vouchers/{id}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.LANDED_COST__REVERSE)
    public Map<String, Object> reverse(@PathVariable String id) {
        return vouchers.reverse(id);
    }

    /** One credited charge (LC-1). */
    public record CreditChargeRequest(
            @NotNull(message = "chargeId must be a string") String chargeId,
            @NotNull(message = "amount must be a positive number") @Positive(message = "amount must be a positive number")
            BigDecimal amount,
            String supplierInvoiceRef) {}

    /** Credit voucher body (LC-1). */
    public record CreditRequest(
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String date,
            String notes,
            @NotNull(message = "charges must be an array") @ArrayMinSize(1) List<@Valid CreditChargeRequest> charges) {}

    @PostMapping("/landed-cost-vouchers/{id}/credit")
    @Idempotent
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> credit(@PathVariable String id, @Valid @RequestBody CreditRequest dto) {
        return vouchers.createCredit(id, dto.date(), dto.notes(), dto.charges().stream().map(c ->
                new LandedCostVoucherService.CreditLine(c.chargeId(), c.amount(), c.supplierInvoiceRef())).toList());
    }

    @PostMapping("/landed-cost-vouchers/{id}/cancel")
    @RequirePermissions(Permissions.LANDED_COST__CREATE)
    public Map<String, Object> cancel(@PathVariable String id) {
        return vouchers.cancel(id);
    }

    // ------------------------------------------------------------------ reports

    @GetMapping("/landed-cost-reports/by-shipment")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> byShipment(@RequestParam String from, @RequestParam String to) {
        return reports.byShipment(day(from, "from"), day(to, "to"));
    }

    @GetMapping("/landed-cost-reports/by-item")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> byItem(@RequestParam String from, @RequestParam String to) {
        return reports.byItem(day(from, "from"), day(to, "to"));
    }

    @GetMapping("/landed-cost-reports/by-supplier")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> bySupplier(@RequestParam String from, @RequestParam String to) {
        return reports.bySupplier(day(from, "from"), day(to, "to"));
    }

    @GetMapping("/landed-cost-reports/clearing")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public Map<String, Object> clearing() {
        return reports.clearing();
    }

    @GetMapping("/landed-cost-reports/product-profitability")
    @RequirePermissions(Permissions.LANDED_COST__READ)
    public List<Map<String, Object>> productProfitability(@RequestParam String from, @RequestParam String to) {
        return reports.productProfitability(day(from, "from"), day(to, "to"));
    }

    static LocalDate day(String v, String field) {
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest(field + " must be a date (YYYY-MM-DD)");
        }
    }
}
