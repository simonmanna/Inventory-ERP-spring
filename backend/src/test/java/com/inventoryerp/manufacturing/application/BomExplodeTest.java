package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.manufacturing.application.BomService.ExplodableBom;
import com.inventoryerp.manufacturing.application.BomService.ExplodableLine;
import com.inventoryerp.manufacturing.application.BomService.ExplodedBom;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Port of {@code bom.service.spec.ts}: the pure scaling / scrap / yield arithmetic. */
class BomExplodeTest {

    static BigDecimal d(double v) {
        return BigDecimal.valueOf(v);
    }

    static ExplodableLine line(String id, double qty, String uom, Double scrap) {
        return new ExplodableLine(id, null, d(qty), uom, scrap == null ? null : d(scrap), null);
    }

    final ExplodableBom bom = new ExplodableBom(d(20), d(100),
            List.of(line("flour", 10, "kg", null), line("sugar", 6, null, 0.0)));

    static List<Double> planned(ExplodedBom r) {
        return r.materials().stream().map(m -> m.qtyPlanned().doubleValue()).toList();
    }

    @Test
    void scalesLinearlyForPartialBatch() {
        assertThat(planned(BomService.explode(bom, d(50)))).containsExactly(25.0, 15.0);
    }

    @Test
    void oneXAtExactlyOneBatch() {
        assertThat(planned(BomService.explode(bom, d(20)))).containsExactly(10.0, 6.0);
    }

    @Test
    void appliesScrapOnTop() {
        ExplodableBom b = new ExplodableBom(d(10), null, List.of(line("flour", 10, null, 5.0)));
        assertThat(BomService.explode(b, d(10)).materials().getFirst().qtyPlanned()).isEqualByComparingTo("10.5");
    }

    @Test
    void carriesUomVerbatim() {
        ExplodedBom r = BomService.explode(bom, d(20));
        assertThat(r.materials().get(0).uomId()).isEqualTo("kg");
        assertThat(r.materials().get(1).uomId()).isNull();
    }

    @Test
    void expectedOutputFromYield() {
        ExplodableBom b = new ExplodableBom(d(100), d(95), List.of(line("flour", 40, null, null)));
        assertThat(BomService.explode(b, d(100)).expectedOutputQty()).isEqualByComparingTo("95");
    }

    @Test
    void yieldDefaultsTo100() {
        ExplodableBom b = new ExplodableBom(d(20), null, bom.lines());
        assertThat(BomService.explode(b, d(40)).expectedOutputQty()).isEqualByComparingTo("40");
    }

    @Test
    void preservesSequence() {
        ExplodedBom r = BomService.explode(bom, d(20));
        assertThat(r.materials().stream().map(m -> m.componentProductId())).containsExactly("flour", "sugar");
        assertThat(r.materials().stream().map(m -> m.sequence())).containsExactly(0, 1);
    }

    @Test
    void rejectsNonPositiveOutput() {
        assertThatThrownBy(() -> BomService.explode(new ExplodableBom(BigDecimal.ZERO, null, List.of()), d(10)))
                .isInstanceOf(ApiException.class).hasMessage("BOM outputQuantity must be > 0");
    }

    @Test
    void fractionalScalingWithoutDrift() {
        ExplodableBom b = new ExplodableBom(d(3), null, List.of(line("egg", 3, null, null)));
        assertThat(BomService.explode(b, d(1)).materials().getFirst().qtyPlanned().doubleValue()).isEqualTo(1.0);
    }

    @Test
    void truthyFollowsJs() {
        assertThat(ProductionPlanService.truthy(null)).isFalse();
        assertThat(ProductionPlanService.truthy(false)).isFalse();
        assertThat(ProductionPlanService.truthy(0)).isFalse();
        assertThat(ProductionPlanService.truthy("")).isFalse();
        assertThat(ProductionPlanService.truthy(true)).isTrue();
        assertThat(ProductionPlanService.truthy("yes")).isTrue();
        assertThat(ProductionPlanService.truthy(1)).isTrue();
        assertThat(ProductionPlanService.truthy(List.of())).isTrue();
    }
}
