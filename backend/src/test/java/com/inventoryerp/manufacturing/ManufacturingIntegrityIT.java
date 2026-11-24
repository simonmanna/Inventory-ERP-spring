package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Integrity fixes 2026-11-24 (manufacturing production-readiness review, step 1): one lock per production order
 * across complete / cancel / reverse / absorb / work-order costing, reversal against the order's own output, and the
 * by-product cost allocation cap. Each test failed (or could fail under the race) before the fix.
 */
class ManufacturingIntegrityIT extends MfgTestSupport {

    private static final String PO = "/manufacturing/production-orders/";

    @Test
    @DisplayName("by-product cost allocation: each at most 100 %, together at most 100 %")
    void byproductAllocationCap() {
        String flour = product("FLOUR-BP");
        String bread = product("BREAD-BP");
        String crumbs = product("CRUMB-BP");
        String bran = product("BRAN-BP");
        String bomId = bom(bread, flour, 10, 1, Map.of());
        Res single = as("POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId",
                bakery, "outputs", List.of(Map.of("productId", crumbs, "kind", "byproduct", "qtyExpected", 1,
                        "costAllocationPct", 150))));
        assertThat(single.status()).isEqualTo(400);
        assertThat(single.raw().body()).contains("must not be greater than 100");
        Res total = as("POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId",
                bakery, "outputs", List.of(
                        Map.of("productId", crumbs, "kind", "byproduct", "qtyExpected", 1, "costAllocationPct", 60),
                        Map.of("productId", bran, "kind", "byproduct", "qtyExpected", 1, "costAllocationPct", 50))));
        assertThat(total.status()).isEqualTo(400);
        assertThat(total.body().get("message").asString())
                .isEqualTo("By-product cost allocation totals 110%; it cannot exceed 100%.");
    }

    @Test
    @DisplayName("reverse counts only this order's output: another order's stock in the same quarantine does not let it pass")
    void reverseCountsOwnOutput() {
        String flour = product("FLOUR-RV");
        String cake = product("CAKE-RV");
        stockIn(bakery, flour, 40, 100);
        String bomId = bom(cake, flour, 20, 4, Map.of("qcRequired", true));
        String a = started(bomId).get("id").asString();
        assertThat(ok(201, "POST", PO + a + "/complete", Map.of("qtyProduced", 20)).get("status").asString())
                .isEqualTo("qc_hold");
        // 12 pass QC (sellable), 8 wait for rework: order A keeps only 8 in quarantine.
        assertThat(ok(201, "POST", PO + a + "/qc", Map.of("passedQty", 12, "reworkQty", 8)).get("status").asString())
                .isEqualTo("qc_hold");
        String b = started(bomId).get("id").asString();
        ok(201, "POST", PO + b + "/complete", Map.of("qtyProduced", 20));
        String quarantine = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                + " AND code = 'QC-QUARANTINE' AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
        assertThat(onHand(cake, quarantine)).isEqualByComparingTo("28");

        Res refused = as("POST", PO + a + "/reverse", Map.of("reason", "wrong recipe"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString())
                .isEqualTo("Cannot reverse: finished goods are no longer fully in stock (sold or moved).");
        assertThat(onHand(cake, quarantine)).isEqualByComparingTo("28");

        // Order B still holds all of its output: it reverses.
        assertThat(ok(201, "POST", PO + b + "/reverse", Map.of("reason", "wrong recipe")).get("status").asString())
                .isEqualTo("cancelled");
        assertThat(onHand(cake, quarantine)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("work-order complete twice at once: the time is costed once")
    void workOrderCompletedOnce() throws Exception {
        JsonNode wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "WC-" + suffix(), "name", "Oven"));
        String wcId = wc.get("id").asString();
        String res = ok(201, "POST", "/manufacturing/resources", Map.of("name", "Baker", "kind", "labour",
                "workCenterId", wcId, "costPerHour", 120, "capacityMinsPerDay", 480)).get("id").asString();
        String flour = product("FLOUR-WO");
        String bun = product("BUN-WO");
        stockIn(bakery, flour, 10, 100);
        String bomId = bom(bun, flour, 10, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Bun line " + suffix(), "bomId", bomId,
                "operations", List.of(Map.of("sequence", 1, "name", "Bake", "workCenterId", wcId, "durationMins", 30))));
        String orderId = started(bomId).get("id").asString();
        String woId = ok(201, "POST", PO + orderId + "/work-orders/generate", null).get(0).get("id").asString();
        ok(200, "PATCH", "/manufacturing/work-orders/" + woId + "/assign", Map.of("resourceId", res));
        ok(201, "POST", "/manufacturing/work-orders/" + woId + "/start", null);

        List<Integer> statuses = race(2, () -> as("POST", "/manufacturing/work-orders/" + woId + "/complete",
                Map.of("labourMins", 30)).status());
        assertThat(statuses).containsExactlyInAnyOrder(201, 400);
        // 30 min × 120/h = 60, once.
        assertThat(ok(200, "GET", PO + orderId, null).get("overheadCost").asString()).isEqualTo("60");
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProductionCostComponent\" WHERE \"orderId\" = ? AND \"sourceId\" = ?")
                .params(orderId, woId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("cancel racing start never leaves a cancelled order with its materials consumed")
    void cancelRacingStart() throws Exception {
        String flour = product("FLOUR-CX");
        String roll = product("ROLL-CX");
        stockIn(bakery, flour, 30, 100);
        String bomId = bom(roll, flour, 10, 2, Map.of());
        BigDecimal wip0 = balance(wipAccount);
        for (int i = 0; i < 3; i++) {
            String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                    "locationId", bakery, "outputLocationId", warehouse)).get("id").asString();
            ok(201, "POST", PO + id + "/confirm", null);
            BigDecimal flourBefore = onHand(flour, bakery);
            int[] turn = {0};
            race(2, () -> {
                boolean first;
                synchronized (turn) {
                    first = turn[0]++ == 0;
                }
                return first ? as("POST", PO + id + "/start", Map.of()).status()
                        : as("POST", PO + id + "/cancel", Map.of("reason", "race")).status();
            });
            JsonNode after = ok(200, "GET", PO + id, null);
            if ("cancelled".equals(after.get("status").asString())) {
                assertThat(onHand(flour, bakery)).as("cancelled order %s keeps no materials", id)
                        .isEqualByComparingTo(flourBefore);
            } else {
                assertThat(after.get("status").asString()).isEqualTo("in_progress");
                ok(201, "POST", PO + id + "/cancel", Map.of("reason", "tidy"));
                assertThat(onHand(flour, bakery)).isEqualByComparingTo(flourBefore);
            }
        }
        assertThat(balance(wipAccount)).isEqualByComparingTo(wip0);
    }

    private <T> List<T> race(int n, java.util.concurrent.Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return call.call();
            }));
        }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}
