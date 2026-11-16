package com.inventoryerp.accounting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure calendar and money arithmetic behind recurring journal templates and deferral schedules (ADR-S073). No I/O,
 * so it is unit-tested without a Spring context.
 */
public final class GlSchedules {

    private GlSchedules() {}

    public static final Set<String> FREQUENCIES = Set.of("daily", "weekly", "monthly", "quarterly", "yearly");

    /** Money slices are rounded to 2 dp, half-up; the last slice takes the remainder so the slices sum exactly. */
    static final int SCALE = 2;

    /**
     * The {@code n}-th occurrence (0 = {@code start}) of a calendar schedule. Months are added from the anchor, so a
     * template starting on the 31st falls on the last day of shorter months and returns to the 31st afterwards.
     */
    public static LocalDate occurrence(LocalDate start, String frequency, int interval, long n) {
        long step = n * Math.max(1, interval);
        return switch (frequency) {
            case "daily" -> start.plusDays(step);
            case "weekly" -> start.plusWeeks(step);
            case "monthly" -> start.plusMonths(step);
            case "quarterly" -> start.plusMonths(step * 3);
            case "yearly" -> start.plusYears(step);
            default -> throw new IllegalArgumentException("Unknown frequency " + frequency);
        };
    }

    /** First occurrence strictly after {@code after} (or {@code start} itself when {@code after} is before it). */
    public static LocalDate nextAfter(LocalDate start, String frequency, int interval, LocalDate after) {
        if (after.isBefore(start)) {
            return start;
        }
        long unitDays = switch (frequency) {
            case "daily" -> 1;
            case "weekly" -> 7;
            case "monthly" -> 28;
            case "quarterly" -> 89;
            default -> 365;
        } * Math.max(1, interval);
        // Estimate from below, then walk forward: occurrences are monotonic in n.
        long n = Math.max(0, ChronoUnit.DAYS.between(start, after) / unitDays - 1);
        while (n > 0 && occurrence(start, frequency, interval, n).isAfter(after)) {
            n--;
        }
        LocalDate d = occurrence(start, frequency, interval, n);
        while (!d.isAfter(after)) {
            n++;
            d = occurrence(start, frequency, interval, n);
        }
        return d;
    }

    /** One planned release of a deferral schedule. */
    public record Slice(int index, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount) {}

    /**
     * Release plan of a deferral: {@code monthly} = {@code months} equal slices of one month each from
     * {@code start}; {@code daily} = calendar-month slices over {@code [start, start + months - 1 day]} weighted by the
     * days each month holds. Both round each slice to 2 dp and put the remainder on the last slice.
     */
    public static List<Slice> deferralPlan(BigDecimal total, LocalDate start, int months, String method) {
        List<Slice> out = new ArrayList<>();
        LocalDate end = start.plusMonths(months).minusDays(1);
        if ("daily".equals(method)) {
            long totalDays = ChronoUnit.DAYS.between(start, end) + 1;
            long cumulativeDays = 0;
            BigDecimal released = BigDecimal.ZERO;
            LocalDate from = start;
            int index = 1;
            while (!from.isAfter(end)) {
                LocalDate monthEnd = from.withDayOfMonth(from.lengthOfMonth());
                LocalDate to = monthEnd.isAfter(end) ? end : monthEnd;
                cumulativeDays += ChronoUnit.DAYS.between(from, to) + 1;
                BigDecimal cumulative = to.equals(end) ? total
                        : total.multiply(BigDecimal.valueOf(cumulativeDays))
                                .divide(BigDecimal.valueOf(totalDays), SCALE, RoundingMode.HALF_UP);
                out.add(new Slice(index++, from, to, cumulative.subtract(released)));
                released = cumulative;
                from = to.plusDays(1);
            }
            return out;
        }
        BigDecimal each = total.divide(BigDecimal.valueOf(months), SCALE, RoundingMode.HALF_UP);
        BigDecimal released = BigDecimal.ZERO;
        for (int k = 0; k < months; k++) {
            BigDecimal amount = k == months - 1 ? total.subtract(released) : each;
            out.add(new Slice(k + 1, start.plusMonths(k), start.plusMonths(k + 1).minusDays(1), amount));
            released = released.add(amount);
        }
        return out;
    }

    /** Net and tax of a tax-inclusive gross amount at {@code ratePercent}; net rounded 2 dp half-up. */
    public static BigDecimal[] splitInclusive(BigDecimal gross, BigDecimal ratePercent) {
        if (ratePercent == null || ratePercent.signum() == 0) {
            return new BigDecimal[] {gross, BigDecimal.ZERO};
        }
        BigDecimal net = gross.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(100).add(ratePercent), SCALE, RoundingMode.HALF_UP);
        return new BigDecimal[] {net, gross.subtract(net)};
    }
}
