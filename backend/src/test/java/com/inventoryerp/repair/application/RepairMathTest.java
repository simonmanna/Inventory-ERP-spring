package com.inventoryerp.repair.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JS date / number semantics the repair services reproduce. */
class RepairMathTest {

    @Test
    @DisplayName("setMonth overflows like JS: Jan 31 + 1 month = Mar 3, Dec + 2 rolls the year")
    void addMonths() {
        assertThat(RepairWarrantyService.addMonths(Instant.parse("2026-01-31T10:00:00Z"), 1))
                .isEqualTo(Instant.parse("2026-03-03T10:00:00Z"));
        assertThat(RepairWarrantyService.addMonths(Instant.parse("2026-12-15T00:00:00Z"), 2))
                .isEqualTo(Instant.parse("2027-02-15T00:00:00Z"));
        assertThat(RepairWarrantyService.addMonths(Instant.parse("2028-01-30T00:00:00Z"), 1))
                .isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
    }

    @Test
    @DisplayName("setDate(+n) and the take/skip/date coercions")
    void coercions() {
        assertThat(RepairContractService.addDays(Instant.parse("2026-02-27T08:00:00Z"), 3))
                .isEqualTo(Instant.parse("2026-03-02T08:00:00Z"));
        assertThat(RepairDb.take(null, 50, 200)).isEqualTo(50);
        assertThat(RepairDb.take("500", 50, 200)).isEqualTo(200);
        assertThatThrownBy(() -> RepairDb.take("x", 50, 200)).isInstanceOf(IllegalStateException.class);
        assertThat(RepairDb.skip("5")).isEqualTo(5);
        assertThatThrownBy(() -> RepairDb.skip("-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RepairDb.skip("1.5")).isInstanceOf(IllegalStateException.class);
        assertThat(RepairDb.jsDate(0L)).isEqualTo(Instant.EPOCH);
        assertThat(RepairDb.dateOrNull("")).isNull();
        assertThatThrownBy(() -> RepairDb.jsDate("not a date")).isInstanceOf(IllegalStateException.class);
        assertThat(RepairDb.s(null)).isEqualTo("null");
        assertThat(RepairDb.isoNow()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
        assertThat(RepairDiagnosisService.orZero(Double.NaN)).isZero();
        assertThat(RepairLifecycles.REPAIR_ORDER_LIFECYCLE.states()).hasSize(10);
        assertThat(RepairLifecycles.REPAIR_ORDER_LIFECYCLE.terminalStates()).isEqualTo(List.of("closed", "cancelled"));
    }
}
