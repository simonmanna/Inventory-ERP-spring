package com.inventoryerp.kernel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.kernel.common.BeverageMath.BottleInput;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Ports of {@code money-qty.spec.ts} and {@code beverage-math.spec.ts}. */
class CommonMathTest {

    @Nested
    @DisplayName("qtyMul")
    class QtyMul {
        @Test
        @DisplayName("multiplies without binary floating-point drift")
        void noDrift() {
            assertThat(0.1 * 3).isNotEqualTo(0.3);
            assertThat(Money.qtyMul("0.1", 3)).isEqualByComparingTo("0.3");
            assertThat(Money.qtyMul("0.018", 7)).isEqualByComparingTo("0.126");
            assertThat(Money.qtyMul("0.018", 100)).isEqualByComparingTo("1.8");
        }

        @Test
        @DisplayName("accumulates exactly across many recipe lines")
        void accumulates() {
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < 100; i++) {
                total = Money.qtyMul(total.add(Money.qtyMul("0.018", 1)), 1);
            }
            assertThat(total).isEqualByComparingTo("1.8");
        }

        @Test
        @DisplayName("rounds to the 6-dp ledger scale and handles multipliers")
        void rounds() {
            assertThat(Money.qtyMul(1, 1.0 / 3)).isEqualByComparingTo("0.333333");
            assertThat(Money.qtyMul("18", "0.001", "1.5", 4)).isEqualByComparingTo("0.108");
        }

        @Test
        @DisplayName("round / approxEqual")
        void roundApprox() {
            assertThat(Money.round(new BigDecimal("2.345"))).isEqualByComparingTo("2.35");
            assertThat(Money.approxEqual(new BigDecimal("1.00005"), BigDecimal.ONE)).isTrue();
            assertThat(Money.approxEqual(new BigDecimal("1.001"), BigDecimal.ONE)).isFalse();
        }
    }

    @Nested
    @DisplayName("beverage math")
    class Beverage {
        @Test
        @DisplayName("computes liquid weight and conversion factor")
        void config() {
            var c = BeverageMath.computeBottleConfig(new BottleInput(750, 450, null, 1150));
            assertThat(c.liquidWeightG()).isEqualTo(700);
            assertThat(c.conversionFactorMlPerG()).isEqualTo(1.0714285714);
            assertThat(c.effectiveEmptyWeightG()).isEqualTo(450);
        }

        @Test
        @DisplayName("prefers the measured tare over the nominal empty weight")
        void tare() {
            var c = BeverageMath.computeBottleConfig(new BottleInput(700, 500, 480, 1180));
            assertThat(c.effectiveEmptyWeightG()).isEqualTo(480);
            assertThat(c.liquidWeightG()).isEqualTo(700);
        }

        @Test
        @DisplayName("rejects physically invalid input with the Nest messages")
        void invalid() {
            assertThatThrownBy(() -> BeverageMath.computeBottleConfig(new BottleInput(null, 450, null, 1150)))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("Digital-weight products require container volume, empty bottle weight, and full bottle weight.");
            assertThatThrownBy(() -> BeverageMath.computeBottleConfig(new BottleInput(0, 450, null, 1150)))
                    .hasMessage("Container volume (ml) must be greater than 0.");
            assertThatThrownBy(() -> BeverageMath.computeBottleConfig(new BottleInput(750, 1150, null, 450)))
                    .hasMessage("Empty bottle weight must be less than full bottle weight.");
        }

        @Test
        @DisplayName("converts weight to remaining ml and back")
        void conversions() {
            double f = 750.0 / 700;
            assertThat(BeverageMath.remainingMlFromWeight(800, 450, f)).isEqualTo(375.0);
            assertThat(BeverageMath.remainingMlFromWeight(400, 450, f)).isEqualTo(0.0);
            assertThat(BeverageMath.weightFromRemainingMl(375, 450, f)).isEqualTo(800.0);
            assertThat(BeverageMath.weightFromRemainingMl(375, 450, 0)).isEqualTo(450.0);
        }

        @Test
        @DisplayName("flags impossible readings and classifies confidence")
        void readings() {
            assertThat(BeverageMath.checkReading(400, 450, 1150).outOfRange()).isTrue();
            assertThat(BeverageMath.checkReading(1151, 450, 1150).outOfRange()).isTrue();
            assertThat(BeverageMath.checkReading(800, 450, 1150).outOfRange()).isFalse();
            assertThat(BeverageMath.classifyConfidence(3, 5, false)).isEqualTo("GOOD");
            assertThat(BeverageMath.classifyConfidence(-8, 5, false)).isEqualTo("SUSPICIOUS");
            assertThat(BeverageMath.classifyConfidence(0, 5, true)).isEqualTo("OUT_OF_RANGE");
        }
    }

    @Test
    @DisplayName("occurredAt validation")
    void occurredAt() {
        assertThat(OccurredAt.resolve(null)).isNull();
        assertThatThrownBy(() -> OccurredAt.resolve("nope")).hasMessage("occurredAt is not a valid ISO-8601 timestamp");
        assertThatThrownBy(() -> OccurredAt.resolve("2999-01-01T00:00:00Z"))
                .hasMessage("occurredAt is in the future — check the device clock");
        assertThatThrownBy(() -> OccurredAt.resolve("2000-01-01T00:00:00Z"))
                .hasMessage("occurredAt is more than 7 days old — check the device clock");
    }
}
