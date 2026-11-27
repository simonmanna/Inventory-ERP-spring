package com.inventoryerp.kernel.common;

import com.inventoryerp.shared.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offline-captured business timestamps (port of {@code occurred-at.ts}): returns {@code null} when the client sent
 * nothing and rejects a value that is not ISO-8601.
 *
 * <p>IPR SW-7 / ADR-S088 (owner decision P15-1, deviation from Nest): a time that is too far ahead or too old is
 * <b>accepted and flagged</b>, never refused, so an offline sale is never dead-lettered for its clock.
 * <ul>
 *   <li>Later than server now + {@code inventory.maxFutureEffectiveMinutes} (default 5): the server receive time is
 *   used instead, flagged {@code clockSkew}.</li>
 *   <li>Older than now − {@code inventory.maxBackdateDays} (default 7): kept as sent, flagged {@code lateSync}.</li>
 * </ul>
 * Nest refused both with 400. The two limits are read from the organisation settings through suppliers installed at
 * start-up ({@code OccurredAtSettings}); outside a tenant they fall back to the defaults.
 */
public final class OccurredAt {

    private static final Logger log = LoggerFactory.getLogger(OccurredAt.class);

    static final Duration DEFAULT_MAX_AGE = Duration.ofDays(7);
    static final Duration DEFAULT_FUTURE_SKEW = Duration.ofMinutes(5);

    /** IPR SW-7: discrepancy kind of a time stored at the receive time because it was too far ahead. */
    public static final String CLOCK_SKEW_KIND = "offline_clock_skew";
    /** IPR SW-7: discrepancy kind of a time accepted although older than the late-sync age. */
    public static final String LATE_SYNC_KIND = "offline_late_sync";

    /**
     * Where a flagged time of an online route is recorded (IPR review 1, finding 1). Installed at start-up by the
     * module that owns the discrepancy report; {@code source} names the route (for example {@code pos.sale}).
     */
    @FunctionalInterface
    public interface FlagRecorder {
        void record(String source, Resolved resolved);
    }

    private static volatile FlagRecorder recorder;

    private static volatile Supplier<Duration> futureLimit = () -> DEFAULT_FUTURE_SKEW;
    private static volatile Supplier<Duration> lateLimit = () -> DEFAULT_MAX_AGE;

    private OccurredAt() {}

    /**
     * A resolved device time: {@code at} is what to store (the receive time when the device was ahead), {@code device}
     * what the device sent.
     */
    public record Resolved(Instant at, Instant device, boolean clockSkew, boolean lateSync) {

        public boolean flagged() {
            return clockSkew || lateSync;
        }
    }

    /** Install the organisation-aware limits (future skew, late-sync age). */
    public static void limits(Supplier<Duration> future, Supplier<Duration> late) {
        futureLimit = future == null ? () -> DEFAULT_FUTURE_SKEW : future;
        lateLimit = late == null ? () -> DEFAULT_MAX_AGE : late;
    }

    /** The current future-skew limit (organisation setting, default 5 minutes). */
    public static Duration futureLimit() {
        try {
            Duration d = futureLimit.get();
            return d == null || d.isNegative() ? DEFAULT_FUTURE_SKEW : d;
        } catch (RuntimeException e) {
            return DEFAULT_FUTURE_SKEW;
        }
    }

    private static Duration lateLimit() {
        try {
            Duration d = lateLimit.get();
            return d == null || d.isNegative() ? DEFAULT_MAX_AGE : d;
        } catch (RuntimeException e) {
            return DEFAULT_MAX_AGE;
        }
    }

    /** The time to store for {@code value} (null when the client sent nothing); never refused for its clock. */
    public static Instant resolve(String value) {
        Resolved r = resolveFlagged(value);
        return r == null ? null : r.at();
    }

    /** Install the recorder of flagged online times (null removes it). */
    public static void recorder(FlagRecorder r) {
        recorder = r;
    }

    /**
     * {@link #resolve} for an online route that also receives the web client's offline-queue replays (POS sale and
     * payment, cash session, cash drawer): the time is accepted as {@link #resolve} does, and a flagged one
     * ({@code clockSkew} / {@code lateSync}) is recorded in the discrepancy report as the sync path does. Inside an
     * offline sync op ({@link EffectiveTime} bound offline) nothing is recorded: the sync push recorded the op's flag.
     */
    public static Instant resolveRecorded(String value, String source) {
        Resolved r = resolveFlagged(value);
        if (r == null) {
            return null;
        }
        if (r.flagged()) {
            EffectiveTime.Context ctx = EffectiveTime.current();
            FlagRecorder rec = recorder;
            if (rec != null && (ctx == null || !ctx.offline())) {
                rec.record(source, r);
            }
        }
        return r.at();
    }

    /** {@link #resolve} with the flags; null when the client sent nothing. */
    public static Resolved resolveFlagged(String value) {
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
        return checkFlagged(date);
    }

    /** The time to store for {@code date}: the receive time when it is too far ahead, else {@code date}. */
    public static Instant check(Instant date) {
        Resolved r = checkFlagged(date);
        return r == null ? null : r.at();
    }

    /** {@link #check} with the flags. */
    public static Resolved checkFlagged(Instant date) {
        if (date == null) {
            return null;
        }
        Instant now = Instant.now();
        if (date.isAfter(now.plus(futureLimit()))) {
            log.warn("[occurredAt] {} is ahead of the server ({}): stored as the receive time (clockSkew)", date, now);
            return new Resolved(now, date, true, false);
        }
        boolean late = date.isBefore(now.minus(lateLimit()));
        if (late) {
            log.info("[occurredAt] {} is older than {}: accepted (lateSync)", date, lateLimit());
        }
        return new Resolved(date, date, false, late);
    }
}
