package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.Instant;
import java.util.ArrayList;
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
 * ADR-S083 S5, O0 correction 3: a rejected (dead-lettered) money op that belongs to a shift blocks its close; a
 * supervisor may override with a reason and {@code pos:override}, audited. Pending ops and resolved dead letters do
 * not block.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncCashCloseIT extends ApiTestSupport {

    String token;
    String deviceId;
    String adminId;
    int seq;
    final List<String> registers = new ArrayList<>();

    @BeforeAll
    void fixtures() {
        JsonNode d = ok(201, "POST", "/sync/devices/register", Map.of("name", "Close tablet " + UUID.randomUUID()
                .toString().substring(0, 6), "platform", "web"));
        token = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN ('open',"
                + " 'investigating')").param(deviceId).update();
        forceCloseSessions(registers);
    }

    /** Opens a shift on a fresh register through the device (the session is stamped with the device). */
    String openShift(String actor) {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String register = ok(201, "POST", "/cash-registers", Map.of("code", "CC-" + suffix, "name", "Close till "
                + suffix)).get("id").asString();
        registers.add(register);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", "cc-" + UUID.randomUUID());
        o.put("deviceSeq", ++seq);
        o.put("type", "cash_session.open");
        o.put("actorUserId", actor);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", Map.of("clientId", "s-" + suffix, "cashRegisterId", register, "openingFloat", 0));
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(o)), Map.of("X-Device-Token",
                token));
        assertThat(r.body().get("results").get(0).get("status").asString()).as(r.raw().body()).isEqualTo("applied");
        return jdbc.sql("SELECT id FROM \"CashSession\" WHERE \"cashRegisterId\" = ? AND status = 'open'").param(register)
                .query(String.class).single();
    }

    String deadLetter(String type, String status, Map<String, Object> payload) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SyncOpDeadLetter\" (id, \"organizationId\", \"deviceId\", \"opId\", \"deviceSeq\","
                + " \"opType\", \"actorUserId\", payload, error, \"httpStatus\", status, \"failureCode\")"
                + " VALUES (?, ?, ?, ?, 1, ?, ?, ?::jsonb, 'Refused', 400, ?, 'BUSINESS_RULE')")
                .params(id, demoOrg, deviceId, "cc-dl-" + UUID.randomUUID(), type, adminId,
                        com.inventoryerp.shared.web.EntityJson.toJson(payload), status).update();
        return id;
    }

    Res close(String sessionId, String overrideReason, String bearer) {
        Map<String, Object> body = new LinkedHashMap<>(withUncountedTenders(Map.of("sessionId", sessionId,
                "closingCounted", 0, "pendingSyncCount", 3)));
        if (overrideReason != null) {
            body.put("syncOverrideReason", overrideReason);
        }
        releaseHeldOrders();
        return call("POST", "/cash-sessions/close", body, Map.of("Authorization", "Bearer " + (bearer == null
                ? adminToken : bearer), "Idempotency-Key", UUID.randomUUID().toString()));
    }

    String status(String sessionId) {
        return jdbc.sql("SELECT status::text FROM \"CashSession\" WHERE id = ?").param(sessionId).query(String.class)
                .single();
    }

    void discardAll() {
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN ('open',"
                + " 'investigating')").param(deviceId).update();
    }

    @Test
    @DisplayName("normal close: client-reported pending ops and resolved dead letters do not block")
    void normalClose() {
        discardAll();
        String session = openShift(adminId);
        deadLetter("sale.checkout", "resolved", Map.of("cashSessionId", session));
        deadLetter("tax.upsert", "open", Map.of()); // not a money op
        Res r = close(session, null, null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(status(session)).isEqualTo("closed");
    }

    @Test
    @DisplayName("an open money dead letter of the shift's device blocks the close (409 SYNC_DEAD_LETTERS_OPEN)")
    void blockedClose() {
        discardAll();
        String session = openShift(adminId);
        String dl = deadLetter("sale.checkout", "investigating", Map.of("cashSessionId", "client-id"));
        Res r = close(session, null, null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(409);
        assertThat(r.body().get("code").asString()).isEqualTo("SYNC_DEAD_LETTERS_OPEN");
        assertThat(r.body().get("deadLetterIds").toString()).contains(dl);
        assertThat(status(session)).isEqualTo("open");
        // Resolving it lets the shift close normally.
        ok(201, "POST", "/sync/dead-letters/" + dl + "/resolve", Map.of("status", "discarded", "note",
                "Sale re-rung at the till"));
        assertThat(close(session, null, null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("supervisor override: needs pos:override and a reason, and is audited with the dead letters it passed")
    void supervisorOverride() {
        discardAll();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "CloseCashier-" + role,
                new String[] {"cash_session:open", "cash_session:close", "pos:read", "pos:checkout"}).update();
        String email = "cc-" + UUID.randomUUID() + "@demo.test";
        String cashier = createUser(demoOrg, email, "Close@12345", role);
        String cashierToken = login("DEMO", email, "Close@12345");
        String session = openShift(cashier);
        String dl = deadLetter("sale.refund", "open", Map.of("cashSessionId", session));

        Res noPermission = close(session, "I checked it", cashierToken);
        assertThat(noPermission.status()).as(noPermission.raw().body()).isEqualTo(403);
        assertThat(status(session)).isEqualTo("open");

        String adminSession = openShift(adminId);
        String dl2 = deadLetter("sale.checkout", "open", Map.of("cashSessionId", adminSession));
        Res blank = close(adminSession, "  ", null);
        assertThat(blank.status()).isEqualTo(409);
        Res overridden = close(adminSession, "Refund paid in cash; reconciled against the paper slip", null);
        assertThat(overridden.status()).as(overridden.raw().body()).isEqualTo(200);
        assertThat(status(adminSession)).isEqualTo("closed");
        String audit = jdbc.sql("SELECT \"newValues\"::text FROM \"AuditLog\" WHERE \"entityId\" = ? AND"
                + " \"newValues\"::text LIKE '%sync_dead_letter_override%'").param(adminSession).query(String.class)
                .single();
        assertThat(audit).contains("Refund paid in cash").contains(dl2).contains(dl);
        // The dead letters stay open for follow-up: the override passes the close, it does not resolve them.
        assertThat(jdbc.sql("SELECT status FROM \"SyncOpDeadLetter\" WHERE id = ?").param(dl2).query(String.class)
                .single()).isEqualTo("open");
    }
}
