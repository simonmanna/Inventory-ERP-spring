package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tyre lifecycle rules (fleet 1.7, ADR-S009 §3): the status machine and the cost / wear calculator.
 *
 * <p>Status machine: {@code in_stock → fitted}; {@code fitted → fitted} (rotation), {@code removed} or
 * {@code scrapped}; {@code removed → fitted | retreaded | scrapped}; {@code retreaded → fitted | scrapped};
 * {@code in_stock → scrapped}; {@code scrapped} is terminal.
 *
 * <p>Cost per km = (purchase cost + retread costs) / km run over all stints; per engine hour likewise for plant tyres.
 * Wear rate and projected life extrapolate the tread used so far linearly down to the minimum tread depth.
 */
public final class TyrePolicy {

    public static final String IN_STOCK = "in_stock";
    public static final String FITTED = "fitted";
    public static final String REMOVED = "removed";
    public static final String RETREADED = "retreaded";
    public static final String SCRAPPED = "scrapped";

    private static final Map<String, Set<String>> NEXT = Map.of(
            IN_STOCK, Set.of(FITTED, SCRAPPED),
            FITTED, Set.of(FITTED, REMOVED, SCRAPPED),
            REMOVED, Set.of(FITTED, RETREADED, SCRAPPED),
            RETREADED, Set.of(FITTED, SCRAPPED),
            SCRAPPED, Set.of());

    /** Statuses a tyre can be fitted from. */
    public static final List<String> FITTABLE = List.of(IN_STOCK, REMOVED, RETREADED);

    private TyrePolicy() {}

    public static boolean canMove(String from, String to) {
        return NEXT.getOrDefault(from, Set.of()).contains(to);
    }

    /** Throws a 400 naming the tyre when {@code from → to} is not allowed. */
    public static void assertTransition(String serial, String from, String to) {
        if (SCRAPPED.equals(from)) {
            throw ApiException.badRequest("Tyre " + serial + " is scrapped; no further changes are possible");
        }
        if (!canMove(from, to)) {
            throw ApiException.badRequest("Tyre " + serial + " is " + label(from) + " and cannot be " + verb(to));
        }
    }

    static String label(String status) {
        return status == null ? "" : status.replace('_', ' ');
    }

    private static String verb(String to) {
        return switch (to) {
            case FITTED -> "fitted";
            case REMOVED -> "removed";
            case RETREADED -> "retreaded";
            case SCRAPPED -> "scrapped";
            default -> "moved to " + label(to);
        };
    }

    // ------------------------------------------------------------------ calculator

    /** Wear and cost figures of one tyre (null where the inputs do not allow a value). */
    public record TyreStats(BigDecimal totalCost, BigDecimal km, BigDecimal hours, BigDecimal costPerKm,
            BigDecimal costPerHour, BigDecimal treadUsedMm, BigDecimal wearPer1000Km, BigDecimal projectedLifeKm,
            BigDecimal remainingTreadPct) {}

    /** Distance (or hours) of a stint: end − start, never negative; zero when either end is unknown. */
    public static BigDecimal stint(BigDecimal start, BigDecimal end) {
        if (start == null || end == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal d = end.subtract(start);
        return d.signum() < 0 ? BigDecimal.ZERO : d;
    }

    /** {@code cost / units} at 4 decimals (HALF_UP); null when nothing has been run yet. */
    public static BigDecimal costPer(BigDecimal cost, BigDecimal units) {
        if (cost == null || units == null || units.signum() <= 0) {
            return null;
        }
        return cost.divide(units, 4, RoundingMode.HALF_UP);
    }

    /**
     * Stats of a tyre: {@code purchaseCost + extraCost} over {@code km} / {@code hours}; tread used, wear per 1,000
     * km, projected life (km at which the tread reaches {@code minTread}) and remaining usable tread %.
     */
    public static TyreStats stats(BigDecimal purchaseCost, BigDecimal extraCost, BigDecimal km, BigDecimal hours,
            BigDecimal newTread, BigDecimal currentTread, BigDecimal minTread) {
        BigDecimal cost = nz(purchaseCost).add(nz(extraCost));
        BigDecimal kmRun = nz(km);
        BigDecimal hoursRun = nz(hours);
        BigDecimal used = null;
        BigDecimal wear = null;
        BigDecimal life = null;
        BigDecimal remaining = null;
        BigDecimal min = nz(minTread);
        if (newTread != null && currentTread != null) {
            used = newTread.subtract(currentTread).max(BigDecimal.ZERO);
            if (kmRun.signum() > 0 && used.signum() > 0) {
                wear = used.multiply(BigDecimal.valueOf(1000)).divide(kmRun, 3, RoundingMode.HALF_UP);
                BigDecimal usable = newTread.subtract(min);
                if (usable.signum() > 0) {
                    life = kmRun.multiply(usable).divide(used, 0, RoundingMode.HALF_UP);
                }
            }
            BigDecimal usable = newTread.subtract(min);
            if (usable.signum() > 0) {
                BigDecimal pct = currentTread.subtract(min).multiply(BigDecimal.valueOf(100))
                        .divide(usable, 1, RoundingMode.HALF_UP);
                remaining = pct.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100).setScale(1));
            }
        }
        return new TyreStats(cost.setScale(2, RoundingMode.HALF_UP), kmRun, hoursRun, costPer(cost, kmRun),
                costPer(cost, hoursRun), used, wear, life, remaining);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
