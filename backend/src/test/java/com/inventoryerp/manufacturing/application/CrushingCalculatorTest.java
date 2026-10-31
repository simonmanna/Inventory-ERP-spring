package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.manufacturing.application.CrushingCalculator.Fraction;
import com.inventoryerp.manufacturing.application.CrushingCalculator.StagePlan;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Crushing arithmetic (ADR-S016): split, meters, per-stage mass balance, yield, performance, survey variance. */
class CrushingCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("tonnes from a % split round HALF_UP to 3 dp; % of a whole to 4 dp")
    void split() {
        assertThat(CrushingCalculator.tonnesOf(d("1000"), d("18.5"))).isEqualByComparingTo("185");
        assertThat(CrushingCalculator.tonnesOf(d("333.333"), d("33.3333"))).isEqualByComparingTo("111.111");
        assertThat(CrushingCalculator.tonnesOf(d("10"), d("0.00005"))).isEqualByComparingTo("0.000");
        assertThat(CrushingCalculator.pctOf(d("1"), d("3"))).isEqualByComparingTo("33.3333");
        assertThat(CrushingCalculator.pctOf(d("1"), BigDecimal.ZERO)).isNull();
    }

    @Test
    @DisplayName("meter delta: direct value wins, else end − start; a meter that went backwards is refused")
    void meters() {
        assertThat(CrushingCalculator.delta(d("1200"), d("1208.5"), null, "kWh")).isEqualByComparingTo("8.5");
        assertThat(CrushingCalculator.delta(d("1200"), d("1208.5"), d("7"), "kWh")).isEqualByComparingTo("7");
        assertThat(CrushingCalculator.delta(null, null, null, "kWh")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> CrushingCalculator.delta(d("10"), d("9"), null, "engine-hours"))
                .hasMessage("The engine-hours end reading is below the start reading");
        assertThat(CrushingCalculator.cost(d("120.5"), d("1.3333"))).isEqualByComparingTo("160.66");
        assertThat(CrushingCalculator.cost(null, d("2"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("3-stage circuit: stage 1 scalpings leave early, the last stage yields main + co-products, rest is loss")
    void stagePlan() {
        List<StagePlan> plan = CrushingCalculator.stagePlan(d("1000"), 3, List.of(
                new Fraction("scalp", 1, false, d("50")),
                new Fraction("dust", 3, false, d("180")),
                new Fraction("10mm", 3, false, d("220")),
                new Fraction("20mm", 3, true, d("500"))));
        assertThat(plan).hasSize(3);
        assertThat(plan.get(0).input()).isEqualByComparingTo("1000");
        assertThat(plan.get(0).good()).isEqualByComparingTo("950");
        assertThat(plan.get(0).fractions()).extracting(Fraction::key).containsExactly("scalp");
        assertThat(plan.get(1).input()).isEqualByComparingTo("950");
        assertThat(plan.get(1).good()).isEqualByComparingTo("950");
        assertThat(plan.get(1).fractions()).isEmpty();
        assertThat(plan.get(2).good()).isEqualByComparingTo("500");
        assertThat(plan.get(2).loss()).isEqualByComparingTo("50");
        assertThat(plan.get(2).fractions()).extracting(Fraction::key).containsExactly("dust", "10mm");
    }

    @Test
    @DisplayName("a stage number beyond the circuit counts as the last stage; outputs above the feed are refused")
    void stagePlanLimits() {
        List<StagePlan> plan = CrushingCalculator.stagePlan(d("100"), 2, List.of(
                new Fraction("a", 9, false, d("40")), new Fraction("m", Integer.MAX_VALUE, true, d("60"))));
        assertThat(plan.get(1).fractions()).hasSize(1);
        assertThat(plan.get(1).loss()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> CrushingCalculator.stagePlan(d("100"), 2, List.of(
                new Fraction("a", 3, false, d("40")), new Fraction("m", 3, true, d("60.5")))))
                .hasMessage("The outputs (100.5 t) exceed the feed (100 t)");
        assertThatThrownBy(() -> CrushingCalculator.stagePlan(d("100"), 2, List.of(
                new Fraction("a", 1, false, d("140")), new Fraction("m", 2, true, d("0")))))
                .hasMessage("The outputs of stage 1 (140 t) exceed what it receives (100 t)");
        assertThatThrownBy(() -> CrushingCalculator.stagePlan(d("100"), 0, List.of()))
                .hasMessage("The plant has no circuit stages");
    }

    @Test
    @DisplayName("yield: outputs, loss, actual vs expected recovery")
    void yield() {
        CrushingCalculator.Yield y = CrushingCalculator.yield(d("1000"), List.of(d("500"), d("220"), d("180"),
                d("50")), d("97"));
        assertThat(y.outputTonnes()).isEqualByComparingTo("950");
        assertThat(y.lossTonnes()).isEqualByComparingTo("50");
        assertThat(y.actualYieldPct()).isEqualByComparingTo("95");
        assertThat(y.yieldVariancePct()).isEqualByComparingTo("-2");
    }

    @Test
    @DisplayName("performance: t/h, availability and utilisation of design capacity")
    void performance() {
        CrushingCalculator.Performance p = CrushingCalculator.performance(d("1000"), d("8"), d("2"), d("150"));
        assertThat(p.throughputTph()).isEqualByComparingTo("125");
        assertThat(p.availabilityPct()).isEqualByComparingTo("80");
        assertThat(p.utilisationPct()).isEqualByComparingTo("83.3333");
        CrushingCalculator.Performance none = CrushingCalculator.performance(d("1000"), null, d("0"), null);
        assertThat(none.throughputTph()).isNull();
        assertThat(none.availabilityPct()).isNull();
        assertThat(none.utilisationPct()).isNull();
    }

    @Test
    @DisplayName("survey: volume × density, variance against book (null % on an empty book)")
    void survey() {
        assertThat(CrushingCalculator.surveyTonnes(d("1234.5"), d("1.6"))).isEqualByComparingTo("1975.2");
        CrushingCalculator.SurveyVariance v = CrushingCalculator.surveyVariance(d("1975.2"), d("2000"));
        assertThat(v.tonnes()).isEqualByComparingTo("-24.8");
        assertThat(v.pct()).isEqualByComparingTo("-1.24");
        assertThat(CrushingCalculator.surveyVariance(d("10"), BigDecimal.ZERO).pct()).isNull();
    }

    @Test
    @DisplayName("run cost kinds map to the stage conversion kinds")
    void kinds() {
        assertThat(CrushingRunService.conversionKind("diesel")).isEqualTo("machine");
        assertThat(CrushingRunService.conversionKind("wear_parts")).isEqualTo("machine");
        assertThat(CrushingRunService.conversionKind("electricity")).isEqualTo("utility");
        assertThat(CrushingRunService.conversionKind("labour")).isEqualTo("labour");
        assertThat(CrushingRunService.conversionKind("maintenance")).isEqualTo("overhead");
    }
}
