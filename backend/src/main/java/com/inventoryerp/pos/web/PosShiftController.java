package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosShiftService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/shift} (port of {@code pos-shift.controller.ts}). */
@RestController
@RequestMapping("/pos/shift")
public class PosShiftController {

    static final String MIN0 = "must not be less than 0";

    /** {@code HandoverBody}. */
    public record HandoverRequest(
            @NotNull(message = STR) String cashRegisterId,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) BigDecimal closingCounted,
            @NotNull(message = STR) String incomingUserId,
            @NotNull(message = STR) String incomingPin,
            @NotNull(message = STR) String approvedById,
            @NotNull(message = STR) String managerPin,
            String varianceReason,
            @DecimalMin(value = "0", message = MIN0) BigDecimal openingFloat,
            String notes,
            Map<String, Object> closingDenomination,
            Map<String, Object> closingAccounts,
            Map<String, Object> uncountedAccounts) {}

    private final PosShiftService svc;

    public PosShiftController(PosShiftService svc) {
        this.svc = svc;
    }

    @PostMapping("/handover")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CLOSE_SESSION)
    public Map<String, Object> handover(@Valid @RequestBody HandoverRequest b) {
        return svc.handover(b.cashRegisterId(), b.closingCounted(), b.incomingUserId(), b.incomingPin(),
                b.approvedById(), b.managerPin(), b.varianceReason(), b.openingFloat(), b.notes(),
                b.closingDenomination(), b.closingAccounts(), b.uncountedAccounts());
    }
}
