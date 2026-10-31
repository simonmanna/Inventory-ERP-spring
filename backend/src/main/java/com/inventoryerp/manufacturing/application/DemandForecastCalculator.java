package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Double exponential smoothing (Holt) of weekly sales, free of the database. Sales of aggregates and blocks follow
 * projects and seasons: an average lags a ramp, the level and trend here follow it. No seasonal term yet, because that
 * needs two years of history this system does not have. The next weeks are the level plus the trend, never below zero,
 * so a falling product forecasts nothing rather than a negative demand.
 */
public final class DemandForecastCalculator {

    private static final BigDecimal ALPHA = new BigDecimal("0.3");
    private static final BigDecimal BETA = new BigDecimal("0.1");
    private static final BigDecimal DAYS_IN_WEEK = BigDecimal.valueOf(7);
    private static final int SCALE = 6;
    private static final int MIN_WEEKS_WITH_SALES = 4;
    private static final BigDecimal NOISY_ERROR_SHARE = new BigDecimal("0.5");

    /**
     * The state after the last week.
     *
     * @param level the smoothed weekly quantity
     * @param trend the change per week
     * @param meanAbsoluteError the mean one-week-ahead error, in quantity per week
     * @param weeksWithSales the weeks of history with a positive quantity
     */
    public record Result(BigDecimal level, BigDecimal trend, BigDecimal meanAbsoluteError, int weeksWithSales) {

        /** {@code low} with little history, {@code medium} when errors are large for the level, else {@code high}. */
        public String confidence() {
            if (weeksWithSales < MIN_WEEKS_WITH_SALES) {
                return "low";
            }
            return level.signum() <= 0 || meanAbsoluteError.compareTo(level.multiply(NOISY_ERROR_SHARE)) > 0 ? "medium"
                    : "high";
        }
    }

    private DemandForecastCalculator() {}

    /** {@code weekly}: the quantity of each week, oldest first. Empty gives zero. */
    public static Result smooth(List<BigDecimal> weekly) {
        if (weekly.isEmpty()) {
            return new Result(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        BigDecimal level = weekly.getFirst();
        BigDecimal trend = BigDecimal.ZERO;
        BigDecimal errors = BigDecimal.ZERO;
        int withSales = level.signum() > 0 ? 1 : 0;
        for (BigDecimal actual : weekly.subList(1, weekly.size())) {
            BigDecimal expected = level.add(trend);
            errors = errors.add(actual.subtract(expected).abs());
            BigDecimal next = ALPHA.multiply(actual).add(BigDecimal.ONE.subtract(ALPHA).multiply(expected))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            trend = BETA.multiply(next.subtract(level)).add(BigDecimal.ONE.subtract(BETA).multiply(trend))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            level = next;
            if (actual.signum() > 0) {
                withSales++;
            }
        }
        BigDecimal mae = weekly.size() < 2 ? BigDecimal.ZERO
                : errors.divide(BigDecimal.valueOf(weekly.size() - 1L), SCALE, RoundingMode.HALF_UP);
        return new Result(level, trend, mae, withSales);
    }

    /** The quantity of the {@code ahead}-th week after the last one (1 = next week). */
    public static BigDecimal week(Result r, int ahead) {
        return r.level().add(r.trend().multiply(BigDecimal.valueOf(ahead))).max(BigDecimal.ZERO);
    }

    /** The quantity of the next {@code days} days: whole weeks in turn, a part week pro rata. */
    public static BigDecimal horizon(Result r, long days) {
        BigDecimal total = BigDecimal.ZERO;
        long left = days;
        for (int ahead = 1; left > 0; ahead++) {
            long part = Math.min(7, left);
            total = total.add(week(r, ahead).multiply(BigDecimal.valueOf(part)).divide(DAYS_IN_WEEK, SCALE,
                    RoundingMode.HALF_UP));
            left -= part;
        }
        return total.setScale(3, RoundingMode.HALF_UP);
    }
}
