package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The reservations screen route (INV-1): names, filters, paging, tenancy. */
class StockReservationOverviewIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String product;
    String location;

    @BeforeAll
    void fixtures() {
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costPrice\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', 1, now())")
                .params(product, demoOrg, "RV-" + sfx, "Crushed stone " + sfx).update();
        location = ok(201, "POST", "/inventory/locations", Map.of("code", "RL-" + sfx, "name", "Yard " + sfx, "type",
                "warehouse")).get("id").asString();
        ok(201, "POST", "/inventory/reservations", Map.of("productId", product, "locationId", location, "quantity", 5,
                "sourceType", "manual", "sourceId", "A-" + sfx, "reason", "Site order"));
        ok(201, "POST", "/inventory/reservations", Map.of("productId", product, "locationId", location, "quantity", 2,
                "sourceType", "order", "sourceId", "B-" + sfx));
        ok(201, "POST", "/inventory/reservations/release", Map.of("sourceType", "order", "sourceId", "B-" + sfx));
    }

    @Test
    @DisplayName("rows carry names and on-hand; status, source type and search filter them; paging works")
    void overview() {
        JsonNode all = ok(200, "GET", "/inventory/reservations/overview?productId=" + product, null);
        assertThat(all.get("meta").get("total").asInt()).isEqualTo(2);
        JsonNode active = ok(200, "GET", "/inventory/reservations/overview?status=active&productId=" + product, null);
        assertThat(active.get("data")).hasSize(1);
        JsonNode row = active.get("data").get(0);
        assertThat(row.get("productName").asString()).isEqualTo("Crushed stone " + sfx);
        assertThat(row.get("locationCode").asString()).isEqualTo("RL-" + sfx);
        assertThat(new java.math.BigDecimal(row.get("onHand").asString())).isEqualByComparingTo("0");
        assertThat(ok(200, "GET", "/inventory/reservations/overview?sourceType=order&productId=" + product, null)
                .get("data").get(0).get("status").asString()).isEqualTo("released");
        assertThat(ok(200, "GET", "/inventory/reservations/overview?q=A-" + sfx, null).get("data")).hasSize(1);
        assertThat(ok(200, "GET", "/inventory/reservations/overview?productId=" + product + "&pageSize=1&page=2", null)
                .get("data")).hasSize(1);
    }

    @Test
    @DisplayName("the old list keeps its plain-array contract")
    void oldListUnchanged() {
        assertThat(ok(200, "GET", "/inventory/reservations?sourceId=A-" + sfx, null).isArray()).isTrue();
    }

    @Test
    @DisplayName("another organization sees none of it")
    void tenantIsolation() {
        assertThat(call("GET", "/inventory/reservations/overview?productId=" + product, null, otherToken).body()
                .get("meta").get("total").asInt()).isZero();
    }
}
