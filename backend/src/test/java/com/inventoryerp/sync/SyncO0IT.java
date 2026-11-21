package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.sync.application.SyncSnapshotService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 O0 on Testcontainers Postgres: the applied-op ledger through {@code /sync/push} (replay, reused opId,
 * concurrent pushes, corrections), batch idempotency, protocol versions, token rotation / expiry / revocation
 * cut-off, device policy, tombstones, snapshots, the dead-letter workflow, menu-child bumps and tenant isolation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncO0IT extends ApiTestSupport {

    String token;
    String deviceId;
    String adminId;
    int seq;
    final List<String> devices = new ArrayList<>();

    @BeforeAll
    void fixtures() {
        String[] d = register("O0 tablet");
        token = d[0];
        deviceId = d[1];
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    @AfterAll
    void cleanup() {
        // Open dead letters only warn at shift close, but keep the shared DEMO org tidy.
        for (String id : devices) {
            jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN"
                    + " ('open', 'investigating')").param(id).update();
        }
    }

    String[] register(String name) {
        JsonNode r = ok(201, "POST", "/sync/devices/register", Map.of("name", name, "platform", "web"));
        devices.add(r.get("id").asString());
        return new String[] {r.get("deviceToken").asString(), r.get("id").asString()};
    }

    Res dev(String tok, String method, String path, Object body, Map<String, String> extra) {
        Map<String, String> h = new HashMap<>(extra);
        h.put("X-Device-Token", tok);
        return call(method, path, body, h);
    }

    Res dev(String method, String path, Object body) {
        return dev(token, method, path, body, Map.of());
    }

    Map<String, Object> op(String opId, String type, Map<String, Object> payload, String actor, Instant at) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", opId);
        o.put("deviceSeq", ++seq);
        o.put("type", type);
        o.put("actorUserId", actor);
        o.put("occurredAt", at.toString());
        o.put("payload", payload);
        return o;
    }

    /** A per-run unique name (the reused test database keeps earlier runs' rows; names are unique per org). */
    static String u(String name) {
        return name + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    Map<String, Object> taxOp(String opId, String taxId, String name) {
        return op(opId, "tax.upsert", Map.of("id", taxId, "name", name, "rate", 5, "code", "O0" + seq), adminId,
                Instant.now());
    }

    JsonNode push(String tok, String devId, List<Map<String, Object>> ops) {
        Res r = dev(tok, "POST", "/sync/push", Map.of("deviceId", devId, "ops", ops), Map.of());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results");
    }

    JsonNode push(List<Map<String, Object>> ops) {
        return push(token, deviceId, ops);
    }

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    Map<String, Object> deadLetter(String opId) {
        return jdbc.sql("SELECT id, status, \"failureCode\", retryable, attempts, \"resolvedByOpId\" FROM"
                + " \"SyncOpDeadLetter\" WHERE \"opId\" = ?").param(opId).query().singleRow();
    }

    @Test
    @DisplayName("C1/S1: an applied op is replayed from the ledger even after its idempotency record is gone")
    void ledgerReplay() {
        String opId = "o0-" + UUID.randomUUID();
        Map<String, Object> o = taxOp(opId, UUID.randomUUID().toString(), u("Ledger VAT"));
        assertThat(push(List.of(o)).get(0).get("status").asString()).isEqualTo("applied");
        Map<String, Object> row = jdbc.sql("SELECT state, \"originChain\"::text AS chain, \"serverId\" FROM"
                + " \"SyncAppliedOp\" WHERE \"opId\" = ?").param(opId).query().singleRow();
        assertThat(row.get("state")).isEqualTo("applied");
        assertThat(row.get("chain").toString()).contains("device:" + deviceId).contains("server:hq");
        jdbc.sql("DELETE FROM \"IdempotencyRecord\" WHERE key = ?").param(opId).update();
        JsonNode again = push(List.of(o)).get(0);
        assertThat(again.get("status").asString()).isEqualTo("replayed");
        assertThat(again.get("mapping").toString()).contains("taxId");
        assertThat(count("SELECT count(*) FROM \"SyncAppliedOp\" WHERE \"opId\" = ?", opId)).isEqualTo(1);
    }

    @Test
    @DisplayName("C1: the same opId with a different payload is refused (OP_ID_REUSED) and dead-lettered")
    void reusedOpId() {
        String opId = "o0-" + UUID.randomUUID();
        String tax = UUID.randomUUID().toString();
        push(List.of(taxOp(opId, tax, u("First"))));
        JsonNode r = push(List.of(taxOp(opId, tax, u("Second")))).get(0);
        assertThat(r.get("status").asString()).isEqualTo("failed");
        assertThat(r.get("httpStatus").asInt()).isEqualTo(409);
        assertThat(r.get("code").asString()).isEqualTo("OP_ID_REUSED");
        assertThat(r.get("retryable").asBoolean()).isFalse();
        assertThat(deadLetter(opId).get("failureCode")).isEqualTo("OP_ID_REUSED");
        assertThat(jdbc.sql("SELECT name FROM \"Tax\" WHERE id = ?").param(tax).query(String.class).single())
                .startsWith("First ");
    }

    @Test
    @DisplayName("S1/S5: a business rejection commits nothing, frees its key, and is closed by a correction op")
    void rejectionThenCorrection() {
        String bad = "o0-" + UUID.randomUUID();
        String customer = UUID.randomUUID().toString();
        JsonNode r = push(List.of(op(bad, "customer.upsert", Map.of("id", customer), adminId, Instant.now()))).get(0);
        assertThat(r.get("httpStatus").asInt()).isEqualTo(400);
        assertThat(r.get("code").asString()).isEqualTo("BUSINESS_RULE");
        assertThat(r.get("retryable").asBoolean()).isFalse();
        assertThat(count("SELECT count(*) FROM \"SyncAppliedOp\" WHERE \"opId\" = ?", bad)).isZero();
        assertThat(count("SELECT count(*) FROM \"IdempotencyRecord\" WHERE key = ?", bad)).isZero();
        String fix = "o0-" + UUID.randomUUID();
        Map<String, Object> correction = op(fix, "customer.upsert", Map.of("id", customer, "name", "Fixed Fay"), adminId,
                Instant.now());
        correction.put("supersedesOpId", bad);
        assertThat(push(List.of(correction)).get(0).get("status").asString()).isEqualTo("applied");
        Map<String, Object> dl = deadLetter(bad);
        assertThat(dl.get("status")).isEqualTo("resolved");
        assertThat(dl.get("resolvedByOpId")).isEqualTo(fix);
        assertThat(jdbc.sql("SELECT \"supersedesOpId\" FROM \"SyncAppliedOp\" WHERE \"opId\" = ?").param(fix)
                .query(String.class).single()).isEqualTo(bad);
    }

    @Test
    @DisplayName("C1: the same opId pushed concurrently is applied exactly once; the others replay or are told to retry")
    void concurrentSameOp() throws Exception {
        String opId = "o0-" + UUID.randomUUID();
        Map<String, Object> o = taxOp(opId, UUID.randomUUID().toString(), u("Race VAT"));
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return push(List.of(o)).get(0);
            }, pool));
        }
        go.countDown();
        int applied = 0;
        for (CompletableFuture<JsonNode> f : futures) {
            JsonNode r = f.get();
            String status = r.get("status").asString();
            if ("applied".equals(status)) {
                applied++;
            } else if ("failed".equals(status)) {
                assertThat(r.get("code").asString()).as(r.toString()).isEqualTo("OPERATION_PENDING");
                assertThat(r.get("retryable").asBoolean()).isTrue();
            } else {
                assertThat(status).isEqualTo("replayed");
            }
        }
        pool.shutdown();
        assertThat(applied).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"SyncAppliedOp\" WHERE \"opId\" = ?", opId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"SyncOpDeadLetter\" WHERE \"opId\" = ?", opId)).isZero();
    }

    @Test
    @DisplayName("S1: a resent batch with the same Idempotency-Key returns the stored results; another body is 409")
    void batchIdempotency() {
        String key = "batch-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("deviceId", deviceId, "ops",
                List.of(taxOp("o0-" + UUID.randomUUID(), UUID.randomUUID().toString(), u("Batch VAT"))));
        Res first = dev(token, "POST", "/sync/push", body, Map.of("Idempotency-Key", key));
        Res second = dev(token, "POST", "/sync/push", body, Map.of("Idempotency-Key", key));
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isIn(200, 201);
        assertThat(second.body().get("results")).isEqualTo(first.body().get("results"));
        Map<String, Object> other = Map.of("deviceId", deviceId, "ops",
                List.of(taxOp("o0-" + UUID.randomUUID(), UUID.randomUUID().toString(), u("Other VAT"))));
        assertThat(dev(token, "POST", "/sync/push", other, Map.of("Idempotency-Key", key)).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("S8: pull refuses an unsupported protocol with 426; push accepts it; v1 pulls get policy, no rotation")
    void protocolVersions() {
        Res old = dev(token, "GET", "/sync/pull?scopes=taxes", null, Map.of("X-Sync-Protocol", "9.0"));
        assertThat(old.status()).isEqualTo(426);
        assertThat(old.body().get("message").asString()).contains("not supported");
        assertThat(dev(token, "POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(taxOp("o0-"
                + UUID.randomUUID(), UUID.randomUUID().toString(), u("Future VAT")))), Map.of("X-Sync-Protocol", "9.0"))
                .status()).isEqualTo(201);
        JsonNode v1 = dev("GET", "/sync/pull?scopes=taxes", null).body();
        assertThat(v1.has("deviceToken")).isFalse();
        assertThat(v1.get("protocolVersion").asString()).isEqualTo("2.0");
        assertThat(v1.get("serverId").asString()).isEqualTo("hq");
        assertThat(v1.get("offlinePolicy").get("offlineWindowHours").asInt()).isEqualTo(72);
        assertThat(v1.get("deviceTokenExpiresAt").isNull()).isFalse();
        assertThat(jdbc.sql("SELECT \"protocolVersion\" FROM \"PosDevice\" WHERE id = ?").param(deviceId)
                .query(String.class).single()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("C4: protocol 2 rotates the token on pull; the previous one works only during its grace")
    void tokenRotation() {
        String[] d = register("Rotating tablet");
        Map<String, String> v2 = Map.of("X-Sync-Protocol", "2.0");
        JsonNode first = dev(d[0], "GET", "/sync/pull?scopes=taxes", null, v2).body();
        String rotated = first.get("deviceToken").asString();
        assertThat(rotated).isNotEqualTo(d[0]);
        // A lost response: the old token still pulls during the grace and is handed a fresh token again.
        JsonNode retry = dev(d[0], "GET", "/sync/pull?scopes=taxes", null, v2).body();
        String latest = retry.get("deviceToken").asString();
        assertThat(dev(latest, "GET", "/sync/pull?scopes=taxes", null, Map.of()).status()).isEqualTo(200);
        jdbc.sql("UPDATE \"PosDevice\" SET \"previousTokenValidUntil\" = now() - interval '1 minute' WHERE id = ?")
                .param(d[1]).update();
        assertThat(dev(d[0], "GET", "/sync/pull?scopes=taxes", null, Map.of()).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("S2/C4: an expired token cannot pull; its push applies ops before the expiry and holds later ones")
    void expiredToken() {
        String[] d = register("Expired tablet");
        Instant expiry = Instant.now().minusSeconds(60);
        jdbc.sql("UPDATE \"PosDevice\" SET \"tokenExpiresAt\" = ? WHERE id = ?")
                .params(java.sql.Timestamp.valueOf(expiry.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()), d[1])
                .update();
        Res pull = dev(d[0], "GET", "/sync/pull?scopes=taxes", null, Map.of());
        assertThat(pull.status()).isEqualTo(401);
        assertThat(pull.body().get("message").asString()).contains("expired");
        String before = "o0-" + UUID.randomUUID();
        String after = "o0-" + UUID.randomUUID();
        JsonNode r = push(d[0], d[1], List.of(
                op(before, "tax.upsert", Map.of("id", UUID.randomUUID().toString(), "name", u("Early"), "rate", 1), adminId,
                        expiry.minusSeconds(120)),
                op(after, "tax.upsert", Map.of("id", UUID.randomUUID().toString(), "name", u("Late"), "rate", 1), adminId,
                        Instant.now())));
        assertThat(r.get(0).get("status").asString()).isEqualTo("applied");
        assertThat(r.get(1).get("code").asString()).isEqualTo("DEVICE_TOKEN_EXPIRED");
        assertThat(deadLetter(after).get("failureCode")).isEqualTo("DEVICE_TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("S2: a revoked device's ops recorded before revokedAt are accepted, later ones are dead-lettered")
    void revocationCutoff() {
        String[] d = register("Stolen tablet");
        Instant beforeRevoke = Instant.now().minusSeconds(30);
        ok(201, "POST", "/sync/devices/" + d[1] + "/revoke", null);
        assertThat(dev(d[0], "GET", "/sync/pull?scopes=taxes", null, Map.of()).status()).isEqualTo(401);
        String late = "o0-" + UUID.randomUUID();
        JsonNode r = push(d[0], d[1], List.of(
                op("o0-" + UUID.randomUUID(), "tax.upsert", Map.of("id", UUID.randomUUID().toString(), "name", u("Pre"),
                        "rate", 1), adminId, beforeRevoke),
                op(late, "tax.upsert", Map.of("id", UUID.randomUUID().toString(), "name", u("Post"), "rate", 1), adminId,
                        Instant.now().plusSeconds(5))));
        assertThat(r.get(0).get("status").asString()).isEqualTo("applied");
        assertThat(r.get(1).get("code").asString()).isEqualTo("DEVICE_REVOKED");
        assertThat(r.get(1).get("retryable").asBoolean()).isFalse();
        assertThat(deadLetter(late).get("failureCode")).isEqualTo("DEVICE_REVOKED");
    }

    @Test
    @DisplayName("C4: a device policy only lowers the org's offline window")
    void devicePolicy() {
        String[] d = register("Policy tablet");
        JsonNode set = ok(201, "POST", "/sync/devices/" + d[1] + "/policy", Map.of("offlineWindowHours", 24));
        assertThat(set.get("effectiveOfflineWindowHours").asInt()).isEqualTo(24);
        assertThat(dev(d[0], "GET", "/sync/pull?scopes=taxes", null, Map.of()).body().get("offlinePolicy")
                .get("offlineWindowHours").asInt()).isEqualTo(24);
        JsonNode high = ok(201, "POST", "/sync/devices/" + d[1] + "/policy", Map.of("offlineWindowHours", 500));
        assertThat(high.get("effectiveOfflineWindowHours").asInt()).isEqualTo(72);
        assertThat(as("POST", "/sync/devices/" + d[1] + "/policy", Map.of("offlineWindowHours", 1000)).status())
                .isEqualTo(400);
        assertThat(as("POST", "/sync/devices/" + UUID.randomUUID() + "/policy", Map.of("offlineWindowHours", 5))
                .status()).isEqualTo(404);
    }

    @Test
    @DisplayName("S7: protocol 2 deltas carry tombstones for soft-deleted rows; protocol 1 deltas do not")
    void tombstones() {
        String tax = UUID.randomUUID().toString();
        push(List.of(taxOp("o0-" + UUID.randomUUID(), tax, u("Doomed VAT"))));
        String cursor = dev("GET", "/sync/pull?scopes=taxes", null).body().get("cursor").asString();
        jdbc.sql("UPDATE \"Tax\" SET \"deletedAt\" = now(), \"updatedAt\" = now() WHERE id = ?").param(tax).update();
        JsonNode v2 = dev(token, "GET", "/sync/pull?scopes=taxes&cursor=" + cursor, null, Map.of("X-Sync-Protocol",
                "2.0")).body().get("data").get("taxes");
        JsonNode tomb = null;
        for (JsonNode t : v2) {
            if (tax.equals(t.get("id").asString())) {
                tomb = t;
            }
        }
        assertThat(tomb).as(v2.toString()).isNotNull();
        assertThat(tomb.get("deletedAt").isNull()).isFalse();
        JsonNode v1 = dev("GET", "/sync/pull?scopes=taxes&cursor=" + cursor, null).body().get("data").get("taxes");
        assertThat(v1.toString()).doesNotContain(tax);
    }

    @Test
    @DisplayName("C5: snapshot pages verify and complete into a delta cursor; partial, tampered or expired ones do not")
    void snapshots() {
        JsonNode snap = ok201(dev("POST", "/sync/snapshot", Map.of("scopes", "taxes,posTables")));
        String id = snap.get("snapshotId").asString();
        assertThat(snap.get("scopes").toString()).contains("taxes").contains("posTables");
        Map<String, Map<String, Object>> claims = new LinkedHashMap<>();
        for (String scope : List.of("taxes", "posTables")) {
            List<String> sums = new ArrayList<>();
            long rows = 0;
            String after = "";
            while (true) {
                JsonNode page = dev("GET", "/sync/snapshot/" + id + "/page?scope=" + scope + "&after=" + after, null)
                        .body();
                sums.add(page.get("pageChecksum").asString());
                rows += page.get("rows").size();
                if (!page.get("hasMore").asBoolean()) {
                    break;
                }
                after = page.get("nextAfter").asString();
            }
            claims.put(scope, Map.of("rowCount", rows, "checksum", SyncSnapshotService.scopeChecksum(sums)));
        }
        Map<String, Map<String, Object>> tampered = new LinkedHashMap<>(claims);
        tampered.put("taxes", Map.of("rowCount", claims.get("taxes").get("rowCount"), "checksum", "nope"));
        Res bad = dev("POST", "/sync/snapshot/" + id + "/complete", Map.of("scopes", tampered));
        assertThat(bad.status()).isEqualTo(409);
        assertThat(bad.body().get("message").asString()).contains("taxes: checksum mismatch");
        JsonNode done = ok201(dev("POST", "/sync/snapshot/" + id + "/complete", Map.of("scopes", claims)));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        Res deltas = dev("GET", "/sync/pull?scopes=taxes&cursor=" + done.get("cursor").asString(), null);
        assertThat(deltas.status()).isEqualTo(200);
        assertThat(dev("POST", "/sync/snapshot/" + id + "/complete", Map.of("scopes", claims)).status()).isEqualTo(409);

        String partial = ok201(dev("POST", "/sync/snapshot", Map.of("scopes", "taxes"))).get("snapshotId").asString();
        Res incomplete = dev("POST", "/sync/snapshot/" + partial + "/complete", Map.of("scopes", Map.of("taxes",
                Map.of("rowCount", 0, "checksum", "x"))));
        assertThat(incomplete.body().get("message").asString()).contains("not every page was downloaded");
        jdbc.sql("UPDATE \"SyncSnapshot\" SET \"expiresAt\" = now() - interval '1 minute' WHERE id = ?").param(partial)
                .update();
        assertThat(dev("GET", "/sync/snapshot/" + partial + "/page?scope=taxes", null).status()).isEqualTo(410);
        String[] other = register("Other tablet");
        String mine = ok201(dev("POST", "/sync/snapshot", Map.of("scopes", "taxes"))).get("snapshotId").asString();
        assertThat(dev(other[0], "GET", "/sync/snapshot/" + mine + "/page?scope=taxes", null, Map.of()).status())
                .isEqualTo(404);
        assertThat(dev("GET", "/sync/snapshot/" + mine + "/page?scope=messages", null).status()).isEqualTo(400);
    }

    JsonNode ok201(Res r) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    @Test
    @DisplayName("S5: dead letters move open → investigating → retried (retryable only), with business rejections refused")
    void deadLetterWorkflow() {
        String failedParent = "o0-" + UUID.randomUUID();
        String child = "o0-" + UUID.randomUUID();
        String client = "c-" + UUID.randomUUID();
        JsonNode r = push(List.of(
                op(failedParent, "customer.upsert", Map.of("clientId", client, "name", "Ghost"), UUID.randomUUID()
                        .toString(), Instant.now()),
                op(child, "customer.upsert", Map.of("id", UUID.randomUUID().toString(), "name", "Child Cat",
                        "partnerId", client), adminId, Instant.now())));
        assertThat(r.get(0).get("code").asString()).isEqualTo("ACTOR_NOT_ALLOWED");
        assertThat(r.get(1).get("code").asString()).isEqualTo("DEPENDENCY_FAILED");
        assertThat(r.get(1).get("retryable").asBoolean()).isTrue();
        String parentDl = (String) deadLetter(failedParent).get("id");
        String childDl = (String) deadLetter(child).get("id");

        Res noNote = as("POST", "/sync/dead-letters/" + parentDl + "/investigate", Map.of("note", " "));
        assertThat(noNote.status()).isEqualTo(400);
        JsonNode inv = ok(201, "POST", "/sync/dead-letters/" + parentDl + "/investigate", Map.of("note", "Checking user"));
        assertThat(inv.get("status").asString()).isEqualTo("investigating");
        Res refused = as("POST", "/sync/dead-letters/" + parentDl + "/retry", Map.of("note", "try"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("business rejection");
        JsonNode listed = ok(200, "GET", "/sync/dead-letters?status=investigating", null);
        assertThat(listed.toString()).contains("Checking user").contains("ACTOR_NOT_ALLOWED");
        assertThat(ok(200, "GET", "/sync/dead-letters/counts", null).has("investigating")).isTrue();

        JsonNode retried = ok(201, "POST", "/sync/dead-letters/" + childDl + "/retry", Map.of("note", "parent fixed"));
        assertThat(retried.get("result").get("status").asString()).as(retried.toString()).isEqualTo("applied");
        assertThat(retried.get("status").asString()).isEqualTo("retried");
        assertThat(count("SELECT count(*) FROM \"SyncAppliedOp\" WHERE \"opId\" = ?", child)).isEqualTo(1);
        // Discarding is still the original Nest resolve route, now also from 'investigating'.
        assertThat(ok(201, "POST", "/sync/dead-letters/" + parentDl + "/resolve", Map.of("status", "discarded",
                "note", "Unknown user, nothing to recover")).get("status").asString()).isEqualTo("discarded");
        assertThat(as("POST", "/sync/dead-letters/" + childDl + "/retry", Map.of("note", "again")).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("S7: changing a menu item's variant or a modifier re-sends the item (parent updatedAt bumped)")
    void menuChildBumpsParent() {
        String cat = UUID.randomUUID().toString();
        String group = UUID.randomUUID().toString();
        String item = UUID.randomUUID().toString();
        String variant = UUID.randomUUID().toString();
        String modifier = UUID.randomUUID().toString();
        push(List.of(
                op("o0-" + UUID.randomUUID(), "menuCategory.upsert", Map.of("id", cat, "name", u("O0 drinks")), adminId,
                        Instant.now()),
                op("o0-" + UUID.randomUUID(), "modifierGroup.upsert", Map.of("id", group, "name", u("O0 milk"), "modifiers",
                        List.of(Map.of("id", modifier, "name", "Soy", "priceDelta", 300))), adminId, Instant.now()),
                op("o0-" + UUID.randomUUID(), "menuItem.upsert", Map.of("id", item, "name", u("O0 latte"), "categoryId", cat,
                        "basePrice", 8000, "variants", List.of(Map.of("id", variant, "name", "Tall", "price", 9000)),
                        "modifierGroupIds", List.of(group)), adminId, Instant.now())));
        String old = "UPDATE \"MenuItem\" SET \"updatedAt\" = '2000-01-01' WHERE id = ?";
        String year = "SELECT extract(year FROM \"updatedAt\")::int FROM \"MenuItem\" WHERE id = ?";
        jdbc.sql(old).param(item).update();
        jdbc.sql("UPDATE \"MenuItemVariant\" SET name = 'Grande' WHERE id = ?").param(variant).update();
        assertThat(jdbc.sql(year).param(item).query(Integer.class).single()).isGreaterThan(2000);
        jdbc.sql(old).param(item).update();
        jdbc.sql("UPDATE \"Modifier\" SET name = 'Oat' WHERE id = ?").param(modifier).update();
        assertThat(jdbc.sql(year).param(item).query(Integer.class).single()).isGreaterThan(2000);
    }

    @Test
    @DisplayName("tenancy: the ledger, snapshots and dead letters are per organization")
    void tenantIsolation() {
        Map<String, Object> other = jdbc.sql("SELECT u.id, u.\"organizationId\" AS org FROM \"User\" u WHERE"
                + " u.\"organizationId\" <> ? AND u.\"isActive\" = true AND u.\"deletedAt\" IS NULL LIMIT 1")
                .param(demoOrg).query().listOfRows().stream().findFirst().orElse(null);
        assumeTrue(other != null, "a second organization with an active user is seeded");
        String otherToken = UUID.randomUUID().toString().replace("-", "") + "o0";
        String otherDevice = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PosDevice\" (id, \"organizationId\", name, platform, \"tokenHash\", prefix, \"updatedAt\")"
                + " VALUES (?, ?, 'Other org tablet', 'web', encode(sha256(convert_to(?, 'UTF8')), 'hex'), ?, now())")
                .params(otherDevice, other.get("org"), otherToken, "X" + UUID.randomUUID().toString().substring(0, 6))
                .update();
        String opId = "o0-" + UUID.randomUUID();
        assertThat(push(List.of(taxOp(opId, UUID.randomUUID().toString(), u("DEMO VAT")))).get(0).get("status").asString())
                .isEqualTo("applied");
        Map<String, Object> theirs = op(opId, "tax.upsert", Map.of("id", UUID.randomUUID().toString(), "name",
                u("Other VAT"), "rate", 2), (String) other.get("id"), Instant.now());
        JsonNode r = push(otherToken, otherDevice, List.of(theirs)).get(0);
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
        assertThat(count("SELECT count(*) FROM \"SyncAppliedOp\" WHERE \"opId\" = ?", opId)).isEqualTo(2);
        String demoSnap = ok201(dev("POST", "/sync/snapshot", Map.of("scopes", "taxes"))).get("snapshotId").asString();
        assertThat(dev(otherToken, "GET", "/sync/snapshot/" + demoSnap + "/page?scope=taxes", null, Map.of()).status())
                .isEqualTo(404);
        jdbc.sql("DELETE FROM \"PosDevice\" WHERE id = ?").param(otherDevice).update();
    }
}
