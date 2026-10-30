package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Stage yield and the WIP relieved by one output posting (ADR-S011 §3), pure and DB-free.
 *
 * <p>Quantities are the stage's joint output in one physical measure: {@code good} (all outputs: main + co-/by-
 * products + waste) and {@code scrap}; {@code processed = good + scrap}.
 *
 * <ul>
 *   <li>Planned gross units = planned output ÷ expected yield; the remaining gross units are those not yet processed
 *       (at least this posting's {@code processed}).</li>
 *   <li>A partial posting relieves in full the {@code direct} value it backflushed itself (materials issued for
 *       exactly this output) plus {@code (pool − direct) × processed ÷ remaining gross} of the carried WIP (manual
 *       issues, transferred-in and conversion cost cover the whole remaining plan); the completing posting relieves the
 *       whole pool, so the stage WIP ends at exactly zero.</li>
 *   <li>Normal loss = {@code processed × (1 − expected yield)} is absorbed by the good output; scrap above it is
 *       abnormal and written off at the relieved cost per processed unit. With no good output everything relieved
 *       is abnormal loss.</li>
 * </ul>
 */
public final class StageYieldCalculator {

    private StageYieldCalculator() {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Inputs of one output posting. */
    public record Input(BigDecimal pool, BigDecimal direct, BigDecimal good, BigDecimal scrap,
            BigDecimal plannedOutput, BigDecimal processedSoFar, BigDecimal expectedYieldPct, boolean completes) {}

    /**
     * {@code relieved} = WIP leaving the stage; {@code goodValue} + {@code abnormalValue} = relieved exactly.
     * {@code actualYieldPct} = good ÷ processed × 100 (null when nothing was processed).
     */
    public record Result(BigDecimal relieved, BigDecimal goodValue, BigDecimal abnormalQty, BigDecimal abnormalValue,
            BigDecimal normalLossQty, BigDecimal actualYieldPct) {}

    public static Result compute(Input in, int scale) {
        BigDecimal pool = nz(in.pool()).max(BigDecimal.ZERO).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal good = nz(in.good());
        BigDecimal scrap = nz(in.scrap());
        if (good.signum() < 0 || scrap.signum() < 0) {
            throw new IllegalArgumentException("quantities must be >= 0");
        }
        BigDecimal processed = good.add(scrap);
        BigDecimal yieldPct = in.expectedYieldPct() == null || in.expectedYieldPct().signum() <= 0 ? HUNDRED
                : in.expectedYieldPct().min(HUNDRED);
        BigDecimal yield = yieldPct.divide(HUNDRED, 10, RoundingMode.HALF_UP);

        BigDecimal relieved;
        if (in.completes()) {
            relieved = pool;
        } else if (processed.signum() == 0) {
            relieved = BigDecimal.ZERO.setScale(scale);
        } else {
            BigDecimal direct = nz(in.direct()).max(BigDecimal.ZERO).min(pool).setScale(scale, RoundingMode.HALF_UP);
            BigDecimal carried = pool.subtract(direct);
            BigDecimal plannedGross = nz(in.plannedOutput()).divide(yield, 10, RoundingMode.HALF_UP);
            BigDecimal remainingGross = plannedGross.subtract(nz(in.processedSoFar())).max(processed);
            relieved = direct.add(carried.multiply(processed).divide(remainingGross, scale, RoundingMode.HALF_UP)
                    .min(carried));
        }

        BigDecimal normalLoss = processed.multiply(BigDecimal.ONE.subtract(yield)).setScale(6, RoundingMode.HALF_UP);
        BigDecimal abnormalQty;
        BigDecimal abnormalValue;
        if (good.signum() == 0) {
            abnormalQty = scrap;
            abnormalValue = relieved;
        } else {
            abnormalQty = scrap.subtract(normalLoss).max(BigDecimal.ZERO);
            abnormalValue = processed.signum() == 0 ? BigDecimal.ZERO.setScale(scale)
                    : relieved.multiply(abnormalQty).divide(processed, scale, RoundingMode.HALF_UP).min(relieved);
        }
        BigDecimal goodValue = relieved.subtract(abnormalValue);
        BigDecimal actual = processed.signum() == 0 ? null
                : good.multiply(HUNDRED).divide(processed, 4, RoundingMode.HALF_UP);
        return new Result(relieved, goodValue, abnormalQty, abnormalValue, normalLoss.min(scrap), actual);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
