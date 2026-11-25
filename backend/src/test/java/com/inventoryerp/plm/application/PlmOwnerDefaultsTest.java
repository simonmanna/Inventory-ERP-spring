package com.inventoryerp.plm.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.core.application.ProductLifecycleGuard;
import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Owner decisions of 2026-11-24 (ADR-S086 §12) that are pure logic: the shipped matrix and the wording. */
class PlmOwnerDefaultsTest {

    static String row(String state) {
        Map<String, String> r = PlmSettingsService.DEFAULTS.get(state);
        return String.join(",", PlmSettingsService.ACTIVITIES.stream().map(r::get).toList());
    }

    @Test
    @DisplayName("shipped matrix: not released → no sale; phase-out → sells stock, not procured; obsolete → nothing")
    void matrix() {
        assertThat(PlmSettingsService.ACTIVITIES).containsExactly(ProductLifecycleGuard.PURCHASE,
                ProductLifecycleGuard.PRODUCE, ProductLifecycleGuard.SELL, ProductLifecycleGuard.POS,
                ProductLifecycleGuard.REPLENISH);
        assertThat(PlmSettingsService.DEFAULTS.keySet()).containsExactly("concept", "design", "prototype",
                "released", "phase_out", "obsolete");
        //                                   purchase produce sell  pos   replenish
        assertThat(row("concept")).isEqualTo("allow,block,block,block,allow");
        assertThat(row("design")).isEqualTo("allow,block,block,block,allow");
        assertThat(row("prototype")).isEqualTo("allow,allow,block,block,allow");
        assertThat(row("released")).isEqualTo("allow,allow,allow,allow,allow");
        assertThat(row("phase_out")).isEqualTo("block,block,allow,allow,block");
        assertThat(row("obsolete")).isEqualTo("block,block,block,block,block");
        assertThat(PlmSettingsService.DEFAULT_COST_POLICY).isEqualTo("propose");
    }

    @Test
    @DisplayName("refusals name the state, the activity and the replacement")
    void messages() {
        Map<String, Object> design = Obj.of("code", "P-1", "lifecycleState", "design", "replacementCode", null);
        assertThat(PlmLifecyclePolicy.message(design, ProductLifecycleGuard.POS))
                .isEqualTo("Product P-1 is in design and cannot be sold at the point of sale.");
        assertThat(PlmLifecyclePolicy.message(design, ProductLifecycleGuard.PRODUCE))
                .isEqualTo("Product P-1 is design in its lifecycle and cannot be produced.");
        Map<String, Object> fading = Obj.of("code", "P-2", "lifecycleState", "phase_out", "replacementCode", "P-3");
        assertThat(PlmLifecyclePolicy.message(fading, ProductLifecycleGuard.PURCHASE))
                .isEqualTo("Product P-2 is being phased out and cannot be purchased. Replacement: P-3.");
        assertThat(PlmLifecyclePolicy.message(fading, ProductLifecycleGuard.PRODUCE))
                .isEqualTo("Product P-2 is phase out in its lifecycle and cannot be produced.");
        assertThat(List.of("purchase", "produce", "sell", "pos", "replenish").stream().map(PlmExceptionService::label))
                .containsExactly("purchasing", "producing", "selling", "selling at the point of sale", "replenishing");
    }

    @Test
    @DisplayName("the ECO activity log carries no valuation figures")
    void activityLineHasNoFigures() {
        Map<String, Object> active = Obj.of("standardCostId", "s", "version", 3, "status", "active", "unitCost",
                new BigDecimal("12.5"), "revaluationAmount", new BigDecimal("40"), "note", null);
        assertThat(PlmCostService.summary(active)).isEqualTo("Standard cost v3 activated; the stock on hand was"
                + " revalued.");
        Map<String, Object> flat = Obj.of("standardCostId", "s", "version", 4, "status", "active", "unitCost",
                new BigDecimal("12.5"), "revaluationAmount", BigDecimal.ZERO, "note", null);
        assertThat(PlmCostService.summary(flat)).isEqualTo("Standard cost v4 activated.");
        Map<String, Object> proposed = Obj.of("standardCostId", "s", "version", 5, "status", "draft", "unitCost",
                new BigDecimal("12.5"), "note", null);
        assertThat(PlmCostService.summary(proposed)).isEqualTo("Standard cost v5 proposed.").doesNotContain("12");
    }
}
