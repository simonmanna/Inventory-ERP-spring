package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Weighbridge arithmetic (fleet 1.4), all {@link BigDecimal} with {@link RoundingMode#HALF_UP}:
 *
 * <ul>
 *   <li><b>Net</b>: gross = the heavier, tare = the lighter of the two weighings, net = gross − tare (kg, 2 dp). Equal
 *       weighings are refused (nothing was loaded or unloaded).
 *   <li><b>Tolerance</b>: variance = net − expected; allowed = max(absolute kg, expected × % / 100); within tolerance when
 *       |variance| ≤ allowed. The absolute part covers the scale resolution on small loads, the percentage large ones.
 *       Product settings override the station's per field.
 *   <li><b>Stored tare</b>: expires {@code validityDays} after it was weighed; usable while active and not expired.
 * </ul>
 */
public final class WeighbridgeCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private WeighbridgeCalculator() {}

    public record Weighing(BigDecimal grossKg, BigDecimal tareKg, BigDecimal netKg) {}

    public record Tolerance(BigDecimal pct, BigDecimal kg) {}

    /** Outcome of the tolerance check; {@code expectedKg} null = nothing to compare with (always within). */
    public record Check(BigDecimal expectedKg, BigDecimal varianceKg, BigDecimal variancePct, BigDecimal allowedKg,
            boolean within) {}

    public static Weighing net(BigDecimal first, BigDecimal second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("Both weighings are required");
        }
        int cmp = first.compareTo(second);
        if (cmp == 0) {
            throw ApiException.badRequest("The second weight equals the first weight (" + first.stripTrailingZeros()
                    .toPlainString() + " kg): nothing was loaded or unloaded");
        }
        BigDecimal gross = (cmp > 0 ? first : second).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tare = (cmp > 0 ? second : first).setScale(2, RoundingMode.HALF_UP);
        return new Weighing(gross, tare, gross.subtract(tare));
    }

    /** Effective tolerance: each product value overrides the station's. */
    public static Tolerance effective(BigDecimal productPct, BigDecimal productKg, BigDecimal stationPct,
            BigDecimal stationKg) {
        BigDecimal pct = productPct != null ? productPct : stationPct == null ? BigDecimal.ZERO : stationPct;
        BigDecimal kg = productKg != null ? productKg : stationKg == null ? BigDecimal.ZERO : stationKg;
        return new Tolerance(pct, kg);
    }

    public static Check check(BigDecimal netKg, BigDecimal expectedKg, Tolerance tolerance) {
        if (expectedKg == null || expectedKg.signum() <= 0) {
            return new Check(null, null, null, null, true);
        }
        BigDecimal expected = expectedKg.setScale(2, RoundingMode.HALF_UP);
        BigDecimal variance = netKg.subtract(expected).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pct = variance.multiply(HUNDRED).divide(expected, 3, RoundingMode.HALF_UP);
        BigDecimal byPct = expected.multiply(tolerance.pct()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal allowed = byPct.max(tolerance.kg().setScale(2, RoundingMode.HALF_UP));
        return new Check(expected, variance, pct, allowed, variance.abs().compareTo(allowed) <= 0);
    }

    /** When a tare weighed at {@code weighedAt} stops being valid. */
    public static Instant tareExpiresAt(Instant weighedAt, int validityDays) {
        if (validityDays < 1) {
            throw new IllegalArgumentException("Tare validity must be at least one day");
        }
        return weighedAt.plus(Duration.ofDays(validityDays));
    }

    /** {@code valid}, {@code expired}, {@code superseded} or {@code revoked}. */
    public static String tareState(String status, Instant expiresAt, Instant now) {
        if (!"active".equals(status)) {
            return status;
        }
        return now.isBefore(expiresAt) ? "valid" : "expired";
    }

    public static boolean tareUsable(String status, Instant expiresAt, Instant now) {
        return "valid".equals(tareState(status, expiresAt, now));
    }
}
