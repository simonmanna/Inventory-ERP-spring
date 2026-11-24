package com.inventoryerp.kernel.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * Whole-day bounds of an org-local wall-clock range (ADR-S070). Fiscal periods and fiscal years store the local clock
 * time of their first and last instant, read back as UTC fields: a range starts at 00:00:00.000 of its first day and
 * ends at 23:59:59.999 of its last day ({@code timestamp(3)}). An end stored at 00:00 drops the whole last day from
 * period control and from the period close (finance readiness audit 2026-11-23, P1-1).
 */
public final class WallClockDays {

    private static final LocalTime LAST_MILLISECOND = LocalTime.of(23, 59, 59, 999_000_000);
    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private WallClockDays() {}

    /** 00:00:00.000 of {@code day}. */
    public static LocalDateTime start(LocalDate day) {
        return day.atStartOfDay();
    }

    /** 23:59:59.999 of {@code day}: the last instant a {@code timestamp(3)} bound can hold. */
    public static LocalDateTime end(LocalDate day) {
        return day.atTime(LAST_MILLISECOND);
    }

    /** {@link #start} as a JDBC value. */
    public static Timestamp startTs(LocalDate day) {
        return Timestamp.valueOf(start(day));
    }

    /** {@link #end} as a JDBC value. */
    public static Timestamp endTs(LocalDate day) {
        return Timestamp.valueOf(end(day));
    }

    /** {@link #end} as the stored wall-clock instant (UTC fields), the representation the JPA entities use. */
    public static Instant endInstant(LocalDate day) {
        return end(day).toInstant(ZoneOffset.UTC);
    }

    /** Whether {@code value} is a bare {@code YYYY-MM-DD}. */
    public static boolean isDateOnly(String value) {
        return value != null && DATE_ONLY.matcher(value.trim()).matches();
    }
}
