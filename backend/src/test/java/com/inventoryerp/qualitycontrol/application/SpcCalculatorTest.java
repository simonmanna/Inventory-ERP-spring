package com.inventoryerp.qualitycontrol.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.inventoryerp.qualitycontrol.application.SpcCalculator.Capability;
import com.inventoryerp.qualitycontrol.application.SpcCalculator.Options;
import com.inventoryerp.qualitycontrol.application.SpcCalculator.Point;
import com.inventoryerp.qualitycontrol.application.SpcCalculator.Result;
import com.inventoryerp.qualitycontrol.application.SpcCalculator.Subgroup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-S087 §12 acceptance: the calculator against published reference data (NIST/SEMATECH e-Handbook of Statistical
 * Methods, sections 6.3.2.2, 6.3.2.1 and 6.1.6), then the edge cases.
 */
class SpcCalculatorTest {

    static final double TOL = 0.0001;

    static List<Subgroup> individuals(double... v) {
        List<Subgroup> out = new ArrayList<>();
        for (int i = 0; i < v.length; i++) {
            out.add(new Subgroup("x" + i, Instant.ofEpochSecond(i), List.of(BigDecimal.valueOf(v[i])), List.of("r" + i)));
        }
        return out;
    }

    static List<Subgroup> groups(double[][] g) {
        List<Subgroup> out = new ArrayList<>();
        for (int i = 0; i < g.length; i++) {
            out.add(new Subgroup("g" + i, Instant.ofEpochSecond(i), Arrays.stream(g[i]).mapToObj(BigDecimal::valueOf)
                    .toList(), List.of()));
        }
        return out;
    }

    static Options opts(Integer baseline, Set<Integer> rules, String lsl, String usl) {
        return new Options(baseline, rules, lsl == null ? null : new BigDecimal(lsl), usl == null ? null
                : new BigDecimal(usl));
    }

    static double d(BigDecimal b) {
        return b.doubleValue();
    }

    @Test
    @DisplayName("NIST 6.3.2.2 flow-rate individuals chart: x̄ 50.81, MR̄ 1.8778, UCL 55.8041, LCL 45.8159, in control")
    void nistIndividuals() {
        Result r = SpcCalculator.analyze(individuals(49.6, 47.6, 49.9, 51.3, 47.8, 51.2, 52.6, 52.4, 53.6, 52.1),
                opts(null, null, null, null));
        assertThat(r.method()).isEqualTo("I-MR");
        assertThat(d(r.center())).isCloseTo(50.81, within(TOL));
        assertThat(d(r.rangeCenter())).isCloseTo(1.8778, within(TOL));
        assertThat(d(r.ucl())).isCloseTo(55.8041, within(TOL));
        assertThat(d(r.lcl())).isCloseTo(45.8159, within(TOL));
        assertThat(d(r.sigma())).isCloseTo(1.8778 / 1.128, within(TOL));
        assertThat(d(r.rangeUcl())).isCloseTo(3.267 * 1.8778, within(0.001));
        assertThat(r.points()).allMatch(p -> !p.violations().contains("1"));
        assertThat(r.points().get(1).range()).isEqualByComparingTo("2.0");
        assertThat(r.points().getFirst().range()).isNull();
        assertThat(r.provisional()).as("10 < 25 individual values").isTrue();
        assertThat(r.limitsType()).isEqualTo("trial");
    }

    @Test
    @DisplayName("NIST 6.1.6 capability example: USL 20, LSL 8, x̄ 16, s 2 → Cp 1.0, Cpu 0.6667, Cpl 1.3333, Cpk 0.6667")
    void nistCapability() {
        Capability c = SpcCalculator.capability(new BigDecimal("16"), new BigDecimal("2"), new BigDecimal("8"),
                new BigDecimal("20"));
        assertThat(d(c.cp())).isCloseTo(1.0, within(TOL));
        assertThat(d(c.cpu())).isCloseTo(0.6667, within(TOL));
        assertThat(d(c.cpl())).isCloseTo(1.3333, within(TOL));
        assertThat(d(c.cpk())).isCloseTo(0.6667, within(TOL));
        Capability upperOnly = SpcCalculator.capability(new BigDecimal("16"), new BigDecimal("2"), null,
                new BigDecimal("20"));
        assertThat(upperOnly.cp()).as("Cp needs both limits").isNull();
        assertThat(d(upperOnly.cpk())).isCloseTo(0.6667, within(TOL));
    }

    @Test
    @DisplayName("NIST 6.3.2.1 constants: A2 = 3/(d2·√n) matches the printed A2 for n = 2..10")
    void constantsAgree() {
        for (int n = 2; n <= 10; n++) {
            double a2 = 3 / (SpcCalculator.D2[n].doubleValue() * Math.sqrt(n));
            assertThat(a2).as("A2(%d)", n).isCloseTo(SpcCalculator.A2[n].doubleValue(), within(0.0015));
            if (n >= 7) { // below 7 the printed D3 is truncated at 0
                assertThat(SpcCalculator.D3[n].add(SpcCalculator.D4[n]).doubleValue()).as("D3 + D4 = 2 (n=%d)", n)
                        .isCloseTo(2.0, within(0.0015));
            }
        }
    }

    @Test
    @DisplayName("X̄-R with n = 5: limits x̿ ± A2·R̄, R chart D3·R̄ / D4·R̄, σ̂ = R̄/d2; 20 subgroups are not provisional")
    void xbarR() {
        double[][] g = new double[20][];
        for (int i = 0; i < 20; i++) {
            double base = 10 + (i % 4) * 0.1;
            g[i] = new double[] {base, base + 0.2, base - 0.1, base + 0.05, base + 0.15};
        }
        Result r = SpcCalculator.analyze(groups(g), opts(null, null, null, null));
        assertThat(r.method()).isEqualTo("Xbar-R");
        double xbb = 0;
        for (double[] s : g) {
            xbb += Arrays.stream(s).average().orElseThrow() / 20;
        }
        double rBar = 0.3; // every subgroup: max − min = 0.2 − (−0.1)
        assertThat(d(r.center())).isCloseTo(xbb, within(1e-9));
        assertThat(d(r.rangeCenter())).isCloseTo(rBar, within(1e-9));
        assertThat(d(r.ucl())).isCloseTo(xbb + 0.577 * rBar, within(1e-9));
        assertThat(d(r.lcl())).isCloseTo(xbb - 0.577 * rBar, within(1e-9));
        assertThat(d(r.rangeUcl())).isCloseTo(2.115 * rBar, within(1e-9));
        assertThat(d(r.rangeLcl())).isZero();
        assertThat(d(r.sigma())).isCloseTo(rBar / 2.326, within(1e-9));
        assertThat(r.provisional()).isFalse();
    }

    @Test
    @DisplayName("varying subgroup sizes: σ̂ = mean(Rᵢ/d2(nᵢ)), limits widen for smaller subgroups")
    void varyingSizes() {
        Result r = SpcCalculator.analyze(groups(new double[][] {{1, 2}, {1, 2, 3}, {2, 2, 3, 3}, {2}}),
                opts(null, null, null, null));
        double sigma = (1 / 1.128 + 2 / 1.693 + 1 / 2.059) / 3;
        assertThat(d(r.sigma())).isCloseTo(sigma, within(1e-9));
        assertThat(r.ucl()).as("no single UCL").isNull();
        double mean = (1 + 2 + 1 + 2 + 3 + 2 + 2 + 3 + 3 + 2) / 10.0;
        Point p2 = r.points().get(1);
        assertThat(d(p2.ucl())).isCloseTo(mean + 3 * sigma / Math.sqrt(3), within(1e-9));
        Point single = r.points().get(3);
        assertThat(d(single.ucl())).isCloseTo(mean + 3 * sigma, within(1e-9));
        assertThat(single.range()).isNull();
    }

    @Test
    @DisplayName("baseline: limits from the first k subgroups only; a later shift breaks rule 1")
    void baseline() {
        double[] v = new double[30];
        for (int i = 0; i < 25; i++) {
            v[i] = 10 + (i % 2 == 0 ? 0.1 : -0.1);
        }
        for (int i = 25; i < 30; i++) {
            v[i] = 12;
        }
        Result r = SpcCalculator.analyze(individuals(v), opts(25, null, "9", "11"));
        assertThat(r.limitsType()).isEqualTo("baseline");
        assertThat(r.baselineSubgroups()).isEqualTo(25);
        assertThat(r.provisional()).isFalse();
        assertThat(d(r.center())).isCloseTo(10.0 + 0.1 / 25, within(1e-9));
        assertThat(r.points().subList(25, 30)).allMatch(p -> p.violations().contains("1"));
        assertThat(r.points().subList(0, 25)).allMatch(p -> !p.violations().contains("1"));
        assertThat(r.capability().valid()).as("30 values, in-control baseline").isTrue();
    }

    @Test
    @DisplayName("Western Electric rules 2, 3 and 4 fire where their run completes")
    void westernElectric() {
        // centre 0, σ̂ from MR: alternate ±1 gives MR̄ 2 → σ̂ ≈ 1.773
        double[] v = {1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1};
        Result base = SpcCalculator.analyze(individuals(v), opts(null, Set.of(1, 2, 3, 4), null, null));
        assertThat(base.points()).allMatch(p -> p.violations().isEmpty());
        List<Subgroup> run = new ArrayList<>(individuals(v));
        double[] shifted = {0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5};
        for (int i = 0; i < shifted.length; i++) {
            run.add(new Subgroup("s" + i, Instant.ofEpochSecond(100 + i), List.of(BigDecimal.valueOf(shifted[i])),
                    List.of()));
        }
        Result r = SpcCalculator.analyze(run, opts(20, Set.of(4), null, null));
        assertThat(r.points().get(27).violations()).containsExactly("4");
        assertThat(r.points().get(26).violations()).as("7 in a row is not yet 8").isEmpty();

        double s = d(base.sigma());
        List<Subgroup> two = new ArrayList<>(individuals(v));
        double[] tail = {2.5 * s, 0, 2.5 * s};
        for (int i = 0; i < tail.length; i++) {
            two.add(new Subgroup("t" + i, Instant.ofEpochSecond(200 + i), List.of(BigDecimal.valueOf(tail[i])),
                    List.of()));
        }
        Result r2 = SpcCalculator.analyze(two, opts(20, Set.of(2), null, null));
        assertThat(r2.points().get(22).violations()).containsExactly("2");
        assertThat(r2.points().get(20).violations()).isEmpty();

        List<Subgroup> four = new ArrayList<>(individuals(v));
        double[] tail4 = {-1.5 * s, -1.5 * s, 0, -1.5 * s, -1.5 * s};
        for (int i = 0; i < tail4.length; i++) {
            four.add(new Subgroup("u" + i, Instant.ofEpochSecond(300 + i), List.of(BigDecimal.valueOf(tail4[i])),
                    List.of()));
        }
        Result r3 = SpcCalculator.analyze(four, opts(20, Set.of(3), null, null));
        assertThat(r3.points().get(24).violations()).containsExactly("3");
    }

    @Test
    @DisplayName("capability is refused below 30 values, without limits, with an out-of-control baseline, at zero σ")
    void capabilityRefusals() {
        Result few = SpcCalculator.analyze(individuals(1, 2, 3, 2, 1), opts(null, null, "0", "4"));
        assertThat(few.capability().valid()).isFalse();
        assertThat(few.capability().reason()).isEqualTo("At least 30 values are needed; there are 5.");

        double[] flat = new double[30];
        Arrays.fill(flat, 5);
        Result zero = SpcCalculator.analyze(individuals(flat), opts(null, null, "4", "6"));
        assertThat(zero.capability().reason()).isEqualTo("undefined (zero variation)");
        assertThat(zero.points()).allMatch(p -> p.violations().isEmpty());

        Result noSpec = SpcCalculator.analyze(individuals(flat), opts(null, null, null, null));
        assertThat(noSpec.capability().reason()).isEqualTo("No specification limits.");

        double[] spike = new double[30];
        for (int i = 0; i < 30; i++) {
            spike[i] = 10 + (i % 2 == 0 ? 0.1 : -0.1);
        }
        spike[10] = 20;
        Result out = SpcCalculator.analyze(individuals(spike), opts(null, null, "9", "11"));
        assertThat(out.points().get(10).violations()).contains("1");
        assertThat(out.capability().reason()).isEqualTo("The baseline is not in control (rule 1).");
    }
}
