package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits delivery-order lines into loads that respect a truck's payload (t) and body volume (m³) — fleet 1.3.
 *
 * <p>Greedy, in line order: each load is filled with as much of the current line as both limits allow (rounded down to
 * the line's unit step, e.g. whole bags or pieces), then the next line tops it up, and a new load starts when nothing
 * more fits. A limit only applies to lines whose weight / volume per unit is known; a line without either factor is not
 * limited by the capacity (a warning says so). A single unit that exceeds the capacity is refused.
 */
public final class LoadPlanner {

    private LoadPlanner() {}

    static final BigDecimal MIN_STEP = new BigDecimal("0.001");

    /** A line to plan; factors are per line unit (null = unknown), {@code step} the smallest quantity (null = 0.001). */
    public record Item(String lineId, String label, String unit, BigDecimal remaining, BigDecimal tonnesPerUnit,
            BigDecimal m3PerUnit, BigDecimal step) {}

    /** Capacity of one load; either limit may be null. */
    public record Capacity(BigDecimal payloadT, BigDecimal volumeM3) {}

    public record PlannedLine(String lineId, BigDecimal quantity) {}

    public record PlannedLoad(List<PlannedLine> lines, BigDecimal tonnes, BigDecimal m3) {}

    public record Plan(List<PlannedLoad> loads, List<String> warnings) {}

    public static Plan plan(List<Item> items, Capacity cap, Integer maxLoads) {
        BigDecimal payload = positive(cap == null ? null : cap.payloadT());
        BigDecimal volume = positive(cap == null ? null : cap.volumeM3());
        List<String> warnings = new ArrayList<>();
        if (payload == null && volume == null) {
            warnings.add("No payload or body volume given: everything goes on one load");
        }
        List<PlannedLoad> loads = new ArrayList<>();
        Map<String, BigDecimal> cur = new LinkedHashMap<>();
        BigDecimal tLeft = payload;
        BigDecimal vLeft = volume;
        BigDecimal tUsed = BigDecimal.ZERO;
        BigDecimal vUsed = BigDecimal.ZERO;
        boolean anyLimited = false;
        for (Item it : items) {
            if (it.remaining() == null || it.remaining().signum() <= 0) {
                continue;
            }
            BigDecimal t = positive(it.tonnesPerUnit());
            BigDecimal m = positive(it.m3PerUnit());
            boolean limitedT = payload != null && t != null;
            boolean limitedV = volume != null && m != null;
            if ((payload != null || volume != null) && !limitedT && !limitedV) {
                warnings.add("No weight or volume data for " + it.label() + ": its quantity is not limited by the"
                        + " vehicle capacity");
            }
            anyLimited |= limitedT || limitedV;
            BigDecimal step = it.step() == null || it.step().signum() <= 0 ? MIN_STEP : it.step();
            BigDecimal rem = it.remaining();
            while (rem.signum() > 0) {
                BigDecimal allowed = rem;
                if (limitedT) {
                    allowed = allowed.min(tLeft.max(BigDecimal.ZERO).divide(t, 6, RoundingMode.DOWN));
                }
                if (limitedV) {
                    allowed = allowed.min(vLeft.max(BigDecimal.ZERO).divide(m, 6, RoundingMode.DOWN));
                }
                allowed = floor(allowed, step);
                if (allowed.compareTo(rem) > 0) {
                    allowed = rem;
                }
                if (allowed.signum() <= 0) {
                    if (cur.isEmpty()) {
                        throw ApiException.badRequest("One " + unit(it) + " of " + it.label() + " ("
                                + (limitedT ? JsNumbers.dec(step.multiply(t).setScale(3, RoundingMode.HALF_UP)) + " t"
                                        : JsNumbers.dec(step.multiply(m).setScale(3, RoundingMode.HALF_UP)) + " m³")
                                + ") does not fit the vehicle capacity");
                    }
                    loads.add(load(cur, tUsed, vUsed));
                    cur = new LinkedHashMap<>();
                    tLeft = payload;
                    vLeft = volume;
                    tUsed = BigDecimal.ZERO;
                    vUsed = BigDecimal.ZERO;
                    continue;
                }
                cur.merge(it.lineId(), allowed, BigDecimal::add);
                if (t != null) {
                    tUsed = tUsed.add(allowed.multiply(t));
                    if (tLeft != null) {
                        tLeft = tLeft.subtract(allowed.multiply(t));
                    }
                }
                if (m != null) {
                    vUsed = vUsed.add(allowed.multiply(m));
                    if (vLeft != null) {
                        vLeft = vLeft.subtract(allowed.multiply(m));
                    }
                }
                rem = rem.subtract(allowed);
            }
        }
        if (!cur.isEmpty()) {
            loads.add(load(cur, tUsed, vUsed));
        }
        if (loads.isEmpty()) {
            throw ApiException.badRequest("Nothing is left to plan on this delivery order");
        }
        if (maxLoads != null && loads.size() > maxLoads) {
            throw ApiException.badRequest("The remaining quantity needs " + loads.size() + " loads (at most " + maxLoads
                    + " allowed)");
        }
        if ((payload != null || volume != null) && !anyLimited && loads.size() == 1) {
            warnings.add("The vehicle capacity limited nothing: no product weight / volume data");
        }
        return new Plan(loads, warnings);
    }

    private static PlannedLoad load(Map<String, BigDecimal> cur, BigDecimal t, BigDecimal m) {
        List<PlannedLine> lines = new ArrayList<>();
        cur.forEach((k, v) -> lines.add(new PlannedLine(k, v.setScale(3, RoundingMode.HALF_UP))));
        return new PlannedLoad(lines, t.setScale(3, RoundingMode.HALF_UP), m.setScale(3, RoundingMode.HALF_UP));
    }

    static BigDecimal floor(BigDecimal v, BigDecimal step) {
        if (v.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return v.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    private static BigDecimal positive(BigDecimal v) {
        return v == null || v.signum() <= 0 ? null : v;
    }

    private static String unit(Item it) {
        return it.unit() == null || it.unit().isBlank() ? "unit" : it.unit();
    }
}
