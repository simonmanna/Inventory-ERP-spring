package com.inventoryerp.hr.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Pay calendar maths (HP-0): the periods of one pay group for one year. Pure, so it is unit tested without Spring.
 *
 * <ul>
 *   <li>MONTHLY: calendar months; payment on {@code paymentDay} (0 or past the month end = last day).
 *   <li>WEEKLY / BIWEEKLY: 7 / 14-day periods stepped from the anchor date; the periods that start in the year
 *       belong to it; payment {@code paymentDay} days after the period end.
 * </ul>
 */
public final class HrPayCalendar {

    /** One period: code, inclusive start and end days, payment date. */
    public record Slot(String code, LocalDate start, LocalDate end, LocalDate paymentDate) {}

    private HrPayCalendar() {}

    public static List<Slot> periods(String groupCode, String frequency, LocalDate anchor, int paymentDay, int year) {
        List<Slot> out = new ArrayList<>();
        if ("MONTHLY".equals(frequency)) {
            for (int m = 1; m <= 12; m++) {
                LocalDate start = LocalDate.of(year, m, 1);
                LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
                LocalDate pay = paymentDay <= 0 || paymentDay > end.getDayOfMonth() ? end
                        : start.withDayOfMonth(paymentDay);
                out.add(new Slot(String.format("%s-%d-%02d", groupCode, year, m), start, end, pay));
            }
            return out;
        }
        int length = "WEEKLY".equals(frequency) ? 7 : 14;
        if (anchor == null) {
            throw new IllegalArgumentException("anchor date required for " + frequency);
        }
        LocalDate start = anchor;
        if (start.getYear() < year) {
            long behind = ChronoUnit.DAYS.between(start, LocalDate.of(year, 1, 1));
            start = start.plusDays(((behind + length - 1) / length) * length);
        }
        int n = 1;
        while (start.getYear() == year) {
            LocalDate end = start.plusDays(length - 1L);
            out.add(new Slot(String.format("%s-%d-%s%02d", groupCode, year, length == 7 ? "W" : "B", n), start, end,
                    end.plusDays(paymentDay)));
            start = start.plusDays(length);
            n++;
        }
        return out;
    }
}
