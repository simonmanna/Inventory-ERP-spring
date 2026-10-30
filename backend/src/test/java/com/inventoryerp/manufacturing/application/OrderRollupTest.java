package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.OrderRollup.Child;
import com.inventoryerp.manufacturing.application.OrderRollup.Rolled;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Status roll-up of multi-level order trees (ADR-S011 §2). */
class OrderRollupTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("open children: a not-started parent awaits components; a running one keeps its status")
    void awaiting() {
        Rolled r = OrderRollup.roll("confirmed", d("0"), List.of(new Child("in_progress", d("50")),
                new Child("completed", d("100"))));
        assertThat(r.status()).isEqualTo("awaiting_components");
        assertThat(r.childrenDone()).isEqualTo(1);
        assertThat(r.childrenOpen()).isEqualTo(1);
        assertThat(r.progressPct()).isEqualByComparingTo("50");
        assertThat(OrderRollup.roll("in_progress", d("50"), List.of(new Child("draft", d("0")))).status())
                .isEqualTo("in_progress");
    }

    @Test
    @DisplayName("all children done: draft / confirmed parent is ready; leaf keeps its own status")
    void ready() {
        assertThat(OrderRollup.roll("draft", d("0"), List.of(new Child("completed", d("100")),
                new Child("cancelled", d("0")))).status()).isEqualTo("ready");
        assertThat(OrderRollup.roll("draft", d("0"), List.of()).status()).isEqualTo("draft");
    }

    @Test
    @DisplayName("completed and cancelled parents roll up as themselves; completed = 100 %")
    void terminal() {
        Rolled done = OrderRollup.roll("completed", d("100"), List.of(new Child("in_progress", d("10"))));
        assertThat(done.status()).isEqualTo("completed");
        assertThat(done.progressPct()).isEqualByComparingTo("100");
        assertThat(OrderRollup.roll("cancelled", d("0"), List.of(new Child("draft", d("0")))).status())
                .isEqualTo("cancelled");
    }

    @Test
    @DisplayName("own progress: stage share, legacy fallbacks")
    void ownProgress() {
        assertThat(OrderRollup.ownProgress("in_progress", 3, 1)).isEqualByComparingTo("33.33");
        assertThat(OrderRollup.ownProgress("completed", 3, 1)).isEqualByComparingTo("100");
        assertThat(OrderRollup.ownProgress("draft", 3, 0)).isEqualByComparingTo("0");
        assertThat(OrderRollup.ownProgress("in_progress", 0, 0)).isEqualByComparingTo("50");
        assertThat(OrderRollup.ownProgress("qc_hold", 0, 0)).isEqualByComparingTo("90");
        assertThat(OrderRollup.ownProgress("confirmed", 2, 1)).isEqualByComparingTo("50");
    }
}
