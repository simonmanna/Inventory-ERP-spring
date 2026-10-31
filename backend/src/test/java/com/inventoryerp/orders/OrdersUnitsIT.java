package com.inventoryerp.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** UOM-1 on {@code /orders}: a line typed in truckloads is stored in the sales unit and remembers what was typed. */
class OrdersUnitsIT extends ApiTestSupport {

    String uom(String code) {
        return jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = ?")
                .params(demoOrg, code).query(String.class).single();
    }

    String sand(String ton) {
        String id = UUID.randomUUID().toString();
        String s = id.substring(0, 8);
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'service', false, 'AVCO', 0, 50, ?, ?, now())")
                .params(id, demoOrg, "OU-" + s, "Sand " + s, ton, ton).update();
        return id;
    }

    @Test
    @DisplayName("2 truckloads of 28 t: 56 t stored, value kept, typed unit returned, kept on append")
    void truckloads() {
        String ton = uom("TON");
        String truck = uom("TRUCK");
        String product = sand(ton);
        ok(200, "PUT", "/products/" + product + "/unit-profile", Map.of("tonnesPerTruckload", 28));
        JsonNode order = ok(200, "POST", "/orders", Map.of("lines", List.of(Map.of("productId", product, "quantity", 2,
                "uomId", truck, "unitPrice", 1400))));
        String id = order.get("id").asString();
        JsonNode line = order.get("items").get(0);
        assertThat(line.get("quantity").decimalValue()).isEqualByComparingTo("56");
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("50");
        assertThat(line.get("entryUomId").asString()).isEqualTo(truck);
        assertThat(line.get("entryQuantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo(new BigDecimal("2800"));
        JsonNode appended = ok(201, "POST", "/orders/" + id + "/items", Map.of("lines", List.of(Map.of("productId",
                product, "quantity", 3))));
        assertThat(appended.get("items").get(0).get("entryUomId").asString()).isEqualTo(truck);
        assertThat(appended.get("items").get(1).has("entryUomId")).isFalse();
        ok(200, "POST", "/orders/" + id + "/cancel", Map.of("reason", "test"));
    }

    @Test
    @DisplayName("a line in the sales unit passes through and records nothing")
    void salesUnit() {
        String ton = uom("TON");
        JsonNode order = ok(200, "POST", "/orders", Map.of("lines", List.of(Map.of("productId", sand(ton), "quantity", 2,
                "uomId", ton))));
        assertThat(order.get("items").get(0).has("entryUomId")).isFalse();
        ok(200, "POST", "/orders/" + order.get("id").asString() + "/cancel", Map.of("reason", "test"));
    }
}
