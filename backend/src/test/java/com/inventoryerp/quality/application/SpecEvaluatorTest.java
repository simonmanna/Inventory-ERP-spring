package com.inventoryerp.quality.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.quality.application.SpecEvaluator.Evaluation;
import com.inventoryerp.quality.application.SpecEvaluator.Parameter;
import com.inventoryerp.quality.application.SpecEvaluator.Sample;
import com.inventoryerp.quality.application.SpecEvaluator.SievePoint;
import com.inventoryerp.quality.application.SpecEvaluator.SieveReading;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Specification evaluation (ADR-S013): numeric limits, sieve grading envelope, boolean / text, critical flag. */
class SpecEvaluatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static Parameter numeric(String id, String min, String max, boolean critical) {
        return new Parameter(id, id.toUpperCase(), id, "numeric", "%", min == null ? null : d(min),
                max == null ? null : d(max), null, null, List.of(), critical);
    }

    /** 20 mm single-size aggregate grading envelope (BS 882 style). */
    static Parameter grading() {
        return new Parameter("g", "GRADING", "Grading", "sieve", "%", null, null, null, null, List.of(
                new SievePoint(d("37.5"), d("100"), d("100")),
                new SievePoint(d("20"), d("85"), d("100")),
                new SievePoint(d("10"), d("0"), d("25")),
                new SievePoint(d("5"), d("0"), d("5"))), true);
    }

    static Sample sieve(int no, String... pairs) {
        List<SieveReading> r = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            r.add(new SieveReading(d(pairs[i]), d(pairs[i + 1])));
        }
        return new Sample(no, null, null, r);
    }

    @Test
    @DisplayName("numeric: inside the limits passes, outside fails, open bounds work")
    void numericLimits() {
        Parameter moisture = numeric("m", null, "3", true);
        assertThat(SpecEvaluator.check(moisture, new Sample(1, d("2.4"), null, null))).isEmpty();
        assertThat(SpecEvaluator.check(moisture, new Sample(1, d("3"), null, null))).isEmpty();
        assertThat(SpecEvaluator.check(moisture, new Sample(1, d("3.1"), null, null))).singleElement().asString()
                .contains("outside ≤ 3");
        Parameter strength = numeric("s", "7", null, true);
        assertThat(SpecEvaluator.check(strength, new Sample(1, d("6.9"), null, null))).hasSize(1);
        assertThat(SpecEvaluator.check(strength, new Sample(1, null, null, null))).containsExactly("no value");
    }

    @Test
    @DisplayName("sieve: every envelope sieve read and within its band; a missing sieve or a band breach fails")
    void sieveEnvelope() {
        Parameter g = grading();
        assertThat(SpecEvaluator.check(g, sieve(1, "37.5", "100", "20", "92", "10", "12", "5", "2"))).isEmpty();
        List<String> wide = SpecEvaluator.check(g, sieve(1, "37.5", "100", "20", "80", "10", "30", "5", "2"));
        assertThat(wide).hasSize(2);
        assertThat(wide.get(0)).contains("20 mm passing 80 % outside 85–100 %");
        assertThat(wide.get(1)).contains("10 mm passing 30 % outside 0–25 %");
        assertThat(SpecEvaluator.check(g, sieve(1, "37.5", "100", "20", "90", "10", "10")))
                .containsExactly("no reading for sieve 5 mm");
        // 20.0 and 20 are the same sieve.
        assertThat(SpecEvaluator.check(g, sieve(1, "37.50", "100", "20.0", "90", "10", "10", "5.000", "1"))).isEmpty();
    }

    @Test
    @DisplayName("sieve readings: passing % cannot rise on a smaller sieve, must be 0–100, no duplicate sieve")
    void sieveValidation() {
        assertThat(SpecEvaluator.validateSieve(List.of(new SieveReading(d("20"), d("90")),
                new SieveReading(d("10"), d("40"))))).isNull();
        assertThat(SpecEvaluator.validateSieve(List.of(new SieveReading(d("10"), d("50")),
                new SieveReading(d("20"), d("40"))))).contains("cannot increase on a smaller sieve");
        assertThat(SpecEvaluator.validateSieve(List.of(new SieveReading(d("10"), d("101"))))).contains("between 0 and 100");
        assertThat(SpecEvaluator.validateSieve(List.of(new SieveReading(d("10"), d("50")),
                new SieveReading(d("10.0"), d("50"))))).contains("listed twice");
        assertThat(SpecEvaluator.validateSieve(List.of())).contains("at least one");
    }

    @Test
    @DisplayName("boolean and text parameters")
    void booleanText() {
        Parameter clean = new Parameter("c", "CLEAN", "Free of clay lumps", "boolean", null, null, null, null, null,
                List.of(), true);
        assertThat(SpecEvaluator.check(clean, new Sample(1, null, "yes", null))).isEmpty();
        assertThat(SpecEvaluator.check(clean, new Sample(1, null, "false", null))).containsExactly(
                "is false, expected true");
        Parameter colour = new Parameter("t", "COLOUR", "Colour", "text", null, null, null, null, "Grey", List.of(), false);
        assertThat(SpecEvaluator.check(colour, new Sample(1, null, "grey", null))).isEmpty();
        assertThat(SpecEvaluator.check(colour, new Sample(1, null, "red", null))).hasSize(1);
    }

    @Test
    @DisplayName("lot: pending until all samples recorded, fail on any critical failure, deviation does not fail")
    void overall() {
        Parameter m = numeric("m", null, "3", true);
        Parameter dims = numeric("len", "438", "442", false);
        List<Parameter> params = List.of(m, dims, grading());
        Map<String, List<Sample>> samples = new java.util.HashMap<>();
        samples.put("m", List.of(new Sample(1, d("2"), null, null)));
        Evaluation partial = SpecEvaluator.evaluate(params, samples, 2);
        assertThat(partial.result()).isEqualTo("pending");
        assertThat(partial.pending()).isEqualTo(3);

        samples.put("m", List.of(new Sample(1, d("2"), null, null), new Sample(2, d("2.5"), null, null)));
        samples.put("len", List.of(new Sample(1, d("443"), null, null), new Sample(2, d("440"), null, null)));
        samples.put("g", List.of(sieve(1, "37.5", "100", "20", "92", "10", "12", "5", "2"),
                sieve(2, "37.5", "100", "20", "95", "10", "15", "5", "3")));
        Evaluation ok = SpecEvaluator.evaluate(params, samples, 2);
        assertThat(ok.result()).isEqualTo("pass");
        assertThat(ok.deviations()).isEqualTo(1);
        assertThat(ok.parameters().get(0).mean()).isEqualByComparingTo("2.25");
        assertThat(ok.parameters().get(1).status()).isEqualTo("deviation");

        // A recorded failure fails the lot even before every sample is in.
        samples.put("m", List.of(new Sample(1, d("4"), null, null)));
        Evaluation bad = SpecEvaluator.evaluate(params, samples, 2);
        assertThat(bad.result()).isEqualTo("fail");
        assertThat(bad.parameters().get(0).status()).isEqualTo("fail");
        assertThat(bad.toMap()).containsEntry("result", "fail").containsKey("parameters");
        assertThat(SpecEvaluator.evaluate(List.of(), Map.of(), 1).result()).isEqualTo("pending");
    }
}
