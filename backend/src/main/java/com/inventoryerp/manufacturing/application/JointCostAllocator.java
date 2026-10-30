package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Joint-cost allocation over the outputs of one stage posting (ADR-S011 §4), pure and DB-free.
 *
 * <ol>
 *   <li><b>Carve-outs first</b>: by-products / waste valued at net realisable value ({@code nrv}: quantity × NRV per
 *       unit, capped at what is left of the joint cost), at {@code zero}, or — legacy order outputs — a fixed
 *       {@code carve_pct} of the joint cost.</li>
 *   <li><b>The rest</b> is shared by the {@code allocated} outputs (main, co-products, by-products set to allocated)
 *       by the rule's method: {@code quantity}, {@code weight} (quantity × weight per unit), {@code market_value}
 *       (quantity × unit market value) or {@code fixed_pct} (the outputs' percentages; the main output takes
 *       100 − Σ others when it has none). A basis that sums to zero falls back to quantity, then to the main
 *       output.</li>
 *   <li><b>Rounding</b>: every share is rounded down to {@code scale} and the remaining units go to the largest
 *       remainders (ties: list order), so the shares always sum <em>exactly</em> to the joint cost.</li>
 * </ol>
 */
public final class JointCostAllocator {

    private JointCostAllocator() {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Allocation methods. */
    public static final List<String> METHODS = List.of("quantity", "weight", "market_value", "fixed_pct");

    /**
     * One output to value. {@code valuation}: allocated | nrv | zero | carve_pct. {@code weightPerUnit},
     * {@code marketValue}, {@code fixedPct} and {@code nrvUnitValue} may be null when the method does not need them.
     */
    public record Share(String key, String role, BigDecimal quantity, String valuation, BigDecimal weightPerUnit,
            BigDecimal marketValue, BigDecimal fixedPct, BigDecimal nrvUnitValue) {

        public Share {
            quantity = quantity == null ? BigDecimal.ZERO : quantity;
            valuation = valuation == null ? "allocated" : valuation;
        }
    }

    /** The value of each output (by key, in input order) and how it was obtained. */
    public record Result(Map<String, BigDecimal> values, String method, BigDecimal carvedOut, BigDecimal allocated) {}

    /** Allocate {@code jointCost} over {@code shares}; the values sum exactly to {@code jointCost}. */
    public static Result allocate(BigDecimal jointCost, List<Share> shares, String method, int scale) {
        if (jointCost == null || jointCost.signum() < 0) {
            throw new IllegalArgumentException("jointCost must be >= 0");
        }
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("at least one output is required");
        }
        String m = method == null || !METHODS.contains(method) ? "quantity" : method;
        BigDecimal total = jointCost.setScale(scale, RoundingMode.HALF_UP);
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        shares.forEach(s -> values.put(s.key(), BigDecimal.ZERO.setScale(scale)));

        // 1) Carve-outs: NRV, zero, legacy fixed % of the pool.
        BigDecimal left = total;
        for (Share s : shares) {
            BigDecimal v;
            switch (s.valuation()) {
                case "nrv" -> v = nz(s.nrvUnitValue()).multiply(s.quantity()).setScale(scale, RoundingMode.HALF_UP);
                case "carve_pct" -> v = total.multiply(nz(s.fixedPct())).divide(HUNDRED, scale, RoundingMode.HALF_UP);
                case "zero" -> v = BigDecimal.ZERO.setScale(scale);
                default -> {
                    continue;
                }
            }
            v = v.max(BigDecimal.ZERO).min(left);
            values.put(s.key(), v);
            left = left.subtract(v);
        }
        BigDecimal carved = total.subtract(left);

        // 2) The rest over the allocated outputs.
        List<Share> pool = shares.stream().filter(s -> "allocated".equals(s.valuation())).toList();
        if (pool.isEmpty()) {
            // Nothing shares the joint cost: the residue stays with the first output so nothing is lost.
            values.merge(shares.getFirst().key(), left, BigDecimal::add);
            return new Result(values, m, carved, left);
        }
        List<BigDecimal> basis = basis(pool, m);
        if (sum(basis).signum() == 0) {
            basis = basis(pool, "quantity");
        }
        if (sum(basis).signum() == 0) {
            basis = new ArrayList<>();
            for (int i = 0; i < pool.size(); i++) {
                basis.add(i == indexOfMain(pool) ? BigDecimal.ONE : BigDecimal.ZERO);
            }
        }
        List<BigDecimal> parts = apportion(left, basis, scale);
        for (int i = 0; i < pool.size(); i++) {
            values.put(pool.get(i).key(), parts.get(i));
        }
        return new Result(values, m, carved, left);
    }

    /**
     * Split {@code amount} over {@code weights} at {@code scale}: floor shares, then one unit at a time to the
     * largest remainders. Shares sum exactly to {@code amount} (which must already be at {@code scale}).
     */
    public static List<BigDecimal> apportion(BigDecimal amount, List<BigDecimal> weights, int scale) {
        BigDecimal w = sum(weights);
        List<BigDecimal> out = new ArrayList<>();
        if (w.signum() == 0) {
            weights.forEach(x -> out.add(BigDecimal.ZERO.setScale(scale)));
            if (!out.isEmpty()) {
                out.set(0, amount.setScale(scale, RoundingMode.HALF_UP));
            }
            return out;
        }
        BigDecimal amt = amount.setScale(scale, RoundingMode.HALF_UP);
        List<BigDecimal> remainders = new ArrayList<>();
        BigDecimal assigned = BigDecimal.ZERO;
        for (BigDecimal x : weights) {
            BigDecimal exact = amt.multiply(x).divide(w, scale + 10, RoundingMode.HALF_UP);
            BigDecimal floor = exact.setScale(scale, RoundingMode.FLOOR);
            out.add(floor);
            remainders.add(exact.subtract(floor));
            assigned = assigned.add(floor);
        }
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        BigDecimal rest = amt.subtract(assigned);
        while (rest.signum() > 0) {
            int best = 0;
            for (int i = 1; i < remainders.size(); i++) {
                if (remainders.get(i).compareTo(remainders.get(best)) > 0) {
                    best = i;
                }
            }
            out.set(best, out.get(best).add(unit));
            remainders.set(best, remainders.get(best).subtract(BigDecimal.ONE));
            rest = rest.subtract(unit);
        }
        while (rest.signum() < 0) { // only when floor of a negative weight; defensive
            out.set(0, out.get(0).subtract(unit));
            rest = rest.add(unit);
        }
        return out;
    }

    private static List<BigDecimal> basis(List<Share> pool, String method) {
        List<BigDecimal> out = new ArrayList<>();
        BigDecimal othersPct = BigDecimal.ZERO;
        boolean mainHasPct = false;
        if ("fixed_pct".equals(method)) {
            for (Share s : pool) {
                if ("main".equals(s.role())) {
                    mainHasPct = s.fixedPct() != null;
                } else {
                    othersPct = othersPct.add(nz(s.fixedPct()));
                }
            }
        }
        for (Share s : pool) {
            BigDecimal b = switch (method) {
                case "weight" -> s.quantity().multiply(s.weightPerUnit() == null ? BigDecimal.ONE : s.weightPerUnit());
                case "market_value" -> s.quantity().multiply(nz(s.marketValue()));
                case "fixed_pct" -> "main".equals(s.role()) && !mainHasPct
                        ? HUNDRED.subtract(othersPct).max(BigDecimal.ZERO) : nz(s.fixedPct());
                default -> s.quantity();
            };
            out.add(b.max(BigDecimal.ZERO));
        }
        return out;
    }

    private static int indexOfMain(List<Share> pool) {
        for (int i = 0; i < pool.size(); i++) {
            if ("main".equals(pool.get(i).role())) {
                return i;
            }
        }
        return 0;
    }

    private static BigDecimal sum(List<BigDecimal> xs) {
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
