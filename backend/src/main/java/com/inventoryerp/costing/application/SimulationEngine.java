package com.inventoryerp.costing.application;

import com.inventoryerp.shared.costing.AllocationCalculator;

import com.inventoryerp.shared.costing.AllocationCalculator.Receiver;
import com.inventoryerp.shared.costing.AllocationCalculator.Rounding;
import com.inventoryerp.shared.costing.AllocationCalculator.Share;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure what-if engine (Cost Management CM-11, ADR-S042, AD-8). It takes a baseline per segment (actual quantities,
 * revenue, cost of sales, distribution, overhead) and a {@link CostContext} of drivers, and returns the simulated
 * economics: unit cost, CM1 / CM2 / CM3, margin and break-even volume. It never reads or writes the database; the
 * fixed-overhead pool is re-spread with {@link AllocationCalculator} (largest remainder, exact sums).
 *
 * <p>Driver model: cost of sales is variable (scales with volume) and moves with material prices, with the imported
 * share also moving with FX; distribution is variable and moves with fuel; overhead is fixed per period and moves with
 * the energy tariff and, inversely, with the shift / capacity pattern; revenue moves with volume and selling prices.
 */
public final class SimulationEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SimulationEngine() {}

    /** Scenario drivers, all as % changes (e.g. 10 = +10 %), except the imported share (0–100 % of cost of sales). */
    public record CostContext(BigDecimal materialPricePct, BigDecimal fuelPricePct, BigDecimal energyTariffPct,
            BigDecimal fxPct, BigDecimal importedCostSharePct, BigDecimal capacityPct, BigDecimal sellingPricePct,
            BigDecimal volumePct) {

        public static CostContext of(Map<String, ?> p) {
            return new CostContext(n(p, "materialPricePct"), n(p, "fuelPricePct"), n(p, "energyTariffPct"),
                    n(p, "fxPct"), n(p, "importedCostSharePct"), n(p, "capacityPct"), n(p, "sellingPricePct"),
                    n(p, "volumePct"));
        }

        private static BigDecimal n(Map<String, ?> p, String k) {
            Object v = p == null ? null : p.get(k);
            return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
        }
    }

    /** Baseline economics of one segment for the period. */
    public record Baseline(String segment, BigDecimal quantity, BigDecimal revenue, BigDecimal cogs,
            BigDecimal distribution, BigDecimal overhead) {}

    /** Simulated economics of one segment. */
    public record Result(String segment, BigDecimal quantity, BigDecimal revenue, BigDecimal cogs,
            BigDecimal distribution, BigDecimal overhead, BigDecimal cm1, BigDecimal cm2, BigDecimal cm3,
            BigDecimal unitCost, BigDecimal marginPct, BigDecimal breakEvenQuantity) {}

    public static List<Result> simulate(List<Baseline> baseline, CostContext c, BigDecimal extraFixedPool) {
        BigDecimal volume = factor(c.volumePct());
        BigDecimal price = factor(c.sellingPricePct());
        BigDecimal importedShare = c.importedCostSharePct().divide(HUNDRED, 10, RoundingMode.HALF_UP);
        BigDecimal cogsFactor = factor(c.materialPricePct()).multiply(BigDecimal.ONE.subtract(importedShare)
                .add(importedShare.multiply(factor(c.fxPct()))));
        BigDecimal fuel = factor(c.fuelPricePct());
        BigDecimal capacity = factor(c.capacityPct());
        BigDecimal overheadFactor = capacity.signum() <= 0 ? factor(c.energyTariffPct())
                : factor(c.energyTariffPct()).divide(capacity, 10, RoundingMode.HALF_UP);

        List<Receiver> receivers = new ArrayList<>();
        Map<String, BigDecimal[]> sim = new HashMap<>();
        BigDecimal pool = extraFixedPool == null ? BigDecimal.ZERO : extraFixedPool;
        for (Baseline b : baseline) {
            BigDecimal qty = b.quantity().multiply(volume).setScale(6, RoundingMode.HALF_UP);
            BigDecimal revenue = b.revenue().multiply(volume).multiply(price).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cogs = b.cogs().multiply(volume).multiply(cogsFactor).setScale(2, RoundingMode.HALF_UP);
            BigDecimal dist = b.distribution().multiply(volume).multiply(fuel).setScale(2, RoundingMode.HALF_UP);
            pool = pool.add(b.overhead().multiply(overheadFactor));
            sim.put(b.segment(), new BigDecimal[] {qty, revenue, cogs, dist});
            receivers.add(new Receiver(b.segment(), revenue.max(BigDecimal.ZERO)));
        }
        pool = pool.setScale(2, RoundingMode.HALF_UP);
        Map<String, BigDecimal> overhead = new HashMap<>();
        boolean anyBasis = receivers.stream().anyMatch(r -> r.basis().signum() > 0);
        if (!receivers.isEmpty() && pool.signum() != 0) {
            List<Receiver> basis = anyBasis ? receivers : receivers.stream().map(r -> new Receiver(r.key(),
                    BigDecimal.ONE)).toList();
            for (Share s : AllocationCalculator.allocate(pool, basis, Rounding.LARGEST_REMAINDER, 2)) {
                overhead.put(s.key(), s.amount());
            }
        }
        List<Result> out = new ArrayList<>();
        for (Baseline b : baseline) {
            BigDecimal[] v = sim.get(b.segment());
            BigDecimal oh = overhead.getOrDefault(b.segment(), BigDecimal.ZERO.setScale(2));
            BigDecimal cm1 = v[1].subtract(v[2]);
            BigDecimal cm2 = cm1.subtract(v[3]);
            BigDecimal cm3 = cm2.subtract(oh);
            BigDecimal totalCost = v[2].add(v[3]).add(oh);
            BigDecimal unitCost = v[0].signum() == 0 ? null : totalCost.divide(v[0], 6, RoundingMode.HALF_UP);
            BigDecimal margin = v[1].signum() == 0 ? null : cm3.multiply(HUNDRED).divide(v[1], 4,
                    RoundingMode.HALF_UP);
            BigDecimal unitContribution = v[0].signum() == 0 ? BigDecimal.ZERO : cm2.divide(v[0], 10,
                    RoundingMode.HALF_UP);
            BigDecimal breakEven = unitContribution.signum() <= 0 ? null : oh.divide(unitContribution, 3,
                    RoundingMode.HALF_UP);
            out.add(new Result(b.segment(), v[0], v[1], v[2], v[3], oh, cm1, cm2, cm3, unitCost, margin, breakEven));
        }
        return out;
    }

    private static BigDecimal factor(BigDecimal pct) {
        return BigDecimal.ONE.add((pct == null ? BigDecimal.ZERO : pct).divide(HUNDRED, 10, RoundingMode.HALF_UP));
    }
}
