package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Fuel efficiency and anomaly rules of fleet 1.5 (ADR-S009 §3), pure functions over the posted fuel logs of one vehicle
 * in fuelling order.
 *
 * <p><b>Efficiency</b> is measured between consecutive <em>full-tank</em> logs (the full-to-full method): the litres of
 * the full log plus every partial fill since the previous full log, over the distance (L/100 km) or the engine hours
 * (L/hour) run between the two full logs. A partial fill, or the first full log of a vehicle, has no efficiency.
 *
 * <p><b>Anomalies</b> recomputed whenever the chain changes ({@link #CHAIN_TYPES}): an overfill (litres above the
 * vehicle's tank size plus the org tolerance), a meter that did not increase since the previous log (or that the meter
 * rule refused), and consumption above / below the vehicle class's band. Fuel-card anomalies are decided once, when a
 * log is posted (see {@code FleetFuelLogService}).
 */
public final class FuelEfficiencyPolicy {

    public static final String OVERFILL = "overfill";
    public static final String METER_NOT_INCREASING = "meter_not_increasing";
    public static final String HIGH_CONSUMPTION = "high_consumption";
    public static final String LOW_CONSUMPTION = "low_consumption";
    public static final String CARD_VEHICLE_MISMATCH = "card_vehicle_mismatch";
    public static final String CARD_LIMIT_EXCEEDED = "card_limit_exceeded";

    /** Anomaly types derived from the log chain (recomputed when an earlier log is posted or reversed). */
    public static final List<String> CHAIN_TYPES = List.of(OVERFILL, METER_NOT_INCREASING, HIGH_CONSUMPTION,
            LOW_CONSUMPTION);

    public static final List<String> TYPES = List.of(OVERFILL, METER_NOT_INCREASING, HIGH_CONSUMPTION,
            LOW_CONSUMPTION, CARD_VEHICLE_MISMATCH, CARD_LIMIT_EXCEEDED);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** One fill: id, litres, full tank or partial, and the meters read at the pump (either may be null). */
    public record Fill(String id, BigDecimal litres, boolean fullTank, BigDecimal odometer, BigDecimal hours) {}

    /** Expected consumption of a vehicle class (any bound may be null = not checked). */
    public record Band(BigDecimal minLPer100Km, BigDecimal maxLPer100Km, BigDecimal minLPerHour,
            BigDecimal maxLPerHour) {
        public static final Band NONE = new Band(null, null, null, null);
    }

    /** Efficiency of a full-tank log against the previous full-tank log ({@link #NONE} when not measurable). */
    public record Efficiency(String previousFullId, BigDecimal segmentLitres, BigDecimal distanceKm,
            BigDecimal hoursRun, BigDecimal lPer100Km, BigDecimal lPerHour) {
        public static final Efficiency NONE = new Efficiency(null, null, null, null, null, null);
    }

    /** A raised anomaly: type, human detail, observed value and the expected bounds (when there are any). */
    public record Flag(String type, String detail, BigDecimal observed, BigDecimal expectedMin, BigDecimal expectedMax) {}

    private FuelEfficiencyPolicy() {}

    /** Efficiency of {@code chain.get(i)} ({@code chain} = posted fills of one vehicle in fuelling order). */
    public static Efficiency efficiency(List<Fill> chain, int i) {
        Fill f = chain.get(i);
        if (!f.fullTank()) {
            return Efficiency.NONE;
        }
        BigDecimal litres = f.litres();
        int j = i - 1;
        while (j >= 0 && !chain.get(j).fullTank()) {
            litres = litres.add(chain.get(j).litres());
            j--;
        }
        if (j < 0) {
            return Efficiency.NONE;
        }
        Fill previous = chain.get(j);
        BigDecimal km = difference(f.odometer(), previous.odometer());
        BigDecimal hours = difference(f.hours(), previous.hours());
        BigDecimal perKm = km != null && km.signum() > 0
                ? litres.multiply(HUNDRED).divide(km, 3, RoundingMode.HALF_UP) : null;
        BigDecimal perHour = hours != null && hours.signum() > 0 ? litres.divide(hours, 3, RoundingMode.HALF_UP) : null;
        return new Efficiency(previous.id(), litres.setScale(3, RoundingMode.HALF_UP), km, hours, perKm, perHour);
    }

    /**
     * Chain anomalies of {@code chain.get(i)}: overfill against {@code tankCapacityL} (+ {@code tolerancePct} %),
     * meters not above the latest earlier log carrying the same meter (or {@code meterRejection}, the meter rule's
     * refusal), and the efficiency {@code e} outside {@code band}.
     */
    public static List<Flag> flags(List<Fill> chain, int i, Efficiency e, Band band, BigDecimal tankCapacityL,
            BigDecimal tolerancePct, String meterRejection) {
        List<Flag> out = new ArrayList<>();
        Fill f = chain.get(i);
        Flag overfill = overfill(f.litres(), tankCapacityL, tolerancePct);
        if (overfill != null) {
            out.add(overfill);
        }
        Flag meter = meterFlag(chain, i, meterRejection);
        if (meter != null) {
            out.add(meter);
        }
        Band b = band == null ? Band.NONE : band;
        List<String> high = new ArrayList<>();
        List<String> low = new ArrayList<>();
        BigDecimal[] highBounds = new BigDecimal[3];
        BigDecimal[] lowBounds = new BigDecimal[3];
        if (e.lPer100Km() != null) {
            check(e.lPer100Km(), b.minLPer100Km(), b.maxLPer100Km(), "L/100 km", high, low, highBounds, lowBounds);
        }
        if (e.lPerHour() != null) {
            check(e.lPerHour(), b.minLPerHour(), b.maxLPerHour(), "L/h", high, low, highBounds, lowBounds);
        }
        if (!high.isEmpty()) {
            out.add(new Flag(HIGH_CONSUMPTION, "Consumption above the class band: " + String.join("; ", high),
                    highBounds[0], highBounds[1], highBounds[2]));
        }
        if (!low.isEmpty()) {
            out.add(new Flag(LOW_CONSUMPTION, "Consumption below the class band: " + String.join("; ", low),
                    lowBounds[0], lowBounds[1], lowBounds[2]));
        }
        return out;
    }

    /** Litres above the tank size + tolerance → overfill flag (null when the tank size is unknown or not exceeded). */
    public static Flag overfill(BigDecimal litres, BigDecimal tankCapacityL, BigDecimal tolerancePct) {
        if (tankCapacityL == null || tankCapacityL.signum() <= 0) {
            return null;
        }
        BigDecimal tol = tolerancePct == null ? BigDecimal.ZERO : tolerancePct;
        BigDecimal limit = tankCapacityL.multiply(BigDecimal.ONE.add(tol.divide(HUNDRED, 6, RoundingMode.HALF_UP)))
                .setScale(3, RoundingMode.HALF_UP);
        if (litres.compareTo(limit) <= 0) {
            return null;
        }
        return new Flag(OVERFILL, "Fuelled " + plain(litres) + " L into a " + plain(tankCapacityL) + " L tank",
                litres, null, limit);
    }

    private static Flag meterFlag(List<Fill> chain, int i, String meterRejection) {
        Fill f = chain.get(i);
        List<String> details = new ArrayList<>();
        BigDecimal observed = null;
        BigDecimal expected = null;
        Fill prevOdo = previousWith(chain, i, Fill::odometer);
        if (f.odometer() != null && prevOdo != null && f.odometer().compareTo(prevOdo.odometer()) <= 0) {
            details.add("odometer " + plain(f.odometer()) + " km is not above the previous fuel log's "
                    + plain(prevOdo.odometer()) + " km");
            observed = f.odometer();
            expected = prevOdo.odometer();
        }
        Fill prevHours = previousWith(chain, i, Fill::hours);
        if (f.hours() != null && prevHours != null && f.hours().compareTo(prevHours.hours()) <= 0) {
            details.add("engine hours " + plain(f.hours()) + " h are not above the previous fuel log's "
                    + plain(prevHours.hours()) + " h");
            if (observed == null) {
                observed = f.hours();
                expected = prevHours.hours();
            }
        }
        if (details.isEmpty() && meterRejection != null && !meterRejection.isBlank()) {
            details.add(meterRejection);
        }
        if (details.isEmpty()) {
            return null;
        }
        String text = String.join("; ", details);
        return new Flag(METER_NOT_INCREASING, Character.toUpperCase(text.charAt(0)) + text.substring(1), observed,
                expected, null);
    }

    private static Fill previousWith(List<Fill> chain, int i, Function<Fill, BigDecimal> meter) {
        for (int j = i - 1; j >= 0; j--) {
            if (meter.apply(chain.get(j)) != null) {
                return chain.get(j);
            }
        }
        return null;
    }

    private static void check(BigDecimal value, BigDecimal min, BigDecimal max, String unit, List<String> high,
            List<String> low, BigDecimal[] highBounds, BigDecimal[] lowBounds) {
        if (max != null && value.compareTo(max) > 0) {
            high.add(plain(value) + " " + unit + " > " + plain(max) + " " + unit);
            if (highBounds[0] == null) {
                highBounds[0] = value;
                highBounds[1] = min;
                highBounds[2] = max;
            }
        }
        if (min != null && value.compareTo(min) < 0) {
            low.add(plain(value) + " " + unit + " < " + plain(min) + " " + unit);
            if (lowBounds[0] == null) {
                lowBounds[0] = value;
                lowBounds[1] = min;
                lowBounds[2] = max;
            }
        }
    }

    private static BigDecimal difference(BigDecimal now, BigDecimal before) {
        return now == null || before == null ? null : now.subtract(before);
    }

    static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
