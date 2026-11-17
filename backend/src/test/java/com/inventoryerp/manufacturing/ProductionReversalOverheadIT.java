package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Review 2026-11-17 (ACR-5): reversing a completed order takes the overhead its completion absorbed back out of WIP
 * (Dr Overhead Absorbed / Cr WIP). Before, the finished goods put the full output value (materials + overhead) back into
 * WIP and only the materials left it, so WIP kept the overhead and the period's absorbed credit stayed as if the order
 * had produced.
 */
class ProductionReversalOverheadIT extends MfgTestSupport {

    @Test
    @DisplayName("reverse a completed order with BOM overhead: WIP flat, absorbed overhead restored, materials back")
    void reverseRestoresOverhead() {
        String flour = product("FLOUR-OH");
        String cake = product("CAKE-OH");
        stockIn(bakery, flour, 30, 5000);
        BigDecimal wipBefore = balance(wipAccount);
        BigDecimal absorbedBefore = balance(overheadAccount);
        BigDecimal flourBefore = onHand(flour, bakery);

        String id = started(bom(cake, flour, 20, 4, Map.of("overheadCost", 4000))).get("id").asString();
        JsonNode done = ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete",
                Map.of("qtyProduced", 20));
        // (20,000 + 4,000) / 20 = 1,200 a cake; 4,000 absorbed (credited), WIP flat
        assertThat(done.get("outputUnitCost").asString()).isEqualTo("1200");
        assertThat(balance(overheadAccount)).isEqualByComparingTo(absorbedBefore.subtract(BigDecimal.valueOf(4000)));
        assertThat(balance(wipAccount)).isEqualByComparingTo(wipBefore);

        JsonNode reversed = ok(201, "POST", "/manufacturing/production-orders/" + id + "/reverse",
                Map.of("reason", "wrong recipe"));
        assertThat(reversed.get("status").asString()).isEqualTo("cancelled");
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("0");
        assertThat(onHand(flour, bakery)).isEqualByComparingTo(flourBefore);
        assertThat(balance(wipAccount)).as("WIP keeps nothing of the reversed order").isEqualByComparingTo(wipBefore);
        assertThat(balance(overheadAccount)).as("the absorbed overhead is given back").isEqualByComparingTo(
                absorbedBefore);
        String code = done.get("orderCode").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'production_reversal' AND \"sourceId\" = ? AND description = ?").params(demoOrg, code,
                "Overhead absorption reversed · " + code).query(Long.class).single()).isEqualTo(1);
    }
}
