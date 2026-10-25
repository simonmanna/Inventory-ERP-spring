package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.shared.web.ApiException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Organisation-calendar dates for money screens (port of {@code org-dates.ts}): a Kampala sale at 01:30
 * belongs to that local day, so day bounds use the organisation time zone.
 */
public final class OrgDates {

    private OrgDates() {}

    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Trading day starts at 06:00 local. */
    public static final int TRADING_DAY_START_HOUR = 6;

    /** The organisation's IANA time zone (UTC when unset or invalid). */
    public static ZoneId orgTimezone(JdbcClient jdbc, String organizationId) {
        String tz = ReadMemo.get(() -> jdbc.sql("SELECT timezone FROM \"Organization\" WHERE id = ?").param(organizationId)
                .query(String.class).optional().orElse(null), "org.timezone", organizationId);
        if (tz == null || tz.isEmpty()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /** Today's date (YYYY-MM-DD) in the zone. */
    public static String orgToday(ZoneId zone, Instant now) {
        return now.atZone(zone).toLocalDate().toString();
    }

    /** [start, end) of a local calendar day. */
    public static Instant[] zonedDayRange(String date, ZoneId zone) {
        LocalDate d = LocalDate.parse(date);
        return new Instant[] {d.atStartOfDay(zone).toInstant(), d.plusDays(1).atStartOfDay(zone).toInstant()};
    }

    /** JS {@code new Date(v)} for ISO strings; {@code null} when invalid. */
    static Instant parse(String v) {
        try {
            if (DATE_ONLY.matcher(v).matches()) {
                return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            try {
                return OffsetDateTime.parse(v).toInstant();
            } catch (DateTimeParseException e) {
                return Instant.parse(v.endsWith("Z") ? v : v + "Z");
            }
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * A filter bound: a bare date is a local calendar day ({@code start} = first instant, {@code end} = last
     * millisecond inclusive); full ISO timestamps are taken as-is.
     */
    public static Instant orgDateBound(String value, String label, ZoneId zone, boolean start) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String v = value.trim();
        if (DATE_ONLY.matcher(v).matches()) {
            Instant[] range = zonedDayRange(v, zone);
            return start ? range[0] : range[1].minusMillis(1);
        }
        Instant d = parse(v);
        if (d == null) {
            throw ApiException.badRequest("Invalid `" + label + "` date: " + value);
        }
        return d;
    }

    /** Posting instant for a manual money operation: today → now; another bare date → local noon; no future. */
    public static Instant orgPostingInstant(String value, ZoneId zone, Instant now) {
        if (value == null || value.isEmpty()) {
            return now;
        }
        String v = value.trim();
        if (!DATE_ONLY.matcher(v).matches()) {
            Instant d = parse(v);
            if (d == null) {
                throw ApiException.badRequest("Invalid date: " + value);
            }
            return d;
        }
        String today = orgToday(zone, now);
        if (v.compareTo(today) > 0) {
            throw ApiException.badRequest("The date cannot be in the future");
        }
        if (v.equals(today)) {
            return now;
        }
        return zonedDayRange(v, zone)[0].plusSeconds(12 * 3600);
    }

    /** The trading date (UTC-midnight instant) an instant belongs to, the trading day starting at 06:00 local. */
    public static Instant tradingDate(Instant at, ZoneId zone) {
        Instant shifted = at.minusSeconds(TRADING_DAY_START_HOUR * 3600L);
        return LocalDate.parse(orgToday(zone, shifted)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
