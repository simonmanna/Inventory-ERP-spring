package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.DemandNetting.Allocation;
import com.inventoryerp.manufacturing.application.DemandNetting.Demand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Demand ranking and netting (ADR-S017). */
class DemandNettingTest {

    private static Demand demand(String type, String ref, int priority, String due, int qty) {
        return new Demand(type, null, null, ref, "p", null, null, Instant.parse(due), priority, BigDecimal.valueOf(qty));
    }

    @Test
    @DisplayName("firm demand before forecast before stock policy; then priority, then due date")
    void ranking() {
        List<Allocation> out = DemandNetting.allocate(BigDecimal.ZERO, List.of(
                demand("stock_policy", "s", 1, "2026-11-01T00:00:00Z", 1),
                demand("forecast", "f", 4, "2026-11-01T00:00:00Z", 1),
                demand("sales_order", "late", 2, "2026-11-20T00:00:00Z", 1),
                demand("sales_order", "soon", 2, "2026-11-10T00:00:00Z", 1),
                demand("delivery_order", "urgent", 4, "2026-11-30T00:00:00Z", 1)));
        assertThat(out.stream().map(a -> a.demand().ref())).containsExactly("urgent", "soon", "late", "f", "s");
    }

    @Test
    @DisplayName("supply covers the most important demand first and the shortfall lands on the rest")
    void allocation() {
        List<Allocation> out = DemandNetting.allocate(BigDecimal.valueOf(10), List.of(
                demand("delivery_order", "normal", 2, "2026-11-01T00:00:00Z", 20),
                demand("delivery_order", "urgent", 4, "2026-11-05T00:00:00Z", 30)));
        assertThat(out.get(0).demand().ref()).isEqualTo("urgent");
        assertThat(out.get(0).covered()).isEqualByComparingTo("10");
        assertThat(out.get(0).uncovered()).isEqualByComparingTo("20");
        assertThat(out.get(1).covered()).isEqualByComparingTo("0");
        assertThat(out.get(1).uncovered()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("negative supply counts as none; priority words map to ranks")
    void edges() {
        assertThat(DemandNetting.allocate(BigDecimal.valueOf(-5), List.of(demand("forecast", "f", 2,
                "2026-11-01T00:00:00Z", 3))).getFirst().uncovered()).isEqualByComparingTo("3");
        assertThat(DemandNetting.priority("urgent")).isEqualTo(DemandNetting.URGENT);
        assertThat(DemandNetting.priority("low")).isEqualTo(DemandNetting.LOW);
        assertThat(DemandNetting.priority(null)).isEqualTo(DemandNetting.NORMAL);
    }
}
