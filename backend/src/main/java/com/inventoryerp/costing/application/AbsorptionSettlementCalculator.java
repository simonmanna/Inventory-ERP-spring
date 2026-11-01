package com.inventoryerp.costing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * IAS 2.13 settlement of absorbed vs actual conversion overhead (Cost Management CM-4, ADR-S034, AD-11), pure.
 *
 * <p><b>Decomposition</b> of {@code actual − absorbed} (positive = under-absorbed) for one work centre and period, with
 * {@code F} the budgeted fixed overhead, {@code N} the normal activity, {@code H} the actual activity and {@code r} the
 * fixed rate the absorption used (the governed rate, else {@code F / N}):
 * <ul>
 *   <li>volume = (N − H) × F / N — activity away from normal capacity;</li>
 *   <li>rate error = (F / N − r) × H — the rate in use did not equal budget ÷ normal capacity;</li>
 *   <li>spending = actual − F − (absorbed − r × H) — everything else (fixed and variable spending).</li>
 * </ul>
 * The three sum to the total exactly (each is rounded to cents and spending takes the rounding).
 *
 * <p><b>Treatment</b> follows the cause, never a blanket pro-rata: low-volume under-absorption is expensed; high-volume
 * over-absorption and favourable spending reduce the period's output; unfavourable spending within tolerance is
 * capitalised into the period's output; above tolerance a reviewer classifies it (abnormal → expensed, normal →
 * capitalised with a justification); a total below the materiality threshold goes to cost of sales.
 */
public final class AbsorptionSettlementCalculator {

    private static final int SCALE = 2;

    private AbsorptionSettlementCalculator() {}

    /** Inputs of one settlement. {@code governedRate} may be null (then F / N is used). */
    public record Inputs(BigDecimal actual, BigDecimal absorbed, BigDecimal budgetFixed, BigDecimal normalActivity,
            BigDecimal actualActivity, BigDecimal governedRate) {}

    /** The decomposition; {@code total = spending + volume + rateError}. */
    public record Decomposition(BigDecimal standardRate, BigDecimal rateUsed, BigDecimal spending, BigDecimal volume,
            BigDecimal rateError, BigDecimal total) {}

    /** One treated component. Positive amounts debit the target, negative credit it. */
    public record Treatment(String component, String cause, String treatment, BigDecimal amount) {}

    /** The plan, or the reason it cannot be settled yet. */
    public record Plan(List<Treatment> lines, String blocker) {}

    public static Decomposition decompose(Inputs in) {
        if (in.normalActivity() == null || in.normalActivity().signum() <= 0) {
            throw new IllegalArgumentException("normal activity must be positive");
        }
        BigDecimal standardRate = in.budgetFixed().divide(in.normalActivity(), 6, RoundingMode.HALF_UP);
        BigDecimal rate = in.governedRate() != null ? in.governedRate() : standardRate;
        BigDecimal h = in.actualActivity();
        BigDecimal total = round(in.actual().subtract(in.absorbed()));
        BigDecimal volume = round(in.normalActivity().subtract(h).multiply(standardRate));
        BigDecimal rateError = round(standardRate.subtract(rate).multiply(h));
        BigDecimal spending = total.subtract(volume).subtract(rateError);
        return new Decomposition(standardRate, rate, spending, volume, rateError, total);
    }

    /**
     * Treat each component by cause. {@code tolerancePct} bounds normal unfavourable spending as a share of the
     * absorbed amount; {@code classification} ({@code normal} / {@code abnormal}) is the reviewer's call above it.
     */
    public static Plan plan(Decomposition d, BigDecimal absorbed, BigDecimal tolerancePct, BigDecimal materiality,
            String classification) {
        List<Treatment> out = new ArrayList<>();
        if (d.total().signum() == 0) {
            return new Plan(out, null);
        }
        if (materiality != null && materiality.signum() > 0 && d.total().abs().compareTo(materiality) < 0) {
            out.add(new Treatment("total", "below_materiality", "cogs", d.total()));
            return new Plan(out, null);
        }
        if (d.volume().signum() > 0) {
            out.add(new Treatment("volume", "low_volume", "expense", d.volume()));
        } else if (d.volume().signum() < 0) {
            out.add(new Treatment("volume", "high_volume", "reduce_inventory", d.volume()));
        }
        BigDecimal unfavourable = d.spending().max(BigDecimal.ZERO).add(d.rateError().max(BigDecimal.ZERO));
        BigDecimal limit = absorbed.abs().multiply(tolerancePct == null ? BigDecimal.ZERO : tolerancePct)
                .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        boolean withinTolerance = unfavourable.compareTo(limit) <= 0;
        if (unfavourable.signum() > 0 && !withinTolerance && classification == null) {
            return new Plan(List.of(), "Unfavourable spending of " + unfavourable.toPlainString() + " exceeds the "
                    + "tolerance of " + limit.toPlainString() + "; classify it as normal or abnormal");
        }
        spendingLike(out, "spending", d.spending(), withinTolerance, classification);
        spendingLike(out, "rate_error", d.rateError(), withinTolerance, classification);
        return new Plan(out, null);
    }

    private static void spendingLike(List<Treatment> out, String component, BigDecimal amount, boolean within,
            String classification) {
        if (amount.signum() < 0) {
            out.add(new Treatment(component, "favourable_" + component, "reduce_inventory", amount));
        } else if (amount.signum() > 0) {
            if (within) {
                out.add(new Treatment(component, "normal_" + component, "capitalise", amount));
            } else if ("abnormal".equals(classification)) {
                out.add(new Treatment(component, "abnormal", "expense", amount));
            } else {
                out.add(new Treatment(component, "normal_reviewed", "capitalise", amount));
            }
        }
    }

    static BigDecimal round(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
