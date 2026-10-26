package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.LicensePeriod;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Term arithmetic. Pure and static: every date a customer is billed against comes from here, so it
 * is the one piece of this service with no dependencies and exhaustive unit tests.
 *
 * <p>All arithmetic is in UTC. Calendar units use {@link ZonedDateTime}, so adding a month to 31
 * January lands on 28/29 February rather than overflowing into March, and a leap day is handled by
 * the same rule. {@code DAY} and {@code TRIAL} are exact 24-hour days, which is what a shop that
 * pre-paid for three days expects.
 */
public final class LicensePeriods {

    private LicensePeriods() {}

    /**
     * End of a term that begins at {@code startsAt}.
     *
     * @return {@code null} for {@link LicensePeriod#PERPETUAL} — a perpetual licence has no end
     */
    public static Instant expiryOf(Instant startsAt, LicensePeriod period, int periodCount) {
        if (startsAt == null) {
            throw new IllegalArgumentException("A start date is required");
        }
        if (periodCount < 1) {
            throw new IllegalArgumentException("A term must be at least one period long");
        }
        ZonedDateTime start = startsAt.atZone(ZoneOffset.UTC);
        return switch (period) {
            case PERPETUAL -> null;
            case DAY, TRIAL -> start.plusDays(periodCount).toInstant();
            case MONTH -> start.plusMonths(periodCount).toInstant();
            case YEAR -> start.plusYears(periodCount).toInstant();
        };
    }

    /**
     * Where a renewal's new term starts: the later of now and the current expiry.
     *
     * <p>Renewing early therefore adds a full period on top of the days already paid for, rather
     * than throwing them away; renewing late starts from today rather than back-dating a term the
     * customer could not use.
     */
    public static Instant renewalStart(Instant now, Instant currentExpiry) {
        if (currentExpiry == null || currentExpiry.isBefore(now)) {
            return now;
        }
        return currentExpiry;
    }

    /** End of the window in which an expired licence still works. */
    public static Instant graceUntil(Instant expiresAt, int graceDays) {
        if (expiresAt == null) {
            return null;
        }
        return expiresAt.plus(Duration.ofDays(Math.max(0, graceDays)));
    }

    /**
     * How long a minted token stays verifiable. It must cover the whole grace window, because a
     * signature that has stopped verifying is indistinguishable from tampering and would lock a
     * shop out mid-grace rather than warning it.
     *
     * <p>A perpetual licence gets a century, which is simply "longer than the product".
     */
    public static Instant tokenExpiry(Instant expiresAt, int graceDays, Instant now) {
        if (expiresAt == null) {
            return now.atZone(ZoneOffset.UTC).plusYears(100).toInstant();
        }
        return graceUntil(expiresAt, graceDays);
    }

    /** Whole days from {@code now} until {@code expiresAt}; negative once past, {@code null} if perpetual. */
    public static Long daysRemaining(Instant now, Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        return Duration.between(now, expiresAt).toDays();
    }
}
