package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.OeeCalculator.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OEE = availability × performance × quality (ADR-S015). */
class OeeCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("textbook shift: 420 planned, 47 down, 60/h ideal, 19 271 of 19 662 good (scaled)")
    void textbook() {
        // 373 run minutes; ideal 1 min per unit → 340 units in 340 ideal minutes; 330 good.
        BigDecimal ideal = OeeCalculator.idealMinutes(d("340"), d("60"));
        assertThat(ideal).isEqualByComparingTo("340");
        Result r = OeeCalculator.compute(420, 47, ideal, d("330"), d("10"));
        assertThat(r.runMinutes()).isEqualTo(373);
        assertThat(r.availability()).isEqualByComparingTo("0.8881");
        assertThat(r.performance()).isEqualByComparingTo("0.9115");
        assertThat(r.quality()).isEqualByComparingTo("0.9706");
        // From the unrounded factors: 373/420 × 340/373 × 330/340 = 330/420.
        assertThat(r.oee()).isEqualByComparingTo("0.7857");
        assertThat(r.performanceCapped()).isFalse();
        assertThat(r.totalQty()).isEqualByComparingTo("340");
    }

    @Test
    @DisplayName("performance above 100 % is capped and flagged (ideal rate set too low)")
    void capped() {
        Result r = OeeCalculator.compute(60, 0, OeeCalculator.idealMinutes(d("120"), d("60")), d("120"), null);
        assertThat(r.performance()).isEqualByComparingTo("1");
        assertThat(r.performanceCapped()).isTrue();
        assertThat(r.oee()).isEqualByComparingTo("1");
        assertThat(r.quality()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("edge cases: no planned time, all down, no output, no ideal rate, downtime above planned")
    void edges() {
        Result none = OeeCalculator.compute(0, 0, null, d("0"), d("0"));
        assertThat(none.availability()).isNull();
        assertThat(none.oee()).isNull();

        Result down = OeeCalculator.compute(480, 600, null, d("0"), d("0"));
        assertThat(down.downtimeMinutes()).isEqualTo(480);
        assertThat(down.runMinutes()).isZero();
        assertThat(down.availability()).isEqualByComparingTo("0");
        assertThat(down.performance()).isNull();
        assertThat(down.oee()).isEqualByComparingTo("0");

        Result idle = OeeCalculator.compute(480, 0, OeeCalculator.idealMinutes(d("0"), d("60")), d("0"), d("0"));
        assertThat(idle.quality()).isNull();
        assertThat(idle.performance()).isEqualByComparingTo("0");
        assertThat(idle.oee()).isEqualByComparingTo("0");

        Result noRate = OeeCalculator.compute(480, 60, null, d("90"), d("10"));
        assertThat(noRate.availability()).isEqualByComparingTo("0.875");
        assertThat(noRate.quality()).isEqualByComparingTo("0.9");
        assertThat(noRate.performance()).isNull();
        assertThat(noRate.oee()).isNull();

        assertThat(OeeCalculator.idealMinutes(d("10"), null)).isNull();
        assertThat(OeeCalculator.idealMinutes(d("10"), d("0"))).isNull();
        assertThat(OeeCalculator.compute(-5, -5, null, null, null).plannedMinutes()).isZero();
    }

    @Test
    @DisplayName("tonnes on a crusher: 150 t/h ideal, 6 h run of 7 h planned, 780 t of which 20 t fines rejected")
    void crusher() {
        Result r = OeeCalculator.compute(420, 60, OeeCalculator.idealMinutes(d("780"), d("150")), d("760"), d("20"));
        assertThat(r.idealMinutes()).isEqualByComparingTo("312");
        assertThat(r.availability()).isEqualByComparingTo("0.8571");
        assertThat(r.performance()).isEqualByComparingTo("0.8667");
        assertThat(r.quality()).isEqualByComparingTo("0.9744");
        assertThat(r.oee()).isEqualByComparingTo("0.7238");
    }
}
