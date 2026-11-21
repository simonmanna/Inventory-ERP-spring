package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Phase I6 (Android POS integrity), server side: the variant catalog and currency scale a device pulls, an offline
 * sale whose price the device saw differently is kept (owner decision 1), partial refunds addressed by line number
 * with one disposition per op, and the {@code kitchen.void} op for a device-held tab.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncDeviceIntegrityIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    String token;
    String deviceId;
    String adminId;
    String registerId;
    String closeRegisterId;
    String managerPin;
    String warehouse;
    int seq;

    @BeforeAll
    void fixtures() {
        JsonNode device = ok(201, "POST", "/sync/devices/register", Map.of("name", "I6 tablet", "platform", "android"));
        token = device.get("deviceToken").asString();
        deviceId = device.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "I6REG-" + suffix, "name", "I6 till " + suffix))
                .get("id").asString();
        closeRegisterId = ok(201, "POST", "/cash-registers", Map.of("code", "I6CLS-" + suffix, "name",
                "I6 close till " + suffix)).get("id").asString();
        managerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        ok(201, "POST", "/pos/override/pin", Map.of("pin", managerPin));
        warehouse = pinPosWarehouse();
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status = 'open'")
                .param(deviceId).update();
        forceCloseSessions(List.of(registerId, closeRegisterId));
    }

    Map<String, Object> op(String type, Map<String, Object> payload) {
        seq++;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", "op-" + UUID.randomUUID());
        o.put("deviceSeq", seq);
        o.put("type", type);
        o.put("actorUserId", adminId);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(List<Map<String, Object>> ops) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", ops), Map.of("X-Device-Token", token));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    JsonNode pull(String scopes) {
        Res r = call("GET", "/sync/pull?scopes=" + scopes, null, Map.of("X-Device-Token", token));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    String product(String name, String type, int price) {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return ok(201, "POST", "/products", Map.of("code", "I6-" + suffix, "name", name + " " + suffix,
                "productType", type, "trackInventory", "stockable".equals(type), "salesPrice", price, "costPrice", 10))
                .get("id").asString();
    }

    String openShift() {
        return openShift(registerId);
    }

    /** The register's open shift, else one opened through the device; returns its server id. */
    String openShift(String register) {
        String open = jdbc.sql("SELECT id FROM \"CashSession\" WHERE \"cashRegisterId\" = ? AND status::text = 'open'")
                .param(register).query(String.class).optional().orElse(null);
        if (open != null) {
            return open;
        }
        String clientId = "sess-" + UUID.randomUUID();
        JsonNode res = push(List.of(op("cash_session.open", Map.of("clientId", clientId, "cashRegisterId", register,
                "openingFloat", 0))));
        assertThat(res.get("results").get(0).get("status").asString()).as(res.toString()).isEqualTo("applied");
        return res.get("results").get(0).get("mapping").get(clientId).asString();
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(productId, warehouse).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("I6 pull: productVariants (effective price, tombstones), tax inclusivity on products, currency scale")
    void pullsVariantsAndCurrency() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String shirt = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"hasVariants\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'stockable', true, 'AVCO', 40, 100, true, now())").params(shirt, demoOrg, "I6V-" + suffix,
                        "Shirt " + suffix).update();
        String red = UUID.randomUUID().toString();
        String big = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProductVariant\" (id, \"organizationId\", \"productId\", name, barcode, \"combinationKey\","
                + " \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, 'Red', ?, ?, 120, now())").params(red, demoOrg, shirt,
                        "BC-" + suffix, red).update();
        jdbc.sql("INSERT INTO \"ProductVariant\" (id, \"organizationId\", \"productId\", name, \"combinationKey\","
                + " \"priceExtra\", \"updatedAt\") VALUES (?, ?, ?, 'XL', ?, 15, now())").params(big, demoOrg, shirt, big)
                .update();
        JsonNode body = pull("productVariants,products");
        assertThat(body.get("currency").get("code").asString()).isNotBlank();
        assertThat(body.get("currency").has("decimalPlaces")).isTrue();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        body.get("data").get("productVariants").forEach(v -> byId.put(v.get("id").asString(), v));
        assertThat(byId.get(red).get("price").decimalValue()).isEqualByComparingTo("120");
        assertThat(byId.get(red).get("barcode").asString()).isEqualTo("BC-" + suffix);
        assertThat(byId.get(big).get("price").decimalValue()).as("product price + extra").isEqualByComparingTo("115");
        assertThat(byId.get(big).get("productId").asString()).isEqualTo(shirt);
        for (JsonNode p : body.get("data").get("products")) {
            if (p.get("tax") != null && !p.get("tax").isNull()) {
                assertThat(p.get("tax").has("isInclusive")).isTrue();
            }
        }

        String cursor = body.get("cursor").asString();
        jdbc.sql("UPDATE \"ProductVariant\" SET \"deletedAt\" = now(), \"updatedAt\" = now() WHERE id = ?").param(big)
                .update();
        Res delta = call("GET", "/sync/pull?scopes=productVariants&cursor=" + cursor, null,
                Map.of("X-Device-Token", token));
        JsonNode tomb = null;
        for (JsonNode v : delta.body().get("data").get("productVariants")) {
            if (big.equals(v.get("id").asString())) {
                tomb = v;
            }
        }
        assertThat(tomb).as("a deleted variant reaches the device as a tombstone").isNotNull();
        assertThat(tomb.get("deletedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("I6 decision 1: an offline sale priced differently by the device is kept at the catalog price;"
            + " shortfall stays on account, overpay is trimmed off the cash; the shift still closes")
    void offlinePriceDifferenceIsKept() {
        String coffee = product("Coffee", "service", 100);
        // Its own register: a fresh shift, so the counted cash below is this test's alone.
        String session = openShift(closeRegisterId);
        // Device priced it at 80 (stale catalog): collected 160 for a 200 sale.
        JsonNode under = push(List.of(op("sale.checkout", Map.of("clientId", "sale-" + UUID.randomUUID(),
                "cashSessionId", session, "expectedTotal", 160, "lines", List.of(Map.of("productId", coffee,
                        "description", "Coffee", "quantity", 2, "unitPrice", 80)),
                "tenders", List.of(Map.of("method", "cash", "amount", 160))))));
        JsonNode u = under.get("results").get(0);
        assertThat(u.get("status").asString()).as("kept, not refused: " + u).isEqualTo("applied");
        Map<String, Object> inv = jdbc.sql("SELECT \"totalAmount\", \"amountResidual\", \"paymentMode\"::text AS mode,"
                + " notes FROM \"Invoice\" WHERE id = ?").param(u.get("mapping").get("invoiceId").asString()).query()
                .singleRow();
        assertThat((BigDecimal) inv.get("totalAmount")).as("catalog price").isEqualByComparingTo("200");
        assertThat((BigDecimal) inv.get("amountResidual")).as("uncollected part").isEqualByComparingTo("40");
        assertThat(inv.get("mode")).isEqualTo("credit");
        assertThat((String) inv.get("notes")).contains("Offline price difference: device charged 160, catalog price 200");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND \"newValues\"::text LIKE"
                + " '%offline_price_variance%'").param(u.get("mapping").get("invoiceId").asString()).query(Long.class)
                .single()).isEqualTo(1);

        // Device priced it at 130: collected 260 (customer handed 300) for a 200 sale.
        JsonNode over = push(List.of(op("sale.checkout", Map.of("clientId", "sale-" + UUID.randomUUID(),
                "cashSessionId", session, "expectedTotal", 260, "amountTendered", 300, "lines", List.of(Map.of(
                        "productId", coffee, "description", "Coffee", "quantity", 2, "unitPrice", 130)),
                "tenders", List.of(Map.of("method", "cash", "amount", 260))))));
        JsonNode o = over.get("results").get(0);
        assertThat(o.get("status").asString()).as(o.toString()).isEqualTo("applied");
        String overInvoice = o.get("mapping").get("invoiceId").asString();
        assertThat(jdbc.sql("SELECT \"amountResidual\" FROM \"Invoice\" WHERE id = ?").param(overInvoice)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT coalesce(sum(a.amount), 0) FROM \"PaymentAllocation\" a WHERE a.\"invoiceId\" = ?")
                .param(overInvoice).query(BigDecimal.class).single()).isEqualByComparingTo("200");

        // Device and server agree: no note, settled.
        JsonNode exact = push(List.of(op("sale.checkout", Map.of("clientId", "sale-" + UUID.randomUUID(),
                "cashSessionId", session, "expectedTotal", 200, "lines", List.of(Map.of("productId", coffee,
                        "description", "Coffee", "quantity", 2, "unitPrice", 100)),
                "tenders", List.of(Map.of("method", "cash", "amount", 200))))));
        String exactInvoice = exact.get("results").get(0).get("mapping").get("invoiceId").asString();
        assertThat(jdbc.sql("SELECT notes FROM \"Invoice\" WHERE id = ?").param(exactInvoice).query(String.class)
                .optional().orElse(null)).isNull();

        // The shortfall invoice is on account, so it does not block the close (expected cash 160 + 200 + 200).
        releaseHeldOrders();
        // ADR-S083 S5: rejected money ops of this device (other tests of this class) block its shifts' close;
        // a back office resolves them first.
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN ('open',"
                + " 'investigating')").param(deviceId).update();
        JsonNode close = push(List.of(op("cash_session.close", withUncountedTenders(Map.of("sessionId", session,
                "closingCounted", 560)))));
        assertThat(close.get("results").get(0).get("status").asString()).as(close.toString()).isEqualTo("applied");
    }

    @Test
    @DisplayName("I6 refunds: lines addressed by lineNumber, one disposition per op (waste, then restock)")
    void partialRefundByLineNumber() {
        String water = product("Water", "stockable", 50);
        String coffee = product("Coffee", "service", 100);
        TenantContext.run(TenantStore.organization(demoOrg), () -> stock.receive(StockMove.of(water, warehouse,
                BigDecimal.TEN).unitCost(BigDecimal.TEN)));
        String session = openShift();
        String sale = "sale-" + UUID.randomUUID();
        JsonNode res = push(List.of(op("sale.checkout", Map.of("clientId", sale, "cashSessionId", session,
                "expectedTotal", 250, "lines", List.of(
                        Map.of("productId", water, "description", "Water", "quantity", 3, "unitPrice", 50),
                        Map.of("productId", coffee, "description", "Coffee", "quantity", 1, "unitPrice", 100)),
                "tenders", List.of(Map.of("method", "cash", "amount", 250))))));
        assertThat(res.get("results").get(0).get("status").asString()).as(res.toString()).isEqualTo("applied");
        String invoiceId = res.get("results").get(0).get("mapping").get("invoiceId").asString();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> {
            for (String job : jdbc.sql("SELECT id FROM \"StockPostingJob\" WHERE \"invoiceId\" = ?").param(invoiceId)
                    .query(String.class).list()) {
                billing().processStockPostingJob(job);
            }
        });
        assertThat(onHand(water)).isEqualByComparingTo("7");

        JsonNode refunds = push(List.of(
                op("sale.refund", Map.of("invoiceId", invoiceId, "reason", "broken seal", "overrideById", adminId,
                        "overridePin", managerPin, "cashSessionId", session, "stockDisposition", "waste",
                        "lines", List.of(Map.of("lineNumber", 1, "quantity", 1)))),
                op("sale.refund", Map.of("invoiceId", invoiceId, "reason", "changed mind", "overrideById", adminId,
                        "overridePin", managerPin, "cashSessionId", session, "stockDisposition", "restock",
                        "lines", List.of(Map.of("lineNumber", 1, "quantity", 1)))),
                op("sale.refund", Map.of("invoiceId", invoiceId, "reason", "nope", "overrideById", adminId,
                        "overridePin", managerPin, "cashSessionId", session, "stockDisposition", "restock",
                        "lines", List.of(Map.of("lineNumber", 9, "quantity", 1))))));
        JsonNode results = refunds.get("results");
        assertThat(results.get(0).get("status").asString()).as(results.toString()).isEqualTo("applied");
        assertThat(results.get(1).get("status").asString()).as(results.toString()).isEqualTo("applied");
        assertThat(results.get(2).get("status").asString()).isEqualTo("failed");
        assertThat(results.get(2).get("error").asString()).isEqualTo("Refund line is not on this invoice");
        String line1 = jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ? AND \"lineNumber\" = 1")
                .param(invoiceId).query(String.class).single();
        assertThat(jdbc.sql("SELECT \"refundedQty\" FROM \"InvoiceItem\" WHERE id = ?").param(line1)
                .query(BigDecimal.class).single()).isEqualByComparingTo("2");
        assertThat(jdbc.sql("SELECT string_agg(\"stockDisposition\"::text, ',' ORDER BY \"createdAt\") FROM \"PosRefund\""
                + " WHERE \"invoiceId\" = ?").param(invoiceId).query(String.class).single()).isEqualTo("waste,restock");
        assertThat(onHand(water)).as("only the restocked bottle comes back").isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("I6 kitchen.void: a fired line off a device tab is wasted once with a manager approval")
    void kitchenVoidWastesStartedFood() {
        String water = product("Juice", "stockable", 50);
        TenantContext.run(TenantStore.organization(demoOrg), () -> stock.receive(StockMove.of(water, warehouse,
                BigDecimal.TEN).unitCost(BigDecimal.TEN)));
        String session = openShift();
        Map<String, Object> voidOp = op("kitchen.void", Map.of("cashSessionId", session, "tableLabel", "Table 4",
                "reason", "dropped", "overrideById", adminId, "overridePin", managerPin, "lines", List.of(Map.of(
                        "productId", water, "description", "Juice", "quantity", 2))));
        JsonNode first = push(List.of(voidOp));
        assertThat(first.get("results").get(0).get("status").asString()).as(first.toString()).isEqualTo("applied");
        JsonNode again = push(List.of(voidOp));
        assertThat(again.get("results").get(0).get("status").asString()).isEqualTo("replayed");
        assertThat(onHand(water)).isEqualByComparingTo("8");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'pos_kitchen_waste' AND"
                + " \"referenceId\" = ?").param("device:" + voidOp.get("opId") + ":0").query(Long.class).single())
                .isEqualTo(1);

        JsonNode refused = push(List.of(
                op("kitchen.void", Map.of("cashSessionId", session, "reason", "dropped", "lines", List.of(Map.of(
                        "productId", water, "description", "Juice", "quantity", 1)))),
                op("kitchen.void", Map.of("cashSessionId", session, "reason", " ", "overrideById", adminId,
                        "overridePin", managerPin, "lines", List.of(Map.of("productId", water, "quantity", 1)))),
                op("kitchen.void", Map.of("cashSessionId", session, "reason", "dropped", "overrideById", adminId,
                        "overridePin", "00000000", "lines", List.of(Map.of("productId", water, "quantity", 1))))));
        assertThat(refused.get("results").get(0).get("httpStatus").asInt()).isEqualTo(403);
        assertThat(refused.get("results").get(1).get("error").asString()).isEqualTo("A reason is required to void an item");
        assertThat(refused.get("results").get(2).get("status").asString()).isEqualTo("failed");
        assertThat(onHand(water)).as("refused voids waste nothing").isEqualByComparingTo("8");
    }

    @Autowired
    com.inventoryerp.pos.application.PosInvoiceService billingService;

    com.inventoryerp.pos.application.PosInvoiceService billing() {
        return billingService;
    }
}
