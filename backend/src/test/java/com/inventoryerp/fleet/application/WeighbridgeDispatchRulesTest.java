package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fleet 1.4b: the "bulk product" test of the weighbridge requirement and the live-ticket wording. */
class WeighbridgeDispatchRulesTest {

    static Map<String, Object> line(String unitCategory, String uomCategory, boolean setting) {
        Map<String, Object> l = new HashMap<>();
        l.put("unitCategory", unitCategory);
        l.put("uomCategory", uomCategory);
        l.put("weighSetting", setting);
        return l;
    }

    @Test
    @DisplayName("bulk = weight / volume / bulk unit, a Weight / Volume UoM category, or weighbridge product settings")
    void bulk() {
        assertThat(FleetWeighbridgeRuleService.isBulk(line("weight", "Weight", false))).isTrue();
        assertThat(FleetWeighbridgeRuleService.isBulk(line("Volume", null, false))).isTrue();
        assertThat(FleetWeighbridgeRuleService.isBulk(line("bulk", null, false))).isTrue();
        assertThat(FleetWeighbridgeRuleService.isBulk(line("unit", "Volume", false))).isTrue();
        assertThat(FleetWeighbridgeRuleService.isBulk(line("quantity", "Quantity", true))).isTrue();
        assertThat(FleetWeighbridgeRuleService.isBulk(line("quantity", "Quantity", false))).isFalse();
        assertThat(FleetWeighbridgeRuleService.isBulk(line(null, null, false))).isFalse();
        assertThat(FleetWeighbridgeRuleService.isBulk(line("length", "Length", false))).isFalse();
    }

    @Test
    @DisplayName("live ticket wording per status")
    void liveLabels() {
        assertThat(FleetWeighbridgeRuleService.liveLabel("open")).isEqualTo("waiting for the first weighing");
        assertThat(FleetWeighbridgeRuleService.liveLabel("first_weighed")).isEqualTo("waiting for the second weighing");
        assertThat(FleetWeighbridgeRuleService.liveLabel("exception")).isEqualTo("out of tolerance, waiting for approval");
        assertThat(FleetWeighbridgeRuleService.liveLabel("odd")).isEqualTo("odd");
    }

    @Test
    @DisplayName("a weighed trip can be re-weighed until dispatch; only open trips take a weight")
    void tripStates() {
        assertThat(TripStatusPolicy.canMove("weighed", "weighed")).isTrue();
        assertThat(TripStatusPolicy.canMove("loading", "weighed")).isTrue();
        assertThat(TripStatusPolicy.isOpen("dispatched")).isFalse();
        assertThat(TripStatusPolicy.isOpen("loading")).isTrue();
    }
}
