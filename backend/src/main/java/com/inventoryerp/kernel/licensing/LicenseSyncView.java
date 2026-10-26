package com.inventoryerp.kernel.licensing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The small licence summary carried on {@code /sync/pull}, for the Android terminal.
 *
 * <p>Deliberately much less than {@code GET /license/status}: a till needs to know whether it may
 * take a sale, how long it has, and what to put on the banner. It has no business knowing the
 * customer's name, the plan's price or this installation's fingerprint, and a POS device token is a
 * weaker credential than a user session — so none of that is sent to it.
 */
public final class LicenseSyncView {

    private LicenseSyncView() {}

    public static Map<String, Object> of(LicenseState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", state.mode().name());
        out.put("reason", state.reason());
        // The one thing the till branches on: may it start a new sale?
        out.put("writable", state.allowsWrites());
        out.put("expiresAt", iso(state.expiresAt()));
        out.put("graceUntil", iso(state.graceUntil()));
        out.put("daysRemaining", state.daysRemaining());
        // Whether this terminal's own features are licensed; the till hides what is not.
        out.put("androidPos", state.grants(LicenseFeatures.ANDROID_POS));
        out.put("message", state.allowsWrites() ? null
                : LicenseMessages.forMode(state.mode(), state.reason()));
        return out;
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
