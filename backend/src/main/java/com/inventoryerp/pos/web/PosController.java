package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.OBJ;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.accounting.application.PosPaymentMethodService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.security.Throttle;
import com.inventoryerp.pos.application.PosOverridesService;
import com.inventoryerp.pos.application.PosSettingsService;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos} terminal settings, approvals, payment accounts and lookup (part of {@code pos.controller.ts}). */
@RestController
@RequestMapping("/pos")
public class PosController {

    public record UpdatePosSettingsRequest(
            @OneOf({"cafe", "retail", "rental"}) String posMode,
            Boolean sharedDrawer,
            @OneOf({"offline", "online"}) String connectionMode,
            Boolean showClosingAmounts,
            // Sidebar visibility of optional Point of Sale pages (web only; absent = shown).
            Boolean showDashboard,
            Boolean showKitchenDisplay,
            Boolean showKitchenReports) {}

    public record OperationApprovalRequest(
            @NotNull(message = STR) String managerId,
            @NotNull(message = STR) String pin,
            @NotNull(message = STR) String operationKey,
            @NotNull(message = STR) String endpoint,
            @NotNull(message = OBJ) Map<String, Object> payload,
            @OneOf({"discount", "price_change", "void", "manual_refund", "write_off"}) String overrideKind) {}

    private final PosSettingsService settings;
    private final PosOverridesService overrides;
    private final PosPaymentMethodService paymentMethods;

    public PosController(PosSettingsService settings, PosOverridesService overrides,
            PosPaymentMethodService paymentMethods) {
        this.settings = settings;
        this.overrides = overrides;
        this.paymentMethods = paymentMethods;
    }

    @GetMapping("/settings")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> getSettings() {
        return settings.getPosSettings();
    }

    @PostMapping("/operation-approval")
    @ResponseStatus(HttpStatus.CREATED)
    @Throttle(limit = 10, ttlMs = 60_000)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> operationApproval(@Valid @RequestBody OperationApprovalRequest dto) {
        return overrides.authorizeOperation(dto.managerId(), dto.pin(), dto.operationKey(), dto.endpoint(),
                dto.payload(), dto.overrideKind());
    }

    @GetMapping("/payment-accounts")
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public List<Map<String, Object>> paymentAccounts() {
        return settings.paymentAccounts();
    }

    @GetMapping("/payment-methods")
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public List<Map<String, Object>> listPaymentMethods() {
        return paymentMethods.terminalPaymentMethods().stream().map(PosPaymentMethodService.View::toMap).toList();
    }

    @PatchMapping("/settings")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> updateSettings(@Valid @RequestBody UpdatePosSettingsRequest dto) {
        return settings.updatePosSettings(PosDtos.data(dto));
    }

    @GetMapping("/lookup")
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> lookup(@RequestParam(required = false) String sku) {
        return settings.findBySku(sku);
    }
}
