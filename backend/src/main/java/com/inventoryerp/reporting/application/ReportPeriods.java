package com.inventoryerp.reporting.application;

import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.reporting.spi.ReportRequest;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Period presets and comparative periods. Pure: {@code today} is the organization's business date and
 * {@code fiscalStart} the first day of its current fiscal year (January 1 when none is defined).
 */
public final class ReportPeriods {

    public static final Set<String> PRESETS = Set.of("TODAY", "YESTERDAY", "THIS_MONTH", "LAST_MONTH",
            "THIS_QUARTER", "LAST_QUARTER", "THIS_YEAR", "LAST_YEAR", "FISCAL_YTD", "LAST_FISCAL_YEAR");
    public static final Set<String> COMPARE = Set.of("PRIOR_PERIOD", "PRIOR_YEAR", "BUDGET", "VARIANCE",
            "VARIANCE_PCT");

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private ReportPeriods() {}

    /** The current column: a range for period reports, a point in time ({@code from == null}) for balance reports. */
    public static ReportPeriod current(ReportDefinition def, ReportRequest r, LocalDate today) {
        return current(def, r, today, today.withDayOfYear(1));
    }

    /** As {@link #current(ReportDefinition, ReportRequest, LocalDate)} with the fiscal year start. */
    public static ReportPeriod current(ReportDefinition def, ReportRequest r, LocalDate today, LocalDate fiscalStart) {
        if (r.period() != null && !r.period().isBlank() && !PRESETS.contains(r.period())) {
            throw ApiException.badRequest("period must be one of: " + String.join(", ", PRESETS.stream().sorted()
                    .toList()));
        }
        LocalDate[] preset = r.period() == null || r.period().isBlank() ? null : preset(r.period(), today,
                fiscalStart);
        if (def.accepts(FilterKey.AS_OF)) {
            LocalDate asOf = date(r.asOf(), "asOf");
            if (asOf == null) {
                asOf = date(r.to(), "to");
            }
            if (asOf == null) {
                asOf = preset == null || preset[1].isAfter(today) ? today : preset[1];
            }
            return new ReportPeriod("CURRENT", "As of " + DAY.format(asOf), null, asOf);
        }
        LocalDate from = date(r.from(), "from");
        LocalDate to = date(r.to(), "to");
        if (from == null && to == null) {
            LocalDate[] p = preset == null ? preset("THIS_MONTH", today, fiscalStart) : preset;
            from = p[0];
            to = p[1];
        } else if (from == null) {
            from = to.withDayOfMonth(1);
        } else if (to == null) {
            to = today.isBefore(from) ? from : today;
        }
        if (from.isAfter(to)) {
            throw ApiException.badRequest("from must be on or before to");
        }
        return new ReportPeriod("CURRENT", label(from, to), from, to);
    }

    /** Comparative periods in request order; {@code VARIANCE} / {@code VARIANCE_PCT} are flags, not periods. */
    public static List<ReportPeriod> comparatives(ReportPeriod cur, List<String> compare) {
        List<ReportPeriod> out = new ArrayList<>();
        for (String c : compare) {
            if (!COMPARE.contains(c)) {
                throw ApiException.badRequest("compare may only contain: " + String.join(", ", COMPARE.stream()
                        .sorted().toList()));
            }
            ReportPeriod p = switch (c) {
                case "PRIOR_PERIOD" -> priorPeriod(cur);
                case "PRIOR_YEAR" -> priorYear(cur);
                default -> null; // BUDGET, VARIANCE and VARIANCE_PCT are columns, not periods
            };
            if (p != null && out.stream().noneMatch(x -> x.key().equals(p.key()))) {
                out.add(p);
            }
        }
        return out;
    }

    static ReportPeriod priorPeriod(ReportPeriod cur) {
        LocalDate to = cur.to();
        if (cur.from() == null) {
            LocalDate t = monthEnd(to) ? to.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()) : to.minusMonths(1);
            return new ReportPeriod("PRIOR_PERIOD", "As of " + DAY.format(t), null, t);
        }
        LocalDate from = cur.from();
        if (from.getDayOfMonth() == 1 && monthEnd(to)) {
            long months = ChronoUnit.MONTHS.between(from, to.plusDays(1));
            LocalDate f = from.minusMonths(months);
            LocalDate t = from.minusDays(1);
            return new ReportPeriod("PRIOR_PERIOD", label(f, t), f, t);
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate t = from.minusDays(1);
        LocalDate f = t.minusDays(days - 1);
        return new ReportPeriod("PRIOR_PERIOD", label(f, t), f, t);
    }

    static ReportPeriod priorYear(ReportPeriod cur) {
        LocalDate t = monthEnd(cur.to()) ? cur.to().minusYears(1).with(TemporalAdjusters.lastDayOfMonth())
                : cur.to().minusYears(1);
        if (cur.from() == null) {
            return new ReportPeriod("PRIOR_YEAR", "As of " + DAY.format(t), null, t);
        }
        LocalDate f = cur.from().minusYears(1);
        return new ReportPeriod("PRIOR_YEAR", label(f, t), f, t);
    }

    static LocalDate[] preset(String preset, LocalDate today, LocalDate fiscalStart) {
        LocalDate q = today.withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
        return switch (preset) {
            case "TODAY" -> new LocalDate[] {today, today};
            case "YESTERDAY" -> new LocalDate[] {today.minusDays(1), today.minusDays(1)};
            case "THIS_MONTH" -> new LocalDate[] {today.withDayOfMonth(1), today.with(TemporalAdjusters
                    .lastDayOfMonth())};
            case "LAST_MONTH" -> {
                LocalDate m = today.minusMonths(1);
                yield new LocalDate[] {m.withDayOfMonth(1), m.with(TemporalAdjusters.lastDayOfMonth())};
            }
            case "THIS_QUARTER" -> new LocalDate[] {q, q.plusMonths(3).minusDays(1)};
            case "LAST_QUARTER" -> new LocalDate[] {q.minusMonths(3), q.minusDays(1)};
            case "THIS_YEAR" -> new LocalDate[] {today.withDayOfYear(1), today.withMonth(12).withDayOfMonth(31)};
            case "LAST_YEAR" -> new LocalDate[] {today.minusYears(1).withDayOfYear(1), today.minusYears(1)
                    .withMonth(12).withDayOfMonth(31)};
            case "FISCAL_YTD" -> new LocalDate[] {fiscalStart, today};
            case "LAST_FISCAL_YEAR" -> new LocalDate[] {fiscalStart.minusYears(1), fiscalStart.minusDays(1)};
            default -> throw ApiException.badRequest("Unknown period " + preset);
        };
    }

    static String label(LocalDate from, LocalDate to) {
        if (from.getDayOfMonth() == 1 && monthEnd(to) && from.getYear() == to.getYear()
                && from.getMonthValue() == to.getMonthValue()) {
            return MONTH.format(from);
        }
        if (from.getDayOfYear() == 1 && to.getMonthValue() == 12 && to.getDayOfMonth() == 31
                && from.getYear() == to.getYear()) {
            return "FY " + from.getYear();
        }
        return DAY.format(from) + " – " + DAY.format(to);
    }

    private static boolean monthEnd(LocalDate d) {
        return d.getDayOfMonth() == d.lengthOfMonth();
    }

    static LocalDate date(String v, String label) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.trim().length() > 10 ? v.trim().substring(0, 10) : v.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid `" + label + "` date: " + v);
        }
    }
}
