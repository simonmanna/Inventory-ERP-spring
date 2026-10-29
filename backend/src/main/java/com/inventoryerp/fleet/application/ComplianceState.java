package com.inventoryerp.fleet.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Expiry state of a compliance document on a business day (ADR-S009 §4): {@code expired} once the expiry date has
 * passed, {@code expiring} from {@code reminderLeadDays} before it up to and including the expiry date,
 * {@code valid} before that, {@code no_expiry} without an expiry date. A renewed document is {@code renewed}.
 */
public final class ComplianceState {

    public static final String EXPIRED = "expired";
    public static final String EXPIRING = "expiring";
    public static final String VALID = "valid";
    public static final String NO_EXPIRY = "no_expiry";
    public static final String RENEWED = "renewed";

    private ComplianceState() {}

    /** State of an active document. */
    public static String of(LocalDate expiresOn, int reminderLeadDays, LocalDate today) {
        if (expiresOn == null) {
            return NO_EXPIRY;
        }
        if (expiresOn.isBefore(today)) {
            return EXPIRED;
        }
        if (!expiresOn.isAfter(today.plusDays(Math.max(0, reminderLeadDays)))) {
            return EXPIRING;
        }
        return VALID;
    }

    /** Days from {@code today} to the expiry date (negative once expired), or null without one. */
    public static Long daysToExpiry(LocalDate expiresOn, LocalDate today) {
        return expiresOn == null ? null : ChronoUnit.DAYS.between(today, expiresOn);
    }
}
