package com.inventoryerp.fixedasset.application;

import com.inventoryerp.kernel.common.Money;
import java.math.BigDecimal;

/**
 * One month of depreciation (port of {@code asset-depreciation.strategy.ts}). {@code usefulLife} is in months; the
 * declining methods use an annual rate of {@code factor / years} applied to the current book value and divided by 12:
 * {@code declining_balance} takes the category's configurable factor (default 2, typically 1.5 or 2) and
 * {@code double_declining} is always 2 (twice the straight-line rate). Units-of-production charges
 * {@code (cost − residual) × units used this period / total estimated units}; it never falls back to straight line, so
 * it needs a {@link Usage}. {@code manual} never depreciates. Every method stops at the residual value. Math runs on
 * {@link BigDecimal} at 20 significant digits (Decimal.js precision); the {@code Decimal(18,6)} columns round the
 * stored values.
 */
public final class DepreciationCalculator {

    private DepreciationCalculator() {}

    /** Inputs of one period. */
    public record Input(BigDecimal cost, BigDecimal salvageValue, int usefulLife, BigDecimal currentBookValue,
            BigDecimal accumulatedDepreciation) {}

    /**
     * Units-of-production inputs: units used in the period, the asset's total estimated units over its life and the
     * units recorded for earlier periods (re-basing after an impairment).
     */
    public record Usage(BigDecimal units, BigDecimal totalUnits, BigDecimal unitsBefore) {

        /** True when both the period's units and a positive total are known. */
        public boolean complete() {
            return units != null && units.signum() >= 0 && totalUnits != null && totalUnits.signum() > 0;
        }
    }

    /** Amount for the period, the new accumulated total and the new book value. */
    public record Result(BigDecimal depreciationAmount, BigDecimal accumulatedDepreciation, BigDecimal bookValue) {}

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    /** Declining-balance factor of existing categories (V517 column default): schedules are unchanged. */
    public static final BigDecimal DEFAULT_DECLINING_FACTOR = BigDecimal.valueOf(2);

    /** One period with the default declining factor and no usage (units-of-production is refused). */
    public static Result calculate(String method, Input in) {
        return calculate(method, in, DEFAULT_DECLINING_FACTOR, null);
    }

    /**
     * One period. {@code decliningFactor} applies to {@code declining_balance} only (null or not positive: 2);
     * {@code usage} is required for {@code units_of_production}.
     *
     * @throws IllegalArgumentException for units-of-production without complete usage
     */
    public static Result calculate(String method, Input in, BigDecimal decliningFactor, Usage usage) {
        return switch (method == null ? "" : method) {
            // ACC-5 (B11): double-declining is twice the straight-line rate (was 4×).
            case "declining_balance" -> declining(in, factor(method, decliningFactor));
            case "double_declining" -> declining(in, DEFAULT_DECLINING_FACTOR);
            case "units_of_production" -> unitsOfProduction(in, usage);
            case "manual" -> new Result(BigDecimal.ZERO, in.accumulatedDepreciation(), in.currentBookValue());
            default -> straightLine(in);
        };
    }

    /** The annual-rate multiplier of a declining method: the configured factor for declining balance, else 2. */
    public static BigDecimal factor(String method, BigDecimal decliningFactor) {
        return "declining_balance".equals(method) && decliningFactor != null && decliningFactor.signum() > 0
                ? decliningFactor : DEFAULT_DECLINING_FACTOR;
    }

    /** The impaired-asset month with the default declining factor and no usage (units-of-production is refused). */
    public static Result calculateImpaired(String method, BigDecimal salvageValue, int usefulLife, BigDecimal carrying,
            BigDecimal accumulatedDepreciation, BigDecimal carryingAfterImpairment, int remainingLifeMonths) {
        return calculateImpaired(method, salvageValue, usefulLife, carrying, accumulatedDepreciation,
                carryingAfterImpairment, remainingLifeMonths, DEFAULT_DECLINING_FACTOR, null);
    }

    /**
     * One month for an impaired asset (IAS 36.63, ADR-S073): straight line spreads the carrying amount after the latest
     * impairment event less the residual value over the remaining life then; the declining methods apply their rate
     * to the current carrying amount; units-of-production spreads the current carrying amount less the residual value
     * over the units still to come ({@code totalUnits − unitsBefore}). The amount never takes the carrying amount below
     * the residual value. {@code accumulatedDepreciation} grows by the amount; the book value is the carrying amount
     * less the amount.
     *
     * @throws IllegalArgumentException for units-of-production without complete usage
     */
    public static Result calculateImpaired(String method, BigDecimal salvageValue, int usefulLife, BigDecimal carrying,
            BigDecimal accumulatedDepreciation, BigDecimal carryingAfterImpairment, int remainingLifeMonths,
            BigDecimal decliningFactor, Usage usage) {
        BigDecimal headroom = Money.max(BigDecimal.ZERO, carrying.subtract(salvageValue));
        BigDecimal amount = switch (method == null ? "" : method) {
            case "declining_balance", "double_declining" -> {
                BigDecimal years = Money.max(BigDecimal.ONE, Money.div(BigDecimal.valueOf(usefulLife), TWELVE));
                yield Money.div(carrying.multiply(Money.div(factor(method, decliningFactor), years),
                        Money.DECIMAL_JS), TWELVE);
            }
            case "units_of_production" -> {
                requireUsage(usage);
                BigDecimal before = usage.unitsBefore() == null ? BigDecimal.ZERO : usage.unitsBefore();
                BigDecimal remaining = Money.max(usage.units(), usage.totalUnits().subtract(before));
                yield remaining.signum() <= 0 ? BigDecimal.ZERO
                        : Money.div(headroom.multiply(usage.units()), remaining);
            }
            case "manual" -> BigDecimal.ZERO;
            default -> Money.div(carryingAfterImpairment.subtract(salvageValue),
                    BigDecimal.valueOf(Math.max(1, remainingLifeMonths)));
        };
        amount = Money.max(BigDecimal.ZERO, Money.min(amount, headroom));
        return new Result(amount, accumulatedDepreciation.add(amount), carrying.subtract(amount));
    }

    private static Result straightLine(Input in) {
        BigDecimal base = in.cost().subtract(in.salvageValue());
        BigDecimal monthly = Money.div(base, BigDecimal.valueOf(Math.max(1, in.usefulLife())));
        BigDecimal amount = Money.min(monthly, in.currentBookValue().subtract(in.salvageValue()));
        return finish(in, amount);
    }

    private static Result declining(Input in, BigDecimal factor) {
        BigDecimal years = Money.max(BigDecimal.ONE, Money.div(BigDecimal.valueOf(in.usefulLife()), TWELVE));
        BigDecimal rate = Money.div(factor, years);
        BigDecimal amount = Money.div(in.currentBookValue().multiply(rate, Money.DECIMAL_JS), TWELVE);
        BigDecimal maxDepr = in.currentBookValue().subtract(in.salvageValue());
        return finish(in, Money.min(amount, maxDepr));
    }

    /** {@code (cost − residual) × units / total units}, never below the residual value. */
    private static Result unitsOfProduction(Input in, Usage usage) {
        requireUsage(usage);
        BigDecimal base = in.cost().subtract(in.salvageValue());
        BigDecimal amount = Money.div(base.multiply(usage.units()), usage.totalUnits());
        BigDecimal maxDepr = in.currentBookValue().subtract(in.salvageValue());
        return finish(in, Money.min(amount, maxDepr));
    }

    private static void requireUsage(Usage usage) {
        if (usage == null || !usage.complete()) {
            throw new IllegalArgumentException("units_of_production needs the asset's total units and the units used"
                    + " in the period");
        }
    }

    /** Accumulated grows by the raw amount (even a negative one, as in Nest); the reported amount is clamped at 0. */
    private static Result finish(Input in, BigDecimal amount) {
        BigDecimal accumulated = in.accumulatedDepreciation().add(amount);
        BigDecimal book = Money.max(in.salvageValue(), in.cost().subtract(accumulated));
        return new Result(Money.max(BigDecimal.ZERO, amount), accumulated, book);
    }
}
