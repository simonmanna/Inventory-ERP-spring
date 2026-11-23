package com.inventoryerp.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Inventory hardening R1 (P1-13, gate G3): a transfer receipt recorded offline before its dispatch synced is applied
 * exactly once — two concurrent appliers (the sync job and a dispatch sync) receive it once, and a crash between the
 * receipt and the resolve commits nothing, so the next run applies it once.
 */
class SyncInventoryRecoveryIT extends ApiTestSupport {

    @Autowired
    SyncStockService syncStock;

    String token;
    String deviceId;
    String adminId;
    String locA;
    String locB;
    String managerPin;
    int seq;

    @BeforeAll
    void fixtures() {
        JsonNode d = ok(201, "POST", "/sync/devices/register", Map.of("name", "Recovery tablet " + sfx(), "platform",
                "web"));
        token = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        locA = ok(201, "POST", "/inventory/locations", Map.of("code", "RA-" + sfx(), "name", "Recovery A " + sfx(),
                "type", "warehouse")).get("id").asString();
        locB = ok(201, "POST", "/inventory/locations", Map.of("code", "RB-" + sfx(), "name", "Recovery B " + sfx(),
                "type", "warehouse")).get("id").asString();
        managerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        ok(201, "POST", "/pos/override/pin", Map.of("pin", managerPin));
    }

    @AfterEach
    void clearHook() {
        SyncStockService.afterReceiptHook = null;
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE kind LIKE 'offline_%' AND payload->>'deviceId'"
                + " = ? AND status = 'open'").param(deviceId).update();
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 5, 10, now())").params(id, demoOrg, "REC-" + sfx(), "Recovery item " + sfx()).update();
        return id;
    }

    JsonNode push(String type, Map<String, Object> payload) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", UUID.randomUUID().toString());
        o.put("deviceSeq", ++seq);
        o.put("type", type);
        o.put("actorUserId", adminId);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", payload);
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(o)), Map.of("X-Device-Token",
                token));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        JsonNode res = r.body().get("results").get(0);
        assertThat(res.get("status").asString()).as(res.toString()).isEqualTo("applied");
        return res;
    }

    BigDecimal onHand(String product, String location) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(product, location).query(BigDecimal.class).single();
    }

    List<Map<String, Object>> waiting(String transferId) {
        return jdbc.sql("SELECT id, status, kind FROM \"InventoryException\" WHERE payload->>'documentId' = ? AND kind IN"
                + " ('offline_awaiting_dispatch', 'offline_held_posting')").param(transferId).query().listOfRows();
    }

    /**
     * A transit transfer of 3 whose partial receipt (2) was recorded offline before its dispatch, then dispatched
     * online (which does not apply waiting receipts). Returns {product, transferId}.
     */
    String[] waitingPartialReceipt() {
        String p = product();
        push("stock.in", Map.of("locationId", locA, "responsibleById", adminId, "approvedById", adminId,
                "approverPin", managerPin, "items", List.of(Map.of("productId", p, "quantity", 10, "unitCost", 5))));
        String transferId = ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", locA, "toLocationId",
                locB, "mode", "transit", "responsibleById", adminId, "approvedById", adminId, "items", List.of(Map.of(
                        "productId", p, "qtyRequested", 3)))).get("id").asString();
        ok(200, "POST", "/inventory/transfers/" + transferId + "/approve", Map.of());
        String item = jdbc.sql("SELECT id FROM \"StockTransferItem\" WHERE \"transferId\" = ?").param(transferId)
                .query(String.class).single();
        JsonNode w = push("transfer.receive", Map.of("transferId", transferId, "lines", List.of(Map.of("itemId", item,
                "received", 2))));
        assertThat(w.get("outcome").get("state").asString()).isEqualTo("awaiting_dispatch");
        Res dispatched = as("POST", "/inventory/transfers/" + transferId + "/dispatch", Map.of());
        assertThat(dispatched.status()).as(dispatched.raw().body()).isIn(200, 201);
        assertThat(onHand(p, locB)).as("the online dispatch leaves the waiting receipt alone").isEqualByComparingTo("0");
        return new String[] {p, transferId};
    }

    int apply(String transferId) {
        return TenantContext.run(TenantStore.organization(demoOrg), () -> syncStock.applyAwaiting(transferId));
    }

    @Test
    @DisplayName("two concurrent appliers receive a waiting partial receipt exactly once")
    void concurrentApplyOnce() throws Exception {
        String[] t = waitingPartialReceipt();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // the first applier holds its claim (row lock) until the second has run
        SyncStockService.afterReceiptHook = () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> apply(t[1]));
        assertThat(inside.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        SyncStockService.afterReceiptHook = null;
        int second = apply(t[1]);
        release.countDown();
        assertThat(first.get() + second).as("applied by exactly one runner").isEqualTo(1);
        assertThat(second).as("the row the first runner holds is skipped").isZero();
        assertThat(onHand(t[0], locB)).isEqualByComparingTo("2");
        assertThat(waiting(t[1])).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("resolved"));
        assertThat(apply(t[1])).as("nothing left to apply").isZero();
        assertThat(onHand(t[0], locB)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("a crash between the receipt and the resolve commits nothing; the next run applies the receipt once")
    void crashBetweenReceiptAndResolve() {
        String[] t = waitingPartialReceipt();
        SyncStockService.afterReceiptHook = () -> {
            throw new IllegalStateException("simulated crash");
        };
        assertThatThrownBy(() -> apply(t[1])).hasMessageContaining("simulated crash");
        SyncStockService.afterReceiptHook = null;
        assertThat(onHand(t[0], locB)).as("the receipt rolled back with the claim").isEqualByComparingTo("0");
        assertThat(waiting(t[1])).singleElement().satisfies(r -> {
            assertThat(r.get("status")).isEqualTo("open");
            assertThat(r.get("kind")).isEqualTo("offline_awaiting_dispatch");
        });
        assertThat(jdbc.sql("SELECT count(*) FROM \"IdempotencyRecord\" WHERE \"organizationId\" = ? AND key = ?")
                .params(demoOrg, "offline-receipt:" + waiting(t[1]).getFirst().get("id")).query(Long.class).single())
                .as("no key survives the rollback").isZero();
        assertThat(apply(t[1])).isEqualTo(1);
        assertThat(onHand(t[0], locB)).isEqualByComparingTo("2");
        assertThat(waiting(t[1])).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("resolved"));
        assertThat(apply(t[1])).isZero();
        assertThat(onHand(t[0], locB)).isEqualByComparingTo("2");
    }
}
