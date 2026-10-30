package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Phase S4 of ADR-S011: time-phased, location-aware ATP (stock by yard, free purchase supply, split proposal,
 * production not connected), firm reservations on confirm under the stock row lock (no oversell under concurrency),
 * backorders pegged to purchase supply, release on cancel, re-plan on close short and on stock arrival.
 */
class SalesAtpIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String yardA;
    String yardB;

    @BeforeAll
    void fixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(customer, demoOrg, "AT-" + sfx, "ATP cust " + sfx)
                .update();
        yardA = location("Yard A " + sfx);
        yardB = location("Yard B " + sfx);
    }

    String location(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 'warehouse', now())").params(id, demoOrg, "L-" + UUID.randomUUID().toString()
                        .substring(0, 8), name).update();
        return id;
    }

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable'::\"ProductType\","
                + " 100, 60, true, now())").params(id, demoOrg, "AP-" + UUID.randomUUID().toString().substring(0, 8),
                        name).update();
        return id;
    }

    void stock(String product, String location, int qty) {
        int n = jdbc.sql("UPDATE \"StockItem\" SET quantity = ? WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(qty, product, location).update();
        if (n == 0) {
            jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", quantity,"
                    + " \"runningAverageCost\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 60, now())").params(UUID
                            .randomUUID().toString(), demoOrg, product, location, qty).update();
        }
    }

    void purchase(String product, int qty, LocalDate expected) {
        String po = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PurchaseOrder\" (id, \"organizationId\", \"orderNumber\", \"partnerId\", status,"
                + " \"expectedDeliveryDate\", \"updatedAt\") VALUES (?, ?, ?, ?, 'approved', ?, now())").params(po, demoOrg,
                        "PO-" + UUID.randomUUID().toString().substring(0, 8), customer, java.sql.Timestamp.valueOf(
                                expected.atStartOfDay())).update();
        jdbc.sql("INSERT INTO \"PurchaseOrderLine\" (id, \"organizationId\", \"purchaseOrderId\", \"productId\","
                + " description, quantity, \"unitPrice\") VALUES (?, ?, ?, ?, 'supply', ?, 50)")
                .params(UUID.randomUUID().toString(), demoOrg, po, product, qty).update();
    }

    JsonNode order(String product, int qty, String location) {
        return ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", location, "lines", List.of(
                Map.of("productId", product, "quantity", qty))));
    }

    BigDecimal reserved(String product, String location) {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockReservation\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ? AND status = 'active'").params(product, location).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("ATP: stock by yard, split proposal, free purchase supply dates the full fill, production not connected")
    void atp() {
        String p = product("Agg " + sfx);
        stock(p, yardA, 150);
        stock(p, yardB, 50);
        purchase(p, 300, LocalDate.now().plusDays(10));
        JsonNode r = ok(200, "POST", "/sales/atp", Map.of("lines", List.of(Map.of("productId", p, "quantity", 180))));
        JsonNode a = r.get("lines").get(0);
        assertThat(a.get("availableNow").asDouble()).isEqualTo(200);
        assertThat(a.get("canPromiseToday").asBoolean()).isTrue();
        assertThat(a.get("fullFillLocation").isNull()).isTrue();
        assertThat(a.get("bestLocation").asString()).isEqualTo(yardA);
        assertThat(a.get("split").size()).isEqualTo(2);
        assertThat(a.get("split").get(1).get("quantity").asDouble()).isEqualTo(30);
        assertThat(r.get("production").get("connected").asBoolean()).isFalse();
        JsonNode big = ok(200, "POST", "/sales/atp", Map.of("lines", List.of(Map.of("productId", p, "quantity", 400))))
                .get("lines").get(0);
        assertThat(big.get("canPromiseToday").asBoolean()).isFalse();
        assertThat(big.get("shortage").asDouble()).isEqualTo(200);
        assertThat(big.get("earliestDate").asString()).isEqualTo(LocalDate.now().plusDays(10).toString());
        JsonNode never = ok(200, "POST", "/sales/atp", Map.of("lines", List.of(Map.of("productId", p, "quantity", 600))))
                .get("lines").get(0);
        assertThat(never.get("earliestDate").isNull()).isTrue();
    }

    @Test
    @DisplayName("concurrent confirmations cannot oversell: 2 × 100 against 150 → 150 reserved, 50 backordered")
    void concurrency() throws Exception {
        String p = product("Sand " + sfx);
        stock(p, yardA, 150);
        String o1 = order(p, 100, yardA).get("id").asString();
        String o2 = order(p, 100, yardA).get("id").asString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> f = new ArrayList<>();
        for (String o : List.of(o1, o2)) {
            f.add(pool.submit(() -> {
                go.await();
                return as("POST", "/sales/orders/" + o + "/confirm", null).status();
            }));
        }
        go.countDown();
        for (Future<Integer> x : f) {
            assertThat(x.get()).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(reserved(p, yardA)).isEqualByComparingTo("150");
        double r1 = ok(200, "GET", "/sales/orders/" + o1, null).get("lines").get(0).get("qty").get("reserved").asDouble();
        double r2 = ok(200, "GET", "/sales/orders/" + o2, null).get("lines").get(0).get("qty").get("reserved").asDouble();
        assertThat(r1 + r2).isEqualTo(150);
        assertThat(Math.min(r1, r2)).isEqualTo(50);
        JsonNode back = ok(200, "GET", "/sales/reports/backorders", null);
        List<String> ids = new ArrayList<>();
        back.forEach(x -> ids.add(x.get("orderId").asString()));
        assertThat(ids).containsAnyOf(o1, o2);
    }

    @Test
    @DisplayName("backorder pegs purchase supply; stock arrival re-plans; close short and cancel release")
    void backorderLifecycle() {
        String p = product("Blocks " + sfx);
        stock(p, yardB, 40);
        purchase(p, 100, LocalDate.now().plusDays(5));
        String so = order(p, 100, yardB).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        JsonNode atp = ok(200, "GET", "/sales/orders/" + so + "/atp", null).get("lines").get(0);
        assertThat(atp.get("reserved").asDouble()).isEqualTo(40);
        assertThat(atp.get("pegs").get(0).get("quantity").asDouble()).isEqualTo(60);
        assertThat(atp.get("promiseAtRisk").asBoolean()).isFalse();
        // another order cannot use the pegged supply twice
        JsonNode other = ok(200, "POST", "/sales/atp", Map.of("lines", List.of(Map.of("productId", p, "quantity", 50))))
                .get("lines").get(0);
        assertThat(other.get("supply").get(0).get("available").asDouble()).isEqualTo(40);
        // stock arrives: re-plan reserves the rest
        stock(p, yardB, 120);
        JsonNode re = ok(200, "POST", "/sales/orders/" + so + "/reserve", null);
        assertThat(re.get("reserved").asDouble()).isEqualTo(100);
        assertThat(re.get("backorder").asDouble()).isZero();
        assertThat(ok(200, "GET", "/sales/orders/" + so + "/reservations", null).get("pegs").size()).isZero();
        // deliver 30 (projection), close short → reservation shrinks to 0
        String line = ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDelivered\" = 30, \"qtyBillable\" = 30 WHERE id = ?").param(line)
                .update();
        ok(200, "POST", "/sales/orders/" + so + "/lines/" + line + "/close-short", Map.of("reason", "Done"));
        assertThat(reserved(p, yardB)).isEqualByComparingTo("0");
        // cancel releases
        String so2 = order(p, 10, yardB).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so2 + "/confirm", null);
        assertThat(reserved(p, yardB)).isEqualByComparingTo("10");
        ok(200, "POST", "/sales/orders/" + so2 + "/cancel", Map.of("reason", "x"));
        assertThat(reserved(p, yardB)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("promised date before the pegged supply date is flagged at risk; service lines never reserve")
    void promiseAtRisk() {
        String p = product("Grade " + sfx);
        purchase(p, 50, LocalDate.now().plusDays(20));
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yardA, "lines", List.of(
                Map.of("productId", p, "quantity", 30, "promisedDate", LocalDate.now().plusDays(3).toString())))).get("id")
                .asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        JsonNode l = ok(200, "GET", "/sales/orders/" + so + "/atp", null).get("lines").get(0);
        assertThat(l.get("promiseAtRisk").asBoolean()).isTrue();
        assertThat(l.get("earliestDate").asString()).isEqualTo(LocalDate.now().plusDays(20).toString());
    }
}
