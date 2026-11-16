package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase A A2 (plan §7): splits a truck-level customer-weight variance over the load lines it can belong to. Pure; no
 * database. Each line's share is proportional to its weight w = E × kg per unit, capped at the line's ceiling (cap =
 * E − the line's other weight claims, so returned, damaged and POD shortage are already out), with the overflow
 * redistributed over the uncapped lines until no cap binds; quantities are then floored to the load line's scale
 * (0.001: the line columns are NUMERIC(14, 3)) and the rounding remainder is handed out one step at a time by
 * largest remainder (ties by line number), never past the variance or a cap. What cannot be placed — beyond every
 * cap, or on a line with no weight factor (needs a manual entry) — is the unallocated remainder.
 */
public final class WeightAllocation {

    /** Quantity scale of a load line. */
    public static final int QTY_SCALE = 3;

    private static final BigDecimal STEP = BigDecimal.ONE.movePointLeft(QTY_SCALE);
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_UP);

    /**
     * One load line: eligible quantity E (line units), ceiling {@code cap} (line units, ≤ E) and kg per line unit
     * (null when the unit has no weight conversion).
     */
    public record Line(String id, int lineNumber, BigDecimal eligibleQty, BigDecimal capQty, BigDecimal kgPerUnit) {

        boolean weighable() {
            return kgPerUnit != null && kgPerUnit.signum() > 0;
        }
    }

    /** A line's share: quantity in line units, its kg, whether its cap bound, whether it needs a manual entry. */
    public record Share(String lineId, BigDecimal qty, BigDecimal kg, boolean capped, boolean needsManual) {}

    /** The proposal: shares in input order, kg placed and the unallocated remainder (both ≥ 0). */
    public record Result(List<Share> shares, BigDecimal allocatedKg, BigDecimal unallocatedKg) {}

    private WeightAllocation() {}

    /** Proposed split of {@code varianceKg} (> 0) over {@code lines}. */
    public static Result propose(BigDecimal varianceKg, List<Line> lines) {
        BigDecimal v = varianceKg == null || varianceKg.signum() < 0 ? BigDecimal.ZERO : varianceKg;
        Map<String, BigDecimal> exactKg = new LinkedHashMap<>();
        Map<String, Boolean> capped = new LinkedHashMap<>();
        List<Line> open = new ArrayList<>();
        for (Line l : lines) {
            exactKg.put(l.id(), BigDecimal.ZERO);
            capped.put(l.id(), false);
            if (l.weighable() && cap(l).signum() > 0 && weight(l).signum() > 0) {
                open.add(l);
            }
        }
        // Proportional split with cap-and-redistribute (each round caps at least one line or settles every share).
        while (!open.isEmpty()) {
            BigDecimal placed = exactKg.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal left = v.subtract(placed);
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal totalW = open.stream().map(WeightAllocation::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Line> breached = new ArrayList<>();
            for (Line l : open) {
                BigDecimal share = left.multiply(weight(l), MC).divide(totalW, MC);
                if (share.compareTo(capKg(l)) >= 0) {
                    breached.add(l);
                }
            }
            if (breached.isEmpty()) {
                for (Line l : open) {
                    exactKg.put(l.id(), left.multiply(weight(l), MC).divide(totalW, MC));
                }
                break;
            }
            for (Line l : breached) {
                exactKg.put(l.id(), capKg(l));
                capped.put(l.id(), true);
            }
            open.removeAll(breached);
        }
        // Floor to the line scale, then largest remainder.
        Map<String, BigDecimal> qty = new LinkedHashMap<>();
        Map<String, BigDecimal> rem = new LinkedHashMap<>();
        BigDecimal sumKg = BigDecimal.ZERO;
        for (Line l : lines) {
            if (!l.weighable()) {
                qty.put(l.id(), BigDecimal.ZERO.setScale(QTY_SCALE));
                continue;
            }
            BigDecimal exact = exactKg.get(l.id()).divide(l.kgPerUnit(), MC);
            BigDecimal floored = exact.setScale(QTY_SCALE, RoundingMode.DOWN).min(cap(l));
            qty.put(l.id(), floored);
            rem.put(l.id(), exact.subtract(floored));
            sumKg = sumKg.add(floored.multiply(l.kgPerUnit()));
        }
        List<Line> order = lines.stream().filter(Line::weighable)
                .sorted(Comparator.comparing((Line l) -> rem.get(l.id())).reversed()
                        .thenComparingInt(Line::lineNumber))
                .toList();
        for (Line l : order) {
            BigDecimal next = qty.get(l.id()).add(STEP);
            BigDecimal stepKg = STEP.multiply(l.kgPerUnit());
            if (rem.get(l.id()).signum() > 0 && next.compareTo(cap(l)) <= 0 && sumKg.add(stepKg).compareTo(v) <= 0) {
                qty.put(l.id(), next);
                sumKg = sumKg.add(stepKg);
            }
        }
        List<Share> shares = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (Line l : lines) {
            BigDecimal q = qty.get(l.id());
            BigDecimal kg = l.weighable() ? kg(q, l.kgPerUnit()) : BigDecimal.ZERO.setScale(QTY_SCALE);
            allocated = allocated.add(kg);
            shares.add(new Share(l.id(), q, kg, capped.get(l.id()) || (l.weighable() && q.compareTo(cap(l)) == 0
                    && cap(l).signum() > 0 && q.signum() > 0), !l.weighable()));
        }
        BigDecimal unallocated = v.subtract(allocated).max(BigDecimal.ZERO).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        return new Result(shares, allocated, unallocated);
    }

    /** Kg of {@code qty} line units at {@code kgPerUnit}, at the kg column scale. */
    public static BigDecimal kg(BigDecimal qty, BigDecimal kgPerUnit) {
        return qty.multiply(kgPerUnit).setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal cap(Line l) {
        return l.capQty() == null || l.capQty().signum() < 0 ? BigDecimal.ZERO : l.capQty();
    }

    private static BigDecimal capKg(Line l) {
        return cap(l).multiply(l.kgPerUnit());
    }

    private static BigDecimal weight(Line l) {
        BigDecimal e = l.eligibleQty() == null || l.eligibleQty().signum() < 0 ? BigDecimal.ZERO : l.eligibleQty();
        return e.multiply(l.kgPerUnit());
    }
}
