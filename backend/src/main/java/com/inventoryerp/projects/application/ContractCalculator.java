package com.inventoryerp.projects.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure contract arithmetic (Cost Management CM-8b, ADR-S039; AD-8): IFRS 15 cost-to-cost progress and cumulative
 * revenue, progress-claim deductions and the IAS 37.66–69 / 68A onerous-contract provision. No side effects, so the
 * simulation engine (CM-11) can reuse it.
 */
public final class ContractCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ContractCalculator() {}

    /** Progress = cost to date ÷ (cost to date + cost to complete), in [0, 1]; 0 when nothing is estimated. */
    public static BigDecimal progress(BigDecimal costToDate, BigDecimal costToComplete) {
        BigDecimal total = costToDate.add(costToComplete);
        if (total.signum() <= 0) {
            return BigDecimal.ZERO.setScale(8);
        }
        BigDecimal p = costToDate.divide(total, 8, RoundingMode.HALF_UP);
        return p.max(BigDecimal.ZERO).min(BigDecimal.ONE.setScale(8));
    }

    /** Cumulative revenue = transaction price × progress, rounded to cents. */
    public static BigDecimal cumulativeRevenue(BigDecimal price, BigDecimal progress) {
        return price.multiply(progress).setScale(2, RoundingMode.HALF_UP);
    }

    /** Deductions of one progress claim. */
    public record Claim(BigDecimal thisClaim, BigDecimal retention, BigDecimal advanceRecovery, BigDecimal net) {}

    /**
     * {@code thisClaim} = cumulative certified − previously certified; retention = thisClaim × retention %; advance
     * recovery = thisClaim × recovery %, never more than the advance still outstanding; net = the rest.
     */
    public static Claim claim(BigDecimal cumulative, BigDecimal previous, BigDecimal retentionPct,
            BigDecimal recoveryPct, BigDecimal advanceOutstanding) {
        BigDecimal thisClaim = cumulative.subtract(previous).setScale(2, RoundingMode.HALF_UP);
        BigDecimal retention = pct(thisClaim, retentionPct);
        BigDecimal recovery = pct(thisClaim, recoveryPct).min(advanceOutstanding.max(BigDecimal.ZERO));
        BigDecimal net = thisClaim.subtract(retention).subtract(recovery);
        return new Claim(thisClaim, retention, recovery, net);
    }

    static BigDecimal pct(BigDecimal amount, BigDecimal pct) {
        if (pct == null || pct.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return amount.multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** Inputs of an onerous-contract assessment (remaining obligation only). */
    public record OnerousInput(BigDecimal remainingPrice, BigDecimal probableClaims, BigDecimal incrementalCost,
            BigDecimal directlyRelatedCost, BigDecimal costOfExiting, BigDecimal impairmentRecognised) {}

    /** Result: benefits, cost of fulfilling (68A), unavoidable cost and the provision. */
    public record OnerousResult(BigDecimal expectedBenefits, BigDecimal costOfFulfilling, BigDecimal unavoidableCost,
            BigDecimal provision, boolean exitCheaper) {}

    /**
     * Expected benefits = remaining transaction price + highly probable claims. Cost of fulfilling = incremental +
     * directly related costs (general and administrative costs not chargeable to the customer are excluded before
     * this call). The least net cost of exiting (IAS 37.68) is the lower of the net cost of fulfilling (fulfilling −
     * benefits) and the penalty for not fulfilling; exiting forgoes the benefits, so the unavoidable cost of the exit
     * route is penalty + benefits. Provision = max(0, unavoidable − benefits − losses already recognised by impairing
     * dedicated assets): the exit-based amount when the penalty is cheaper.
     */
    public static OnerousResult onerous(OnerousInput in) {
        BigDecimal benefits = in.remainingPrice().add(nz(in.probableClaims()));
        BigDecimal fulfilling = nz(in.incrementalCost()).add(nz(in.directlyRelatedCost()));
        boolean exitCheaper = in.costOfExiting() != null
                && in.costOfExiting().compareTo(fulfilling.subtract(benefits)) < 0;
        BigDecimal unavoidable = exitCheaper ? in.costOfExiting().add(benefits) : fulfilling;
        BigDecimal provision = unavoidable.subtract(benefits).subtract(nz(in.impairmentRecognised()))
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new OnerousResult(benefits.setScale(2, RoundingMode.HALF_UP), fulfilling.setScale(2,
                RoundingMode.HALF_UP), unavoidable.setScale(2, RoundingMode.HALF_UP), provision, exitCheaper);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
