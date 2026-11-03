package com.inventoryerp.fixedasset.application;

import com.inventoryerp.kernel.common.Money;
import java.math.BigDecimal;

/**
 * One month of depreciation (port of {@code asset-depreciation.strategy.ts}). {@code usefulLife} is in months; the
 * declining methods use an annual rate of {@code 2 / years} (declining) or {@code 4 / years} (double declining)
 * applied to the current book value and divided by 12. Units-of-production falls back to straight line and
 * {@code manual} never depreciates. Math runs on {@link BigDecimal} at 20 significant digits (Decimal.js precision);
 * the {@code Decimal(18,6)} columns round the stored values.
 */
public final class DepreciationCalculator {

    private DepreciationCalculator() {}

    /** Inputs of one period. */
    public record Input(BigDecimal cost, BigDecimal salvageValue, int usefulLife, BigDecimal currentBookValue,
            BigDecimal accumulatedDepreciation) {}

    /** Amount for the period, the new accumulated total and the new book value. */
    public record Result(BigDecimal depreciationAmount, BigDecimal accumulatedDepreciation, BigDecimal bookValue) {}

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    public static Result calculate(String method, Input in) {
        return switch (method == null ? "" : method) {
            // ACC-5 (B11): double-declining is twice the straight-line rate (was 4×); declining balance keeps Nest 2×.
            case "declining_balance" -> declining(in, BigDecimal.valueOf(2));
            case "double_declining" -> declining(in, BigDecimal.valueOf(2));
            case "manual" -> new Result(BigDecimal.ZERO, in.accumulatedDepreciation(), in.currentBookValue());
            default -> straightLine(in);
        };
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

    /** Accumulated grows by the raw amount (even a negative one, as in Nest); the reported amount is clamped at 0. */
    private static Result finish(Input in, BigDecimal amount) {
        BigDecimal accumulated = in.accumulatedDepreciation().add(amount);
        BigDecimal book = Money.max(in.salvageValue(), in.cost().subtract(accumulated));
        return new Result(Money.max(BigDecimal.ZERO, amount), accumulated, book);
    }
}
