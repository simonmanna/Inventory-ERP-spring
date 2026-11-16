package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The accounting day is the organisation-local calendar day (ADR-S070). A posting at 22:30 UTC on 31 January is a
 * 1 February entry for an organisation on Africa/Kampala (UTC+3): period control, entry numbering and every ledger
 * report bucket it by {@code Organization.timezone}. For a UTC organisation every method here is the identity of the
 * old UTC-day behaviour.
 *
 * <p>Two kinds of value meet the ledger:
 *
 * <ul>
 *   <li>{@code JournalEntry.postingDate} and other true timestamps: a real instant (stored as UTC).
 *   <li>Date-like values: a bare {@code YYYY-MM-DD} from a request, and the stored bounds of a fiscal period, fiscal
 *       year, VAT settlement or books lock. These are <em>org-local wall-clock</em> values: {@code 2026-01-31 23:59:59.999}
 *       on a period means the end of 31 January in the organisation's zone. {@link #fromWallClock} turns one into the
 *       real instant to compare with {@code postingDate}; {@link #toWallClock} goes the other way.
 * </ul>
 *
 * Bounds are computed here, in Java, so ledger SQL keeps index-friendly {@code postingDate >= ? AND postingDate < ?}.
 */
public final class AccountingDay {

    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final LocalTime NOON = LocalTime.NOON;

    private AccountingDay() {}

    /** The current tenant's zone (UTC when unset or invalid). */
    public static ZoneId zone(JdbcClient jdbc) {
        return zone(jdbc, TenantContext.organizationId());
    }

    /** {@code organizationId}'s zone (UTC when unset or invalid). */
    public static ZoneId zone(JdbcClient jdbc, String organizationId) {
        return organizationId == null ? ZoneOffset.UTC : OrgDates.orgTimezone(jdbc, organizationId);
    }

    /** Whether the zone is UTC itself (then every conversion here is the identity). */
    public static boolean isUtc(ZoneId zone) {
        return zone == null || zone.normalized().equals(ZoneOffset.UTC);
    }

    /** The accounting (org-local) date of an instant. */
    public static LocalDate of(Instant at, ZoneId zone) {
        return at.atZone(zone == null ? ZoneOffset.UTC : zone).toLocalDate();
    }

    /** The accounting year of an instant (entry numbering). */
    public static int year(Instant at, ZoneId zone) {
        return of(at, zone).getYear();
    }

    /** First instant of the local day {@code d}. */
    public static Instant start(LocalDate d, ZoneId zone) {
        return d.atStartOfDay(zone).toInstant();
    }

    /** First instant after the local day {@code d} (exclusive end). */
    public static Instant endExclusive(LocalDate d, ZoneId zone) {
        return d.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** {@link #start} as a JDBC bound for {@code postingDate}. */
    public static Timestamp startTs(LocalDate d, ZoneId zone) {
        return FiscalPeriodControl.ts(start(d, zone));
    }

    /** {@link #endExclusive} as a JDBC bound for {@code postingDate}. */
    public static Timestamp endExclusiveTs(LocalDate d, ZoneId zone) {
        return FiscalPeriodControl.ts(endExclusive(d, zone));
    }

    /** Last millisecond of the local day {@code d} (inclusive end; {@code postingDate} is {@code timestamp(3)}). */
    public static Instant endInclusive(LocalDate d, ZoneId zone) {
        return endExclusive(d, zone).minusMillis(1);
    }

    /**
     * A request date: a bare {@code YYYY-MM-DD} is the first instant of that local day; a full ISO timestamp is taken
     * as-is (as {@link OrgDates#parse}). {@code null} for blank, {@code null} result for invalid input.
     */
    public static Instant parse(String value, ZoneId zone) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String v = value.trim();
        if (DATE_ONLY.matcher(v).matches() && !isUtc(zone)) {
            try {
                return start(LocalDate.parse(v), zone);
            } catch (java.time.format.DateTimeParseException e) {
                return null;
            }
        }
        return OrgDates.parse(v);
    }

    /** Whether {@code value} is a bare {@code YYYY-MM-DD}. */
    public static boolean isDateOnly(String value) {
        return value != null && DATE_ONLY.matcher(value.trim()).matches();
    }

    /** A stored org-local wall-clock value (read as UTC fields) as the real instant. Identity for UTC. */
    public static Instant fromWallClock(Instant wallClock, ZoneId zone) {
        if (wallClock == null || isUtc(zone)) {
            return wallClock;
        }
        return wallClock.atOffset(ZoneOffset.UTC).toLocalDateTime().atZone(zone).toInstant();
    }

    /** As {@link #fromWallClock(Instant, ZoneId)} for a JDBC timestamp (a bound to pass back to SQL). */
    public static Timestamp fromWallClock(Timestamp wallClock, ZoneId zone) {
        if (wallClock == null || isUtc(zone)) {
            return wallClock;
        }
        return FiscalPeriodControl.ts(wallClock.toLocalDateTime().atZone(zone).toInstant());
    }

    /**
     * SQL for a stored org-local wall-clock column (e.g. {@code p."startDate"}) as the UTC timestamp it denotes, to compare
     * with {@code postingDate} without wrapping {@code postingDate} itself. The column itself for UTC. The zone id comes
     * from {@link ZoneId} (validated: letters, digits, {@code / _ + -}), so inlining it is safe.
     */
    public static String sqlFromWallClock(String column, ZoneId zone) {
        if (isUtc(zone)) {
            return column;
        }
        String id = zone.getId();
        if (!id.matches("[A-Za-z0-9/_+\\-]+")) {
            throw new IllegalArgumentException("Unexpected zone id: " + id);
        }
        return "(" + column + " AT TIME ZONE '" + id + "' AT TIME ZONE 'UTC')";
    }

    /** A real instant as the org-local wall clock (read as UTC fields), to compare with stored date-like bounds. */
    public static Instant toWallClock(Instant at, ZoneId zone) {
        if (at == null || isUtc(zone)) {
            return at;
        }
        return at.atZone(zone).toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    /**
     * Normalise a posting instant. A date-only posting arrives as exactly UTC midnight of its date (callers build it with
     * {@code LocalDate.atStartOfDay(ZoneOffset.UTC)}); outside UTC it becomes local noon of that date so it falls on the
     * intended accounting day in any zone (UTC midnight is the previous evening in New York). Real timestamps, and every
     * instant of a UTC organisation, are returned unchanged.
     */
    public static Instant normalize(Instant at, ZoneId zone) {
        if (at == null || isUtc(zone)) {
            return at;
        }
        if (at.getEpochSecond() % 86_400 != 0 || at.getNano() != 0) {
            return at;
        }
        return at.atOffset(ZoneOffset.UTC).toLocalDate().atTime(NOON).atZone(zone).toInstant();
    }
}
