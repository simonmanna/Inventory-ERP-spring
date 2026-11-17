package com.inventoryerp.fixedasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.fixedasset.application.DepreciationCalculator;
import com.inventoryerp.fixedasset.application.DepreciationCalculator.Input;
import com.inventoryerp.fixedasset.application.DepreciationCalculator.Result;
import com.inventoryerp.fixedasset.application.DepreciationCalculator.Usage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** {@code asset-depreciation.strategy.ts} semantics (no Nest spec exists; cases derived from the source). */
class DepreciationCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static String r6(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    @Test
    void straightLineSpreadsCostLessSalvageOverMonths() {
        Result r = DepreciationCalculator.calculate("straight_line",
                new Input(d("1000"), d("100"), 60, d("1000"), d("0")));
        assertThat(r6(r.depreciationAmount())).isEqualTo("15");
        assertThat(r6(r.accumulatedDepreciation())).isEqualTo("15");
        assertThat(r6(r.bookValue())).isEqualTo("985");
    }

    @Test
    void straightLineStopsAtSalvage() {
        Result r = DepreciationCalculator.calculate("straight_line",
                new Input(d("1000"), d("100"), 60, d("110"), d("890")));
        assertThat(r6(r.depreciationAmount())).isEqualTo("10");
        assertThat(r6(r.bookValue())).isEqualTo("100");
        Result done = DepreciationCalculator.calculate("straight_line",
                new Input(d("1000"), d("100"), 60, d("100"), d("900")));
        assertThat(done.depreciationAmount().signum()).isZero();
    }

    @Test
    void decliningBalanceUsesTwoOverYearsOfBookValue() {
        // 5 years -> 40 % a year -> 1000 * 0.4 / 12
        Result r = DepreciationCalculator.calculate("declining_balance",
                new Input(d("1000"), d("0"), 60, d("1000"), d("0")));
        assertThat(r6(r.depreciationAmount())).isEqualTo("33.333333");
        Result dd = DepreciationCalculator.calculate("double_declining",
                new Input(d("1000"), d("0"), 60, d("1000"), d("0")));
        // ACC-5 (B11): double-declining = 2 × straight-line rate (Nest used 4 ×, i.e. 66.666667 here).
        assertThat(r6(dd.depreciationAmount())).isEqualTo("33.333333");
    }

    @Test
    void shortLivesUseAtLeastOneYearAndManualNeverDepreciates() {
        Result r = DepreciationCalculator.calculate("declining_balance",
                new Input(d("1200"), d("0"), 6, d("1200"), d("0")));
        assertThat(r6(r.depreciationAmount())).isEqualTo("200");
        Result unknown = DepreciationCalculator.calculate("something_else",
                new Input(d("1200"), d("0"), 12, d("1200"), d("0")));
        assertThat(r6(unknown.depreciationAmount())).as("unknown methods are straight line").isEqualTo("100");
        Result manual = DepreciationCalculator.calculate("manual",
                new Input(d("1200"), d("0"), 12, d("700"), d("500")));
        assertThat(manual.depreciationAmount().signum()).isZero();
        assertThat(r6(manual.bookValue())).isEqualTo("700");
    }

    // ---- R14: configurable declining factor, units of production without a straight-line fallback ----

    @Test
    void decliningBalanceUsesTheConfiguredFactorAndDoubleDecliningStaysTwo() {
        Input in = new Input(d("1200"), d("0"), 60, d("1200"), d("0"));
        // 1.5 / 5 years = 30 % a year -> 1200 * 0.3 / 12 = 30
        assertThat(r6(DepreciationCalculator.calculate("declining_balance", in, d("1.5"), null)
                .depreciationAmount())).isEqualTo("30");
        // Default (and null / non-positive) factor: 2 -> 40, the pre-R14 schedule.
        assertThat(r6(DepreciationCalculator.calculate("declining_balance", in).depreciationAmount())).isEqualTo("40");
        assertThat(r6(DepreciationCalculator.calculate("declining_balance", in, null, null).depreciationAmount()))
                .isEqualTo("40");
        assertThat(r6(DepreciationCalculator.calculate("declining_balance", in, d("0"), null).depreciationAmount()))
                .isEqualTo("40");
        // Double declining ignores the factor.
        assertThat(r6(DepreciationCalculator.calculate("double_declining", in, d("1.5"), null).depreciationAmount()))
                .isEqualTo("40");
    }

    @Test
    void decliningFactorAppliesToImpairedAssetsToo() {
        Result r = DepreciationCalculator.calculateImpaired("declining_balance", d("0"), 60, d("600"), d("200"),
                d("600"), 50, d("1.5"), null);
        // 600 * (1.5 / 5) / 12 = 15
        assertThat(r6(r.depreciationAmount())).isEqualTo("15");
        Result dd = DepreciationCalculator.calculateImpaired("double_declining", d("0"), 60, d("600"), d("200"),
                d("600"), 50, d("1.5"), null);
        assertThat(r6(dd.depreciationAmount())).isEqualTo("20");
    }

    @Test
    void unitsOfProductionChargesCostLessResidualPerUnit() {
        Input in = new Input(d("1100"), d("100"), 12, d("1100"), d("0"));
        Result r = DepreciationCalculator.calculate("units_of_production", in, null,
                new Usage(d("1500"), d("10000"), d("0")));
        // (1100 - 100) * 1500 / 10000 = 150
        assertThat(r6(r.depreciationAmount())).isEqualTo("150");
        assertThat(r6(r.accumulatedDepreciation())).isEqualTo("150");
        assertThat(r6(r.bookValue())).isEqualTo("950");
        Result idle = DepreciationCalculator.calculate("units_of_production", in, null,
                new Usage(d("0"), d("10000"), d("0")));
        assertThat(idle.depreciationAmount().signum()).as("no units, no charge").isZero();
    }

    @Test
    void unitsOfProductionStopsAtTheResidualValue() {
        Input nearlyDone = new Input(d("1100"), d("100"), 12, d("150"), d("950"));
        Result r = DepreciationCalculator.calculate("units_of_production", nearlyDone, null,
                new Usage(d("5000"), d("10000"), d("9500")));
        assertThat(r6(r.depreciationAmount())).isEqualTo("50");
        assertThat(r6(r.bookValue())).isEqualTo("100");
        Result done = DepreciationCalculator.calculate("units_of_production",
                new Input(d("1100"), d("100"), 12, d("100"), d("1000")), null, new Usage(d("10"), d("10000"), null));
        assertThat(done.depreciationAmount().signum()).isZero();
    }

    @Test
    void unitsOfProductionNeverFallsBackToStraightLine() {
        Input in = new Input(d("1200"), d("0"), 12, d("1200"), d("0"));
        assertThatThrownBy(() -> DepreciationCalculator.calculate("units_of_production", in))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DepreciationCalculator.calculate("units_of_production", in, null,
                new Usage(null, d("100"), null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DepreciationCalculator.calculate("units_of_production", in, null,
                new Usage(d("10"), d("0"), null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DepreciationCalculator.calculateImpaired("units_of_production", d("0"), 12,
                d("600"), d("0"), d("600"), 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void impairedUnitsOfProductionSpreadsTheCarryingAmountOverTheRemainingUnits() {
        // Carrying 600 after impairment, residual 100, 4000 of 10000 units used before: 500 * 1000 / 6000.
        Result r = DepreciationCalculator.calculateImpaired("units_of_production", d("100"), 12, d("600"), d("400"),
                d("600"), 10, null, new Usage(d("1000"), d("10000"), d("4000")));
        assertThat(r6(r.depreciationAmount())).isEqualTo("83.333333");
        // Usage beyond the estimate takes the rest of the headroom, never more.
        Result over = DepreciationCalculator.calculateImpaired("units_of_production", d("100"), 12, d("600"),
                d("400"), d("600"), 10, null, new Usage(d("7000"), d("10000"), d("4000")));
        assertThat(r6(over.depreciationAmount())).isEqualTo("500");
        assertThat(r6(over.bookValue())).isEqualTo("100");
    }
}
