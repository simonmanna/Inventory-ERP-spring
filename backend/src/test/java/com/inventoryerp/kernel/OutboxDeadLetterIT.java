package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.events.OutboxAdminController;
import com.inventoryerp.kernel.events.OutboxHandlers;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** PR-1: a failing outbox row backs off, turns dead after the attempt limit, no longer blocks others, and retries. */
class OutboxDeadLetterIT extends ApiTestSupport {

    @Autowired
    OutboxHandlers handlers;

    String insert(String org, String eventName) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"EventOutbox\" (id, \"organizationId\", \"eventName\", payload, status, attempts,"
                + " \"createdAt\") VALUES (?, ?, ?, '{}'::jsonb, 'pending', 0, now() - interval '1 day')")
                .params(id, org, eventName).update();
        return id;
    }

    Map<String, Object> row(String id) {
        return jdbc.sql("SELECT status, attempts, \"nextAttemptAt\" IS NOT NULL AS waiting FROM \"EventOutbox\""
                + " WHERE id = ?").param(id).query().singleRow();
    }

    /** Ship until {@code id} is no longer pending, skipping its backoff each round. */
    void runToEnd(String id) {
        for (int i = 0; i < 20 && "pending".equals(row(id).get("status")); i++) {
            jdbc.sql("UPDATE \"EventOutbox\" SET \"nextAttemptAt\" = NULL WHERE id = ?").param(id).update();
            drainOutboxFully();
            outboxWorker.tick();
        }
    }

    @Test
    @DisplayName("failing row: backs off, dies after the limit, is listed per organization and can be retried")
    void deadLetter() {
        String broken = "test.broken." + UUID.randomUUID();
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger delivered = new AtomicInteger();
        handlers.subscribe(broken, payload -> {
            if (fail.get()) {
                throw new IllegalStateException("subscriber down");
            }
            delivered.incrementAndGet();
        });
        String id = insert(demoOrg, broken);
        String foreign = insert(otherOrg, broken);

        outboxWorker.tick();
        Map<String, Object> first = row(id);
        assertThat(first.get("status")).isEqualTo("pending");
        assertThat(((Number) first.get("attempts")).intValue()).isEqualTo(1);
        assertThat(first.get("waiting")).isEqualTo(true);

        // While it waits, a newer healthy row is shipped.
        String healthyName = "test.healthy." + UUID.randomUUID();
        handlers.subscribe(healthyName, payload -> delivered.incrementAndGet());
        String healthy = insert(demoOrg, healthyName);
        drainOutboxFully();
        assertThat(row(healthy).get("status")).isEqualTo("shipped");

        runToEnd(id);
        runToEnd(foreign);
        assertThat(row(id).get("status")).isEqualTo("dead");
        assertThat(((Number) row(id).get("attempts")).intValue()).isEqualTo(10);

        JsonNode dead = ok(200, "GET", "/admin/outbox/dead?eventName=" + broken, null);
        assertThat(dead.get("data")).hasSize(1);
        assertThat(dead.get("data").get(0).get("id").asString()).isEqualTo(id);
        assertThat(dead.get("data").get(0).get("lastError").asString()).contains("subscriber down");
        assertThat(call("POST", "/admin/outbox/" + foreign + "/retry", null, adminToken).status()).isEqualTo(404);

        fail.set(false);
        int before = delivered.get();
        assertThat(ok(200, "POST", "/admin/outbox/" + id + "/retry", null).get("status").asString())
                .isEqualTo("pending");
        drainOutboxFully();
        assertThat(row(id).get("status")).isEqualTo("shipped");
        assertThat(delivered.get()).isEqualTo(before + 1);
        assertThat(call("POST", "/admin/outbox/" + id + "/retry", null, adminToken).status()).isEqualTo(404);
        // The other organization's dead row is untouched by this organization's bulk retry.
        assertThat(ok(200, "POST", "/admin/outbox/dead/retry?eventName=" + broken, null).get("retried").asInt())
                .isZero();
        assertThat(row(foreign).get("status")).isEqualTo("dead");
    }

    @Test
    @DisplayName("admin outbox routes: 401 without a session, 403 without their permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix(OutboxAdminController.class)).isEqualTo(3);
    }
}
