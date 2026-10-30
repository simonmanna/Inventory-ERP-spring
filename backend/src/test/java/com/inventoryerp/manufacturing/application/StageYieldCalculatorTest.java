package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.manufacturing.application.StageYieldCalculator.Input;
import com.inventoryerp.manufacturing.application.StageYieldCalculator.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Stage yield, partial relief and abnormal scrap (ADR-S011 §3). */
class StageYieldCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("partial: 40 of 100 planned (100 % yield) relieves 40 % of the pool")
    void partial() {
        Result r = StageYieldCalculator.compute(new Input(d("1000"), null, d("40"), d("0"), d("100"), d("0"), d("100"),
                false), 6);
        assertThat(r.relieved()).isEqualByComparingTo("400");
        assertThat(r.goodValue()).isEqualByComparingTo("400");
        assertThat(r.abnormalQty()).isEqualByComparingTo("0");
        assertThat(r.actualYieldPct()).isEqualByComparingTo("100");
        // Second partial: 600 left over 60 remaining gross → 30 more relieves half.
        Result r2 = StageYieldCalculator.compute(new Input(d("600"), d("0"), d("30"), d("0"), d("100"), d("40"), d("100"),
                false), 6);
        assertThat(r2.relieved()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("completing posting relieves the whole pool; normal loss (90 % yield) absorbed by good output")
    void completesWithNormalLoss() {
        Result r = StageYieldCalculator.compute(new Input(d("900"), null, d("90"), d("10"), d("90"), d("0"), d("90"),
                true), 6);
        assertThat(r.relieved()).isEqualByComparingTo("900");
        assertThat(r.normalLossQty()).isEqualByComparingTo("10");
        assertThat(r.abnormalQty()).isEqualByComparingTo("0");
        assertThat(r.goodValue()).isEqualByComparingTo("900");
        assertThat(r.actualYieldPct()).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("scrap above the normal loss is abnormal and written off at cost per processed unit")
    void abnormalScrap() {
        // 100 % expected yield, 80 good + 20 scrap: all 20 abnormal; pool 1000 → 200 abnormal, 800 good.
        Result r = StageYieldCalculator.compute(new Input(d("1000"), null, d("80"), d("20"), d("100"), d("0"), d("100"),
                true), 6);
        assertThat(r.abnormalQty()).isEqualByComparingTo("20");
        assertThat(r.abnormalValue()).isEqualByComparingTo("200");
        assertThat(r.goodValue()).isEqualByComparingTo("800");
        assertThat(r.goodValue().add(r.abnormalValue())).isEqualByComparingTo(r.relieved());
    }

    @Test
    @DisplayName("no good output: everything relieved is abnormal loss; nothing processed relieves nothing")
    void zeroYield() {
        Result r = StageYieldCalculator.compute(new Input(d("500"), null, d("0"), d("5"), d("10"), d("0"), d("100"),
                true), 6);
        assertThat(r.abnormalValue()).isEqualByComparingTo("500");
        assertThat(r.goodValue()).isEqualByComparingTo("0");
        Result none = StageYieldCalculator.compute(new Input(d("500"), null, d("0"), d("0"), d("10"), d("0"), d("100"),
                false), 6);
        assertThat(none.relieved()).isEqualByComparingTo("0");
        assertThat(none.actualYieldPct()).isNull();
        assertThatThrownBy(() -> StageYieldCalculator.compute(new Input(d("1"), null, d("-1"), d("0"), d("1"), d("0"),
                d("100"), false), 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("backflushed value of the posting is relieved in full, the carried WIP pro rata")
    void directPlusCarried() {
        // 300 backflushed now + 700 carried; 25 of 100 gross processed → 300 + 175.
        Result r = StageYieldCalculator.compute(new Input(d("1000"), d("300"), d("25"), d("0"), d("100"), d("0"),
                d("100"), false), 6);
        assertThat(r.relieved()).isEqualByComparingTo("475");
    }

    @Test
    @DisplayName("over-production beyond plan never relieves more than the pool")
    void overProduction() {
        Result r = StageYieldCalculator.compute(new Input(d("100"), null, d("150"), d("0"), d("100"), d("0"), d("100"),
                false), 6);
        assertThat(r.relieved()).isEqualByComparingTo("100");
    }
}
