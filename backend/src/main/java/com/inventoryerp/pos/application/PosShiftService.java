package com.inventoryerp.pos.application;

import com.inventoryerp.accounting.application.CashSessionService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Shift handover from the terminal (port of {@code pos-shift.service.ts}). */
@Service
public class PosShiftService {

    private final CashSessionService sessions;
    private final PosAuthService auth;
    private final PosOverridesService overrides;

    public PosShiftService(CashSessionService sessions, PosAuthService auth, PosOverridesService overrides) {
        this.sessions = sessions;
        this.auth = auth;
        this.overrides = overrides;
    }

    /** Incoming PIN + manager PIN (+ pos:override), then the atomic close/open. */
    public Map<String, Object> handover(String cashRegisterId, BigDecimal closingCounted, String incomingUserId,
            String incomingPin, String approvedById, String managerPin, String varianceReason, BigDecimal openingFloat,
            String notes, Map<String, Object> closingDenomination, Map<String, Object> closingAccounts,
            Map<String, Object> uncountedAccounts) {
        auth.pinLogin(incomingUserId, incomingPin);
        auth.pinLogin(approvedById, managerPin);
        overrides.assertCanOverride(approvedById, "shift_handover");
        return sessions.handover(new CashSessionService.HandoverInput(cashRegisterId, closingCounted, incomingUserId,
                varianceReason, openingFloat, notes, approvedById, closingDenomination, closingAccounts, uncountedAccounts));
    }
}
