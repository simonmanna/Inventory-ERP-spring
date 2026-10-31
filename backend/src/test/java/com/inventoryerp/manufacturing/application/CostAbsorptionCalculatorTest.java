package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.CostAbsorptionCalculator.Amount;
import com.inventoryerp.manufacturing.application.CostAbsorptionCalculator.Rates;
import com.inventoryerp.manufacturing.application.CostAbsorptionCalculator.Usage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Actual conversion cost of a work order (ADR-S018). */
class CostAbsorptionCalculatorTest {

    static final Rates RATES = new Rates(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("5"),
            new BigDecimal("0.25"), "machine_hours");

    private static Usage usage(boolean labourCosted, boolean machineCosted) {
        return new Usage(new BigDecimal("3"), new BigDecimal("2"), new BigDecimal("100"), labourCosted, machineCosted);
    }

    private static BigDecimal amount(List<Amount> list, String kind) {
        return list.stream().filter(a -> a.kind().equals(kind)).map(Amount::amount).findFirst().orElse(null);
    }

    @Test
    @DisplayName("each kind is its basis times its rate, overhead on machine hours by default")
    void amounts() {
        List<Amount> out = CostAbsorptionCalculator.amounts(usage(false, false), RATES);
        assertThat(amount(out, "labour")).isEqualByComparingTo("30");
        assertThat(amount(out, "machine")).isEqualByComparingTo("40");
        assertThat(amount(out, "utility")).isEqualByComparingTo("25");
        assertThat(amount(out, "overhead")).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("overhead can follow labour hours")
    void labourBasis() {
        Rates r = new Rates(RATES.labour(), RATES.machine(), RATES.overhead(), RATES.energy(), "labour_hours");
        assertThat(amount(CostAbsorptionCalculator.amounts(usage(false, false), r), "overhead"))
                .isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("a kind already costed through the resource is left out; zero amounts are dropped")
    void skips() {
        List<Amount> out = CostAbsorptionCalculator.amounts(usage(true, true), RATES);
        assertThat(amount(out, "labour")).isNull();
        assertThat(amount(out, "machine")).isNull();
        assertThat(amount(out, "overhead")).isNotNull();
        assertThat(CostAbsorptionCalculator.amounts(new Usage(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false,
                false), RATES)).isEmpty();
    }

    @Test
    @DisplayName("amounts round HALF_UP to 2 decimals")
    void rounding() {
        Rates r = new Rates(new BigDecimal("0.335"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "machine_hours");
        Usage u = new Usage(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, false, false);
        assertThat(CostAbsorptionCalculator.amounts(u, r).getFirst().amount()).isEqualByComparingTo("0.34");
    }
}
