package com.inventoryerp.rental.application;

import com.inventoryerp.pos.application.PosSql;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Pure rental arithmetic shared by the services, with the JavaScript number semantics of the Nest source: rate-band
 * matching ({@code resolveRate}), rate-period units ({@code calcUnits}), late days and late fees
 * ({@code createReturn}), the deposit requirement ({@code computeDeposit}), the deposit status ({@code deriveStatus})
 * and the customer trust score ({@code recompute}).
 */
public final class RentalMath {

    static final double DAY_MS = 86_400_000d;
    static final double HOUR_MS = 3_600_000d;

    private RentalMath() {}

    /**
     * The first band (already ordered by priority ASC, minUnits DESC) with {@code minUnits <= units <= maxUnits}, an
     * open-ended band when {@code maxUnits} is null; {@code null} when none matches.
     */
    public static Map<String, Object> pickBand(List<Map<String, Object>> bands, double units) {
        for (Map<String, Object> b : bands) {
            double min = ((Number) b.get("minUnits")).doubleValue();
            Object max = b.get("maxUnits");
            if (units >= min && (max == null || units <= ((Number) max).doubleValue())) {
                return b;
            }
        }
        return null;
    }

    /** Duration in rate-period units, rounded up and at least 1 (hour / day / week / month = 30 days). */
    public static int calcUnits(Instant from, Instant to, String period) {
        double ms = to.toEpochMilli() - from.toEpochMilli();
        if ("hour".equals(period)) {
            return (int) Math.max(1, Math.ceil(ms / HOUR_MS));
        }
        if ("week".equals(period)) {
            return (int) Math.max(1, Math.ceil(ms / (7 * DAY_MS)));
        }
        if ("month".equals(period)) {
            return (int) Math.max(1, Math.ceil(ms / (30 * DAY_MS)));
        }
        return (int) Math.max(1, Math.ceil(ms / DAY_MS));
    }

    /** {@code Math.max(0, Math.ceil((now - dueAt) / 1 day))}: any started day past due counts. */
    public static int lateDays(Instant now, Instant dueAt) {
        return (int) Math.max(0, Math.ceil((now.toEpochMilli() - dueAt.toEpochMilli()) / DAY_MS));
    }

    /** {@code lateDays > 0 ? lateDays × lateFeePerDay × quantity : 0}. */
    public static double lateFee(int lateDays, double lateFeePerDay, double quantity) {
        return lateDays > 0 ? lateDays * lateFeePerDay * quantity : 0;
    }

    /**
     * Deposit required by the {@code rental.depositPolicy}: a truthy {@code fixedAmount} in {@code fixed} mode, else
     * {@code ceil(sum(lineTotal) × percent / 100)} (10 % when unset). A missing policy is {@code percent 10}.
     */
    public static double computeDeposit(List<Map<String, Object>> lines, Map<?, ?> policyIn) {
        Map<?, ?> policy = policyIn != null ? policyIn : Map.of("mode", "percent", "percent", 10);
        Object mode = policy.get("mode") != null ? policy.get("mode") : "percent";
        if ("fixed".equals(mode) && PosSql.truthy(policy.get("fixedAmount"))) {
            return PosSql.num(policy.get("fixedAmount"));
        }
        double pct = (policy.get("percent") != null ? PosSql.num(policy.get("percent")) : 10) / 100;
        double base = 0;
        for (Map<String, Object> l : lines) {
            base += PosSql.num(l.get("lineTotal"));
        }
        return Math.ceil(base * pct);
    }

    /**
     * Deposit status after a movement: {@code held} until something is collected; once applied + refunded +
     * forfeited reach the collected total it is {@code refunded} / {@code forfeited} when that was the only kind of
     * release, else {@code applied}; in between {@code partially_applied}.
     */
    public static String depositStatus(double collected, double applied, double refunded, double forfeited) {
        if (collected == 0) {
            return "held";
        }
        double consumed = applied + refunded + forfeited;
        if (consumed >= collected) {
            if (refunded > 0 && applied == 0 && forfeited == 0) {
                return "refunded";
            }
            if (forfeited > 0 && applied == 0 && refunded == 0) {
                return "forfeited";
            }
            return "applied";
        }
        return "partially_applied";
    }

    /**
     * Trust score 0–100: late returns −5 each (max −30), damage events −15 each (max −30), lost items −20 each,
     * cancelled reservations −2 each (max −10), an unpaid balance −2 per started 100,000 (max −10).
     */
    public static int score(long lateReturns, long damageEvents, long lostItems, long cancelled, double unpaidBalance) {
        double score = 100;
        score -= Math.min(lateReturns * 5, 30);
        score -= Math.min(damageEvents * 15, 30);
        score -= lostItems * 20;
        score -= Math.min(cancelled * 2, 10);
        if (unpaidBalance > 0) {
            score -= Math.min(Math.ceil(unpaidBalance / 100_000) * 2, 10);
        }
        return (int) Math.max(0, Math.min(100, score));
    }

    /** 80+ good · 50–79 caution · below 50 blocked. */
    public static String band(int score) {
        return score >= 80 ? "good" : score >= 50 ? "caution" : "blocked";
    }
}
