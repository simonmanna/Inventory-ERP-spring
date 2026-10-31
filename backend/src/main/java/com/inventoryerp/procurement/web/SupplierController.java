package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.ProcurementPolicyService.PolicyContext;
import com.inventoryerp.procurement.application.SupplierService;
import com.inventoryerp.procurement.application.SupplierService.BankAccountInput;
import com.inventoryerp.procurement.application.SupplierService.CertificateInput;
import com.inventoryerp.procurement.application.SupplierService.PriceInput;
import com.inventoryerp.procurement.application.SupplierService.ProductInput;
import com.inventoryerp.procurement.application.SupplierService.ProfileInput;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /procurement/suppliers} — supplier lifecycle on top of the core partner (ADR-PROCUREMENT-001; no Nest
 * counterpart): qualification status, dual-control bank accounts, certificates, catalogue, price history, eligibility.
 */
@RestController
@RequestMapping("/procurement/suppliers")
@Validated
public class SupplierController {

    public record ProfileRequest(@OneOf({"low", "medium", "high"}) String riskClass, List<String> categories,
            String currencyCode, @Min(value = 0, message = "leadTimeDays must not be less than 0") Integer leadTimeDays,
            String registrationNumber, String tradingName, String notes) {}

    public record ActionRequest(String reason) {}

    public record BankAccountRequest(@NotBlank(message = "bankName is required") String bankName, String branchName,
            @NotBlank(message = "accountName is required") String accountName,
            @NotBlank(message = "accountNumber is required") String accountNumber, String swiftCode,
            String currencyCode, Boolean isDefault) {}

    public record CertificateRequest(@NotBlank(message = "type is required") String type, String number, String issuer,
            String validFrom, String validTo, String fileId, String notes) {}

    public record ProductRequest(@NotBlank(message = "productId is required") String productId, String supplierSku,
            String purchaseUomId, @DecimalMin(value = "0", message = "minOrderQty must not be less than 0")
            BigDecimal minOrderQty, @Min(value = 0, message = "leadTimeDays must not be less than 0") Integer leadTimeDays,
            Boolean preferred, String notes) {}

    public record PriceRequest(@NotBlank(message = "productId is required") String productId, String uomId,
            String currencyCode, @NotNull(message = "unitPrice is required")
            @DecimalMin(value = "0", message = "unitPrice must not be less than 0") BigDecimal unitPrice,
            BigDecimal quantity, String validFrom, String validTo) {}

    private final SupplierService suppliers;

    public SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @GetMapping
    @RequirePermissions(Permissions.SUPPLIER__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"prospect", "provisional", "approved", "suspended", "blacklisted",
                    "unqualified"}) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int pageSize) {
        return suppliers.list(search, status, page, pageSize);
    }

    /** Supplier picker for order entry (buyers need not see supplier master data). */
    @GetMapping("/options")
    @RequirePermissions(Permissions.PROCUREMENT__PURCHASE_ORDER__READ)
    public List<Map<String, Object>> options(@RequestParam(required = false) String search) {
        return suppliers.options(search);
    }

    @GetMapping("/certificates/expiring")
    @RequirePermissions(Permissions.SUPPLIER__READ)
    public List<Map<String, Object>> expiring(@RequestParam(defaultValue = "30") @Min(0) @Max(3650) int days) {
        return suppliers.expiringCertificates(days);
    }

    @GetMapping("/price-history")
    @RequirePermissions(Permissions.SUPPLIER__READ)
    public Map<String, Object> priceHistory(@RequestParam String productId,
            @RequestParam(required = false) String partnerId) {
        return suppliers.priceHistory(productId, partnerId);
    }

    @GetMapping("/{partnerId}")
    @RequirePermissions(Permissions.SUPPLIER__READ)
    public Map<String, Object> get(@PathVariable String partnerId) {
        return suppliers.get(partnerId);
    }

    @GetMapping("/{partnerId}/eligibility")
    @RequirePermissions(Permissions.SUPPLIER__READ)
    public Map<String, Object> eligibility(@PathVariable String partnerId,
            @RequestParam(required = false) String branchId, @RequestParam(required = false) String categoryId) {
        return suppliers.eligibility(partnerId, PolicyContext.branch(branchId).withCategory(categoryId), LocalDate.now())
                .toMap();
    }

    @PutMapping("/{partnerId}/profile")
    @RequirePermissions(Permissions.SUPPLIER__MANAGE)
    public Map<String, Object> upsertProfile(@PathVariable String partnerId, @Valid @RequestBody ProfileRequest dto) {
        return suppliers.upsertProfile(partnerId, new ProfileInput(dto.riskClass(), dto.categories(), dto.currencyCode(),
                dto.leadTimeDays(), dto.registrationNumber(), dto.tradingName(), dto.notes()));
    }

    @PostMapping("/{partnerId}/profile/{action}")
    @RequirePermissions(Permissions.SUPPLIER__APPROVE)
    public Map<String, Object> transition(@PathVariable String partnerId,
            @PathVariable @OneOf({"approve", "provisional", "suspend", "blacklist", "reinstate"}) String action,
            @RequestBody(required = false) ActionRequest dto) {
        return suppliers.transition(partnerId, action, dto == null ? null : dto.reason());
    }

    @PostMapping("/{partnerId}/bank-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SUPPLIER__MANAGE)
    public Map<String, Object> addBankAccount(@PathVariable String partnerId,
            @Valid @RequestBody BankAccountRequest dto) {
        return suppliers.addBankAccount(partnerId, new BankAccountInput(dto.bankName(), dto.branchName(),
                dto.accountName(), dto.accountNumber(), dto.swiftCode(), dto.currencyCode(), dto.isDefault()));
    }

    @PostMapping("/bank-accounts/{id}/verify")
    @RequirePermissions(Permissions.SUPPLIER__VERIFY_BANK)
    public Map<String, Object> verifyBankAccount(@PathVariable String id) {
        return suppliers.verifyBankAccount(id);
    }

    @PostMapping("/bank-accounts/{id}/revoke")
    @RequirePermissions(Permissions.SUPPLIER__MANAGE)
    public Map<String, Object> revokeBankAccount(@PathVariable String id) {
        return suppliers.revokeBankAccount(id);
    }

    @PostMapping("/{partnerId}/certificates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SUPPLIER__MANAGE)
    public Map<String, Object> addCertificate(@PathVariable String partnerId,
            @Valid @RequestBody CertificateRequest dto) {
        return suppliers.addCertificate(partnerId, new CertificateInput(dto.type(), dto.number(), dto.issuer(),
                dto.validFrom(), dto.validTo(), dto.fileId(), dto.notes()));
    }

    @DeleteMapping("/certificates/{id}")
    @RequirePermissions(Permissions.SUPPLIER__MANAGE)
    public Map<String, Object> removeCertificate(@PathVariable String id) {
        return suppliers.removeCertificate(id);
    }

    @PutMapping("/{partnerId}/products")
    @RequirePermissions(Permissions.SUPPLIER__MANAGE)
    public Map<String, Object> upsertProduct(@PathVariable String partnerId, @Valid @RequestBody ProductRequest dto) {
        return suppliers.upsertProduct(partnerId, new ProductInput(dto.productId(), dto.supplierSku(),
                dto.purchaseUomId(), dto.minOrderQty(), dto.leadTimeDays(), dto.preferred(), dto.notes()));
    }

    @DeleteMapping("/products/{id}")
    @RequirePermissions(Permissions.SUPPLIER__MANAGE)
    public Map<String, Object> removeProduct(@PathVariable String id) {
        return suppliers.removeProduct(id);
    }

    @PostMapping("/{partnerId}/prices")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.SUPPLIER__MANAGE)
    public Map<String, Object> recordPrice(@PathVariable String partnerId, @Valid @RequestBody PriceRequest dto) {
        return suppliers.recordPrice(partnerId, new PriceInput(dto.productId(), dto.uomId(), dto.currencyCode(),
                dto.unitPrice(), dto.quantity(), dto.validFrom(), dto.validTo()));
    }
}
