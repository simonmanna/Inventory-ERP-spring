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
 * ADR-S083 S2, O0 correction 2: after revocation nothing new is applied on the device's word. Server time
 * (receivedAt vs revokedAt) and server sequence state (lastAcceptedSeq) decide; a supervisor recovers genuine
 * pre-revocation ops in device order, with sequence gaps acknowledged and every recovery audited.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncRevocationIT extends ApiTestSupport {

    String adminId;
    final List<String> devices = new ArrayList<>();

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    @AfterAll
    void cleanup() {
        for (String id : devices) {
            jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN"
                    + " ('open', 'investigating')").param(id).update();
        }
    }

    String[] register(String name) {
        JsonNode r = ok(201, "POST", "/sync/devices/register", Map.of("name", name + " " + UUID.randomUUID()
                .toString().substring(0, 6), "platform", "web"));
        devices.add(r.get("id").asString());
        return new String[] {r.get("deviceToken").asString(), r.get("id").asString()};
    }

    static Map<String, Object> taxOp(String opId, int seq, String taxId, Instant occurredAt) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", opId);
        o.put("deviceSeq", seq);
        o.put("type", "tax.upsert");
        o.put("occurredAt", occurredAt.toString());
        o.put("payload", Map.of("id", taxId, "name", "Rev VAT " + taxId.substring(0, 8), "rate", 2));
        return o;
    }

    Map<String, Object> taxOp(int seq, String taxId, Instant occurredAt) {
        Map<String, Object> o = taxOp("rev-" + UUID.randomUUID(), seq, taxId, occurredAt);
        o.put("actorUserId", adminId);
        return o;
    }

    JsonNode push(String[] d, List<Map<String, Object>> ops) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", d[1], "ops", ops), Map.of("X-Device-Token", d[0]));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results");
    }

    long taxes(String id) {
        return jdbc.sql("SELECT count(*) FROM \"Tax\" WHERE id = ?").param(id).query(Long.class).single();
    }

    String deadLetterId(String opId) {
        return jdbc.sql("SELECT id FROM \"SyncOpDeadLetter\" WHERE \"opId\" = ?").param(opId).query(String.class).single();
    }

    Map<String, Object> deviceRow(String id) {
        return jdbc.sql("SELECT \"lastAcceptedSeq\", \"revokedAtSeq\", \"revokedAt\" FROM \"PosDevice\" WHERE id = ?")
                .param(id).query().singleRow();
    }

    @Test
    @DisplayName("clock manipulation: a skewed device clock is fine before revocation, and a back-dated or"
            + " future-dated op after revocation is held either way")
    void backDatedAfterRevocationIsHeld() {
        String[] d = register("Skewed tablet");
        String early = UUID.randomUUID().toString();
        JsonNode before = push(d, List.of(taxOp(1, early, Instant.now().minusSeconds(86_400 * 6)))).get(0);
        assertThat(before.get("status").asString()).as(before.toString()).isEqualTo("applied");
        assertThat(deviceRow(d[1]).get("lastAcceptedSeq")).isEqualTo(1);

        ok(201, "POST", "/sync/devices/" + d[1] + "/revoke", null);
        assertThat(deviceRow(d[1]).get("revokedAtSeq")).isEqualTo(1);
        String backDated = UUID.randomUUID().toString();
        String future = UUID.randomUUID().toString();
        Map<String, Object> past = taxOp(2, backDated, Instant.now().minusSeconds(86_400 * 30));
        JsonNode r = push(d, List.of(past, taxOp(3, future, Instant.now().plusSeconds(86_400)))).get(0);
        assertThat(r.get("status").asString()).isEqualTo("failed");
        assertThat(r.get("code").asString()).isEqualTo("DEVICE_REVOKED");
        assertThat(r.get("retryable").asBoolean()).isFalse();
        assertThat(taxes(backDated)).isZero();
        assertThat(taxes(future)).isZero();
        Map<String, Object> dl = jdbc.sql("SELECT \"failureCode\", \"receivedAt\", \"occurredAt\" FROM"
                + " \"SyncOpDeadLetter\" WHERE \"opId\" = ?").param(past.get("opId")).query().singleRow();
        assertThat(dl.get("failureCode")).isEqualTo("DEVICE_REVOKED");
        // Server receipt time is recorded separately from the device's claim.
        assertThat(((java.sql.Timestamp) dl.get("receivedAt")).toInstant())
                .isAfter(((java.sql.Timestamp) dl.get("occurredAt")).toInstant());
        // Supervisor retry is not the path for held ops.
        assertThat(as("POST", "/sync/dead-letters/" + deadLetterId((String) past.get("opId")) + "/retry",
                Map.of("note", "try")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("replay after revocation returns the stored result with no new effect; a changed payload is held")
    void replayAfterRevocation() {
        String[] d = register("Replay tablet");
        String tax = UUID.randomUUID().toString();
        Map<String, Object> o = taxOp(1, tax, Instant.now());
        JsonNode first = push(d, List.of(o)).get(0);
        assertThat(first.get("status").asString()).isEqualTo("applied");
        ok(201, "POST", "/sync/devices/" + d[1] + "/revoke", null);
        JsonNode again = push(d, List.of(o)).get(0);
        assertThat(again.get("status").asString()).as(again.toString()).isEqualTo("replayed");
        assertThat(again.get("mapping").toString()).isEqualTo(first.get("mapping").toString());
        assertThat(taxes(tax)).isEqualTo(1);
        Map<String, Object> changed = new LinkedHashMap<>(o);
        changed.put("payload", Map.of("id", tax, "name", "Tampered", "rate", 99));
        JsonNode held = push(d, List.of(changed)).get(0);
        assertThat(held.get("code").asString()).isEqualTo("DEVICE_REVOKED");
        assertThat(jdbc.sql("SELECT rate FROM \"Tax\" WHERE id = ?").param(tax).query(java.math.BigDecimal.class)
                .single()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("supervisor recovery: in device order, a sequence gap needs acknowledgement, and it is audited")
    void recoveryInOrderWithGap() {
        String[] d = register("Recover tablet");
        push(d, List.of(taxOp(1, UUID.randomUUID().toString(), Instant.now())));
        ok(201, "POST", "/sync/devices/" + d[1] + "/revoke", null);
        String t3 = UUID.randomUUID().toString();
        String t4 = UUID.randomUUID().toString();
        Map<String, Object> op3 = taxOp(3, t3, Instant.now().minusSeconds(600));
        Map<String, Object> op4 = taxOp(4, t4, Instant.now().minusSeconds(500));
        push(d, List.of(op3, op4)); // seq 2 never arrives
        String dl3 = deadLetterId((String) op3.get("opId"));
        String dl4 = deadLetterId((String) op4.get("opId"));

        Res outOfOrder = as("POST", "/sync/dead-letters/" + dl4 + "/recover", Map.of("note", "Shift sales"));
        assertThat(outOfOrder.status()).isEqualTo(409);
        assertThat(outOfOrder.body().get("message").asString()).contains("Recover operation sequence 3 first");
        Res gap = as("POST", "/sync/dead-letters/" + dl3 + "/recover", Map.of("note", "Shift sales"));
        assertThat(gap.status()).isEqualTo(409);
        assertThat(gap.body().get("message").asString()).contains("Sequence gap").contains("[2]");
        assertThat(as("POST", "/sync/dead-letters/" + dl3 + "/recover", Map.of("note", " ")).status()).isEqualTo(400);
        assertThat(call("POST", "/sync/dead-letters/" + dl3 + "/recover", Map.of("note", "x"), weakToken()).status())
                .isEqualTo(403);

        JsonNode rec3 = ok(201, "POST", "/sync/dead-letters/" + dl3 + "/recover", Map.of("note",
                "Paper receipts match; seq 2 was a voided test", "acknowledgeGap", true));
        assertThat(rec3.get("result").get("status").asString()).as(rec3.toString()).isEqualTo("applied");
        assertThat(rec3.get("status").asString()).isEqualTo("resolved");
        assertThat(taxes(t3)).isEqualTo(1);
        Map<String, Object> row = jdbc.sql("SELECT \"recoveredById\", \"recoveryGapAcknowledged\" FROM"
                + " \"SyncOpDeadLetter\" WHERE id = ?").param(dl3).query().singleRow();
        assertThat(row.get("recoveredById")).isEqualTo(adminId);
        assertThat(row.get("recoveryGapAcknowledged")).isEqualTo(true);
        String audit = jdbc.sql("SELECT \"newValues\"::text FROM \"AuditLog\" WHERE \"entityId\" = ? AND"
                + " \"newValues\"::text LIKE '%recover%' ORDER BY \"createdAt\" DESC LIMIT 1").param(dl3)
                .query(String.class).single();
        assertThat(audit).contains("lastAcceptedSeq").contains("occurredAtClaimedByDevice").contains("missingSeqs");
        assertThat(deviceRow(d[1]).get("lastAcceptedSeq")).isEqualTo(3);

        JsonNode rec4 = ok(201, "POST", "/sync/dead-letters/" + dl4 + "/recover", Map.of("note", "Shift sales"));
        assertThat(rec4.get("result").get("status").asString()).isEqualTo("applied");
        assertThat(taxes(t4)).isEqualTo(1);
        // Recovered once: a second recovery is refused, and a re-push from the device is a replay.
        assertThat(as("POST", "/sync/dead-letters/" + dl4 + "/recover", Map.of("note", "again")).status())
                .isEqualTo(400);
        assertThat(push(d, List.of(op4)).get(0).get("status").asString()).isEqualTo("replayed");
        assertThat(taxes(t4)).isEqualTo(1);
    }

    @Test
    @DisplayName("an op whose sequence is not above lastAcceptedSeq cannot be recovered (sequence regression)")
    void sequenceRegressionRefused() {
        String[] d = register("Regress tablet");
        push(d, List.of(taxOp(5, UUID.randomUUID().toString(), Instant.now())));
        ok(201, "POST", "/sync/devices/" + d[1] + "/revoke", null);
        Map<String, Object> low = taxOp(2, UUID.randomUUID().toString(), Instant.now().minusSeconds(3600));
        push(d, List.of(low));
        Res r = as("POST", "/sync/dead-letters/" + deadLetterId((String) low.get("opId")) + "/recover",
                Map.of("note", "Claimed pre-revocation"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("message").asString()).contains("not above the device's last accepted sequence 5");
    }

    @Test
    @DisplayName("an expired token holds every push regardless of occurredAt; recovery works the same way")
    void expiredTokenHeld() {
        String[] d = register("Expired tablet");
        push(d, List.of(taxOp(1, UUID.randomUUID().toString(), Instant.now())));
        jdbc.sql("UPDATE \"PosDevice\" SET \"tokenExpiresAt\" = now() - interval '1 minute' WHERE id = ?").param(d[1])
                .update();
        String tax = UUID.randomUUID().toString();
        Map<String, Object> o = taxOp(2, tax, Instant.now().minusSeconds(86_400));
        JsonNode r = push(d, List.of(o)).get(0);
        assertThat(r.get("code").asString()).isEqualTo("DEVICE_TOKEN_EXPIRED");
        assertThat(taxes(tax)).isZero();
        JsonNode rec = ok(201, "POST", "/sync/dead-letters/" + deadLetterId((String) o.get("opId")) + "/recover",
                Map.of("note", "Token lapsed mid-shift"));
        assertThat(rec.get("result").get("status").asString()).isEqualTo("applied");
        assertThat(taxes(tax)).isEqualTo(1);
        JsonNode listed = ok(200, "GET", "/sync/dead-letters?status=resolved", null);
        assertThat(listed.toString()).contains("recoveredAt");
    }
}
