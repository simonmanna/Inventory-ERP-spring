package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Offline sync on Testcontainers Postgres: device registry, device-token transport auth, pull scopes and cursor
 * (incl. the {@code sync-staff-revocation.spec.ts} rules), push ops through the online services with per-op
 * idempotency, client-id mapping, 424 dependencies and dead letters (ports {@code sync-cash-inventory.spec.ts}
 * against the shared DEMO org).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncIT extends ApiTestSupport {

    String token;
    String deviceId;
    String adminId;
    String productId;
    String registerId;
    int seq;

    @BeforeAll
    void fixtures() {
        JsonNode device = ok(201, "POST", "/sync/devices/register", Map.of("name", "Counter tablet", "platform",
                "android"));
        token = device.get("deviceToken").asString();
        deviceId = device.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        productId = ok(201, "POST", "/products", Map.of("code", "SYNC-" + suffix, "name", "Sync coffee " + suffix,
                "productType", "service", "salesPrice", 100, "costPrice", 0)).get("id").asString();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "SREG-" + suffix, "name", "Sync till " + suffix))
                .get("id").asString();
        pinPosWarehouse();
    }

    /** Open dead letters block every later shift close in the shared DEMO org — discard ours. */
    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status = 'open'")
                .param(deviceId).update();
        forceCloseSessions(List.of(registerId));
    }

    Res device(String method, String path, Object body) {
        return call(method, path, body, Map.of("X-Device-Token", token));
    }

    Map<String, Object> op(String type, Map<String, Object> payload) {
        return op(type, payload, adminId);
    }

    Map<String, Object> op(String type, Map<String, Object> payload, String actor) {
        seq++;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", "op-" + UUID.randomUUID());
        o.put("deviceSeq", seq);
        o.put("type", type);
        o.put("actorUserId", actor);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(List<Map<String, Object>> ops) {
        Res r = device("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", ops));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    @Test
    @DisplayName("device registry: one-time token stored as sha256, list, revoke kills the transport")
    void devices() {
        JsonNode d = ok(201, "POST", "/sync/devices/register", Map.of("name", "Spare phone"));
        assertThat(d.get("platform").asString()).isEqualTo("android");
        assertThat(d.get("prefix").asString()).startsWith("D");
        String t = d.get("deviceToken").asString();
        assertThat(jdbc.sql("SELECT \"tokenHash\" FROM \"PosDevice\" WHERE id = ?").param(d.get("id").asString())
                .query(String.class).single()).hasSize(64).isNotEqualTo(t);
        Res blank = as("POST", "/sync/devices/register", Map.of("name", "  "));
        assertThat(blank.body().get("message").asString()).isEqualTo("A device name is required");
        assertThat(ok(200, "GET", "/sync/devices", null).toString()).contains(d.get("id").asString());
        assertThat(call("GET", "/sync/pull?scopes=taxes", null, Map.of("X-Device-Token", t)).status()).isEqualTo(200);
        ok(201, "POST", "/sync/devices/" + d.get("id").asString() + "/revoke", null);
        Res revoked = call("GET", "/sync/pull?scopes=taxes", null, Map.of("X-Device-Token", t));
        assertThat(revoked.status()).isEqualTo(401);
        assertThat(revoked.body().get("message").asString()).isEqualTo("A valid X-Device-Token is required");
        Res none = call("POST", "/sync/push", Map.of("deviceId", "x", "ops", List.of()), (String) null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(ok(201, "POST", "/sync/devices/" + d.get("id").asString() + "/revoke", null).get("revokedAt").isNull())
                .isFalse();
    }

    @Test
    @DisplayName("pull: all scopes, opaque cursor with watermarks, unknown scope and malformed cursor 400")
    void pull() {
        Res all = device("GET", "/sync/pull", null);
        assertThat(all.status()).as(all.raw().body()).isEqualTo(200);
        JsonNode data = all.body().get("data");
        for (String s : List.of("menuItems", "menuCategories", "modifierGroups", "taxes", "posTables", "cashRegisters",
                "staff", "settings", "products", "productCategories", "productPackagings", "partners", "reservations",
                "conversations", "messages", "paymentMethods", "ledgerAccounts", "expenseCategories", "stockLocations",
                "stockLevels", "suppliers")) {
            assertThat(data.has(s)).as(s).isTrue();
        }
        String cursor = all.body().get("cursor").asString();
        JsonNode decoded = mapper.readTree(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        assertThat(decoded.get("products").asString()).isEqualTo(all.body().get("serverTime").asString());
        assertThat(decoded.get("messagesSeq").asString()).isNotEmpty();
        JsonNode products = data.get("products");
        assertThat(products.toString()).contains(productId);
        JsonNode drawer = null;
        for (JsonNode a : data.get("ledgerAccounts")) {
            if (a.get("roles").toString().contains("drawer")) {
                drawer = a;
            }
        }
        assertThat(drawer).isNotNull();
        // The 5 s overlap re-sends rows committed around the watermark; a watermark in the future returns nothing.
        JsonNode delta = device("GET", "/sync/pull?scopes=products&cursor=" + cursor, null).body();
        assertThat(delta.get("data").has("taxes")).isFalse();
        String future = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"products\":\""
                + Instant.now().plusSeconds(3600) + "\"}").getBytes(StandardCharsets.UTF_8));
        assertThat(device("GET", "/sync/pull?scopes=products&cursor=" + future, null).body().get("data").get("products"))
                .isEmpty();
        Res unknown = device("GET", "/sync/pull?scopes=products,nope", null);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body().get("message").asString()).isEqualTo("Unknown sync scope(s): nope");
        Res bad = device("GET", "/sync/pull?cursor=!!!*", null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("message").asString())
                .isEqualTo("Malformed sync cursor — pull from scratch (omit the cursor)");
    }

    @Test
    @DisplayName("staff scope: revoked and deleted accounts arrive as tombstones without a usable PIN hash")
    void staffRevocation() {
        String hash = "$2b$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV";
        String active = createUser(demoOrg, "sync-a-" + UUID.randomUUID() + "@demo.test", "Sync@12345", null);
        String revoked = createUser(demoOrg, "sync-r-" + UUID.randomUUID() + "@demo.test", "Sync@12345", null);
        String deleted = createUser(demoOrg, "sync-d-" + UUID.randomUUID() + "@demo.test", "Sync@12345", null);
        jdbc.sql("UPDATE \"User\" SET \"pinHash\" = ? WHERE id IN (?, ?, ?)").params(hash, active, revoked, deleted)
                .update();
        String cursor = device("GET", "/sync/pull?scopes=staff", null).body().get("cursor").asString();
        jdbc.sql("UPDATE \"User\" SET \"isActive\" = false, \"updatedAt\" = now() + interval '10 seconds' WHERE id = ?")
                .param(revoked).update();
        jdbc.sql("UPDATE \"User\" SET \"deletedAt\" = now(), \"isActive\" = false, \"updatedAt\" = now()"
                + " + interval '10 seconds' WHERE id = ?").param(deleted).update();
        JsonNode delta = device("GET", "/sync/pull?scopes=staff&cursor=" + cursor, null).body().get("data").get("staff");
        Map<String, JsonNode> byId = new HashMap<>();
        delta.forEach(r -> byId.put(r.get("id").asString(), r));
        assertThat(byId.get(revoked).get("isActive").asBoolean()).isFalse();
        assertThat(byId.get(revoked).get("pinHash").isNull()).isTrue();
        assertThat(byId.get(deleted).get("deletedAt").isNull()).isFalse();
        assertThat(byId.get(deleted).get("pinHash").isNull()).isTrue();
        JsonNode full = device("GET", "/sync/pull?scopes=staff", null).body().get("data").get("staff");
        JsonNode a = null;
        for (JsonNode r : full) {
            assertThat(r.has("passwordHash")).isFalse();
            assertThat(r.has("mfaSecret")).isFalse();
            if (r.get("id").asString().equals(active)) {
                a = r;
            }
        }
        assertThat(a.get("pinHash").asString()).isEqualTo(hash);
        assertThat(a.get("roles").isArray()).isTrue();
    }

    @Test
    @DisplayName("push: shift open → sale (provisional → final numbers) → pay-out → close; replay; 424; dead letters")
    void shiftAndSale() {
        String session = "sess-" + UUID.randomUUID();
        String sale = "sale-" + UUID.randomUUID();
        String customer = UUID.randomUUID().toString();
        String expenseAccount = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ?"
                + " AND key = 'default_expense' AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(op("cash_session.open", Map.of("clientId", session, "cashRegisterId", registerId, "openingFloat", 0)));
        ops.add(op("customer.upsert", Map.of("id", customer, "name", "Offline Ann", "phone", "+256700", "loyaltyPoints", 5)));
        ops.add(op("sale.checkout", Map.of("clientId", sale, "provisionalNumber", "D1-000001", "cashSessionId", session,
                "partnerId", customer, "lines", List.of(Map.of("productId", productId, "description", "Coffee",
                        "quantity", 3, "unitPrice", 100)), "tenders", List.of(Map.of("method", "cash", "amount", 300)),
                "amountTendered", 500)));
        Map<String, Object> close = withUncountedTenders(Map.of("sessionId", session, "closingCounted", 300));
        releaseHeldOrders();
        ops.add(op("cash_session.close", close));
        JsonNode res = push(ops);
        JsonNode results = res.get("results");
        for (JsonNode r : results) {
            assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
            assertThat(r.get("error").isNull()).isTrue();
        }
        assertThat(res.get("lastPushSeq").asInt()).isEqualTo(seq);
        JsonNode saleResult = results.get(2);
        assertThat(saleResult.get("finalNumbers").get("invoiceNumber").asString()).startsWith("INV-");
        String invoiceId = saleResult.get("mapping").get("invoiceId").asString();
        assertThat(jdbc.sql("SELECT \"provisionalNumber\" FROM \"Invoice\" WHERE id = ?").param(invoiceId)
                .query(String.class).single()).isEqualTo("D1-000001");
        assertThat(jdbc.sql("SELECT \"deviceId\" FROM \"Invoice\" WHERE id = ?").param(invoiceId).query(String.class)
                .single()).isEqualTo(deviceId);
        assertThat(jdbc.sql("SELECT \"customFields\"->>'loyaltyPoints' FROM \"Partner\" WHERE id = ?").param(customer)
                .query(String.class).single()).isEqualTo("5");
        assertThat(jdbc.sql("SELECT status::text FROM \"CashSession\" WHERE \"cashRegisterId\" = ? ORDER BY \"openedAt\""
                + " DESC LIMIT 1").param(registerId).query(String.class).single()).isEqualTo("closed");
        assertThat(jdbc.sql("SELECT \"lastPushSeq\" FROM \"PosDevice\" WHERE id = ?").param(deviceId)
                .query(Integer.class).single()).isEqualTo(seq);

        // A re-pushed batch replays: nothing posts twice.
        JsonNode replay = push(ops);
        assertThat(replay.get("results").get(2).get("status").asString()).isEqualTo("replayed");
        assertThat(replay.get("results").get(2).get("mapping").get("invoiceId").asString()).isEqualTo(invoiceId);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"provisionalNumber\" = 'D1-000001' AND"
                + " \"organizationId\" = ?").param(demoOrg).query(Long.class).single()).isEqualTo(1);

        // A failed open makes the dependent sale fail fast with 424; both dead-letter.
        String badSession = "sess-" + UUID.randomUUID();
        JsonNode broken = push(List.of(
                op("cash_session.open", Map.of("clientId", badSession, "cashRegisterId", UUID.randomUUID().toString(),
                        "openingFloat", 0)),
                op("sale.checkout", Map.of("clientId", "sale-" + UUID.randomUUID(), "cashSessionId", badSession,
                        "lines", List.of(Map.of("productId", productId, "description", "Coffee", "quantity", 1,
                                "unitPrice", 100)), "tenders", List.of(Map.of("method", "cash", "amount", 100)))),
                op("cash_session.movement", Map.of("sessionId", UUID.randomUUID().toString(), "movementType", "pay_out",
                        "amount", 10, "reason", "soap", "counterpartAccountId", expenseAccount))));
        assertThat(broken.get("results").get(0).get("status").asString()).isEqualTo("failed");
        assertThat(broken.get("results").get(1).get("httpStatus").asInt()).isEqualTo(424);
        assertThat(broken.get("results").get(1).get("error").asString())
                .isEqualTo("Depends on failed op (clientId " + badSession + ")");
        JsonNode counts = ok(200, "GET", "/sync/dead-letters/counts", null);
        assertThat(counts.get("open").asInt()).isGreaterThanOrEqualTo(3);
        JsonNode letters = ok(200, "GET", "/sync/dead-letters", null);
        assertThat(letters.get(0).get("deviceName").asString()).isEqualTo("Counter tablet");
        String letterId = letters.get(0).get("id").asString();
        Res noNote = as("POST", "/sync/dead-letters/" + letterId + "/resolve", Map.of("status", "discarded", "note", " "));
        assertThat(noNote.body().get("message").asString())
                .isEqualTo("A note is required — record what happened to this sale");
        JsonNode resolved = ok(201, "POST", "/sync/dead-letters/" + letterId + "/resolve", Map.of("status", "discarded",
                "note", "Test op"));
        assertThat(resolved.get("status").asString()).isEqualTo("discarded");
        Res twice = as("POST", "/sync/dead-letters/" + letterId + "/resolve", Map.of("status", "resolved", "note", "x"));
        assertThat(twice.body().get("message").asString()).isEqualTo("Already discarded");
    }

    @Test
    @DisplayName("push: unknown actor 403, master data upserts, pos.mode whitelist, PIN change of own account only")
    void masterData() {
        JsonNode unknown = push(List.of(op("tax.upsert", Map.of("id", UUID.randomUUID().toString(), "name", "X"),
                UUID.randomUUID().toString())));
        assertThat(unknown.get("results").get(0).get("httpStatus").asInt()).isEqualTo(403);
        assertThat(unknown.get("results").get(0).get("error").asString()).startsWith("Unknown or inactive actorUserId");
        String cat = UUID.randomUUID().toString();
        String group = UUID.randomUUID().toString();
        String item = UUID.randomUUID().toString();
        String table = UUID.randomUUID().toString();
        JsonNode res = push(List.of(
                op("menuCategory.upsert", Map.of("id", cat, "name", "Offline drinks")),
                op("modifierGroup.upsert", Map.of("id", group, "name", "Milk", "modifiers",
                        List.of(Map.of("id", UUID.randomUUID().toString(), "name", "Oat", "priceDelta", 500)))),
                op("menuItem.upsert", Map.of("id", item, "name", "Flat white", "categoryId", cat, "basePrice", 9000,
                        "variants", List.of(Map.of("id", UUID.randomUUID().toString(), "name", "Large", "price", 11000)),
                        "modifierGroupIds", List.of(group))),
                op("posTable.upsert", Map.of("id", table, "number", 77, "seats", 4)),
                op("setting.set", Map.of("key", "pos.colour", "value", "red")),
                op("staff.pinChange", Map.of("userId", UUID.randomUUID().toString(), "newPinHash", "$2b$x"))));
        JsonNode r = res.get("results");
        for (int i = 0; i < 4; i++) {
            assertThat(r.get(i).get("status").asString()).as(r.get(i).toString()).isEqualTo("applied");
        }
        assertThat(r.get(4).get("error").asString()).isEqualTo("Device may not set setting 'pos.colour'");
        assertThat(r.get(5).get("httpStatus").asInt()).isEqualTo(403);
        JsonNode pulled = device("GET", "/sync/pull?scopes=menuItems", null).body().get("data").get("menuItems");
        JsonNode mine = null;
        for (JsonNode m : pulled) {
            if (m.get("id").asString().equals(item)) {
                mine = m;
            }
        }
        assertThat(mine.get("variants")).hasSize(1);
        assertThat(mine.get("modifierGroups").get(0).get("modifierGroup").get("modifiers")).hasSize(1);
        assertThat(jdbc.sql("SELECT name FROM \"PosTable\" WHERE id = ?").param(table).query(String.class).single())
                .isEqualTo("77");
        push(List.of(op("menuItem.delete", Map.of("id", item))));
        assertThat(jdbc.sql("SELECT \"isAvailable\" FROM \"MenuItem\" WHERE id = ?").param(item).query(Boolean.class)
                .single()).isFalse();
    }

    @Test
    @DisplayName("push: stock in / out with approver PIN, wrong PIN dead-letters, spot count books the variance")
    void stock() {
        String location = pinPosWarehouse();
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String water = ok(201, "POST", "/products", Map.of("code", "SW-" + suffix, "name", "Water " + suffix,
                "productType", "stockable", "trackInventory", true, "costPrice", 40, "salesPrice", 100)).get("id").asString();
        String managerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        ok(201, "POST", "/pos/override/pin", Map.of("pin", managerPin));
        JsonNode in = push(List.of(op("stock.in", Map.of("locationId", location, "approvedById", adminId,
                "approverPin", managerPin, "notes", "Delivery", "items", List.of(Map.of("productId", water,
                        "quantity", 10, "unitCost", 40))))));
        assertThat(in.get("results").get(0).get("status").asString()).as(in.toString()).isEqualTo("applied");
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "SyncCashier-" + role,
                new String[] {"pos:read", "pos:checkout"}).update();
        String cashier = createUser(demoOrg, "sync-c-" + UUID.randomUUID() + "@demo.test", "Sync@12345", role);
        JsonNode wrong = push(List.of(op("stock.out", Map.of("locationId", location, "responsibleById", cashier,
                "approvedById", adminId, "approverPin", "00000000", "notes", "Waste",
                "items", List.of(Map.of("productId", water, "quantity", 2))), cashier)));
        assertThat(wrong.get("results").get(0).get("status").asString()).isEqualTo("failed");
        JsonNode count = push(List.of(op("stock.count", Map.of("locationId", location, "reason", "Breakage",
                "lines", List.of(Map.of("productId", water, "countedQty", 7))))));
        assertThat(count.get("results").get(0).get("status").asString()).as(count.toString()).isEqualTo("applied");
        BigDecimal onHand = jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(water, location).query(BigDecimal.class).single();
        assertThat(onHand).isEqualByComparingTo("7");
        JsonNode levels = device("GET", "/sync/pull?scopes=stockLevels", null).body().get("data").get("stockLevels");
        assertThat(levels.toString()).contains(water);
    }

    @Test
    @DisplayName("push validation answers class-validator messages after the device guard")
    void validation() {
        Res bad = device("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(Map.of("opId", "x",
                "deviceSeq", 0, "type", "nope", "actorUserId", adminId, "occurredAt", "yesterday", "payload", Map.of()))));
        assertThat(bad.status()).isEqualTo(400);
        String messages = bad.body().get("message").toString();
        assertThat(messages).contains("ops.0.deviceSeq must not be less than 1");
        assertThat(messages).contains("ops.0.occurredAt must be a valid ISO 8601 date string");
        assertThat(messages).contains("ops.0.type must be one of the following values: cash_session.open");
        Res empty = device("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of()));
        assertThat(empty.body().get("message").toString()).contains("ops must contain at least 1 elements");
    }

    @Test
    @DisplayName("authorization matrix: 8 routes (pull / push are public, device-authenticated)")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.sync")).isEqualTo(8);
    }
}
