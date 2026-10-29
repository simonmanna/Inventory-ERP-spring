package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Driver allowance pricing (fleet 1.2, ADR-S009): which allowance rule applies to a claim line and what it pays.
 *
 * <p>A rule applies to a line when it is active, has the line's basis ({@code per_trip / per_day / per_night}), is
 * effective on the claim date ({@code effectiveFrom ≤ date ≤ effectiveTo}, open-ended without {@code effectiveTo}) and
 * its optional scope matches: no vehicle class or the claim vehicle's class, no route zone or the claim's zone
 * (case-insensitive). Among the applicable rules the most specific wins — class and zone (3) over class (2) over
 * zone (1) over a general rule (0); ties go to the latest {@code effectiveFrom}, then the code. Amounts are
 * {@code rate × quantity} rounded HALF_UP to 2 decimals.
 */
public final class AllowanceCalculator {

    private AllowanceCalculator() {}

    /** The fields of an allowance rule the calculator looks at. */
    public record Rule(String id, String code, String basis, BigDecimal amount, LocalDate effectiveFrom,
            LocalDate effectiveTo, String categoryId, String routeZone, boolean active) {}

    /** The rule paying {@code basis} on {@code on} for a vehicle of {@code categoryId} in {@code routeZone}, or null. */
    public static Rule select(List<Rule> rules, String basis, LocalDate on, String categoryId, String routeZone) {
        return rules.stream()
                .filter(r -> applies(r, basis, on, categoryId, routeZone))
                .max(Comparator.comparingInt(AllowanceCalculator::specificity)
                        .thenComparing(Rule::effectiveFrom)
                        .thenComparing(Rule::code, Comparator.reverseOrder()))
                .orElse(null);
    }

    static boolean applies(Rule r, String basis, LocalDate on, String categoryId, String routeZone) {
        return r.active() && r.basis().equals(basis) && !on.isBefore(r.effectiveFrom())
                && (r.effectiveTo() == null || !on.isAfter(r.effectiveTo()))
                && (r.categoryId() == null || r.categoryId().equals(categoryId))
                && (zone(r.routeZone()) == null || zone(r.routeZone()).equals(zone(routeZone)));
    }

    static int specificity(Rule r) {
        return (r.categoryId() != null ? 2 : 0) + (zone(r.routeZone()) != null ? 1 : 0);
    }

    /** {@code rate × quantity}, 2 decimals HALF_UP. */
    public static BigDecimal amount(BigDecimal rate, BigDecimal quantity) {
        return rate.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    }

    /** Sum of line amounts, 2 decimals. */
    public static BigDecimal total(List<BigDecimal> amounts) {
        return amounts.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Do two rules compete for the same lines: same basis, same class scope, same zone scope and overlapping effective
     * periods? (Two such rules would make the price ambiguous, so the service refuses the second one.)
     */
    public static boolean overlaps(Rule a, Rule b) {
        if (!a.basis().equals(b.basis()) || !Objects.equals(a.categoryId(), b.categoryId())
                || !Objects.equals(zone(a.routeZone()), zone(b.routeZone()))) {
            return false;
        }
        LocalDate aTo = a.effectiveTo() == null ? LocalDate.MAX : a.effectiveTo();
        LocalDate bTo = b.effectiveTo() == null ? LocalDate.MAX : b.effectiveTo();
        return !a.effectiveFrom().isAfter(bTo) && !b.effectiveFrom().isAfter(aTo);
    }

    /** Normalized route zone (trimmed, lower case) or null when blank. */
    static String zone(String z) {
        return z == null || z.isBlank() ? null : z.trim().toLowerCase(Locale.ROOT);
    }
}
