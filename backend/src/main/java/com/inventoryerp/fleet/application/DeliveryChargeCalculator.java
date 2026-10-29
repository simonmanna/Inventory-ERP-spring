package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Transport / delivery charge billed to the customer (fleet 1.3). Rule choice: active rules whose customer, zone and
 * distance band match (null = any); the most specific wins — customer + zone, then customer, then zone, then general;
 * among equals a rule with a distance band beats one without, then the earlier in the list (newest first). Amount by
 * basis: {@code flat} once per delivery order, {@code per_trip} × delivered trips, {@code per_tonne} × delivered tonnes,
 * {@code per_m3} × delivered m³, {@code per_km} × the trips' distance (or the site distance × trips when no trip has
 * one); never below {@code minAmount}; HALF_UP to 2 decimals.
 */
public final class DeliveryChargeCalculator {

    private DeliveryChargeCalculator() {}

    public record Rule(String id, String name, String partnerId, String zone, BigDecimal minDistanceKm,
            BigDecimal maxDistanceKm, String basis, BigDecimal rate, BigDecimal minAmount, String taxId) {}

    public record Facts(String partnerId, String zone, BigDecimal siteDistanceKm, int trips, BigDecimal tonnes,
            BigDecimal m3, BigDecimal tripKm) {}

    public record Charge(Rule rule, BigDecimal quantity, BigDecimal amount, String description) {}

    public static Optional<Rule> select(List<Rule> rules, Facts f) {
        return rules.stream().filter(r -> matches(r, f))
                .sorted(Comparator.comparingInt((Rule r) -> -score(r))).findFirst();
    }

    static boolean matches(Rule r, Facts f) {
        if (r.partnerId() != null && !r.partnerId().equals(f.partnerId())) {
            return false;
        }
        if (r.zone() != null && (f.zone() == null || !r.zone().trim().equalsIgnoreCase(f.zone().trim()))) {
            return false;
        }
        if (r.minDistanceKm() != null || r.maxDistanceKm() != null) {
            if (f.siteDistanceKm() == null) {
                return false;
            }
            if (r.minDistanceKm() != null && f.siteDistanceKm().compareTo(r.minDistanceKm()) < 0) {
                return false;
            }
            if (r.maxDistanceKm() != null && f.siteDistanceKm().compareTo(r.maxDistanceKm()) > 0) {
                return false;
            }
        }
        return true;
    }

    static int score(Rule r) {
        int s = (r.partnerId() != null ? 20 : 0) + (r.zone() != null ? 10 : 0);
        return s * 2 + (r.minDistanceKm() != null || r.maxDistanceKm() != null ? 1 : 0);
    }

    public static Charge compute(Rule r, Facts f) {
        BigDecimal qty = switch (r.basis()) {
            case "flat" -> BigDecimal.ONE;
            case "per_trip" -> BigDecimal.valueOf(f.trips());
            case "per_tonne" -> nz(f.tonnes());
            case "per_m3" -> nz(f.m3());
            case "per_km" -> f.tripKm() != null && f.tripKm().signum() > 0 ? f.tripKm()
                    : nz(f.siteDistanceKm()).multiply(BigDecimal.valueOf(f.trips()));
            default -> BigDecimal.ZERO;
        };
        qty = qty.setScale(3, RoundingMode.HALF_UP);
        BigDecimal amount = r.rate().multiply(qty).setScale(2, RoundingMode.HALF_UP);
        if (r.minAmount() != null && amount.compareTo(r.minAmount()) < 0) {
            amount = r.minAmount().setScale(2, RoundingMode.HALF_UP);
        }
        String unit = switch (r.basis()) {
            case "per_trip" -> " trip(s)";
            case "per_tonne" -> " t";
            case "per_m3" -> " m³";
            case "per_km" -> " km";
            default -> "";
        };
        String description = "flat".equals(r.basis()) ? "Transport charge · " + r.name()
                : "Transport charge · " + r.name() + " · " + qty.stripTrailingZeros().toPlainString() + unit;
        return new Charge(r, qty, amount, description);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
