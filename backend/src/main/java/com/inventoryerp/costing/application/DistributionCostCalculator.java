package com.inventoryerp.costing.application;

import com.inventoryerp.shared.costing.AllocationCalculator;

import com.inventoryerp.shared.costing.AllocationCalculator.Receiver;
import com.inventoryerp.shared.costing.AllocationCalculator.Rounding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owned-fleet delivery cost per trip (Cost Management CM-7, ADR-S037), pure.
 *
 * <p>Each vehicle's period cost is {@code rate per km × (loaded km + empty km)}. The empty return legs are charged by
 * the elected policy: {@code loaded_leg} — each trip carries its own empty return; {@code pooled} — the vehicle's
 * empty km are shared over its trips by loaded tonne-km. The vehicle total is rounded once and split over its trips by
 * charged km with the largest remainder, so the trip amounts sum exactly. Cost per tonne-km = amount ÷ (tonnes ×
 * loaded km).
 */
public final class DistributionCostCalculator {

    private DistributionCostCalculator() {}

    /** One delivered trip. */
    public record Trip(String loadId, String vehicleId, BigDecimal loadedKm, BigDecimal emptyKm, BigDecimal tonnes) {}

    /** A trip's charge. */
    public record Charged(String loadId, String vehicleId, BigDecimal loadedKm, BigDecimal emptyKm,
            BigDecimal tonnes, BigDecimal ratePerKm, BigDecimal amount, BigDecimal perTonneKm) {}

    public static List<Charged> charge(List<Trip> trips, Map<String, BigDecimal> ratePerKm, String policy) {
        if (!"loaded_leg".equals(policy) && !"pooled".equals(policy)) {
            throw new IllegalArgumentException("policy must be loaded_leg or pooled");
        }
        Map<String, List<Trip>> byVehicle = new LinkedHashMap<>();
        for (Trip t : trips) {
            byVehicle.computeIfAbsent(t.vehicleId(), k -> new ArrayList<>()).add(t);
        }
        List<Charged> out = new ArrayList<>();
        for (Map.Entry<String, List<Trip>> e : byVehicle.entrySet()) {
            BigDecimal rate = ratePerKm.get(e.getKey());
            if (rate == null) {
                throw new IllegalArgumentException("no rate for vehicle " + e.getKey());
            }
            List<Trip> list = e.getValue();
            Map<String, BigDecimal> empty = emptyShares(list, policy);
            BigDecimal totalKm = BigDecimal.ZERO;
            List<Receiver> receivers = new ArrayList<>();
            for (Trip t : list) {
                BigDecimal km = nz(t.loadedKm()).add(empty.get(t.loadId()));
                totalKm = totalKm.add(km);
                receivers.add(new Receiver(t.loadId(), km));
            }
            BigDecimal total = rate.multiply(totalKm).setScale(2, RoundingMode.HALF_UP);
            Map<String, BigDecimal> amounts = new LinkedHashMap<>();
            if (totalKm.signum() > 0) {
                for (AllocationCalculator.Share s : AllocationCalculator.allocate(total, receivers,
                        Rounding.LARGEST_REMAINDER, 2)) {
                    amounts.put(s.key(), s.amount());
                }
            }
            for (Trip t : list) {
                BigDecimal amount = amounts.getOrDefault(t.loadId(), BigDecimal.ZERO);
                BigDecimal tkm = nz(t.tonnes()).multiply(nz(t.loadedKm()));
                out.add(new Charged(t.loadId(), t.vehicleId(), nz(t.loadedKm()), empty.get(t.loadId()), nz(t.tonnes()),
                        rate, amount, tkm.signum() == 0 ? null : amount.divide(tkm, 6, RoundingMode.HALF_UP)));
            }
        }
        return out;
    }

    /** Empty km charged to each trip of one vehicle. */
    static Map<String, BigDecimal> emptyShares(List<Trip> trips, String policy) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if ("loaded_leg".equals(policy)) {
            trips.forEach(t -> out.put(t.loadId(), nz(t.emptyKm())));
            return out;
        }
        BigDecimal pool = trips.stream().map(t -> nz(t.emptyKm())).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Receiver> weights = new ArrayList<>();
        for (Trip t : trips) {
            weights.add(new Receiver(t.loadId(), nz(t.tonnes()).multiply(nz(t.loadedKm()))));
        }
        boolean weighted = weights.stream().anyMatch(w -> w.basis().signum() > 0);
        if (pool.signum() == 0 || !weighted) {
            trips.forEach(t -> out.put(t.loadId(), weighted ? BigDecimal.ZERO : nz(t.emptyKm())));
            return out;
        }
        for (AllocationCalculator.Share s : AllocationCalculator.allocate(pool, weights, Rounding.LARGEST_REMAINDER,
                1)) {
            out.put(s.key(), s.amount());
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
