package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * Cash-drawer openings (LP2b): {@code POST /pos/cash-drawer/openings} records with idempotent replay, needs its
 * permission and the caller's own open shift for a no-sale, stays inside the organization, is pushable offline as
 * {@code cash_drawer.open}, and reaches the loss-prevention ledger as the {@code cash_drawer.opened} event C11 counts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PosCashDrawerIT extends ApiTestSupport {

    static final String PATH = "/pos/cash-drawer/openings";

    String registerId;
    String cashierId;
    String cashier;
    String colleagueId;
    String colleague;
    String sessionId;
    String openingId;

    @BeforeAll
    void fixtures() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Drawer-" + role,
                        new String[] {"cash_session:open", "cash_session:read", "pos:access"}).update();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        cashierId = createUser(demoOrg, "drawer-a-" + suffix + "@demo.test", "Drawer@12345", role);
        colleagueId = createUser(demoOrg, "drawer-b-" + suffix + "@demo.test", "Drawer@12345", role);
        cashier = login("DEMO", "drawer-a-" + suffix + "@demo.test", "Drawer@12345");
        colleague = login("DEMO", "drawer-b-" + suffix + "@demo.test", "Drawer@12345");
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "CDO-" + suffix.toUpperCase(), "name",
                "Drawer till " + suffix)).get("id").asString();
        Res opened = keyed(cashier, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId,
                "openingFloat", 0), UUID.randomUUID().toString());
        assertThat(opened.status()).as(opened.raw().body()).isEqualTo(200);
        sessionId = opened.body().get("id").asString();
    }

    @AfterAll
    void cleanup() {
        forceCloseSessions(List.of(registerId));
    }

    Res keyed(String token, String method, String path, Object body, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        if (key != null) {
            h.put("Idempotency-Key", key);
        } else {
            h.put(NO_IDEMPOTENCY_KEY, "1");
        }
        return call(method, path, body, h);
    }

    long openings(String session) {
        return jdbc.sql("SELECT count(*) FROM \"CashDrawerOpening\" WHERE \"cashSessionId\" = ?").param(session)
                .query(Long.class).single();
    }

    @Test
    @Order(1)
    @DisplayName("a no-sale opening is recorded on the caller's shift and replayed by its Idempotency-Key")
    void recordAndReplay() {
        Map<String, Object> body = Map.of("reason", "no_sale", "note", "Change for the coffee machine");
        Res noKey = keyed(cashier, "POST", PATH, body, null);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body().get("message").asString())
                .isEqualTo("An Idempotency-Key header is required for this operation so a retry can never charge twice.");

        String key = UUID.randomUUID().toString();
        Res first = keyed(cashier, "POST", PATH, body, key);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        openingId = first.body().get("id").asString();
        assertThat(first.body().get("cashSessionId").asString()).isEqualTo(sessionId);
        assertThat(first.body().get("cashRegisterId").asString()).isEqualTo(registerId);
        assertThat(first.body().get("userId").asString()).isEqualTo(cashierId);
        assertThat(first.body().get("reason").asString()).isEqualTo("no_sale");
        assertThat(first.body().get("note").asString()).isEqualTo("Change for the coffee machine");

        Res replay = keyed(cashier, "POST", PATH, body, key);
        assertThat(replay.status()).as(replay.raw().body()).isEqualTo(200);
        assertThat(replay.body().get("id").asString()).isEqualTo(openingId);
        assertThat(openings(sessionId)).isEqualTo(1);

        Res bad = keyed(cashier, "POST", PATH, Map.of("reason", "because"), UUID.randomUUID().toString());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(validationMessages(bad)).containsExactly("reason must be one of the following values: sale,"
                + " refund, cash_movement, no_sale, float, other");
        Res halfReference = keyed(cashier, "POST", PATH, Map.of("reason", "sale", "referenceId", "x"),
                UUID.randomUUID().toString());
        assertThat(halfReference.body().get("message").asString())
                .isEqualTo("referenceType and referenceId are given together");
        Res missingReference = keyed(cashier, "POST", PATH, Map.of("reason", "sale", "referenceType", "invoice",
                "referenceId", UUID.randomUUID().toString()), UUID.randomUUID().toString());
        assertThat(missingReference.status()).isEqualTo(404);
        assertThat(missingReference.body().get("message").asString()).isEqualTo("The referenced invoice was not found");
    }

    @Test
    @Order(2)
    @DisplayName("403 without cash_session:open; 401 without a token")
    void permission() {
        Res weak = keyed(weakToken(), "POST", PATH, Map.of("reason", "no_sale"), UUID.randomUUID().toString());
        assertThat(weak.status()).isEqualTo(403);
        Res anonymous = call("POST", PATH, Map.of("reason", "no_sale"), Map.of("Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(anonymous.status()).isEqualTo(401);
    }

    @Test
    @Order(3)
    @DisplayName("a no-sale needs the caller's own open shift on that till; other reasons may use the till's shift")
    void openSessionRequired() {
        Res none = keyed(colleague, "POST", PATH, Map.of("reason", "no_sale"), UUID.randomUUID().toString());
        assertThat(none.status()).isEqualTo(404);
        assertThat(none.body().get("message").asString()).isEqualTo("No open cash session");
        Res onTill = keyed(colleague, "POST", PATH, Map.of("reason", "no_sale", "cashRegisterId", registerId),
                UUID.randomUUID().toString());
        assertThat(onTill.status()).isEqualTo(404);
        assertThat(onTill.body().get("message").asString()).isEqualTo("No open session on this register");
        Res othersShift = keyed(colleague, "POST", PATH, Map.of("reason", "no_sale", "sessionId", sessionId),
                UUID.randomUUID().toString());
        assertThat(othersShift.status()).isEqualTo(403);
        assertThat(othersShift.body().get("message").asString())
                .isEqualTo("This cash session belongs to a different cashier");

        // A manager loading the float into a colleague's till: recorded against the shift open there.
        Res fl = keyed(colleague, "POST", PATH, Map.of("reason", "float", "cashRegisterId", registerId),
                UUID.randomUUID().toString());
        assertThat(fl.status()).as(fl.raw().body()).isEqualTo(200);
        assertThat(fl.body().get("cashSessionId").asString()).isEqualTo(sessionId);
        assertThat(fl.body().get("userId").asString()).isEqualTo(colleagueId);
        assertThat(openings(sessionId)).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("another organization neither sees the shift nor the till")
    void tenantIsolation() {
        Res bySession = keyed(otherToken, "POST", PATH, Map.of("reason", "other", "sessionId", sessionId),
                UUID.randomUUID().toString());
        assertThat(bySession.status()).isEqualTo(404);
        assertThat(bySession.body().get("message").asString()).isEqualTo("No open cash session");
        Res byRegister = keyed(otherToken, "POST", PATH, Map.of("reason", "other", "cashRegisterId", registerId),
                UUID.randomUUID().toString());
        assertThat(byRegister.status()).isEqualTo(404);
        assertThat(byRegister.body().get("message").asString()).isEqualTo("Cash register not found");
        assertThat(jdbc.sql("SELECT count(*) FROM \"CashDrawerOpening\" WHERE \"cashRegisterId\" = ? AND"
                + " \"organizationId\" <> ?").params(registerId, demoOrg).query(Long.class).single()).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("an Android till pushes a no-sale opening as the cash_drawer.open op")
    void syncPush() {
        JsonNode device = ok(201, "POST", "/sync/devices/register", Map.of("name", "Drawer tablet", "platform",
                "android"));
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("opId", "op-" + UUID.randomUUID());
        op.put("deviceSeq", 1);
        op.put("type", "cash_drawer.open");
        op.put("actorUserId", cashierId);
        op.put("occurredAt", Instant.now().toString());
        op.put("payload", Map.of("sessionId", sessionId, "reason", "no_sale", "note", "Offline no-sale"));
        Res r = call("POST", "/sync/push", Map.of("deviceId", device.get("id").asString(), "ops", List.of(op)),
                Map.of("X-Device-Token", device.get("deviceToken").asString()));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        JsonNode result = r.body().get("results").get(0);
        assertThat(result.get("status").asString()).as(result.toString()).isEqualTo("applied");
        assertThat(openings(sessionId)).isEqualTo(3);
        ok(201, "POST", "/sync/devices/" + device.get("id").asString() + "/revoke", null);
    }

    @Test
    @Order(6)
    @DisplayName("the projection turns the opening into a cash_drawer.opened ledger event C11 counts")
    void projection() {
        drainOutboxFully();
        ok(201, "POST", "/loss-prevention/projection/run", null);
        Map<String, Object> event = jdbc.sql("SELECT type, \"actorId\", \"sessionId\", \"branchId\", \"evidenceLevel\","
                + " payload ->> 'reason' AS reason, payload ->> 'tillId' AS till FROM \"LpEvent\" WHERE"
                + " \"organizationId\" = ? AND source = 'erp-pos' AND \"sourceEventId\" = ?")
                .params(demoOrg, "cash-drawer-opening:" + openingId).query().singleRow();
        assertThat(event.get("type")).isEqualTo("cash_drawer.opened");
        assertThat(event.get("reason")).isEqualTo("no_sale");
        assertThat(event.get("till")).isEqualTo(registerId);
        assertThat(event.get("actorId")).isEqualTo(cashierId);
        assertThat(event.get("sessionId")).isEqualTo(sessionId);
        assertThat(event.get("evidenceLevel")).isEqualTo("session");
        // What C11 counts: this cashier's no-sale openings on the ledger.
        assertThat(jdbc.sql("SELECT count(*) FROM \"LpEvent\" WHERE \"organizationId\" = ? AND type ="
                + " 'cash_drawer.opened' AND \"actorId\" = ? AND payload ->> 'reason' = 'no_sale'")
                .params(demoOrg, cashierId).query(Long.class).single()).isEqualTo(2);
        // A second sweep reads the overlap again: duplicates, never a second event.
        ok(201, "POST", "/loss-prevention/projection/run", null);
        assertThat(jdbc.sql("SELECT count(*) FROM \"LpEvent\" WHERE \"organizationId\" = ? AND \"sourceEventId\" = ?")
                .params(demoOrg, "cash-drawer-opening:" + openingId).query(Long.class).single()).isEqualTo(1);
    }
}
