package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Nurse-Saul maturity of a curing batch (ADR-S014, Phase 2.4): {@code M = Σ max(0, T̄ − T₀) × Δt} in °C·h, where
 * {@code T̄} is the mean temperature of each interval between consecutive readings (the first interval, from the
 * curing start to the first reading, at the first reading's temperature) and {@code T₀} the datum temperature
 * (default −10 °C). Maturity is counted up to the last reading only (no extrapolation). Pure.
 */
public final class MaturityCalculator {

    private MaturityCalculator() {}

    /** A temperature reading. */
    public record Reading(Instant at, BigDecimal temperatureC) {}

    /** Maturity in °C·h at scale 2; readings before {@code start} are ignored, order does not matter. */
    public static BigDecimal maturity(Instant start, BigDecimal datumC, List<Reading> readings) {
        BigDecimal datum = datumC == null ? BigDecimal.valueOf(-10) : datumC;
        List<Reading> sorted = readings.stream().filter(r -> r.temperatureC() != null && !r.at().isBefore(start))
                .sorted((a, b) -> a.at().compareTo(b.at())).toList();
        BigDecimal total = BigDecimal.ZERO;
        Instant prevAt = start;
        BigDecimal prevT = null;
        for (Reading r : sorted) {
            BigDecimal mean = prevT == null ? r.temperatureC()
                    : prevT.add(r.temperatureC()).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
            BigDecimal hours = BigDecimal.valueOf(Duration.between(prevAt, r.at()).toSeconds())
                    .divide(BigDecimal.valueOf(3600), 6, RoundingMode.HALF_UP);
            BigDecimal excess = mean.subtract(datum).max(BigDecimal.ZERO);
            total = total.add(excess.multiply(hours));
            prevAt = r.at();
            prevT = r.temperatureC();
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
