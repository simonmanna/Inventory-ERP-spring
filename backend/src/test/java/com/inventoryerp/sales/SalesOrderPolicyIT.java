package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SG-2: stock check policy, alternative warehouse, variant-aware reservations, alerts, approval and cancel guards. */
class SalesOrderPolicyIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String product;
    String customer;
    String yardA;
    String yardB;

    String id(JsonNode n) {
        return n.get("id").asString();
    }

    void stock(String productId, String location, int qty, String variantKey) {
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", \"variantKey\","
                + " quantity, \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(UUID.randomUUID().toString(), demoOrg,
                productId, location, variantKey, BigDecimal.valueOf(qty)).update();
    }

    @BeforeAll
    void fixtures() {
        product = id(ok(201, "POST", "/products", Map.of("code", "G2P-" + sfx, "name", "SG2 item " + sfx, "productType",
                "stockable", "trackInventory", true, "salesPrice", 100, "costPrice", 40)));
        yardA = id(ok(201, "POST", "/inventory/locations", Map.of("code", "G2A-" + sfx, "name", "SG2 A", "type",
                "warehouse")));
        yardB = id(ok(201, "POST", "/inventory/locations", Map.of("code", "G2B-" + sfx, "name", "SG2 B", "type",
                "warehouse")));
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "G2C-" + sfx, "SG2 cust " + sfx).update();
        stock(product, yardA, 3, "");
        stock(product, yardB, 50, "");
    }

    @AfterEach
    void resetPolicy() {
        ok(200, "PUT", "/sales/settings", Map.of("stockCheckPolicy", "warn", "orderApprovalAlways", false));
    }

    JsonNode draft(int qty) {
        return ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yardA, "lines", List.of(Map.of(
                "productId", product, "quantity", qty))));
    }

    @Test
    @DisplayName("stock policy: warn confirms with warnings, block refuses, none ignores")
    void stockPolicy() {
        JsonNode warned = draft(10);
        assertThat(warned.get("stockWarnings").get(0).get("shortage").asDouble()).isEqualTo(7);
        JsonNode confirmed = ok(200, "POST", "/sales/orders/" + id(warned) + "/confirm", null);
        assertThat(confirmed.get("status").asString()).isEqualTo("confirmed");
        assertThat(confirmed.get("stockWarnings").size()).isEqualTo(1);

        ok(200, "PUT", "/sales/settings", Map.of("stockCheckPolicy", "block"));
        JsonNode blocked = draft(10);
        Res r = as("POST", "/sales/orders/" + id(blocked) + "/confirm", null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("Not enough stock");

        ok(200, "PUT", "/sales/settings", Map.of("stockCheckPolicy", "none"));
        assertThat(ok(200, "POST", "/sales/orders/" + id(blocked) + "/confirm", null).get("status").asString())
                .isEqualTo("confirmed");
    }

    @Test
    @DisplayName("a short line moves to a warehouse that can cover it; one that cannot is refused")
    void alternativeWarehouse() {
        JsonNode o = ok(200, "POST", "/sales/orders/" + id(draft(10)) + "/confirm", null);
        String line = id(o.get("lines").get(0));
        Res refused = as("POST", "/sales/orders/" + id(o) + "/lines/" + line + "/source-location", Map.of("locationId",
                yardA));
        assertThat(refused.status()).isEqualTo(400);
        JsonNode moved = ok(200, "POST", "/sales/orders/" + id(o) + "/lines/" + line + "/source-location", Map.of(
                "locationId", yardB));
        JsonNode l = moved.get("lines").get(0);
        assertThat(l.get("sourceLocationId").asString()).isEqualTo(yardB);
        assertThat(l.get("reservedLocationId").asString()).isEqualTo(yardB);
        assertThat(l.get("qtyReserved").asDouble()).isEqualTo(10);
    }

    @Test
    @DisplayName("reservation and availability are per variant")
    void variantAware() {
        String p = id(ok(201, "POST", "/products", Map.of("code", "G2V-" + sfx, "name", "SG2 variant " + sfx,
                "productType", "stockable", "trackInventory", true, "salesPrice", 10)));
        String v1 = UUID.randomUUID().toString();
        String v2 = UUID.randomUUID().toString();
        for (String v : List.of(v1, v2)) {
            jdbc.sql("INSERT INTO \"ProductVariant\" (id, \"organizationId\", \"productId\", name, \"combinationKey\","
                    + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, now())").params(v, demoOrg, p, "V " + v, v).update();
        }
        stock(p, yardA, 2, v1);
        stock(p, yardA, 9, v2);
        JsonNode o = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yardA, "lines", List.of(
                Map.of("productId", p, "variantId", v1, "quantity", 5))));
        assertThat(o.get("stockWarnings").get(0).get("shortage").asDouble()).as("v2's stock does not cover v1")
                .isEqualTo(3);
        JsonNode c = ok(200, "POST", "/sales/orders/" + id(o) + "/confirm", null);
        assertThat(c.get("lines").get(0).get("qtyReserved").asDouble()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT \"variantId\" FROM \"StockReservation\" WHERE \"sourceId\" = ?").param(id(c.get("lines")
                .get(0))).query(String.class).single()).isEqualTo(v1);
    }

    @Test
    @DisplayName("hold and shortage alert the owner; orderApprovalAlways sends every order to approval")
    void alertsAndApproval() {
        JsonNode o = ok(200, "POST", "/sales/orders/" + id(draft(10)) + "/confirm", null);
        ok(200, "POST", "/sales/orders/" + id(o) + "/hold", Map.of("reason", "check"));
        long alerts = jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category IN"
                + " ('sales_order_hold', 'sales_order_shortage') AND title LIKE ?").params(demoOrg, "%" + o.get("number")
                .asString() + "%").query(Long.class).single();
        assertThat(alerts).isEqualTo(2);

        ok(200, "PUT", "/sales/settings", Map.of("orderApprovalAlways", true));
        JsonNode pending = ok(200, "POST", "/sales/orders/" + id(draft(1)) + "/confirm", null);
        assertThat(pending.get("status").asString()).isEqualTo("pending_approval");
    }

    @Test
    @DisplayName("cancel: a confirmed order needs a reason; open deposits and pending changes block it")
    void cancelGuards() {
        JsonNode o = ok(200, "POST", "/sales/orders/" + id(draft(1)) + "/confirm", null);
        String path = "/sales/orders/" + id(o) + "/cancel";
        assertThat(as("POST", path, Map.of()).status()).isEqualTo(400);
        jdbc.sql("INSERT INTO \"CustomerDeposit\" (id, \"organizationId\", number, \"partnerId\", \"salesOrderId\", source,"
                + " amount, \"receivedDate\") VALUES (?, ?, ?, ?, ?, 'receipt', 10, CURRENT_DATE)").params(UUID.randomUUID()
                .toString(), demoOrg, "G2D-" + sfx, customer, id(o)).update();
        Res r = as("POST", path, Map.of("reason", "x"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("deposits");
        jdbc.sql("UPDATE \"CustomerDeposit\" SET status = 'void' WHERE \"salesOrderId\" = ?").param(id(o)).update();
        assertThat(ok(200, "POST", path, Map.of("reason", "x")).get("status").asString()).isEqualTo("cancelled");
    }
}
