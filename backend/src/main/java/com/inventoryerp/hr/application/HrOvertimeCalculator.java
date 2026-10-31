package com.inventoryerp.hr.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Overtime maths (HR-3), free of the database: which rule applies to an employee, and overtime minutes per day turned
 * into weighted hours. The payroll engine multiplies the weighted hours by the hourly rate.
 */
public final class HrOvertimeCalculator {

    private static final BigDecimal MINUTES_PER_HOUR = new BigDecimal(60);

    private HrOvertimeCalculator() {}

    /** The rule of an employee: the first targeted rule that matches, else the first untargeted rule, else null. */
    public static Map<String, Object> pick(List<Map<String, Object>> rules, Map<String, Object> employee) {
        Map<String, Object> fallback = null;
        for (Map<String, Object> rule : rules) {
            Object target = rule.get("appliesTo");
            if (target == null || target.toString().isBlank()) {
                if (fallback == null) {
                    fallback = rule;
                }
            } else if (matches(target.toString(), employee)) {
                return rule;
            }
        }
        return fallback;
    }

    private static boolean matches(String appliesTo, Map<String, Object> employee) {
        String[] parts = appliesTo.split(":", 2);
        return parts.length == 2 && parts[1].equals(String.valueOf(employee.get(parts[0])));
    }

    /** Sum of (minutes ÷ 60 × the day's multiplier); a holiday beats a weekend. */
    public static BigDecimal weightedHours(Map<LocalDate, BigDecimal> minutesByDay, Map<String, Object> rule,
            Predicate<LocalDate> holiday) {
        Set<DayOfWeek> weekend = weekendDays(String.valueOf(rule.get("weekendDays")));
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, BigDecimal> day : minutesByDay.entrySet()) {
            LocalDate date = day.getKey();
            String kind = holiday.test(date) ? "holidayMultiplier"
                    : weekend.contains(date.getDayOfWeek()) ? "weekendMultiplier" : "weekdayMultiplier";
            total = total.add(day.getValue().multiply(new BigDecimal(rule.get(kind).toString())));
        }
        return total.divide(MINUTES_PER_HOUR, 6, RoundingMode.HALF_UP);
    }

    static Set<DayOfWeek> weekendDays(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> DayOfWeek.valueOf(s.toUpperCase())).collect(Collectors.toSet());
    }
}
