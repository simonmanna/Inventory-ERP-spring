package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure arithmetic of crushing &amp; screening (ADR-S016), DB-free: tonnes from a % split, meter deltas, the per-stage
 * mass balance of a run (what each circuit stage passes on, what it yields, what is lost), yield and loss, conversion
 * cost from meters, throughput / availability / utilisation, and stockpile survey tonnes and variance. Tonnes are kept
 * at 3 decimals, percentages at 4, money at 2 — all {@code HALF_UP}.
 */
public final class CrushingCalculator {

    private CrushingCalculator() {}

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Tonnes of a fraction that is {@code pct} % of {@code feed}. */
    public static BigDecimal tonnesOf(BigDecimal feed, BigDecimal pct) {
        if (feed == null || pct == null) {
            return BigDecimal.ZERO.setScale(3);
        }
        return feed.multiply(pct).divide(HUNDRED, 3, RoundingMode.HALF_UP);
    }

    /** {@code part} as % of {@code whole} (4 dp), null when the whole is zero. */
    public static BigDecimal pctOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, 4, RoundingMode.HALF_UP);
    }

    /** A meter delta: the direct value when given, else end − start (refused when the meter went backwards). */
    public static BigDecimal delta(BigDecimal start, BigDecimal end, BigDecimal direct, String what) {
        if (direct != null) {
            return direct;
        }
        if (start == null || end == null) {
            return BigDecimal.ZERO;
        }
        if (end.compareTo(start) < 0) {
            throw new IllegalArgumentException("The " + what + " end reading is below the start reading");
        }
        return end.subtract(start);
    }

    /** {@code quantity × rate} at 2 dp (0 when either is missing). */
    public static BigDecimal cost(BigDecimal quantity, BigDecimal rate) {
        if (quantity == null || rate == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return quantity.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ mass balance

    /** One measured fraction of a run: the circuit stage that yields it and whether it is the main product. */
    public record Fraction(String key, int stage, boolean main, BigDecimal tonnes) {}

    /**
     * What one circuit stage receives ({@code input}), passes on or keeps as its main output ({@code good}), yields as
     * co-/by-products / waste ({@code fractions}) and loses ({@code loss}, the last stage only).
     */
    public record StagePlan(int sequence, BigDecimal input, BigDecimal good, BigDecimal loss, List<Fraction> fractions) {}

    /**
     * Walk the circuit: stage 1 receives the feed, each stage before the last passes on its input less its own
     * fractions, the last stage's good output is the main product and the rest of its input is the process loss.
     * Refused (IllegalArgumentException) when a stage's fractions exceed what it receives.
     */
    public static List<StagePlan> stagePlan(BigDecimal feed, int stages, List<Fraction> fractions) {
        if (stages < 1) {
            throw new IllegalArgumentException("The plant has no circuit stages");
        }
        List<StagePlan> out = new ArrayList<>();
        BigDecimal input = feed;
        for (int s = 1; s <= stages; s++) {
            final int seq = s;
            boolean last = s == stages;
            List<Fraction> own = fractions.stream().filter(f -> Math.min(Math.max(f.stage(), 1), stages) == seq
                    && !(last && f.main())).toList();
            BigDecimal sum = own.stream().map(Fraction::tonnes).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!last) {
                BigDecimal good = input.subtract(sum);
                if (good.signum() < 0) {
                    throw new IllegalArgumentException("The outputs of stage " + s + " (" + plain(sum)
                            + " t) exceed what it receives (" + plain(input) + " t)");
                }
                out.add(new StagePlan(s, input, good, BigDecimal.ZERO, own));
                input = good;
            } else {
                BigDecimal main = fractions.stream().filter(Fraction::main).map(Fraction::tonnes)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal loss = input.subtract(main).subtract(sum);
                if (loss.signum() < 0) {
                    BigDecimal total = fractions.stream().map(Fraction::tonnes).reduce(BigDecimal.ZERO, BigDecimal::add);
                    throw new IllegalArgumentException("The outputs (" + plain(total) + " t) exceed the feed ("
                            + plain(feed) + " t)");
                }
                out.add(new StagePlan(s, input, main, loss, own));
            }
        }
        return out;
    }

    /** Yield of a run: outputs, loss = feed − outputs, actual yield % and its variance to the expected recovery. */
    public record Yield(BigDecimal outputTonnes, BigDecimal lossTonnes, BigDecimal actualYieldPct,
            BigDecimal expectedYieldPct, BigDecimal yieldVariancePct) {}

    public static Yield yield(BigDecimal feed, List<BigDecimal> outputs, BigDecimal expectedRecoveryPct) {
        BigDecimal out = outputs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = pctOf(out, feed);
        BigDecimal expected = expectedRecoveryPct == null ? null : expectedRecoveryPct.setScale(4, RoundingMode.HALF_UP);
        return new Yield(out, feed.subtract(out), actual, expected,
                actual == null || expected == null ? null : actual.subtract(expected));
    }

    // ------------------------------------------------------------------ plant performance

    /** Throughput (t/h), availability % (operating ÷ scheduled) and utilisation % (throughput ÷ capacity). */
    public record Performance(BigDecimal throughputTph, BigDecimal availabilityPct, BigDecimal utilisationPct) {}

    public static Performance performance(BigDecimal tonnes, BigDecimal operatingHours, BigDecimal downtimeHours,
            BigDecimal capacityTph) {
        BigDecimal op = operatingHours == null ? BigDecimal.ZERO : operatingHours;
        BigDecimal down = downtimeHours == null ? BigDecimal.ZERO : downtimeHours;
        BigDecimal tph = op.signum() > 0 && tonnes != null ? tonnes.divide(op, 3, RoundingMode.HALF_UP) : null;
        BigDecimal availability = pctOf(op, op.add(down));
        BigDecimal utilisation = tph == null || capacityTph == null || capacityTph.signum() == 0 ? null
                : pctOf(tph, capacityTph);
        return new Performance(tph, availability, utilisation);
    }

    // ------------------------------------------------------------------ stockpile survey

    /** Surveyed tonnes = volume (m³) × bulk density (t/m³), 3 dp. */
    public static BigDecimal surveyTonnes(BigDecimal volumeM3, BigDecimal bulkDensity) {
        return volumeM3.multiply(bulkDensity).setScale(3, RoundingMode.HALF_UP);
    }

    /** Survey variance against the book stock: tonnes (surveyed − book) and % of book (null when book is 0). */
    public record SurveyVariance(BigDecimal tonnes, BigDecimal pct) {}

    public static SurveyVariance surveyVariance(BigDecimal surveyed, BigDecimal book) {
        BigDecimal b = book == null ? BigDecimal.ZERO : book;
        BigDecimal v = surveyed.subtract(b).setScale(3, RoundingMode.HALF_UP);
        return new SurveyVariance(v, pctOf(v, b));
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
