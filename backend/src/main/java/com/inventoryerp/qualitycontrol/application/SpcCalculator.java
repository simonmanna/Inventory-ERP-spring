package com.inventoryerp.qualitycontrol.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shewhart control charts and capability (ADR-S087 §12). Pure: no Spring, no database; {@link BigDecimal} with
 * {@link MathContext#DECIMAL64} throughout.
 *
 * <ul>
 *   <li>Subgroups of one value: I-MR chart, σ̂ = MR̄ / d2(2) = MR̄ / 1.128, limits x̄ ± 3·MR̄/1.128 (NIST/SEMATECH
 *       e-Handbook 6.3.2.2), MR chart UCL = D4(2)·MR̄.
 *   <li>Subgroups of a constant size n (2..10): X̄-R, limits x̿ ± A2·R̄, R chart D3·R̄ / D4·R̄, σ̂ = R̄ / d2(n)
 *       (constants as printed in e-Handbook 6.3.2.1).
 *   <li>Subgroups of varying size: σ̂ = mean(Rᵢ / d2(nᵢ)) over subgroups with nᵢ ≥ 2, per-subgroup limits
 *       x̿ ± 3σ̂/√nᵢ (x̿ = mean of all baseline values) and R limits D3(nᵢ)·d2(nᵢ)·σ̂ / D4(nᵢ)·d2(nᵢ)·σ̂.
 * </ul>
 *
 * Limits come only from the data — the first {@code baseline} subgroups, or all of them as trial limits. Spec limits
 * (USL / LSL) are never used for control limits; they only feed capability.
 */
public final class SpcCalculator {

    static final MathContext MC = MathContext.DECIMAL64;
    static final int MAX_SUBGROUP = 10;
    static final int MIN_SUBGROUPS = 20;
    static final int MIN_INDIVIDUALS = 25;
    static final int MIN_CAPABILITY_VALUES = 30;

    // Index = n. NIST/SEMATECH e-Handbook 6.3.2.1 (A2, D3, D4); d2 standard values (d2(2) = 1.128 as in 6.3.2.2).
    static final BigDecimal[] A2 = dec(null, null, "1.880", "1.023", "0.729", "0.577", "0.483", "0.419", "0.373",
            "0.337", "0.308");
    static final BigDecimal[] D3 = dec(null, null, "0", "0", "0", "0", "0", "0.076", "0.136", "0.184", "0.223");
    static final BigDecimal[] D4 = dec(null, null, "3.267", "2.575", "2.282", "2.115", "2.004", "1.924", "1.864",
            "1.816", "1.777");
    static final BigDecimal[] D2 = dec(null, null, "1.128", "1.693", "2.059", "2.326", "2.534", "2.704", "2.847",
            "2.970", "3.078");

    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private static final BigDecimal SIX = BigDecimal.valueOf(6);

    private SpcCalculator() {}

    /** One rational subgroup, in time order. {@code refs} are the check / sample ids behind the values. */
    public record Subgroup(String key, Instant at, List<BigDecimal> values, List<String> refs) {}

    /**
     * @param baseline the first {@code baseline} subgroups set the limits; null = all (trial limits)
     * @param rules Western Electric rules to evaluate (1..4)
     */
    public record Options(Integer baseline, Set<Integer> rules, BigDecimal lsl, BigDecimal usl) {}

    /** A plotted subgroup: centre-chart value, range (MR for I-MR), its limits and the rules it breaks. */
    public record Point(int index, String key, Instant at, int n, BigDecimal value, BigDecimal range, BigDecimal ucl,
            BigDecimal lcl, BigDecimal rangeUcl, BigDecimal rangeLcl, boolean baseline, List<String> violations) {}

    public record Capability(boolean valid, String reason, int n, BigDecimal mean, BigDecimal sigma, BigDecimal cp,
            BigDecimal cpk, BigDecimal cpu, BigDecimal cpl) {}

    /**
     * @param ucl the centre chart's upper limit when it is the same for every point (null when subgroup sizes vary)
     */
    public record Result(String method, String sigmaEstimator, int values, int subgroups, int baselineSubgroups,
            String limitsType, boolean provisional, BigDecimal center, BigDecimal ucl, BigDecimal lcl,
            BigDecimal rangeCenter, BigDecimal rangeUcl, BigDecimal rangeLcl, BigDecimal sigma, List<Point> points,
            Capability capability) {}

    public static Result analyze(List<Subgroup> subgroups, Options o) {
        if (subgroups.isEmpty()) {
            throw new IllegalArgumentException("No subgroups");
        }
        for (Subgroup s : subgroups) {
            if (s.values().isEmpty() || s.values().size() > MAX_SUBGROUP) {
                throw new IllegalArgumentException("Subgroup size must be 1.." + MAX_SUBGROUP);
            }
        }
        int k = subgroups.size();
        int b = o.baseline() == null ? k : Math.max(1, Math.min(o.baseline(), k));
        boolean individuals = subgroups.stream().allMatch(s -> s.values().size() == 1)
                || subgroups.subList(0, b).stream().noneMatch(s -> s.values().size() >= 2);
        Result r = individuals ? individuals(subgroups, b, o) : xbarR(subgroups, b, o);
        return r;
    }

    // ------------------------------------------------------------------ I-MR

    private static Result individuals(List<Subgroup> subgroups, int b, Options o) {
        // A subgroup of several values in an otherwise individual series is plotted by its mean.
        List<BigDecimal> x = subgroups.stream().map(s -> mean(s.values())).toList();
        int k = x.size();
        BigDecimal center = mean(x.subList(0, b));
        List<BigDecimal> mrBase = new ArrayList<>();
        for (int i = 1; i < b; i++) {
            mrBase.add(x.get(i).subtract(x.get(i - 1)).abs());
        }
        BigDecimal mrBar = mrBase.isEmpty() ? BigDecimal.ZERO : mean(mrBase);
        BigDecimal halfWidth = THREE.multiply(mrBar, MC).divide(D2[2], MC);
        BigDecimal ucl = center.add(halfWidth, MC);
        BigDecimal lcl = center.subtract(halfWidth, MC);
        BigDecimal mrUcl = D4[2].multiply(mrBar, MC);
        BigDecimal sigma = mrBar.divide(D2[2], MC);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            Subgroup s = subgroups.get(i);
            BigDecimal mr = i == 0 ? null : x.get(i).subtract(x.get(i - 1)).abs();
            points.add(new Point(i, s.key(), s.at(), s.values().size(), x.get(i), mr, ucl, lcl, mrUcl,
                    BigDecimal.ZERO, i < b, new ArrayList<>()));
        }
        flag(points, center, o.rules());
        int values = subgroups.stream().mapToInt(s -> s.values().size()).sum();
        boolean provisional = b < MIN_INDIVIDUALS;
        return new Result("I-MR", "MR̄/d2 (d2 = 1.128)", values, k, b, o.baseline() == null ? "trial" : "baseline",
                provisional, center, ucl, lcl, mrBar, mrUcl, BigDecimal.ZERO, sigma, points,
                capability(subgroups, points, b, sigma, o));
    }

    // ------------------------------------------------------------------ X̄-R

    private static Result xbarR(List<Subgroup> subgroups, int b, Options o) {
        int k = subgroups.size();
        List<Subgroup> base = subgroups.subList(0, b);
        int n0 = base.getFirst().values().size();
        boolean constant = subgroups.stream().allMatch(s -> s.values().size() == n0) && n0 >= 2;
        BigDecimal center;
        BigDecimal rBar = null;
        BigDecimal sigma;
        if (constant) {
            center = mean(base.stream().map(s -> mean(s.values())).toList());
            rBar = mean(base.stream().map(s -> range(s.values())).toList());
            sigma = rBar.divide(D2[n0], MC);
        } else {
            List<BigDecimal> all = new ArrayList<>();
            base.forEach(s -> all.addAll(s.values()));
            center = mean(all);
            List<BigDecimal> scaled = base.stream().filter(s -> s.values().size() >= 2)
                    .map(s -> range(s.values()).divide(D2[s.values().size()], MC)).toList();
            sigma = mean(scaled);
        }
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            Subgroup s = subgroups.get(i);
            int n = s.values().size();
            BigDecimal half;
            BigDecimal rUcl = null;
            BigDecimal rLcl = null;
            if (constant) {
                half = A2[n].multiply(rBar, MC);
                rUcl = D4[n].multiply(rBar, MC);
                rLcl = D3[n].multiply(rBar, MC);
            } else {
                half = THREE.multiply(sigma, MC).divide(BigDecimal.valueOf(n).sqrt(MC), MC);
                if (n >= 2) {
                    BigDecimal rc = D2[n].multiply(sigma, MC);
                    rUcl = D4[n].multiply(rc, MC);
                    rLcl = D3[n].multiply(rc, MC);
                }
            }
            points.add(new Point(i, s.key(), s.at(), n, mean(s.values()), n >= 2 ? range(s.values()) : null,
                    center.add(half, MC), center.subtract(half, MC), rUcl, rLcl, i < b, new ArrayList<>()));
        }
        flag(points, center, o.rules());
        int values = subgroups.stream().mapToInt(s -> s.values().size()).sum();
        BigDecimal ucl = constant ? points.getFirst().ucl() : null;
        BigDecimal lcl = constant ? points.getFirst().lcl() : null;
        return new Result("Xbar-R", constant ? "R̄/d2 (n = " + n0 + ")" : "mean(Rᵢ/d2(nᵢ))", values, k, b,
                o.baseline() == null ? "trial" : "baseline", b < MIN_SUBGROUPS, center, ucl, lcl, rBar,
                constant ? D4[n0].multiply(rBar, MC) : null, constant ? D3[n0].multiply(rBar, MC) : null, sigma,
                points, capability(subgroups, points, b, sigma, o));
    }

    // ------------------------------------------------------------------ Western Electric rules

    /** Rules 1–4 on the centre chart (zones from each point's own limits) plus R / MR beyond its limits ("R"). */
    static void flag(List<Point> points, BigDecimal center, Set<Integer> rules) {
        Set<Integer> on = rules == null || rules.isEmpty() ? Set.of(1, 4) : new TreeSet<>(rules);
        int k = points.size();
        double[] z = new double[k];
        for (int i = 0; i < k; i++) {
            Point p = points.get(i);
            BigDecimal sigmaX = p.ucl().subtract(center, MC).divide(THREE, MC);
            BigDecimal dev = p.value().subtract(center, MC);
            if (sigmaX.signum() == 0) {
                z[i] = dev.signum() == 0 ? 0 : dev.signum() * Double.POSITIVE_INFINITY;
            } else {
                z[i] = dev.divide(sigmaX, MC).doubleValue();
            }
        }
        for (int i = 0; i < k; i++) {
            List<String> v = points.get(i).violations();
            if (on.contains(1) && Math.abs(z[i]) > 3) {
                v.add("1");
            }
            if (on.contains(2) && window(z, i, 3, 2, 2)) {
                v.add("2");
            }
            if (on.contains(3) && window(z, i, 5, 4, 1)) {
                v.add("3");
            }
            if (on.contains(4) && i >= 7) {
                boolean above = true;
                boolean below = true;
                for (int j = i - 7; j <= i; j++) {
                    above &= z[j] > 0;
                    below &= z[j] < 0;
                }
                if (above || below) {
                    v.add("4");
                }
            }
            Point p = points.get(i);
            if (p.range() != null && p.rangeUcl() != null && (p.range().compareTo(p.rangeUcl()) > 0
                    || (p.rangeLcl() != null && p.rangeLcl().signum() > 0 && p.range().compareTo(p.rangeLcl()) < 0))) {
                v.add("R");
            }
        }
    }

    /** {@code need} of the {@code size} points ending at {@code i} lie beyond {@code zone}σ on the same side. */
    private static boolean window(double[] z, int i, int size, int need, double zone) {
        if (i < size - 1) {
            return false;
        }
        int up = 0;
        int down = 0;
        for (int j = i - size + 1; j <= i; j++) {
            if (z[j] > zone) {
                up++;
            } else if (z[j] < -zone) {
                down++;
            }
        }
        // the point itself must be one of them, so the rule fires where the run completes
        return (up >= need && z[i] > zone) || (down >= need && z[i] < -zone);
    }

    // ------------------------------------------------------------------ capability

    private static Capability capability(List<Subgroup> subgroups, List<Point> points, int b, BigDecimal sigma,
            Options o) {
        List<BigDecimal> all = new ArrayList<>();
        subgroups.forEach(s -> all.addAll(s.values()));
        BigDecimal mean = mean(all);
        if (o.lsl() == null && o.usl() == null) {
            return invalid("No specification limits.", all.size(), mean, sigma);
        }
        if (all.size() < MIN_CAPABILITY_VALUES) {
            return invalid("At least " + MIN_CAPABILITY_VALUES + " values are needed; there are " + all.size() + ".",
                    all.size(), mean, sigma);
        }
        if (points.subList(0, b).stream().anyMatch(p -> p.violations().contains("1"))) {
            return invalid("The baseline is not in control (rule 1).", all.size(), mean, sigma);
        }
        if (sigma.signum() == 0) {
            return invalid("undefined (zero variation)", all.size(), mean, sigma);
        }
        Capability c = capability(mean, sigma, o.lsl(), o.usl());
        return new Capability(true, null, all.size(), mean, sigma, c.cp(), c.cpk(), c.cpu(), c.cpl());
    }

    /** Cp, Cpu, Cpl, Cpk from a mean and σ̂ (e-Handbook 6.1.6); Cp needs both limits, Cpk is the smaller index. */
    public static Capability capability(BigDecimal mean, BigDecimal sigma, BigDecimal lsl, BigDecimal usl) {
        BigDecimal cp = lsl != null && usl != null ? usl.subtract(lsl, MC).divide(SIX.multiply(sigma, MC), MC) : null;
        BigDecimal threeSigma = THREE.multiply(sigma, MC);
        BigDecimal cpu = usl == null ? null : usl.subtract(mean, MC).divide(threeSigma, MC);
        BigDecimal cpl = lsl == null ? null : mean.subtract(lsl, MC).divide(threeSigma, MC);
        BigDecimal cpk = cpu == null ? cpl : cpl == null ? cpu : cpu.min(cpl);
        return new Capability(true, null, 0, mean, sigma, cp, cpk, cpu, cpl);
    }

    private static Capability invalid(String reason, int n, BigDecimal mean, BigDecimal sigma) {
        return new Capability(false, reason, n, mean, sigma, null, null, null, null);
    }

    // ------------------------------------------------------------------ helpers

    static BigDecimal mean(List<BigDecimal> v) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal d : v) {
            sum = sum.add(d, MC);
        }
        return sum.divide(BigDecimal.valueOf(v.size()), MC);
    }

    static BigDecimal range(List<BigDecimal> v) {
        BigDecimal max = v.getFirst();
        BigDecimal min = v.getFirst();
        for (BigDecimal d : v) {
            max = max.max(d);
            min = min.min(d);
        }
        return max.subtract(min, MC);
    }

    private static BigDecimal[] dec(String... s) {
        BigDecimal[] out = new BigDecimal[s.length];
        for (int i = 0; i < s.length; i++) {
            out[i] = s[i] == null ? null : new BigDecimal(s[i]);
        }
        return out;
    }
}
