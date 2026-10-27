package com.inventoryerp.beverage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.beverage.application.BeverageCountService.BevProduct;
import com.inventoryerp.beverage.application.BeverageCountService.ComputedLine;
import com.inventoryerp.beverage.application.BeverageCountService.Reading;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Per-line bottle-count math ({@code BeverageCountService.computeLine}). */
class BeverageCountMathTest {

    /** 700 ml bottle, 500 g empty, 1200 g full → 1 ml/g; 30 ml pour; 5 g tolerance. */
    static final BevProduct WHISKY = new BevProduct("p", "Whisky", "ml", 700, 500, 1200, 1.0, 30, 5);

    @Test
    @DisplayName("sealed bottles + open readings → counted ml, variance, shots, GOOD within tolerance")
    void withinTolerance() {
        ComputedLine c = BeverageCountService.computeLine(WHISKY, 1, List.of(new Reading(1100, "B1", null)), 1297);
        assertThat(c.counted()).isTrue();
        assertThat(c.countedMl()).isEqualTo(1300);
        assertThat(c.varianceMl()).isEqualTo(3);
        assertThat(c.varianceG()).isEqualTo(3);
        assertThat(c.confidence()).isEqualTo("GOOD");
        assertThat(c.expectedShots()).isEqualTo(1300.0 / 30, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(c.readings().getFirst().measurementSource()).isEqualTo("MANUAL");
        assertThat(c.readings().getFirst().remainingMl()).isEqualTo(600);
    }

    @Test
    @DisplayName("over tolerance → SUSPICIOUS; below tare → OUT_OF_RANGE with 0 ml remaining")
    void flagged() {
        ComputedLine short100 = BeverageCountService.computeLine(WHISKY, 0, List.of(new Reading(1100, null, "SCALE")),
                700);
        assertThat(short100.varianceMl()).isEqualTo(-100);
        assertThat(short100.confidence()).isEqualTo("SUSPICIOUS");
        ComputedLine tare = BeverageCountService.computeLine(WHISKY, 0, List.of(new Reading(400, null, null)), 0);
        assertThat(tare.confidence()).isEqualTo("OUT_OF_RANGE");
        assertThat(tare.readings().getFirst().remainingMl()).isZero();
        assertThat(tare.readings().getFirst().confidence()).isEqualTo("OUT_OF_RANGE");
    }

    @Test
    @DisplayName("nothing weighed → not counted; unknown product config → no shots, 5 g default tolerance")
    void notCounted() {
        ComputedLine none = BeverageCountService.computeLine(WHISKY, 0, List.of(), 50);
        assertThat(none.counted()).isFalse();
        assertThat(none.varianceMl()).isEqualTo(-50);
        ComputedLine unknown = BeverageCountService.computeLine(null, 0, List.of(new Reading(10, null, null)), 0);
        assertThat(unknown.expectedShots()).isNull();
        assertThat(unknown.varianceG()).isZero();
        assertThat(unknown.confidence()).isEqualTo("GOOD");
    }
}
