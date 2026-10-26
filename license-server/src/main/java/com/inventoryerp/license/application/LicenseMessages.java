package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.LicenseStatus;

/**
 * The exact words a customer sees when activation fails.
 *
 * <p>These are the authoritative strings from docs/spec/licensing.md §5 and are duplicated in the
 * ERP's {@code LicenseMessages}. They are shown to shopkeepers, so they say what to do next and
 * never why internally — in particular, an unknown key and a malformed one give the same answer, so
 * the route cannot be used to discover which keys exist.
 */
public final class LicenseMessages {

    public static final String KEY_NOT_RECOGNISED = "Licence key not recognised.";
    public static final String ALREADY_ACTIVATED = "This licence is already activated on another installation.";
    public static final String REVOKED = "This licence has been revoked. Contact your supplier.";
    public static final String SUSPENDED = "This licence is suspended. Contact your supplier.";
    public static final String PENDING = "This licence has not been issued yet. Contact your supplier.";
    public static final String WRONG_INSTANCE = "This licence file was issued for a different installation.";

    private LicenseMessages() {}

    /** Why a licence in this state cannot hand out a token. */
    public static String forStatus(LicenseStatus status) {
        return switch (status) {
            case REVOKED -> REVOKED;
            case SUSPENDED -> SUSPENDED;
            case PENDING -> PENDING;
            // An expired licence still activates: the install needs its token to learn that it is
            // expired and to show the renewal prompt. Blocking here would leave it merely broken.
            case ACTIVE, EXPIRED -> null;
        };
    }
}
