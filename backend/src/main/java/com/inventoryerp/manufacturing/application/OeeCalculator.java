package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Overall equipment effectiveness (ADR-S015), pure: {@code OEE = availability × performance × quality} with
 *
 * <ul>
 *   <li>availability = run time ÷ planned production time (shift − breaks − planned stops; run = planned − unplanned
 *       downtime),</li>
 *   <li>performance = ideal time for the total count (count ÷ ideal rate per hour) ÷ run time, capped at 100 %,</li>
 *   <li>quality = good count ÷ total count.</li>
 * </ul>
 *
 * Ratios are rounded to 4 decimals (HALF_UP); OEE is computed from the unrounded factors. Planned time with no run
 * time or no output is OEE 0; no planned time, or no ideal rate, leaves the undefined factors (and OEE) null.
 * Aggregates (several shifts) are computed from the summed minutes, ideal minutes and counts, never by averaging.
 */
public final class OeeCalculator {

    private static final int SCALE = 4;
    private static final int WORK = 10;
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private OeeCalculator() {}

    /** The OEE of one shift or an aggregate. */
    public record Result(long plannedMinutes, long downtimeMinutes, long runMinutes, BigDecimal goodQty,
            BigDecimal scrapQty, BigDecimal totalQty, BigDecimal idealMinutes, BigDecimal availability,
            BigDecimal performance, BigDecimal quality, BigDecimal oee, boolean performanceCapped) {}

    /** Ideal minutes to produce {@code totalQty} at {@code ratePerHour}; null without a positive rate. */
    public static BigDecimal idealMinutes(BigDecimal totalQty, BigDecimal ratePerHour) {
        if (ratePerHour == null || ratePerHour.signum() <= 0 || totalQty == null) {
            return null;
        }
        return totalQty.multiply(SIXTY).divide(ratePerHour, 6, RoundingMode.HALF_UP);
    }

    public static Result compute(long plannedMinutes, long unplannedDowntimeMinutes, BigDecimal idealMinutes,
            BigDecimal goodQty, BigDecimal scrapQty) {
        long planned = Math.max(0, plannedMinutes);
        long down = Math.min(Math.max(0, unplannedDowntimeMinutes), planned);
        long run = planned - down;
        BigDecimal good = goodQty == null ? BigDecimal.ZERO : goodQty;
        BigDecimal scrap = scrapQty == null ? BigDecimal.ZERO : scrapQty;
        BigDecimal total = good.add(scrap);

        BigDecimal a = planned > 0 ? ratio(BigDecimal.valueOf(run), BigDecimal.valueOf(planned)) : null;
        BigDecimal p = null;
        boolean capped = false;
        if (idealMinutes != null && run > 0) {
            p = ratio(idealMinutes, BigDecimal.valueOf(run));
            if (p.compareTo(BigDecimal.ONE) > 0) {
                p = BigDecimal.ONE;
                capped = true;
            }
        }
        BigDecimal q = total.signum() > 0 ? ratio(good, total) : null;

        BigDecimal oee;
        if (a == null) {
            oee = null;
        } else if (a.signum() == 0 || total.signum() == 0) {
            oee = BigDecimal.ZERO.setScale(SCALE);
        } else if (p == null) {
            oee = null;
        } else {
            oee = a.multiply(p).multiply(q).setScale(SCALE, RoundingMode.HALF_UP);
        }
        return new Result(planned, down, run, good, scrap, total, idealMinutes, round(a), round(p), round(q), oee,
                capped);
    }

    private static BigDecimal ratio(BigDecimal num, BigDecimal den) {
        return num.divide(den, WORK, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
