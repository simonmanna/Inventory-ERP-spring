package com.inventoryerp.kernel.common;

import com.inventoryerp.shared.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Offline-captured business timestamps (port of {@code occurred-at.ts}): returns {@code null} when the
 * client sent nothing; rejects invalid, future (&gt; 5 min skew) and &gt; 7 day old values.
 */
public final class OccurredAt {

    private static final Duration MAX_AGE = Duration.ofDays(7);
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

    private OccurredAt() {}

    public static Instant resolve(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Instant date;
        try {
            date = value.matches("\\d{4}-\\d{2}-\\d{2}") ? Instant.parse(value + "T00:00:00Z")
                    : OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("occurredAt is not a valid ISO-8601 timestamp");
        }
        return check(date);
    }

    public static Instant check(Instant date) {
        if (date == null) {
            return null;
        }
        Instant now = Instant.now();
        if (date.isAfter(now.plus(FUTURE_SKEW))) {
            throw ApiException.badRequest("occurredAt is in the future — check the device clock");
        }
        if (date.isBefore(now.minus(MAX_AGE))) {
            throw ApiException.badRequest("occurredAt is more than 7 days old — check the device clock");
        }
        return date;
    }
}
