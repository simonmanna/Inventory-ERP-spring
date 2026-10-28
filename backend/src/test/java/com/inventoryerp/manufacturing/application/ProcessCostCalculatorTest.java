package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.manufacturing.application.ProcessCostCalculator.Input;
import com.inventoryerp.manufacturing.application.ProcessCostCalculator.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Equivalent-unit process costing (ADR-S008 §2) on textbook cases. */
class ProcessCostCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** Opening 2,000 (100 % material, 40 % conversion; 6,000 / 2,600); started 18,000; done 16,000; closing 4,000. */
    static Input base(String method, String conversionAdded) {
        return new Input(method, d("2000"), d("100"), d("40"), BigDecimal.ZERO, d("6000"), d("2600"), d("18000"),
                d("16000"), d("4000"), d("100"), d("25"), BigDecimal.ZERO, BigDecimal.ZERO, null, null, BigDecimal.ZERO,
                d("54000"), d(conversionAdded));
    }

    @Test
    @DisplayName("weighted average: EU 20,000 / 17,000; 3 + 3 per EU; completed 96,000; closing WIP 15,000")
    void weightedAverage() {
        Result r = ProcessCostCalculator.compute(base("weighted_average", "48400"));
        assertThat(r.equivalentUnits().get("material")).isEqualByComparingTo("20000");
        assertThat(r.equivalentUnits().get("conversion")).isEqualByComparingTo("17000");
        assertThat(r.costPerUnit().get("material")).isEqualByComparingTo("3");
        assertThat(r.costPerUnit().get("conversion")).isEqualByComparingTo("3");
        assertThat(r.completedCost()).isEqualByComparingTo("96000");
        assertThat(r.closingWipCost()).isEqualByComparingTo("15000");
        assertThat(r.unitCost()).isEqualByComparingTo("6");
        assertThat(r.closingWipByElement().get("material")).isEqualByComparingTo("12000");
        assertThat(r.closingWipByElement().get("conversion")).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("FIFO: EU 18,000 / 16,200; only period costs spread; opening WIP cost goes out first; totals exact")
    void fifo() {
        Result r = ProcessCostCalculator.compute(base("fifo", "48400"));
        assertThat(r.equivalentUnits().get("material")).isEqualByComparingTo("18000");
        assertThat(r.equivalentUnits().get("conversion")).isEqualByComparingTo("16200");
        assertThat(r.costPerUnit().get("material")).isEqualByComparingTo("3");
        assertThat(r.closingWipCost()).isEqualByComparingTo("14987.654321");
        assertThat(r.completedCost()).isEqualByComparingTo("96012.345679");
        assertThat(r.completedCost().add(r.closingWipCost())).isEqualByComparingTo("111000");
    }

    @Test
    @DisplayName("losses: normal loss absorbed (no EU), abnormal loss costed at 14.75 and written off")
    void losses() {
        Result r = ProcessCostCalculator.compute(new Input("weighted_average", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, d("1000"), d("800"), d("100"),
                d("100"), d("50"), d("50"), d("50"), d("100"), d("100"), BigDecimal.ZERO, d("9500"), d("4275")));
        assertThat(r.equivalentUnits().get("material")).isEqualByComparingTo("950");
        assertThat(r.equivalentUnits().get("conversion")).isEqualByComparingTo("900");
        assertThat(r.costPerUnit().get("total")).isEqualByComparingTo("14.75");
        assertThat(r.completedCost()).isEqualByComparingTo("11800");
        assertThat(r.abnormalLossCost()).isEqualByComparingTo("737.5");
        assertThat(r.closingWipCost()).isEqualByComparingTo("1237.5");
    }

    @Test
    @DisplayName("transferred-in cost is 100 % complete; no closing WIP sends rounding to the output")
    void transferredIn() {
        Result r = ProcessCostCalculator.compute(new Input("weighted_average", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, d("3"), d("3"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, d("100"), d("0"),
                d("0.01")));
        assertThat(r.completedCost()).isEqualByComparingTo("100.01");
        assertThat(r.closingWipCost()).isEqualByComparingTo("0");
        assertThat(r.unitCost()).isEqualByComparingTo("33.336667");
    }

    @Test
    @DisplayName("guards: units must balance; FIFO needs opening WIP completed; percentages 0–100")
    void guards() {
        assertThatThrownBy(() -> ProcessCostCalculator.compute(new Input("weighted_average", BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, d("10"), d("5"),
                d("1"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO))).hasMessageStartingWith("Units do not balance");
        assertThatThrownBy(() -> ProcessCostCalculator.compute(new Input("fifo", d("10"), d("50"), d("50"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, d("5"), d("5"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO))).hasMessageStartingWith("FIFO process costing needs");
        assertThatThrownBy(() -> ProcessCostCalculator.compute(new Input("weighted_average", BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, d("1"), d("0"),
                d("1"), d("120"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO))).hasMessage("Completion percentages must be between 0 and 100");
    }
}
