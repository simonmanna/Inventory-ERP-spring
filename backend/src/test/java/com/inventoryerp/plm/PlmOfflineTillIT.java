package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Owner decision 2026-11-25 (ADR-S086 §6): the PLM till rule applies to counter checkouts online and to sales
 * replayed from an offline till. A refused replay commits nothing, is held as a {@code LIFECYCLE_BLOCKED} dead letter
 * (not retryable by the device), and a supervisor retries it under its original operation id once the product may be
 * sold — the server checks the rule again, and the sale posts exactly once.
 */
class PlmOfflineTillIT extends PlmTestSupport {

    static final String PLM = "/manufacturing/plm";
    static final String BLOCKED = "LIFECYCLE_BLOCKED";

    String token;
    String deviceId;
    String registerId;
    int seq;

    @BeforeEach
    void shippedDefaults() {
        jdbc.sql("DELETE FROM \"PlmSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
    }

    @AfterAll
    void cleanup() {
        if (deviceId != null) {
            jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status = 'open'")
                    .param(deviceId).update();
        }
        if (registerId != null) {
            forceCloseSessions(List.of(registerId));
        }
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state))
                .get("id").asString();
    }

    String code(String productId) {
        return jdbc.sql("SELECT code FROM \"Product\" WHERE id = ?").param(productId).query(String.class).single();
    }

    static Map<String, Object> tillLine(String productId, int qty) {
        return Map.of("productId", productId, "description", "Item", "quantity", qty, "unitPrice", 15);
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

    int invoicesWithProvisional(String provisional) {
        return jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"organizationId\" = ? AND \"provisionalNumber\" = ?")
                .params(demoOrg, provisional).query(Integer.class).single();
    }

    @Test
    @DisplayName("till: an unreleased product is refused online (LIFECYCLE_BLOCKED) and on offline replay; the held sale"
            + " is retried by a supervisor once the product is released, and posts exactly once")
    void offlineReplayIsChecked() {
        String suffix = suffix();
        JsonNode device = ok(201, "POST", "/sync/devices/register", Map.of("name", "PLM till " + suffix, "platform",
                "android"));
        token = device.get("deviceToken").asString();
        deviceId = device.get("id").asString();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "PREG-" + suffix, "name", "PLM till " + suffix))
                .get("id").asString();

        String design = product("XOFFLINE");
        String item = register(design, "design");
        String refusal = "Product " + code(design) + " is in design and cannot be sold at the point of sale.";

        // Online counter checkout: refused before anything is written, with the machine code.
        String session = "sess-" + UUID.randomUUID();
        JsonNode opened = push(List.of(op("cash_session.open", Map.of("clientId", session, "cashRegisterId",
                registerId, "openingFloat", 0))));
        assertThat(opened.get("results").get(0).get("status").asString()).isEqualTo("applied");
        String sessionId = opened.get("results").get(0).get("mapping").get(session).asString();
        Res online = call("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines",
                List.of(tillLine(design, 1)), "tenders", List.of(Map.of("method", "cash", "amount", 15))),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(online.status()).as(online.raw().body()).isEqualTo(400);
        assertThat(online.body().get("message").asString()).isEqualTo(refusal);
        assertThat(online.body().get("code").asString()).isEqualTo(BLOCKED);

        // Offline replay: refused too — rolled back and held as a dead letter the device may not retry itself.
        String provisional = "PLM-" + suffix;
        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(op("sale.checkout", Map.of("clientId", "sale-" + UUID.randomUUID(), "provisionalNumber", provisional,
                "cashSessionId", session, "lines", List.of(tillLine(design, 1)), "tenders",
                List.of(Map.of("method", "cash", "amount", 15)), "amountTendered", 15)));
        JsonNode sale = push(ops).get("results").get(0);
        assertThat(sale.get("status").asString()).isEqualTo("failed");
        assertThat(sale.get("code").asString()).isEqualTo(BLOCKED);
        assertThat(sale.get("retryable").asBoolean()).isFalse();
        assertThat(sale.get("error").asString()).isEqualTo(refusal);
        assertThat(invoicesWithProvisional(provisional)).isZero();
        String opId = (String) ops.getFirst().get("opId");
        Map<String, Object> dl = jdbc.sql("SELECT id, status, \"failureCode\", retryable FROM \"SyncOpDeadLetter\""
                + " WHERE \"deviceId\" = ? AND \"opId\" = ?").params(deviceId, opId).query().singleRow();
        assertThat(dl.get("status")).isEqualTo("open");
        assertThat(dl.get("failureCode")).isEqualTo(BLOCKED);
        assertThat(dl.get("retryable")).isEqualTo(false);
        String dlId = (String) dl.get("id");

        // A supervisor retry while the product is still unreleased is refused again by the server: nothing posts.
        ok(201, "POST", "/sync/dead-letters/" + dlId + "/retry", Map.of("note", "trying before release"));
        assertThat(invoicesWithProvisional(provisional)).isZero();
        assertThat(jdbc.sql("SELECT status FROM \"SyncOpDeadLetter\" WHERE id = ?").param(dlId).query(String.class)
                .single()).isEqualTo("open");

        // Released: the retry runs the stored sale under its original opId and it posts once.
        jdbc.sql("UPDATE \"PlmItem\" SET \"lifecycleState\" = 'released' WHERE id = ?").param(item).update();
        JsonNode retried = ok(201, "POST", "/sync/dead-letters/" + dlId + "/retry", Map.of("note", "released in ECO"));
        assertThat(retried.get("status").asString()).isEqualTo("retried");
        assertThat(invoicesWithProvisional(provisional)).isEqualTo(1);
        Res again = as("POST", "/sync/dead-letters/" + dlId + "/retry", Map.of("note", "twice"));
        assertThat(again.status()).isEqualTo(400);
        assertThat(invoicesWithProvisional(provisional)).isEqualTo(1);
        // The device pushing the same op again replays the stored result: still one sale.
        JsonNode replay = push(ops).get("results").get(0);
        assertThat(replay.get("status").asString()).isEqualTo("replayed");
        assertThat(invoicesWithProvisional(provisional)).isEqualTo(1);
        releaseHeldOrders();
    }
}
