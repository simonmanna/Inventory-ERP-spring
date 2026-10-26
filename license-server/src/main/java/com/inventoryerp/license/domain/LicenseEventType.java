package com.inventoryerp.license.domain;

/**
 * The audit trail of a licence. Append-only: every commercial decision and every unusual thing an
 * install reports lands here, because this is what gets read when a customer disputes a lockout.
 */
public final class LicenseEventType {

    public static final String ISSUED = "issued";
    public static final String ACTIVATED = "activated";
    public static final String REACTIVATED = "reactivated";
    public static final String DEACTIVATED = "deactivated";
    public static final String HEARTBEAT_GAP = "heartbeat_gap";
    public static final String RENEWED = "renewed";
    public static final String SUSPENDED = "suspended";
    public static final String RESUMED = "resumed";
    public static final String REVOKED = "revoked";
    public static final String EXPIRED = "expired";
    public static final String GRACE_ENTERED = "grace_entered";
    public static final String READ_ONLY_ENTERED = "read_only_entered";
    public static final String LIMIT_EXCEEDED = "limit_exceeded";
    public static final String TAMPER_SUSPECTED = "tamper_suspected";
    public static final String OFFLINE_TOKEN_ISSUED = "offline_token_issued";
    public static final String ACTIVATION_REJECTED = "activation_rejected";
    public static final String REMINDER_SENT = "reminder_sent";
    public static final String KEY_ROTATED = "key_rotated";

    private LicenseEventType() {}
}
