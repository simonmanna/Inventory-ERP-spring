package com.inventoryerp.sync.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * ADR-S083 C4/C6: offline policy. Three separate concepts, each configured on its own:
 * <ul>
 *   <li>user offline session: the offline-authorization window ({@code sync.offlineWindowHours}, default 72, an org
 *   setting a device policy may only lower), idle timeout and PIN lockout;</li>
 *   <li>device token expiry ({@code sync.deviceTokenTtlDays}, default 30);</li>
 *   <li>device revocation: an explicit admin action ({@code PosDevice.revokedAt}).</li>
 * </ul>
 * Variance tolerances per transaction type are org settings ({@code sync.varianceTolerance.<type>}, percent); payments
 * have none: a payment records the amount actually received.
 */
@Service
public class SyncPolicyService {

    public static final int DEFAULT_OFFLINE_WINDOW_HOURS = 72;
    public static final int DEFAULT_TOKEN_TTL_DAYS = 30;
    public static final int DEFAULT_IDLE_MINUTES = 30;
    public static final int DEFAULT_PIN_MAX_ATTEMPTS = 5;
    public static final int DEFAULT_PIN_LOCKOUT_MINUTES = 5;
    public static final BigDecimal DEFAULT_VARIANCE_PERCENT = new BigDecimal("0.5");
    /** Transaction types that carry a pricing tolerance. {@code payment} deliberately has none. */
    public static final java.util.List<String> TOLERANCE_TYPES = java.util.List.of("salesOrder", "quote", "posSale");
    /** Grace during which the token replaced by a rotation still authenticates. */
    public static final long PREVIOUS_TOKEN_GRACE_HOURS = 24;

    private final JdbcClient jdbc;

    public SyncPolicyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String setting(String organizationId, String key) {
        return jdbc.sql("SELECT value::text FROM \"Setting\" WHERE \"scopeType\" = 'organization' AND key = ?"
                        + " AND (\"organizationId\" = ? OR \"scopeId\" = ?) ORDER BY \"updatedAt\" DESC LIMIT 1")
                .params(key, organizationId, organizationId).query(String.class).optional().map(v -> v.replace("\"", "").trim())
                .orElse(null);
    }

    int intSetting(String organizationId, String key, int fallback, int min, int max) {
        String v = setting(organizationId, key);
        try {
            int n = v == null ? fallback : Integer.parseInt(v);
            return n < min || n > max ? fallback : n;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int tokenTtlDays(String organizationId) {
        return intSetting(organizationId, "sync.deviceTokenTtlDays", DEFAULT_TOKEN_TTL_DAYS, 1, 365);
    }

    /** The org window, lowered (never raised) by the device policy. */
    public int offlineWindowHours(String organizationId, Integer deviceOverride) {
        int org = intSetting(organizationId, "sync.offlineWindowHours", DEFAULT_OFFLINE_WINDOW_HOURS, 1, 720);
        return deviceOverride == null || deviceOverride <= 0 ? org : Math.min(org, deviceOverride);
    }

    /** Percent tolerance for a transaction type; {@code payment} (or any unknown type) has none. */
    public BigDecimal varianceTolerancePercent(String organizationId, String type) {
        if (!TOLERANCE_TYPES.contains(type)) {
            return BigDecimal.ZERO;
        }
        String v = setting(organizationId, "sync.varianceTolerance." + type);
        try {
            BigDecimal d = v == null ? DEFAULT_VARIANCE_PERCENT : new BigDecimal(v);
            return d.signum() < 0 ? DEFAULT_VARIANCE_PERCENT : d;
        } catch (NumberFormatException e) {
            return DEFAULT_VARIANCE_PERCENT;
        }
    }

    /**
     * ADR-S083 O5: the most one rep may have confirmed from offline orders within the offline window
     * ({@code sync.offlineOrderCap}, base currency; 0 or unset = no cap). Beyond it an order is put on hold.
     */
    public BigDecimal offlineOrderCap(String organizationId) {
        String v = setting(organizationId, "sync.offlineOrderCap");
        try {
            BigDecimal d = v == null || v.isBlank() ? BigDecimal.ZERO : new BigDecimal(v);
            return d.signum() < 0 ? BigDecimal.ZERO : d;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** What a device enforces while offline (sent on every pull). */
    public Map<String, Object> offlinePolicy(String organizationId, Integer deviceOverride) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("offlineWindowHours", offlineWindowHours(organizationId, deviceOverride));
        p.put("idleTimeoutMinutes", intSetting(organizationId, "sync.offlineIdleMinutes", DEFAULT_IDLE_MINUTES, 1, 1440));
        p.put("pinMaxAttempts", intSetting(organizationId, "sync.pinMaxAttempts", DEFAULT_PIN_MAX_ATTEMPTS, 1, 20));
        p.put("pinLockoutMinutes", intSetting(organizationId, "sync.pinLockoutMinutes", DEFAULT_PIN_LOCKOUT_MINUTES, 1,
                1440));
        // ADR-S083 O5 (additive): the device warns before it records offline orders beyond the rep's cap.
        p.put("offlineOrderCap", com.inventoryerp.pos.application.PosSql.js(offlineOrderCap(organizationId)));
        p.put("varianceTolerance", Map.of("salesOrder", com.inventoryerp.pos.application.PosSql.js(
                varianceTolerancePercent(organizationId, "salesOrder")), "quote", com.inventoryerp.pos.application.PosSql
                        .js(varianceTolerancePercent(organizationId, "quote"))));
        return p;
    }
}
