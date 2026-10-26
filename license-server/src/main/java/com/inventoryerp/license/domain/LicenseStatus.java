package com.inventoryerp.license.domain;

/**
 * Server-side licence status. {@link #value()} is the exact {@code license.status} column value and
 * the token's {@code lic.status} claim.
 *
 * <p>Note what this is <em>not</em>: the install's enforcement mode. {@code active} here only means
 * "sold and not stopped"; whether an install is inside its term, in grace or read-only is derived
 * from the dates by the client (see {@code LicenseStateMachine}). The server keeps {@code expired}
 * as a bookkeeping state so a lapsed licence can be listed and chased.
 */
public enum LicenseStatus {

    /** Created, not yet issued: it has no dates and no token can be minted. */
    PENDING("pending"),

    /** Sold and running. */
    ACTIVE("active"),

    /** Temporarily stopped (non-payment). Reversible with no loss of term. */
    SUSPENDED("suspended"),

    /** Term ended and not renewed. Reversible by renewing. */
    EXPIRED("expired"),

    /** Terminal. Read-only on the install immediately, with no grace. */
    REVOKED("revoked");

    private final String value;

    LicenseStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Can a token be minted for an install in this state? */
    public boolean canActivate() {
        return this == ACTIVE || this == EXPIRED;
    }

    public static LicenseStatus of(String value) {
        for (LicenseStatus s : values()) {
            if (s.value.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown licence status '" + value + "'");
    }
}
