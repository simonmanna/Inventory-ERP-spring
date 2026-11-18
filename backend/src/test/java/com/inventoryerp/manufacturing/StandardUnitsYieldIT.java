package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Review 2026-11-17: the BOM standard is per GOOD unit — a run is expected to yield {@code outputQuantity ×
 * expectedYieldPct} (ACR-18) — and the variance split compares actual and standard quantities in the same (base) unit
 * even when the BOM line is written in another unit (ACR-10).
 */
class StandardUnitsYieldIT extends MfgTestSupport {

    String unit(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, ?, 'unit', 1, 1, 0.001, now())")
                .params(id, demoOrg, code + "-" + suffix(), code).update();
        return id;
    }

    void flourIn(String flour, int grams, String unitCost) {
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", bakery, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", flour, "quantity", grams, "unitCost",
                        unitCost))));
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("2 kg of flour (counted in grams) for 10 cakes at 80% yield: standard 125 a good cake; producing the"
            + " expected 8 shows a price variance of 200 and no usage variance")
    void standardPerGoodUnitInBaseUnits() {
        String gram = unit("G");
        String kg = unit("KG");
        String flour = product("FLOUR-SY");
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ?, \"trackInventory\" = true, \"manufacturingRole\" = 'raw' WHERE"
                + " id = ?").params(gram, flour).update();
        ok(200, "PATCH", "/products/" + flour, Map.of("units", List.of(Map.of("uomId", kg, "factor", 1000,
                "isPurchase", true, "isSales", true))));
        String cake = product("CAKE-SY");
        flourIn(flour, 2000, "0.5");

        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Cake " + suffix(), "outputProductId",
                cake, "outputQuantity", 10, "expectedYieldPct", 80, "lines", List.of(Map.of("componentProductId", flour,
                        "quantity", 2, "uomId", kg))));
        String bomId = draft.get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);

        // ACR-18: 2,000 g × 0.5 = 1,000 a run; a run is expected to yield 8 good cakes → 125 each (not 100)
        JsonNode cost = ok(200, "GET", "/manufacturing/boms/" + bomId + "/cost", null);
        assertThat(num(cost.get("totalCost"))).isEqualByComparingTo("1000");
        assertThat(num(cost.get("expectedGoodQuantity"))).isEqualByComparingTo("8");
        assertThat(num(cost.get("unitCost"))).isEqualByComparingTo("125");
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/standard-cost", null);

        // the flour costs more by the time it is used: 2,000 g more at 0.7 → the order issues 2,000 g at 0.6
        flourIn(flour, 2000, "0.7");
        String orderId = started(bomId).get("id").asString();
        assertThat(onHand(flour, bakery)).isEqualByComparingTo("2000");
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/complete", Map.of("qtyProduced", 8));

        // ACR-10: price (0.6 − 0.5) × 2,000 g = 200 — not (600 − 0.5) × 2 kg; usage 0 — the 2 kg is the 2,000 g the
        // standard allows for 8 good cakes (ACR-18: not 1,600 g, which would call the normal loss a usage variance)
        JsonNode variances = ok(200, "GET", "/manufacturing/reports/variances?orderId=" + orderId, null);
        assertThat(num(variances.get("totals").get("material_price"))).isEqualByComparingTo("200");
        assertThat(num(variances.get("totals").get("material_usage"))).isEqualByComparingTo("0");
    }
}
