package com.inventoryerp.fixedasset;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.fixedasset.application.DepreciationCalculator;
import com.inventoryerp.fixedasset.application.DepreciationCalculator.Input;
import com.inventoryerp.fixedasset.application.DepreciationCalculator.Result;
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
        assertThat(r6(dd.depreciationAmount())).isEqualTo("66.666667");
    }

    @Test
    void shortLivesUseAtLeastOneYearAndUnknownMethodsFallBack() {
        Result r = DepreciationCalculator.calculate("declining_balance",
                new Input(d("1200"), d("0"), 6, d("1200"), d("0")));
        assertThat(r6(r.depreciationAmount())).isEqualTo("200");
        Result units = DepreciationCalculator.calculate("units_of_production",
                new Input(d("1200"), d("0"), 12, d("1200"), d("0")));
        assertThat(r6(units.depreciationAmount())).isEqualTo("100");
        Result manual = DepreciationCalculator.calculate("manual",
                new Input(d("1200"), d("0"), 12, d("700"), d("500")));
        assertThat(manual.depreciationAmount().signum()).isZero();
        assertThat(r6(manual.bookValue())).isEqualTo("700");
    }
}
