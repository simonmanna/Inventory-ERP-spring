package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fleet 1.9 cost and profitability formulas (pure functions, unit-tested in {@code FleetCostingPoliciesTest}). Money
 * is rounded HALF_UP to 2 decimals, unit rates (per km / hour / tonne / trip) to 4 decimals, percentages to 1 or 2.
 *
 * <ul>
 *   <li><b>Total cost of ownership</b> of a unit over a period = depreciation + fuel + maintenance + tyres + insurance
 *       + compliance fees + incidents + driver allowances + fines ({@link CostBuckets#total()}).</li>
 *   <li><b>Unit rates</b> = cost / use in the same period (km and engine hours from the lifetime meters, tonnes
 *       delivered and trips of the unit); unknown or zero use gives no rate.</li>
 *   <li><b>Own-fleet trip cost</b> = the unit's rate on the allocation basis (km, else tonnes, else per trip) × the
 *       trip's quantity, plus the driver allowances claimed for that trip. The rate's cost pool leaves out
 *       allowances already claimed for a trip, so nothing is counted twice.</li>
 *   <li><b>Delivery margin</b> = goods revenue + transport charge − product cost − trip cost (own fleet) or freight
 *       (transporter).</li>
 * </ul>
 */
public final class FleetCostCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private FleetCostCalculator() {
    }

    /** Cost of a unit split by source; every bucket ≥ 0 except incidents (insurance recoveries can exceed repairs). */
    public record CostBuckets(BigDecimal depreciation, BigDecimal fuel, BigDecimal maintenance, BigDecimal tyres,
            BigDecimal insurance, BigDecimal compliance, BigDecimal incidents, BigDecimal allowances,
            BigDecimal fines) {

        public static final CostBuckets ZERO = new CostBuckets(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        /** Total cost of ownership of the period. */
        public BigDecimal total() {
            return money(depreciation.add(fuel).add(maintenance).add(tyres).add(insurance).add(compliance)
                    .add(incidents).add(allowances).add(fines));
        }

        /** Running cost (total without depreciation). */
        public BigDecimal running() {
            return money(total().subtract(depreciation));
        }

        public CostBuckets plus(CostBuckets o) {
            return new CostBuckets(depreciation.add(o.depreciation), fuel.add(o.fuel), maintenance.add(o.maintenance),
                    tyres.add(o.tyres), insurance.add(o.insurance), compliance.add(o.compliance),
                    incidents.add(o.incidents), allowances.add(o.allowances), fines.add(o.fines));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("depreciation", money(depreciation));
            m.put("fuel", money(fuel));
            m.put("maintenance", money(maintenance));
            m.put("tyres", money(tyres));
            m.put("insurance", money(insurance));
            m.put("compliance", money(compliance));
            m.put("incidents", money(incidents));
            m.put("allowances", money(allowances));
            m.put("fines", money(fines));
            m.put("total", total());
            return m;
        }
    }

    /** Use of a unit in the period. Null = unknown (meter not tracked / no readings). */
    public record Usage(BigDecimal km, BigDecimal hours, BigDecimal tonnes, int trips) {

        public static final Usage NONE = new Usage(null, null, null, 0);

        public Usage plus(Usage o) {
            return new Usage(sum(km, o.km), sum(hours, o.hours), sum(tonnes, o.tonnes), trips + o.trips);
        }

        private static BigDecimal sum(BigDecimal a, BigDecimal b) {
            return a == null ? b : b == null ? a : a.add(b);
        }
    }

    /** Unit rates of a cost over a usage (null when the use is unknown or zero). */
    public record Rates(BigDecimal perKm, BigDecimal perHour, BigDecimal perTonne, BigDecimal perTrip) {

        public static Rates of(BigDecimal cost, Usage u) {
            return new Rates(rate(cost, u.km()), rate(cost, u.hours()), rate(cost, u.tonnes()),
                    rate(cost, BigDecimal.valueOf(u.trips())));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("perKm", perKm);
            m.put("perHour", perHour);
            m.put("perTonne", perTonne);
            m.put("perTrip", perTrip);
            return m;
        }
    }

    /** Allocated own-fleet cost of a trip and the basis actually used ({@code km}, {@code tonne}, {@code trip}). */
    public record Allocation(BigDecimal amount, String basis) {}

    /** Margin of a delivery (trip, order, customer, ...). */
    public record Profit(BigDecimal revenue, BigDecimal charge, BigDecimal productCost, BigDecimal tripCost,
            BigDecimal margin, BigDecimal marginPct) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revenue", revenue);
            m.put("transportCharge", charge);
            m.put("productCost", productCost);
            m.put("tripCost", tripCost);
            m.put("margin", margin);
            m.put("marginPct", marginPct);
            return m;
        }
    }

    // ------------------------------------------------------------------ rounding

    public static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** {@code cost / units} to 4 decimals; null when {@code units} is null or not positive. */
    public static BigDecimal rate(BigDecimal cost, BigDecimal units) {
        if (cost == null || units == null || units.signum() <= 0) {
            return null;
        }
        return cost.divide(units, 4, RoundingMode.HALF_UP);
    }

    /** {@code part / whole × 100} to {@code scale} decimals; null when {@code whole} is null or not positive. */
    public static BigDecimal pct(BigDecimal part, BigDecimal whole, int scale) {
        if (part == null || whole == null || whole.signum() <= 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, scale, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ periods

    /** Inclusive days of {@code [aFrom, aTo] ∩ [bFrom, bTo]} (0 when they do not overlap). */
    public static long overlapDays(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        LocalDate start = aFrom.isAfter(bFrom) ? aFrom : bFrom;
        LocalDate end = aTo.isBefore(bTo) ? aTo : bTo;
        return end.isBefore(start) ? 0 : ChronoUnit.DAYS.between(start, end) + 1;
    }

    /**
     * The part of {@code amount} (a premium / fee valid {@code validFrom .. validTo}, inclusive) that falls in the
     * period {@code [from, to]}, spread evenly per day. Without an end date the whole amount falls on its start day;
     * without a start date on {@code fallbackDay} (the day it was recorded).
     */
    public static BigDecimal amortised(BigDecimal amount, LocalDate validFrom, LocalDate validTo, LocalDate fallbackDay,
            LocalDate from, LocalDate to) {
        if (amount == null || amount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        LocalDate start = validFrom != null ? validFrom : fallbackDay;
        if (start == null) {
            return BigDecimal.ZERO;
        }
        if (validTo == null || validTo.isBefore(start)) {
            return !start.isBefore(from) && !start.isAfter(to) ? money(amount) : BigDecimal.ZERO;
        }
        long total = ChronoUnit.DAYS.between(start, validTo) + 1;
        long in = overlapDays(start, validTo, from, to);
        if (in == 0) {
            return BigDecimal.ZERO;
        }
        return money(amount.multiply(BigDecimal.valueOf(in)).divide(BigDecimal.valueOf(total), 6,
                RoundingMode.HALF_UP));
    }

    /**
     * The part of a monthly depreciation line ({@code period} {@code YYYY-MM}) that falls in {@code [from, to]},
     * pro rata by days of that month.
     */
    public static BigDecimal depreciationShare(String period, BigDecimal amount, LocalDate from, LocalDate to) {
        if (amount == null || period == null) {
            return BigDecimal.ZERO;
        }
        YearMonth ym;
        try {
            ym = YearMonth.parse(period.length() > 7 ? period.substring(0, 7) : period);
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();
        long in = overlapDays(first, last, from, to);
        if (in == 0) {
            return BigDecimal.ZERO;
        }
        return money(amount.multiply(BigDecimal.valueOf(in)).divide(BigDecimal.valueOf(ym.lengthOfMonth()), 6,
                RoundingMode.HALF_UP));
    }

    /**
     * Meter use in a period from lifetime readings: last reading in the period − the last one before it (else the
     * first one in the period). Null without readings in the period; never negative.
     */
    public static BigDecimal meterUse(BigDecimal lastBefore, BigDecimal firstIn, BigDecimal lastIn) {
        if (lastIn == null) {
            return null;
        }
        BigDecimal base = lastBefore != null ? lastBefore : firstIn;
        if (base == null) {
            return null;
        }
        BigDecimal use = lastIn.subtract(base);
        return use.signum() < 0 ? BigDecimal.ZERO : use;
    }

    // ------------------------------------------------------------------ allocation and profitability

    /**
     * Own-fleet trip cost: {@code basis} {@code km} (default) uses the unit's cost per km × trip km, {@code tonne} the
     * cost per tonne × trip tonnes, {@code trip} the cost per trip. When the chosen basis has no rate or no trip
     * quantity it falls back km → tonne → trip. Zero (basis {@code none}) when the unit has no rate at all.
     */
    public static Allocation allocate(String basis, Rates rates, BigDecimal tripKm, BigDecimal tripTonnes) {
        String[] order = switch (basis == null ? "km" : basis) {
            case "tonne" -> new String[] {"tonne", "km", "trip"};
            case "trip" -> new String[] {"trip", "km", "tonne"};
            default -> new String[] {"km", "tonne", "trip"};
        };
        for (String b : order) {
            switch (b) {
                case "km" -> {
                    if (rates.perKm() != null && tripKm != null && tripKm.signum() > 0) {
                        return new Allocation(money(rates.perKm().multiply(tripKm)), "km");
                    }
                }
                case "tonne" -> {
                    if (rates.perTonne() != null && tripTonnes != null && tripTonnes.signum() > 0) {
                        return new Allocation(money(rates.perTonne().multiply(tripTonnes)), "tonne");
                    }
                }
                default -> {
                    if (rates.perTrip() != null) {
                        return new Allocation(money(rates.perTrip()), "trip");
                    }
                }
            }
        }
        return new Allocation(BigDecimal.ZERO.setScale(2), "none");
    }

    /** Pro-rata share {@code amount × part / whole} (2 decimals); an equal share {@code amount / count} without weights. */
    public static BigDecimal share(BigDecimal amount, BigDecimal part, BigDecimal whole, int count) {
        if (amount == null || amount.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (part != null && whole != null && whole.signum() > 0) {
            return money(amount.multiply(part).divide(whole, 6, RoundingMode.HALF_UP));
        }
        return count <= 0 ? money(amount) : money(amount.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP));
    }

    /** Revenue of a line: {@code qty × price × (1 − discount % / 100)}, 2 decimals (net of tax). */
    public static BigDecimal lineRevenue(BigDecimal qty, BigDecimal unitPrice, BigDecimal discountPct) {
        if (qty == null || unitPrice == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal gross = qty.multiply(unitPrice);
        if (discountPct != null && discountPct.signum() != 0) {
            gross = gross.multiply(HUNDRED.subtract(discountPct)).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        }
        return money(gross);
    }

    /**
     * Product cost of a dispatched line still with the customer: the issue value less what came back at the same
     * cost ({@code stockValue × (dispatched − returned) / dispatched}).
     */
    public static BigDecimal deliveredCost(BigDecimal stockValue, BigDecimal dispatched, BigDecimal returned) {
        if (stockValue == null || dispatched == null || dispatched.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal kept = dispatched.subtract(nz(returned));
        if (kept.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return money(stockValue.multiply(kept).divide(dispatched, 6, RoundingMode.HALF_UP));
    }

    /** Margin and margin % of sales (revenue + charge); null % without sales. */
    public static Profit profit(BigDecimal revenue, BigDecimal charge, BigDecimal productCost, BigDecimal tripCost) {
        BigDecimal r = money(nz(revenue));
        BigDecimal c = money(nz(charge));
        BigDecimal p = money(nz(productCost));
        BigDecimal t = money(nz(tripCost));
        BigDecimal margin = r.add(c).subtract(p).subtract(t);
        return new Profit(r, c, p, t, margin, pct(margin, r.add(c), 2));
    }

    // ------------------------------------------------------------------ deliveries

    /**
     * On time: delivered on or before the requested day; on the requested day itself, also by the end of the
     * delivery window ({@code windowTo} HH:MM) when there is one.
     */
    public static boolean onTime(LocalDate requested, String windowTo, LocalDateTime deliveredLocal) {
        if (requested == null || deliveredLocal == null) {
            return false;
        }
        LocalDate day = deliveredLocal.toLocalDate();
        if (day.isBefore(requested)) {
            return true;
        }
        if (day.isAfter(requested)) {
            return false;
        }
        if (windowTo == null || windowTo.isBlank()) {
            return true;
        }
        try {
            return !deliveredLocal.toLocalTime().isAfter(LocalTime.parse(windowTo.trim()));
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ freight bills

    /** Allowed variance of a supplier invoice: {@code max(absolute, total × pct / 100)}, 2 decimals. */
    public static BigDecimal allowedVariance(BigDecimal total, BigDecimal pct, BigDecimal absolute) {
        BigDecimal byPct = pct == null ? BigDecimal.ZERO
                : nz(total).abs().multiply(pct).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal abs = nz(absolute);
        return money(byPct.max(abs));
    }
}
