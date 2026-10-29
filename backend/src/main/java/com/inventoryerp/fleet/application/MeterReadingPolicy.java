package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The monotonic meter rule (ADR-S009 §4). A meter's <em>lifetime</em> value (raw reading + the offset added by meter
 * replacements) never decreases over time: a reading must be at least the latest earlier reading and at most the
 * earliest later reading. Going down is only possible through an approved meter correction.
 */
public final class MeterReadingPolicy {

    /** Clock skew tolerated between a device and the server before a reading counts as "in the future". */
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /** A neighbouring valid reading: raw value (for messages), lifetime value (for the rule) and time. */
    public record Neighbour(BigDecimal value, BigDecimal lifetime, Instant at) {}

    private MeterReadingPolicy() {}

    /** "Odometer" / "Hour meter". */
    public static String meterLabel(String meterType) {
        return "hours".equals(meterType) ? "Hour meter" : "Odometer";
    }

    /** "km" / "h". */
    public static String unit(String meterType) {
        return "hours".equals(meterType) ? "h" : "km";
    }

    /** Does a vehicle configured with {@code vehicleMeterType} (odometer / hours / both / none) track this meter? */
    public static boolean tracks(String vehicleMeterType, String meterType) {
        return "both".equals(vehicleMeterType) || (vehicleMeterType != null && vehicleMeterType.equals(meterType));
    }

    /** A reading may not be stamped in the future (beyond {@link #FUTURE_TOLERANCE}). */
    public static void assertNotFuture(Instant readingAt, Instant now) {
        if (readingAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw ApiException.badRequest("Reading time cannot be in the future");
        }
    }

    /** Readings cannot predate the last meter replacement (the old meter's history is closed). */
    public static void assertAfterReplacement(String meterType, Instant readingAt, Instant replacedAt) {
        if (replacedAt != null && readingAt.isBefore(replacedAt)) {
            throw ApiException.badRequest(meterLabel(meterType) + " reading predates the meter replacement on "
                    + day(replacedAt));
        }
    }

    /**
     * Checks a reading of raw {@code value} / {@code lifetime} against the previous and next valid readings of the
     * same meter (either may be null).
     */
    public static void assertMonotonic(String meterType, BigDecimal value, BigDecimal lifetime, Neighbour previous,
            Neighbour next) {
        String unit = unit(meterType);
        if (previous != null && lifetime.compareTo(previous.lifetime()) < 0) {
            throw ApiException.badRequest(meterLabel(meterType) + " reading " + plain(value) + " " + unit
                    + " is lower than the previous reading of " + plain(previous.value()) + " " + unit + " on "
                    + day(previous.at()) + ". Request a meter correction instead.");
        }
        if (next != null && lifetime.compareTo(next.lifetime()) > 0) {
            throw ApiException.badRequest(meterLabel(meterType) + " reading " + plain(value) + " " + unit
                    + " is higher than the later reading of " + plain(next.value()) + " " + unit + " on "
                    + day(next.at()) + ".");
        }
    }

    static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    static String day(Instant at) {
        return LocalDate.ofInstant(at, BusinessZone.get()).toString();
    }
}
