package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
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
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 O2: a client-minted reference resolves even when the op that created it was applied in an EARLIER push
 * batch (the web client never rewrites a sent payload, so the server resolves through the applied-op ledger). Same
 * organization and device only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncCrossBatchIT extends ApiTestSupport {

    String token;
    String deviceId;
    String otherToken;
    String otherDeviceId;
    String adminId;
    String registerId;
    String managerPin;
    int seq;
    int otherSeq;

    @BeforeAll
    void fixtures() {
        JsonNode d = ok(201, "POST", "/sync/devices/register", Map.of("name", "Cross-batch tablet " + UUID.randomUUID()
                .toString().substring(0, 6), "platform", "web"));
        token = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
        JsonNode o = ok(201, "POST", "/sync/devices/register", Map.of("name", "Cross-batch other " + UUID.randomUUID()
                .toString().substring(0, 6), "platform", "web"));
        otherToken = o.get("deviceToken").asString();
        otherDeviceId = o.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "XB-" + suffix, "name", "Cross-batch till "
                + suffix)).get("id").asString();
        managerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        ok(201, "POST", "/pos/override/pin", Map.of("pin", managerPin));
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" IN (?, ?) AND status IN"
                + " ('open', 'investigating')").params(deviceId, otherDeviceId).update();
        forceCloseSessions(List.of(registerId));
    }

    Map<String, Object> op(String opId, String type, Map<String, Object> payload, boolean other) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", opId);
        o.put("deviceSeq", other ? ++otherSeq : ++seq);
        o.put("type", type);
        o.put("actorUserId", adminId);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(Map<String, Object> op, boolean other) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", other ? otherDeviceId : deviceId, "ops", List.of(op)),
                Map.of("X-Device-Token", other ? otherToken : token));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results").get(0);
    }

    String product() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return ok(201, "POST", "/products", Map.of("code", "XB-" + suffix, "name", "Cross-batch tea " + suffix,
                "productType", "service", "trackInventory", false, "salesPrice", 50, "costPrice", 10)).get("id")
                .asString();
    }

    @Test
    @DisplayName("offline shift-open, then (separate batches) a sale in it and a void of that sale: all resolve")
    void referencesResolveAcrossBatches() {
        String sessionClient = UUID.randomUUID().toString();
        JsonNode open = push(op(sessionClient, "cash_session.open", Map.of("clientId", sessionClient, "cashRegisterId",
                registerId, "openingFloat", 0), false), false);
        assertThat(open.get("status").asString()).as(open.toString()).isEqualTo("applied");
        String sessionId = open.get("mapping").get(sessionClient).asString();

        String tea = product();
        String saleOp = UUID.randomUUID().toString();
        JsonNode sale = push(op(saleOp, "sale.checkout", Map.of("clientId", saleOp, "cashSessionId", sessionClient,
                "expectedTotal", 50, "lines", List.of(Map.of("productId", tea, "description", "Tea", "quantity", 1,
                        "unitPrice", 50)), "tenders", List.of(Map.of("method", "cash", "amount", 50))), false), false);
        assertThat(sale.get("status").asString()).as(sale.toString()).isEqualTo("applied");
        String invoiceId = sale.get("mapping").get("invoiceId").asString();
        assertThat(jdbc.sql("SELECT \"cashSessionId\" FROM \"Invoice\" WHERE id = ?").param(invoiceId).query(String.class)
                .single()).as("the sale landed in the shift opened in the earlier batch").isEqualTo(sessionId);

        JsonNode voided = push(op(UUID.randomUUID().toString(), "sale.void", Map.of("invoiceId", saleOp, "reason",
                "Wrong item", "cashSessionId", sessionClient, "overrideById", adminId, "overridePin", managerPin,
                "stockDisposition", "no_return"), false), false);
        assertThat(voided.get("status").asString()).as(voided.toString()).isEqualTo("applied");
        assertThat(voided.get("mapping").get("invoiceId").asString()).isEqualTo(invoiceId);
    }

    @Test
    @DisplayName("another device cannot resolve this device's client ids")
    void otherDeviceDoesNotResolve() {
        String sessionClient = UUID.randomUUID().toString();
        String register = ok(201, "POST", "/cash-registers", Map.of("code", "XO-" + UUID.randomUUID().toString()
                .substring(0, 6).toUpperCase(), "name", "Cross-batch other till")).get("id").asString();
        JsonNode open = push(op(sessionClient, "cash_session.open", Map.of("clientId", sessionClient, "cashRegisterId",
                register, "openingFloat", 0), false), false);
        assertThat(open.get("status").asString()).isEqualTo("applied");
        JsonNode movement = push(op(UUID.randomUUID().toString(), "cash_session.movement", Map.of("sessionId",
                sessionClient, "movementType", "pay_in", "amount", 5, "reason", "Float top-up"), true), true);
        assertThat(movement.get("status").asString()).as(movement.toString()).isEqualTo("failed");
        forceCloseSessions(List.of(register));
    }

    Res restClose(String sessionId, int counted) {
        releaseHeldOrders();
        return call("POST", "/cash-sessions/close", withUncountedTenders(Map.of("sessionId", sessionId, "closingCounted",
                counted)), Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
    }

    Map<String, Object> closed(String sessionId) {
        return jdbc.sql("SELECT status::text AS status, \"closingExpected\", \"closingCounted\" FROM \"CashSession\""
                + " WHERE id = ?").param(sessionId).query().singleRow();
    }

    JsonNode sale(String sessionRef, String product, int amount) {
        String saleOp = UUID.randomUUID().toString();
        JsonNode r = push(op(saleOp, "sale.checkout", Map.of("clientId", saleOp, "cashSessionId", sessionRef,
                "expectedTotal", amount, "lines", List.of(Map.of("productId", product, "description", "Tea", "quantity", 1,
                        "unitPrice", amount)), "tenders", List.of(Map.of("method", "cash", "amount", amount))), false), false);
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
        return r;
    }

    @Test
    @DisplayName("shift recovery: opened offline + closed online, and opened online + closed offline, both reconcile")
    void shiftRecoveryBothWays() {
        forceCloseSessions(List.of(registerId)); // one open shift per cashier: start clean
        String tea = product();
        // Opened offline (op), a sale offline, closed online over REST with the server id.
        String reg1 = ok(201, "POST", "/cash-registers", Map.of("code", "XR1-" + UUID.randomUUID().toString().substring(0, 6)
                .toUpperCase(), "name", "Recovery till 1")).get("id").asString();
        String client = UUID.randomUUID().toString();
        JsonNode open = push(op(client, "cash_session.open", Map.of("clientId", client, "cashRegisterId", reg1,
                "openingFloat", 0), false), false);
        assertThat(open.get("status").asString()).as(open.toString()).isEqualTo("applied");
        String s1 = open.get("mapping").get(client).asString();
        sale(client, tea, 50);
        Res rest = restClose(s1, 50);
        assertThat(rest.status()).as(rest.raw().body()).isEqualTo(200);
        assertThat(closed(s1).get("status")).isEqualTo("closed");
        assertThat((BigDecimal) closed(s1).get("closingExpected")).isEqualByComparingTo("50");

        // Opened online over REST, a sale offline, closed offline (op).
        String reg2 = ok(201, "POST", "/cash-registers", Map.of("code", "XR2-" + UUID.randomUUID().toString().substring(0, 6)
                .toUpperCase(), "name", "Recovery till 2")).get("id").asString();
        Res opened = call("POST", "/cash-sessions/open", Map.of("cashRegisterId", reg2, "openingFloat", 0), Map.of(
                "Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(opened.status()).as(opened.raw().body()).isEqualTo(200);
        String s2 = opened.body().get("id").asString();
        sale(s2, tea, 40);
        releaseHeldOrders();
        JsonNode close = push(op(UUID.randomUUID().toString(), "cash_session.close", withUncountedTenders(Map.of("sessionId",
                s2, "closingCounted", 40)), false), false);
        assertThat(close.get("status").asString()).as(close.toString()).isEqualTo("applied");
        assertThat(closed(s2).get("status")).isEqualTo("closed");
        assertThat((BigDecimal) closed(s2).get("closingExpected")).isEqualByComparingTo("40");
        forceCloseSessions(List.of(reg1, reg2));
    }
}
