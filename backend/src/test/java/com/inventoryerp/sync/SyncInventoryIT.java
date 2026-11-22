package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.settings.SettingResolverService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 O4 / S4: warehouse work recorded offline — transit transfers (dispatch / receive, including a receipt
 * before its dispatch), adjustments, goods receipts against a purchase order, counts measured at the time of
 * counting, held postings and negative-stock flags, and the warehouse pull scopes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncInventoryIT extends ApiTestSupport {

    String token;
    String deviceId;
    String adminId;
    String locA;
    String locB;
    String pcs;
    int seq;
    String negativeSetting;
    String managerPin;

    @Autowired
    SettingResolverService settingResolver;

    void evictSettings() {
        settingResolver.invalidate(demoOrg);
    }

    @BeforeAll
    void fixtures() {
        JsonNode d = ok(201, "POST", "/sync/devices/register", Map.of("name", "Stock tablet " + sfx(), "platform", "web"));
        token = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        locA = ok(201, "POST", "/inventory/locations", Map.of("code", "OA-" + sfx(), "name", "Offline A " + sfx(), "type",
                "warehouse")).get("id").asString();
        locB = ok(201, "POST", "/inventory/locations", Map.of("code", "OB-" + sfx(), "name", "Offline B " + sfx(), "type",
                "warehouse")).get("id").asString();
        managerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        ok(201, "POST", "/pos/override/pin", Map.of("pin", managerPin));
        pcs = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, 'PCS', 'unit', 1, 1, 1, now())")
                .params(pcs, demoOrg, "OPCS-" + sfx()).update();
    }

    @AfterEach
    void dropSetting() {
        if (negativeSetting != null) {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(negativeSetting).update();
            negativeSetting = null;
            evictSettings();
        }
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE kind LIKE 'offline_%' AND payload->>'deviceId'"
                + " = ? AND status = 'open'").param(deviceId).update();
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN ('open',"
                + " 'investigating')").param(deviceId).update();
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    void allowNegative(boolean allow) {
        negativeSetting = UUID.randomUUID().toString();
        jdbc.sql("DELETE FROM \"Setting\" WHERE \"organizationId\" = ? AND key = 'inventory.allowNegativeStock'"
                + " AND \"scopeType\" = 'organization'").param(demoOrg).update();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'inventory.allowNegativeStock', ?::jsonb, 'organization', now())")
                .params(negativeSetting, demoOrg, String.valueOf(allow)).update();
        evictSettings();
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 5, 10, ?, ?, now())")
                .params(id, demoOrg, "OFF-" + sfx(), "Offline item " + sfx(), pcs, pcs).update();
        return id;
    }

    Map<String, Object> op(String type, Map<String, Object> payload, Instant occurredAt) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", payload.containsKey("clientId") ? payload.get("clientId") : UUID.randomUUID().toString());
        o.put("deviceSeq", ++seq);
        o.put("type", type);
        o.put("actorUserId", adminId);
        o.put("occurredAt", occurredAt.toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(String type, Map<String, Object> payload) {
        return push(op(type, payload, Instant.now()));
    }

    JsonNode push(Map<String, Object> op) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(op)), Map.of("X-Device-Token",
                token));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results").get(0);
    }

    JsonNode applied(String type, Map<String, Object> payload) {
        JsonNode r = push(type, payload);
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
        return r;
    }

    void stockIn(String product, String location, int qty) {
        applied("stock.in", Map.of("locationId", location, "responsibleById", adminId, "approvedById", adminId,
                "approverPin", managerPin, "items", List.of(Map.of("productId", product, "quantity", qty, "unitCost", 5))));
    }

    BigDecimal onHand(String product, String location) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(product, location).query(BigDecimal.class).single();
    }

    Map<String, Object> transferRow(String id) {
        return jdbc.sql("SELECT status::text AS status, \"recordedOffline\", \"occurredAt\" FROM \"StockTransfer\" WHERE"
                + " id = ?").param(id).query().singleRow();
    }

    List<Map<String, Object>> exceptions(String kind, String documentId) {
        return jdbc.sql("SELECT id, status, reason, quantity FROM \"InventoryException\" WHERE kind = ? AND"
                + " payload->>'documentId' = ?").params(kind, documentId).query().listOfRows();
    }

    String itemOf(String transferId) {
        return jdbc.sql("SELECT id FROM \"StockTransferItem\" WHERE \"transferId\" = ?").param(transferId)
                .query(String.class).single();
    }

    @Test
    @DisplayName("ad-hoc dispatch creates and dispatches a transit transfer; a receipt in a later batch names it by"
            + " its client id")
    void dispatchThenReceive() {
        String p = product();
        stockIn(p, locA, 10);
        String client = UUID.randomUUID().toString();
        JsonNode dispatched = applied("transfer.dispatch", Map.of("clientId", client, "fromLocationId", locA,
                "toLocationId", locB, "items", List.of(Map.of("productId", p, "quantity", 4))));
        String transferId = dispatched.get("mapping").get(client).asString();
        assertThat(dispatched.get("outcome").get("state").asString()).isEqualTo("dispatched");
        Map<String, Object> t = transferRow(transferId);
        assertThat(t.get("status")).isEqualTo("in_transit");
        assertThat(t.get("recordedOffline")).isEqualTo(true);
        assertThat(t.get("occurredAt")).isNotNull();
        assertThat(onHand(p, locA)).isEqualByComparingTo("6");

        applied("transfer.receive", Map.of("transferId", client, "lines", List.of(Map.of("itemId", itemOf(transferId),
                "received", 4))));
        assertThat(transferRow(transferId).get("status")).isEqualTo("completed");
        assertThat(onHand(p, locB)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a receipt recorded before the server has the dispatch waits, then applies when the dispatch syncs")
    void receiptWaitsForDispatch() {
        String p = product();
        stockIn(p, locA, 10);
        String transferId = ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", locA, "toLocationId", locB,
                "mode", "transit", "responsibleById", adminId, "approvedById", adminId, "items", List.of(Map.of(
                        "productId", p, "qtyRequested", 3)))).get("id").asString();
        ok(200, "POST", "/inventory/transfers/" + transferId + "/approve", Map.of());
        String item = itemOf(transferId);

        JsonNode waiting = applied("transfer.receive", Map.of("transferId", transferId, "lines", List.of(Map.of(
                "itemId", item, "received", 3))));
        assertThat(waiting.get("outcome").get("state").asString()).isEqualTo("awaiting_dispatch");
        assertThat(exceptions("offline_awaiting_dispatch", transferId)).singleElement()
                .satisfies(e -> assertThat(e.get("status")).isEqualTo("open"));
        assertThat(onHand(p, locB)).isEqualByComparingTo("0");

        applied("transfer.dispatch", Map.of("transferId", transferId));
        assertThat(transferRow(transferId).get("status")).isEqualTo("completed");
        assertThat(onHand(p, locB)).isEqualByComparingTo("3");
        assertThat(exceptions("offline_awaiting_dispatch", transferId)).singleElement()
                .satisfies(e -> assertThat(e.get("status")).isEqualTo("resolved"));
    }

    @Test
    @DisplayName("negative stock blocked: the dispatch is kept and held for a supervisor (op applied, not dead-lettered)")
    void heldWhenNegativeStockBlocked() {
        allowNegative(false);
        String p = product();
        stockIn(p, locA, 2);
        String client = UUID.randomUUID().toString();
        JsonNode r = applied("transfer.dispatch", Map.of("clientId", client, "fromLocationId", locA, "toLocationId", locB,
                "items", List.of(Map.of("productId", p, "quantity", 5))));
        String transferId = r.get("mapping").get(client).asString();
        assertThat(r.get("outcome").get("state").asString()).isEqualTo("held");
        assertThat(r.get("outcome").get("reason").asString()).startsWith("Insufficient stock");
        assertThat(transferRow(transferId).get("status")).isEqualTo("approved");
        assertThat(exceptions("offline_held_posting", transferId)).singleElement()
                .satisfies(e -> assertThat((String) e.get("reason")).startsWith("Insufficient stock"));
        assertThat(onHand(p, locA)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("negative stock allowed: an offline issue posts and the shortfall is flagged in the report")
    void negativeFlaggedWhenAllowed() {
        allowNegative(true);
        String p = product();
        stockIn(p, locA, 1);
        JsonNode r = applied("stock.out", Map.of("locationId", locA, "responsibleById", adminId, "approvedById", adminId,
                "approverPin", managerPin, "items", List.of(Map.of("productId", p, "quantity", 3))));
        assertThat(onHand(p, locA)).isEqualByComparingTo("-2");
        Map<String, Object> flag = jdbc.sql("SELECT quantity, payload->>'documentCode' AS code FROM \"InventoryException\""
                + " WHERE kind = 'offline_negative_stock' AND \"productId\" = ?").param(p).query().singleRow();
        assertThat((BigDecimal) flag.get("quantity")).isEqualByComparingTo("-2");
        String docId = (String) flag.get("code");
        assertThat(docId).isNotBlank();
        JsonNode report = ok(200, "GET", "/sync/stock-discrepancies?deviceId=" + deviceId + "&kind=offline_negative_stock",
                null);
        assertThat(report.toString()).contains(docId).contains("\"onHandAfter\":-2");
    }

    @Test
    @DisplayName("a transfer never takes its source below zero (existing rule): a short dispatch is held, whatever the"
            + " negative-stock setting")
    void shortDispatchHeldEvenWhenNegativeAllowed() {
        allowNegative(true);
        String p = product();
        stockIn(p, locA, 1);
        String client = UUID.randomUUID().toString();
        JsonNode r = applied("transfer.dispatch", Map.of("clientId", client, "fromLocationId", locA, "toLocationId", locB,
                "items", List.of(Map.of("productId", p, "quantity", 3))));
        assertThat(r.get("outcome").get("state").asString()).isEqualTo("held");
        assertThat(onHand(p, locA)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("stock.adjust: a pending adjustment of on-hand + change, waiting for approval")
    void adjustmentWaitsForApproval() {
        String p = product();
        stockIn(p, locA, 7);
        String client = UUID.randomUUID().toString();
        JsonNode r = applied("stock.adjust", Map.of("clientId", client, "locationId", locA, "reason", "damaged",
                "lines", List.of(Map.of("productId", p, "qtyChange", -2))));
        String adjId = r.get("mapping").get(client).asString();
        Map<String, Object> adj = jdbc.sql("SELECT a.status::text AS status, a.\"recordedOffline\", i.\"qtyActual\","
                + " i.\"qtyDiff\" FROM \"StockAdjustment\" a JOIN \"StockAdjustmentItem\" i ON i.\"adjId\" = a.id WHERE a.id = ?")
                .param(adjId).query().singleRow();
        assertThat(adj.get("status")).isEqualTo("pending");
        assertThat(adj.get("recordedOffline")).isEqualTo(true);
        assertThat((BigDecimal) adj.get("qtyActual")).isEqualByComparingTo("5");
        assertThat((BigDecimal) adj.get("qtyDiff")).isEqualByComparingTo("-2");
        assertThat(onHand(p, locA)).isEqualByComparingTo("7"); // nothing posts until approved
    }

    @Test
    @DisplayName("grn.receive: a goods receipt against a purchase order is created and posted")
    void goodsReceiptPosts() {
        String p = product();
        String supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, false, now())").params(supplier, demoOrg, "OS-" + sfx(), "Offline supplier")
                .update();
        String poId = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", locB,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId", p, "description",
                        "Stock", "quantity", 5, "unitPrice", 5)))).get("id").asString();
        String poLine = jdbc.sql("SELECT id FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?").param(poId)
                .query(String.class).single();
        String client = UUID.randomUUID().toString();
        JsonNode r = applied("grn.receive", Map.of("clientId", client, "purchaseOrderId", poId, "warehouseId", locB,
                "lines", List.of(Map.of("purchaseOrderLineId", poLine, "productId", p, "description", "Stock", "quantity",
                        5))));
        String grn = r.get("mapping").get(client).asString();
        Map<String, Object> row = jdbc.sql("SELECT status::text AS status, \"recordedOffline\" FROM \"GoodsReceiptNote\""
                + " WHERE id = ?").param(grn).query().singleRow();
        assertThat(row.get("status")).as(r.toString()).isEqualTo("posted");
        assertThat(row.get("recordedOffline")).isEqualTo(true);
        assertThat(onHand(p, locB)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("stock.count: the variance is counted − on-hand when counted (later movements carry it forward)")
    void countMeasuredWhenCounted() {
        String p = product();
        stockIn(p, locA, 10);
        Instant countedAt = Instant.now();
        sleep(50);
        stockIn(p, locA, 5); // after the count, before it syncs
        JsonNode r = push(op("stock.count", Map.of("locationId", locA, "lines", List.of(Map.of("productId", p,
                "countedQty", 8))), countedAt));
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
        String session = r.get("mapping").get("countId").asString();
        Map<String, Object> line = jdbc.sql("SELECT \"systemQty\", \"countedQty\" FROM \"InventoryCountLine\" WHERE"
                + " \"sessionId\" = ? AND \"productId\" = ?").params(session, p).query().singleRow();
        assertThat((BigDecimal) line.get("systemQty")).isEqualByComparingTo("15");
        // counted 8 when on hand was 10: two missing; the 5 received later are carried forward.
        assertThat((BigDecimal) line.get("countedQty")).isEqualByComparingTo("13");
    }

    @Test
    @DisplayName("warehouse scopes come only when named; a default pull is unchanged")
    void warehouseScopes() {
        String p = product();
        stockIn(p, locA, 4);
        Res named = call("GET", "/sync/pull?scopes=openPurchaseOrders,transfers,lots,serials,uomConversions", null,
                Map.of("X-Device-Token", token));
        assertThat(named.status()).as(named.raw().body()).isEqualTo(200);
        for (String s : List.of("openPurchaseOrders", "transfers", "lots", "serials", "uomConversions")) {
            assertThat(named.body().get("data").has(s)).as(s).isTrue();
        }
        Res all = call("GET", "/sync/pull", null, Map.of("X-Device-Token", token));
        assertThat(all.body().get("data").has("lots")).isFalse();
        assertThat(all.body().get("data").has("openPurchaseOrders")).isFalse();
        Res bad = call("GET", "/sync/pull?scopes=nope", null, Map.of("X-Device-Token", token));
        assertThat(bad.status()).isEqualTo(400);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
