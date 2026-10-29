package com.inventoryerp.repair.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.repair.domain.RepairJob;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JS date / number semantics the repair services reproduce, and the write guards of {@link RepairDb}. */
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
        assertThat(RepairLifecycles.REPAIR_ORDER_LIFECYCLE.states()).hasSize(10);
        assertThat(RepairLifecycles.REPAIR_ORDER_LIFECYCLE.terminalStates()).isEqualTo(List.of("closed", "cancelled"));
    }

    @Test
    @DisplayName("JS Number() on exact decimals: strings, booleans, blanks, NaN, Infinity")
    void jsNumbers() {
        assertThat(RepairDb.jsNum(null)).isEqualByComparingTo("0");
        assertThat(RepairDb.jsNum("")).isEqualByComparingTo("0");
        assertThat(RepairDb.jsNum("  2.5 ")).isEqualByComparingTo("2.5");
        assertThat(RepairDb.jsNum("1e3")).isEqualByComparingTo("1000");
        assertThat(RepairDb.jsNum(".5")).isEqualByComparingTo("0.5");
        assertThat(RepairDb.jsNum("0x10")).isEqualByComparingTo("16");
        assertThat(RepairDb.jsNum(true)).isEqualByComparingTo("1");
        assertThat(RepairDb.jsNum(false)).isEqualByComparingTo("0");
        assertThat(RepairDb.jsNum(7)).isEqualByComparingTo("7");
        assertThat(RepairDb.jsNum(0.1)).isEqualByComparingTo("0.1");
        assertThat(RepairDb.jsNum(new BigDecimal("12.345678"))).isEqualByComparingTo("12.345678");
        assertThat(RepairDb.jsNum("abc")).isNull();
        assertThat(RepairDb.jsNum("Infinity")).isNull();
        assertThat(RepairDb.jsNum(Double.NaN)).isNull();
        assertThat(RepairDb.jsNum(List.of())).isNull();
        assertThat(RepairDb.jsNumOrZero("abc")).isEqualByComparingTo("0");
        assertThat(RepairDb.positive(null)).isFalse();
        assertThat(RepairDb.positive(BigDecimal.ZERO)).isFalse();
        assertThat(RepairDb.positive(new BigDecimal("0.001"))).isTrue();
        assertThat(RepairDb.finite(BigDecimal.TEN, "x")).isEqualByComparingTo("10");
        assertThatThrownBy(() -> RepairDb.finite(null, "unitPrice")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unitPrice");
    }

    @Test
    @DisplayName("Math.round(n * 100) / 100 without floating-point error; halves round towards +infinity")
    void round2() {
        assertThat(RepairDb.round2(new BigDecimal("6.999"))).isEqualByComparingTo("7");
        assertThat(RepairDb.round2(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
        assertThat(RepairDb.round2(new BigDecimal("1.004"))).isEqualByComparingTo("1");
        assertThat(RepairDb.round2(new BigDecimal("-2.005"))).isEqualByComparingTo("-2");
        assertThat(RepairDb.round2(new BigDecimal("-2.006"))).isEqualByComparingTo("-2.01");
        assertThat(RepairDb.round2(new BigDecimal("3").multiply(new BigDecimal("2.333")))).isEqualByComparingTo("7");
        assertThat(RepairDb.round2(new BigDecimal("1.5").multiply(new BigDecimal("30")))).isEqualByComparingTo("45");
    }

    @Test
    @DisplayName("writes: unknown keys and a foreign organizationId are rejected before any SQL is built")
    void writeGuards() {
        TenantContext.run(TenantStore.organization("org-a"), () -> {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("notes", "x");
            ok.put("checklist", List.of(Map.of("label", "a")));
            ok.put("deadline", "2026-01-01T00:00:00Z");
            ok.put("organizationId", "org-a");
            Map<String, Object> out = RepairDb.coerce(RepairJob.class, ok);
            assertThat(out.get("deadline")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(out.get("checklist")).isInstanceOf(com.inventoryerp.pos.application.PosDb.Json.class);

            Map<String, Object> injected = Map.of("notes\" = 'x', \"organizationId", "org-b");
            assertThatThrownBy(() -> RepairDb.coerce(RepairJob.class, injected))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown argument");
            assertThatThrownBy(() -> RepairDb.coerce(RepairJob.class, Map.of("bogus", 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Invalid `prisma.repairJob` invocation: Unknown argument `bogus`.");
            assertThatThrownBy(() -> RepairDb.coerce(RepairJob.class, Map.of("organizationId", "org-b")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("row-level security");
        });
    }
}
