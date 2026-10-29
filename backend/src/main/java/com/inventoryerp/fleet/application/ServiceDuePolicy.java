package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Next-due computation of a preventive-maintenance schedule (fleet 1.6): a service is due by kilometres, engine hours
 * or calendar days — <b>whichever comes first</b>. Each interval counts from the schedule's baseline (last service,
 * or the schedule start); meter values are lifetime values (raw + replacement offset), so a meter replacement never
 * resets a due point.
 *
 * <p>Per dimension: {@code overdue} when the due point has passed (remaining &lt; 0), {@code due} when it is within
 * the lead window (0 ≤ remaining ≤ lead), else {@code ok}. A dimension whose current value is unknown (no reading
 * yet) never triggers. A missing meter baseline counts from 0 (a new unit's first service). The overall state is the
 * worst dimension; {@code trigger} names the dimension that drives it (closest to its due point relative to the
 * interval).
 */
public final class ServiceDuePolicy {

    public static final String OK = "ok";
    public static final String DUE = "due";
    public static final String OVERDUE = "overdue";

    private ServiceDuePolicy() {}

    /** Intervals (null = not used) and their "due soon" lead windows. */
    public record Interval(BigDecimal km, BigDecimal hours, Integer days, BigDecimal leadKm, BigDecimal leadHours,
            Integer leadDays) {}

    /** Baseline of the next due point: last service date and lifetime meters (nullable meters count from 0). */
    public record Baseline(LocalDate lastDoneOn, BigDecimal lastDoneKm, BigDecimal lastDoneHours) {}

    /** Current lifetime meters (null = no reading) and the business date. */
    public record Current(LocalDate today, BigDecimal km, BigDecimal hours) {}

    /** One dimension: due point, what remains (km / h / days; null when the current value is unknown), state. */
    public record Dimension(String dimension, BigDecimal interval, BigDecimal nextDue, LocalDate nextDueOn,
            BigDecimal remaining, String state) {}

    /** Overall result. */
    public record DueStatus(String state, String trigger, List<Dimension> dimensions) {

        public Dimension dimension(String name) {
            return dimensions.stream().filter(d -> d.dimension().equals(name)).findFirst().orElse(null);
        }
    }

    public static DueStatus evaluate(Interval i, Baseline b, Current c) {
        List<Dimension> dims = new ArrayList<>();
        if (i.km() != null) {
            BigDecimal next = nz(b.lastDoneKm()).add(i.km());
            BigDecimal remaining = c.km() == null ? null : next.subtract(c.km());
            dims.add(new Dimension("km", i.km(), next, null, remaining, state(remaining, nz(i.leadKm()))));
        }
        if (i.hours() != null) {
            BigDecimal next = nz(b.lastDoneHours()).add(i.hours());
            BigDecimal remaining = c.hours() == null ? null : next.subtract(c.hours());
            dims.add(new Dimension("hours", i.hours(), next, null, remaining, state(remaining, nz(i.leadHours()))));
        }
        if (i.days() != null) {
            LocalDate base = b.lastDoneOn() == null ? c.today() : b.lastDoneOn();
            LocalDate nextOn = base.plusDays(i.days());
            BigDecimal remaining = BigDecimal.valueOf(ChronoUnit.DAYS.between(c.today(), nextOn));
            int lead = i.leadDays() == null ? 0 : i.leadDays();
            dims.add(new Dimension("days", BigDecimal.valueOf(i.days()), null, nextOn, remaining,
                    state(remaining, BigDecimal.valueOf(lead))));
        }
        Dimension trigger = null;
        for (Dimension d : dims) {
            if (d.remaining() == null) {
                continue;
            }
            if (trigger == null || rank(d.state()) > rank(trigger.state())
                    || (rank(d.state()) == rank(trigger.state()) && ratio(d).compareTo(ratio(trigger)) < 0)) {
                trigger = d;
            }
        }
        return new DueStatus(trigger == null ? OK : trigger.state(), trigger == null ? null : trigger.dimension(),
                dims);
    }

    /** {@code overdue} &gt; {@code due} &gt; {@code ok}. */
    public static int rank(String state) {
        return OVERDUE.equals(state) ? 2 : DUE.equals(state) ? 1 : 0;
    }

    private static String state(BigDecimal remaining, BigDecimal lead) {
        if (remaining == null) {
            return OK;
        }
        if (remaining.signum() < 0) {
            return OVERDUE;
        }
        return remaining.compareTo(lead) <= 0 ? DUE : OK;
    }

    /** Remaining as a share of the interval (smaller = closer to due). */
    private static BigDecimal ratio(Dimension d) {
        return d.remaining().divide(d.interval(), 6, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
