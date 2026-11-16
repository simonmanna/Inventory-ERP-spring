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
 * Phase I2 review N3 ({@code docs/audits/2026-11-15-phase-I-regression-and-gap-review.md}): a production order whose
 * BOM is written in kilograms for products counted in grams reverses, cancels and returns materials in base units at
 * the cost the moves actually carried.
 */
class ProductionUnitsIT extends MfgTestSupport {

    String unit(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, ?, 'unit', 1, 1, 0.001, now())")
                .params(id, demoOrg, code + "-" + suffix(), code).update();
        return id;
    }

    /** A product counted in {@code gram} that also declares {@code kg} = 1000 g. */
    String inGrams(String code, String gram, String kg) {
        String p = product(code);
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ?, \"trackInventory\" = true WHERE id = ?").params(gram, p).update();
        ok(200, "PATCH", "/products/" + p, Map.of("units", List.of(Map.of("uomId", kg, "factor", 1000, "isPurchase",
                true, "isSales", true))));
        return p;
    }

    /** Flour 2 kg → bread 9 kg BOM, 5000 g of flour at 0.5 / g in the bakery; a started order. */
    String[] startedInKilograms() {
        String gram = unit("G");
        String kg = unit("KG");
        String flour = inGrams("FLOUR", gram, kg);
        String bread = inGrams("BREAD", gram, kg);
        jdbc.sql("UPDATE \"Product\" SET \"manufacturingRole\" = 'raw' WHERE id = ?").param(flour).update();
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", bakery, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", flour, "quantity", 5000, "unitCost",
                        "0.5"))));
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Bread kg " + suffix(),
                "outputProductId", bread, "outputQuantity", 9, "outputUomId", kg, "lines", List.of(Map.of(
                        "componentProductId", flour, "quantity", 2, "uomId", kg))));
        String bomId = draft.get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);
        String id = started(bomId).get("id").asString();
        return new String[] {id, flour, bread, kg};
    }

    @Test
    @DisplayName("N3: reversing an order in kg returns the grams that moved, at their cost")
    void reversalInBaseUnits() {
        String[] o = startedInKilograms();
        String id = o[0];
        String flour = o[1];
        String bread = o[2];
        assertThat(onHand(flour, bakery)).as("2 kg consumed").isEqualByComparingTo("3000");
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete", Map.of("qtyProduced", 9, "uomId", o[3]));
        assertThat(onHand(bread, warehouse)).as("9 kg received as grams").isEqualByComparingTo("9000");
        BigDecimal wipBefore = balance(wipAccount);

        ok(201, "POST", "/manufacturing/production-orders/" + id + "/reverse", Map.of());
        assertThat(onHand(bread, warehouse)).as("all 9000 g of bread leave").isEqualByComparingTo("0");
        assertThat(onHand(flour, bakery)).as("2000 g of flour come back").isEqualByComparingTo("5000");
        BigDecimal flourValue = jdbc.sql("SELECT quantity * \"runningAverageCost\" FROM \"StockItem\" WHERE"
                + " \"productId\" = ? AND \"locationId\" = ?").params(flour, bakery).query(BigDecimal.class).single();
        assertThat(flourValue).as("5000 g at 0.5").isEqualByComparingTo("2500");
        assertThat(balance(wipAccount)).as("WIP nets back to where it was").isEqualByComparingTo(wipBefore);
    }

    @Test
    @DisplayName("N3: cancelling a started order in kg returns the grams consumed, at their cost")
    void cancelReturnsBaseUnits() {
        String[] o = startedInKilograms();
        assertThat(onHand(o[1], bakery)).isEqualByComparingTo("3000");
        ok(201, "POST", "/manufacturing/production-orders/" + o[0] + "/cancel", Map.of("reason", "review N3"));
        assertThat(onHand(o[1], bakery)).as("2000 g back, not 2 g").isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("N8: reversing an order issues its by-products too and clears their value from WIP")
    void reversalIssuesByproducts() {
        String gram = unit("G");
        String kg = unit("KG");
        String flour = inGrams("FLOUR", gram, kg);
        String bread = inGrams("BREAD", gram, kg);
        String bran = inGrams("BRAN", gram, kg);
        jdbc.sql("UPDATE \"Product\" SET \"manufacturingRole\" = 'raw' WHERE id = ?").param(flour).update();
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", bakery, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", flour, "quantity", 5000, "unitCost",
                        "0.5"))));
        String bomId = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Bread + bran " + suffix(),
                "outputProductId", bread, "outputQuantity", 9, "outputUomId", kg, "lines", List.of(Map.of(
                        "componentProductId", flour, "quantity", 2, "uomId", kg)))).get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);
        String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse, "outputs", List.of(Map.of("productId", bran,
                        "kind", "byproduct", "qtyExpected", 1, "uomId", kg, "costAllocationPct", 10)))).get("id")
                .asString();
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/confirm", null);
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/start", Map.of());
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete", Map.of("qtyProduced", 9, "uomId", kg));
        assertThat(onHand(bran, warehouse)).as("1 kg of bran received").isEqualByComparingTo("1000");
        BigDecimal wipBefore = balance(wipAccount);

        ok(201, "POST", "/manufacturing/production-orders/" + id + "/reverse", Map.of());
        assertThat(onHand(bran, warehouse)).as("the bran leaves with the bread").isEqualByComparingTo("0");
        assertThat(onHand(bread, warehouse)).isEqualByComparingTo("0");
        assertThat(onHand(flour, bakery)).isEqualByComparingTo("5000");
        assertThat(balance(wipAccount)).as("WIP is not short by the by-product credit")
                .isEqualByComparingTo(wipBefore);
    }

    @Test
    @DisplayName("M-3: a confirmed order reserves its materials in base units and releases them once consumed")
    void reservationsInBaseUnitsUntilConsumed() {
        String gram = unit("G");
        String kg = unit("KG");
        String flour = inGrams("FLOUR", gram, kg);
        String bread = inGrams("BREAD", gram, kg);
        jdbc.sql("UPDATE \"Product\" SET \"manufacturingRole\" = 'raw' WHERE id = ?").param(flour).update();
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", bakery, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", flour, "quantity", 5000, "unitCost",
                        "0.5"))));
        String bomId = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Bread res " + suffix(),
                "outputProductId", bread, "outputQuantity", 9, "outputUomId", kg, "lines", List.of(Map.of(
                        "componentProductId", flour, "quantity", 2, "uomId", kg)))).get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);
        String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse)).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/confirm", null);
        assertThat(jdbc.sql("SELECT quantity FROM \"StockReservation\" WHERE \"sourceId\" = ? AND status = 'active'")
                .param(id).query(BigDecimal.class).single()).as("2 kg = 2000 g held back").isEqualByComparingTo("2000");
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/start", Map.of());
        assertThat(jdbc.sql("SELECT count(*) FROM \"StockReservation\" WHERE \"sourceId\" = ? AND status = 'active'")
                .param(id).query(Long.class).single()).as("consumed flour is no longer reserved").isZero();
    }
}
