package com.inventoryerp.costing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.costing.application.SimulationEngine.Baseline;
import com.inventoryerp.costing.application.SimulationEngine.CostContext;
import com.inventoryerp.costing.application.SimulationEngine.Result;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cost Management CM-11 (ADR-S042): the pure what-if engine. */
class SimulationEngineTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static final List<Baseline> BASE = List.of(
            new Baseline("blocks", d("100"), d("1000"), d("600"), d("50"), d("100")),
            new Baseline("gravel", d("50"), d("500"), d("200"), d("20"), d("50")));

    @Test
    @DisplayName("neutral scenario reproduces the baseline; overhead pool re-spread exactly by revenue")
    void neutral() {
        List<Result> r = SimulationEngine.simulate(BASE, CostContext.of(Map.of()), BigDecimal.ZERO);
        assertThat(r.get(0).revenue()).isEqualByComparingTo("1000");
        assertThat(r.get(0).cm2()).isEqualByComparingTo("350");
        assertThat(r.get(0).overhead().add(r.get(1).overhead())).isEqualByComparingTo("150");
        assertThat(r.get(0).overhead()).isEqualByComparingTo("100"); // 1000 / 1500 of 150
        assertThat(r.get(0).unitCost()).isEqualByComparingTo("7.5"); // (600 + 50 + 100) / 100
        assertThat(r.get(0).breakEvenQuantity()).isEqualByComparingTo("28.571"); // 100 / 3.5
    }

    @Test
    @DisplayName("drivers: material, FX on the imported share, fuel, energy over capacity, price and volume")
    void drivers() {
        CostContext c = CostContext.of(Map.of("materialPricePct", 10, "fxPct", 20, "importedCostSharePct", 50,
                "fuelPricePct", 10, "energyTariffPct", 10, "capacityPct", 10, "sellingPricePct", 5, "volumePct", 20));
        List<Result> r = SimulationEngine.simulate(BASE, c, d("30"));
        Result blocks = r.get(0);
        assertThat(blocks.quantity()).isEqualByComparingTo("120");
        assertThat(blocks.revenue()).isEqualByComparingTo("1260"); // 1000 × 1.2 × 1.05
        assertThat(blocks.cogs()).isEqualByComparingTo("871.20"); // 600 × 1.2 × 1.1 × (0.5 + 0.5 × 1.2)
        assertThat(blocks.distribution()).isEqualByComparingTo("66"); // 50 × 1.2 × 1.1
        // fixed pool = 150 × 1.1 / 1.1 + 30 extra = 180, spread by simulated revenue 1260 : 630
        assertThat(blocks.overhead().add(r.get(1).overhead())).isEqualByComparingTo("180");
        assertThat(blocks.overhead()).isEqualByComparingTo("120");
        assertThat(SimulationEngine.simulate(BASE, c, d("30"))).as("deterministic").isEqualTo(r);
    }

    @Test
    @DisplayName("no contribution → no break-even; zero quantity → no unit cost")
    void edges() {
        List<Result> r = SimulationEngine.simulate(List.of(new Baseline("loss", d("0"), d("100"), d("150"), d("0"),
                d("10"))), CostContext.of(Map.of()), BigDecimal.ZERO);
        assertThat(r.get(0).unitCost()).isNull();
        assertThat(r.get(0).breakEvenQuantity()).isNull();
        assertThat(r.get(0).cm3()).isEqualByComparingTo("-60");
    }
}
