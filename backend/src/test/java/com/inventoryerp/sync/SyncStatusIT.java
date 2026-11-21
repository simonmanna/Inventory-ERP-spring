package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** ADR-S083 S9: the sync status dashboard and metrics. */
class SyncStatusIT extends ApiTestSupport {

    @Autowired
    MeterRegistry meters;

    static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.path(field).asString())) {
                return n;
            }
        }
        return null;
    }

    @Test
    @DisplayName("devices, branch tiles, today's ops and dead letters; push counter; tenant-scoped")
    void dashboard() {
        String admin = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        String branch = jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? ORDER BY code LIMIT 1")
                .param(demoOrg).query(String.class).single();
        String name = "Status " + UUID.randomUUID().toString().substring(0, 6);
        JsonNode device = ok(201, "POST", "/sync/devices/register", Map.of("name", name, "platform", "web",
                "branchId", branch));
        String deviceId = device.get("id").asString();
        double before = meters.find("erp.sync.push.ops").tag("status", "applied").counters().stream()
                .mapToDouble(c -> c.count()).sum();
        String cust = UUID.randomUUID().toString();
        Res push = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(Map.of("opId", cust,
                "deviceSeq", 1, "type", "customer.upsert", "actorUserId", admin, "occurredAt", Instant.now().toString(),
                "payload", Map.of("clientId", cust, "id", cust, "name", name)))),
                Map.of("X-Device-Token", device.get("deviceToken").asString(), "X-Sync-Protocol", "2.0"));
        assertThat(push.status()).as(push.raw().body()).isEqualTo(201);
        assertThat(meters.find("erp.sync.push.ops").tag("status", "applied").counters().stream()
                .mapToDouble(c -> c.count()).sum()).isEqualTo(before + 1);
        assertThat(meters.find("erp.sync.devices.stale").gauge()).isNotNull();
        assertThat(meters.find("erp.sync.lag.seconds.max").gauge().value()).isGreaterThanOrEqualTo(0);

        JsonNode status = ok(200, "GET", "/sync/status", null);
        JsonNode mine = find(status.get("devices"), "id", deviceId);
        assertThat(mine).isNotNull();
        assertThat(mine.get("state").asString()).isEqualTo("online");
        assertThat(mine.get("opsToday").asLong()).isEqualTo(1);
        assertThat(mine.get("protocolVersion").asString()).startsWith("2");
        assertThat(mine.get("lastSyncAt").isNull()).isFalse();
        JsonNode tile = find(status.get("branches"), "branchId", branch);
        assertThat(tile).isNotNull();
        assertThat(tile.get("tills").asInt()).isPositive();
        assertThat(tile.get("tillsOnline").asInt()).isPositive();
        assertThat(status.get("summary").get("appliedToday").asLong()).isPositive();
        assertThat(status.get("summary").get("online").asInt()).isPositive();
        assertThat(status.has("reconciliation")).isTrue();
        assertThat(status.get("opsToday").get("applied").asLong()).isPositive();

        Res otherRes = call("GET", "/sync/status", null, otherToken);
        assertThat(otherRes.status()).isEqualTo(200);
        JsonNode other = otherRes.body();
        assertThat(find(other.get("devices"), "id", deviceId)).as("another organisation").isNull();
        assertThat(call("GET", "/sync/status", null, weakToken()).status()).isEqualTo(403);
    }
}
