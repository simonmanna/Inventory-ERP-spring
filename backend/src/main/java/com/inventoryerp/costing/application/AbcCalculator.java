package com.inventoryerp.costing.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Activity-based cost assignment (ADR-S030 §3), pure: rate = pool / total driver volume, each cost object gets rate ×
 * its volume. The largest object takes the rounding so the assigned amounts add up to the pool exactly. A pool with no
 * driver volume stays unassigned.
 */
public final class AbcCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    /** One activity's pool and driver volume per cost object. */
    public record Activity(String id, BigDecimal pool, Map<String, BigDecimal> volumes) {}

    /** Rate, total volume, the cost per object and what stayed unassigned. */
    public record Assignment(String id, BigDecimal pool, BigDecimal driverTotal, BigDecimal rate,
            Map<String, BigDecimal> costs, BigDecimal unassigned) {}

    private AbcCalculator() {}

    public static Assignment assign(Activity a) {
        BigDecimal pool = a.pool().setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : a.volumes().values()) {
            if (v != null && v.signum() > 0) {
                total = total.add(v);
            }
        }
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        if (total.signum() == 0 || pool.signum() == 0) {
            return new Assignment(a.id(), pool, total, BigDecimal.ZERO, costs, pool);
        }
        BigDecimal rate = pool.divide(total, MC);
        BigDecimal assigned = BigDecimal.ZERO;
        String largest = null;
        BigDecimal largestVolume = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : a.volumes().entrySet()) {
            BigDecimal v = e.getValue();
            if (v == null || v.signum() <= 0) {
                continue;
            }
            BigDecimal cost = v.multiply(rate, MC).setScale(2, RoundingMode.HALF_UP);
            costs.put(e.getKey(), cost);
            assigned = assigned.add(cost);
            if (v.compareTo(largestVolume) > 0) {
                largestVolume = v;
                largest = e.getKey();
            }
        }
        BigDecimal rest = pool.subtract(assigned);
        if (rest.signum() != 0 && largest != null) {
            costs.put(largest, costs.get(largest).add(rest));
        }
        return new Assignment(a.id(), pool, total, rate.setScale(6, RoundingMode.HALF_UP), costs, BigDecimal.ZERO
                .setScale(2));
    }

    /** Sum of the costs of {@code assignments} per object. */
    public static Map<String, BigDecimal> totals(List<Assignment> assignments) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Assignment a : assignments) {
            a.costs().forEach((k, v) -> out.merge(k, v, BigDecimal::add));
        }
        return out;
    }
}
