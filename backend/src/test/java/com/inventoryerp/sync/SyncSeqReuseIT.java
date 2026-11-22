package com.inventoryerp.sync;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 O6: a protocol-2 client never re-keys an op, so the same device sequence under another opId is refused
 * ({@code SEQ_REUSED}, dead letter, never applied). Retries of the same opId, corrections (new op, new sequence,
 * {@code supersedesOpId}) and protocol-1 clients (older Android builds, which still re-key) are unaffected.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncSeqReuseIT extends ApiTestSupport {

    String adminId;
    String v2Token;
    String v2Device;
    String v1Token;
    String v1Device;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        JsonNode a = ok(201, "POST", "/sync/devices/register", Map.of("name", "Seq v2 " + UUID.randomUUID().toString()
                .substring(0, 6), "platform", "android"));
        v2Token = a.get("deviceToken").asString();
        v2Device = a.get("id").asString();
        JsonNode b = ok(201, "POST", "/sync/devices/register", Map.of("name", "Seq v1 " + UUID.randomUUID().toString()
                .substring(0, 6), "platform", "android"));
        v1Token = b.get("deviceToken").asString();
        v1Device = b.get("id").asString();
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" IN (?, ?) AND status IN"
                + " ('open', 'investigating')").params(v2Device, v1Device).update();
    }

    Map<String, Object> customer(String opId, int seq, Map<String, Object> payload) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", opId);
        o.put("deviceSeq", seq);
        o.put("type", "customer.upsert");
        o.put("actorUserId", adminId);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(boolean v2, Map<String, Object> op) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Device-Token", v2 ? v2Token : v1Token);
        if (v2) {
            headers.put("X-Sync-Protocol", "2.0");
        }
        Res r = call("POST", "/sync/push", Map.of("deviceId", v2 ? v2Device : v1Device, "ops", List.of(op)), headers);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results").get(0);
    }

    static Map<String, Object> named(String name) {
        String id = UUID.randomUUID().toString();
        return Map.of("id", id, "name", name + " " + id.substring(0, 6));
    }

    @Test
    @DisplayName("protocol 2: another opId on an applied sequence is SEQ_REUSED; the same opId still replays")
    void reuseOfAppliedSequence() {
        String first = UUID.randomUUID().toString();
        Map<String, Object> payload = named("Seq Sam");
        assertThat(push(true, customer(first, 101, payload)).get("status").asString()).isEqualTo("applied");

        String rekeyed = UUID.randomUUID().toString();
        Map<String, Object> other = named("Seq Rekeyed");
        JsonNode r = push(true, customer(rekeyed, 101, other));
        assertThat(r.get("status").asString()).isEqualTo("failed");
        assertThat(r.get("httpStatus").asInt()).isEqualTo(409);
        assertThat(r.get("code").asString()).isEqualTo("SEQ_REUSED");
        assertThat(r.get("retryable").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ?").param(other.get("id")).query(Long.class)
                .single()).as("never applied").isZero();
        assertThat(jdbc.sql("SELECT \"failureCode\" FROM \"SyncOpDeadLetter\" WHERE \"opId\" = ?").param(rekeyed)
                .query(String.class).single()).isEqualTo("SEQ_REUSED");

        assertThat(push(true, customer(first, 101, payload)).get("status").asString()).isEqualTo("replayed");
    }

    @Test
    @DisplayName("protocol 2: a dead-lettered sequence is not reusable either; a correction with a new sequence applies")
    void reuseOfRejectedSequenceAndCorrection() {
        String bad = UUID.randomUUID().toString();
        String id = UUID.randomUUID().toString();
        JsonNode r = push(true, customer(bad, 201, Map.of("id", id)));
        assertThat(r.get("code").asString()).isEqualTo("BUSINESS_RULE");

        JsonNode rekey = push(true, customer(UUID.randomUUID().toString(), 201, Map.of("id", id, "name", "Seq Fixed")));
        assertThat(rekey.get("code").asString()).as("the old re-key pattern").isEqualTo("SEQ_REUSED");

        String fix = UUID.randomUUID().toString();
        Map<String, Object> correction = customer(fix, 202, Map.of("id", id, "name", "Seq Fixed"));
        correction.put("supersedesOpId", bad);
        assertThat(push(true, correction).get("status").asString()).isEqualTo("applied");
        assertThat(jdbc.sql("SELECT status FROM \"SyncOpDeadLetter\" WHERE \"opId\" = ?").param(bad).query(String.class)
                .single()).isEqualTo("resolved");
    }

    @Test
    @DisplayName("protocol 1 (older Android builds re-key a rejected op): a reused sequence is still accepted")
    void protocolOneUnchanged() {
        assertThat(push(false, customer(UUID.randomUUID().toString(), 301, named("Seq Old"))).get("status").asString())
                .isEqualTo("applied");
        assertThat(push(false, customer(UUID.randomUUID().toString(), 301, named("Seq Old Rekey"))).get("status")
                .asString()).isEqualTo("applied");
    }
}
