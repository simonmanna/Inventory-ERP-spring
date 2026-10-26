package com.inventoryerp.kernel.licensing;

/**
 * The exact words a shopkeeper reads when the licence stops them.
 *
 * <p>Authoritative list from docs/spec/licensing.md §5, duplicated from the licence server's own
 * {@code LicenseMessages} for the activation failures. They are shown directly in the UI, so each
 * one says what has happened and what to do about it — never a status code, never a hint about this
 * installation's internals.
 */
public final class LicenseMessages {

    public static final String READ_ONLY =
            "Your licence has expired. The system is read-only until it is renewed.";
    public static final String REVOKED = "This licence has been revoked. Contact your supplier.";
    public static final String SUSPENDED = "This licence is suspended. Contact your supplier.";
    public static final String TRIAL_OVER = "The trial period has ended. Activate a licence to continue.";

    public static final String KEY_NOT_RECOGNISED = "Licence key not recognised.";
    public static final String ALREADY_ACTIVATED = "This licence is already activated on another installation.";
    public static final String WRONG_INSTANCE = "This licence file was issued for a different installation.";
    public static final String UNREACHABLE =
            "The licence server could not be reached. Check this machine's internet connection, "
                    + "or ask your supplier for an offline licence file.";

    private LicenseMessages() {}

    /** Why writes are refused in this mode. */
    public static String forMode(LicenseMode mode, String reason) {
        if (mode == LicenseMode.REVOKED) {
            return REVOKED;
        }
        if ("suspended".equals(reason)) {
            return SUSPENDED;
        }
        if (mode == LicenseMode.UNLICENSED) {
            return TRIAL_OVER;
        }
        return READ_ONLY;
    }

    public static String featureNotLicensed(String feature) {
        return "'" + feature + "' is not included in your licence.";
    }

    public static String deviceLimit(int limit) {
        return "Your licence allows " + limit + " POS device(s). Deactivate a device or upgrade your plan.";
    }

    public static String userLimit(int limit) {
        return "Your licence allows " + limit + " user(s). Deactivate a user or upgrade your plan.";
    }

    public static String organizationLimit(int limit) {
        return "Your licence allows " + limit + " organization(s). Upgrade your plan to add more.";
    }
}
