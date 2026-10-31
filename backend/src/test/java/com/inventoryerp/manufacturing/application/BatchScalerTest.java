package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Batch scaling of a block mix design (ADR-S014). */
class BatchScalerTest {

    /** 100 blocks per batch: cement 50 kg (+2 % scrap), sand 200 kg, aggregate 300 kg; 0.5 m³; 100 / pallet. */
    static BatchScaler.Recipe recipe(BigDecimal yield) {
        return new BatchScaler.Recipe(BigDecimal.valueOf(100), yield, List.of(
                new BatchScaler.Material("cement", "Cement", null, BigDecimal.valueOf(50), BigDecimal.valueOf(2)),
                new BatchScaler.Material("sand", "Sand", null, BigDecimal.valueOf(200), null),
                new BatchScaler.Material("agg", "Aggregate", null, BigDecimal.valueOf(300), BigDecimal.ZERO)),
                "cement", BigDecimal.valueOf(50), new BigDecimal("0.5"), 100, 10);
    }

    @Test
    void thousandBlocks() {
        BatchScaler.Result r = BatchScaler.scale(recipe(null), "units", BigDecimal.valueOf(1000), true);
        assertThat(r.batches()).isEqualByComparingTo("10");
        assertThat(r.units()).isEqualByComparingTo("1000");
        assertThat(r.expectedGood()).isEqualByComparingTo("1000");
        assertThat(r.pallets()).isEqualTo(10);
        assertThat(r.cycles()).isEqualTo(100);
        assertThat(r.volumeM3()).isEqualByComparingTo("5");
        assertThat(r.materials().get(0).quantity()).isEqualByComparingTo("510"); // 50 × 1.02 × 10
        assertThat(r.materials().get(0).bags()).isEqualByComparingTo("10.2");
        assertThat(r.materials().get(0).cement()).isTrue();
        assertThat(r.materials().get(1).quantity()).isEqualByComparingTo("2000");
        assertThat(r.cementBags()).isEqualByComparingTo("10.2");
        assertThat(r.cementBagsToDraw()).isEqualTo(11);
    }

    @Test
    void wholeBatchesRoundUpExceptCementBags() {
        assertThat(BatchScaler.scale(recipe(null), "units", BigDecimal.valueOf(950), true).batches())
                .isEqualByComparingTo("10");
        assertThat(BatchScaler.scale(recipe(null), "units", BigDecimal.valueOf(950), false).batches())
                .isEqualByComparingTo("9.5");
        // 1.02 bags per batch: 10 bags make 9 whole batches, never more cement than available.
        BatchScaler.Result bags = BatchScaler.scale(recipe(null), "cement_bags", BigDecimal.TEN, true);
        assertThat(bags.batches()).isEqualByComparingTo("9");
        assertThat(bags.cementBags()).isLessThanOrEqualTo(BigDecimal.TEN);
        assertThat(BatchScaler.scale(recipe(null), "volume_m3", new BigDecimal("1.2"), true).batches())
                .isEqualByComparingTo("3");
        assertThat(BatchScaler.scale(recipe(null), "cycles", BigDecimal.valueOf(25), true).units())
                .isEqualByComparingTo("300"); // 250 blocks → 3 batches
        assertThat(BatchScaler.scale(recipe(null), "batches", BigDecimal.valueOf(2), true).units())
                .isEqualByComparingTo("200");
    }

    @Test
    void palletsGrossUpForYield() {
        BatchScaler.Result r = BatchScaler.scale(recipe(BigDecimal.valueOf(95)), "pallets", BigDecimal.valueOf(5), true);
        // 500 good blocks / 95 % = 526.3 → 6 batches = 600 units → 570 good → 6 pallets.
        assertThat(r.batches()).isEqualByComparingTo("6");
        assertThat(r.expectedGood()).isEqualByComparingTo("570");
        assertThat(r.pallets()).isEqualTo(6);
    }

    @Test
    void refusals() {
        assertThatThrownBy(() -> BatchScaler.scale(recipe(null), "tonnes", BigDecimal.ONE, true))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BatchScaler.scale(recipe(null), "units", BigDecimal.ZERO, true))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BatchScaler.scale(recipe(null), "cement_bags", new BigDecimal("0.5"), true))
                .hasMessage("Not enough for one full batch of this mix design.");
        BatchScaler.Recipe noCement = new BatchScaler.Recipe(BigDecimal.TEN, null, List.of(), null,
                BigDecimal.valueOf(50), null, 1, 1);
        assertThatThrownBy(() -> BatchScaler.scale(noCement, "cement_bags", BigDecimal.ONE, true))
                .hasMessage("The mix design has no cement line to scale by bags.");
        assertThatThrownBy(() -> BatchScaler.scale(noCement, "volume_m3", BigDecimal.ONE, true))
                .hasMessage("The mix design has no batch volume (m³) to scale by.");
    }
}
