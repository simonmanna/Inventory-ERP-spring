package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * LP1 §12 follow-ups: an agent's commands and their status, deleting a camera, and each zone's {@code createdAt} in
 * the agent's configuration.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LpVisionFollowUpIT extends LpVisionSupport {

    String agentId;
    String cameraId;
    String zoneId;
    String snapshotCommand;

    JsonNode heartbeat() {
        Map<String, Object> beat = new LinkedHashMap<>();
        beat.put("agentVersion", "0.1.0");
        beat.put("configVersion", jdbc.sql("SELECT \"configVersion\" FROM \"LpAgent\" WHERE id = ?").param(agentId)
                .query(Integer.class).single());
        beat.put("clockDriftMs", 0);
        beat.put("diskFreeBytes", 50_000_000_000L);
        beat.put("diskTotalBytes", 120_000_000_000L);
        beat.put("queueLength", 0);
        beat.put("observedThrough", Instant.now().minusSeconds(5).toString());
        beat.put("cameras", List.of(Map.of("cameraId", cameraId, "streamState", "ok")));
        return agentOk(200, "POST", "/loss-prevention/agents/heartbeat", beat);
    }

    String message(Res r, int status) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(status);
        return r.body().get("message").asString();
    }

    @Test
    @Order(1)
    @DisplayName("the commands sent to an agent are listed with their state; one is read with its result")
    void commands() throws Exception {
        Enrolled e = createAndEnrol("Follow-up box " + tag);
        agentId = e.agentId();
        agentToken = e.token();
        cameraId = ok(201, "POST", "/loss-prevention/cameras", Map.of("agentId", agentId, "name", "Till cam " + tag,
                "streamAlias", "fu-1")).get("id").asString();
        zoneId = ok(201, "POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Drawer", "zoneType",
                "cash_drawer", "linkedType", "till", "linkedId", till, "polygon", square())).get("id").asString();
        assertThat(heartbeat().get("health").asString()).isIn("online", "degraded");

        snapshotCommand = ok(201, "POST", "/loss-prevention/cameras/" + cameraId + "/snapshot", null).get("commandId")
                .asString();
        String base = "/loss-prevention/agents/" + agentId + "/commands";
        JsonNode one = ok(200, "GET", base + "/" + snapshotCommand, null);
        assertThat(one.get("kind").asString()).isEqualTo("snapshot");
        assertThat(one.get("state").asString()).isIn("pending", "delivered");
        assertThat(one.get("payload").get("cameraId").asString()).isEqualTo(cameraId);

        List<String> kinds = new ArrayList<>();
        ok(200, "GET", base, null).forEach(c -> kinds.add(c.get("kind").asString()));
        assertThat(kinds).contains("snapshot", "config_changed");
        JsonNode snapshots = ok(200, "GET", base + "?kind=snapshot&limit=5", null);
        assertThat(snapshots.size()).isEqualTo(1);
        assertThat(snapshots.get(0).get("id").asString()).isEqualTo(snapshotCommand);

        // The box takes it and reports: the status shows the result.
        agentOk(200, "GET", "/loss-prevention/agents/commands?waitSeconds=0", null);
        agentOk(200, "POST", "/loss-prevention/agents/commands/" + snapshotCommand + "/result", Map.of("status",
                "failed", "message", "Camera busy"));
        JsonNode done = ok(200, "GET", base + "/" + snapshotCommand, null);
        assertThat(done.get("state").asString()).isEqualTo("failed");
        assertThat(done.get("result").get("message").asString()).isEqualTo("Camera busy");
        assertThat(done.get("completedAt").isNull()).isFalse();
        assertThat(ok(200, "GET", base + "?state=failed", null).size()).isGreaterThanOrEqualTo(1);

        assertThat(message(as("GET", base + "?state=lost", null), 400))
                .isEqualTo("state must be one of: pending, delivered, done, failed, expired");
        assertThat(message(as("GET", base + "?kind=reboot", null), 400)).startsWith("kind must be one of:");
        assertThat(message(as("GET", base + "/no-such-command", null), 404)).isEqualTo("Command not found");
        assertThat(as("GET", "/loss-prevention/agents/no-such-agent/commands", null).status()).isEqualTo(404);
    }

    @Test
    @Order(2)
    @DisplayName("the agent's configuration carries when each zone was drawn")
    void zoneCreatedAt() {
        JsonNode config = agentOk(200, "GET", "/loss-prevention/agents/config", null);
        JsonNode zone = null;
        for (JsonNode c : config.get("cameras")) {
            for (JsonNode z : c.get("zones")) {
                if (zoneId.equals(z.get("id").asString())) {
                    zone = z;
                }
            }
        }
        assertThat(zone).isNotNull();
        Instant created = Instant.parse(zone.get("createdAt").asString());
        assertThat(created).isBefore(Instant.now().plusSeconds(5)).isAfter(Instant.now().minusSeconds(600));
    }

    @Test
    @Order(3)
    @DisplayName("a deleted camera disappears, its zones retire, its alias is free and the agent stops reading it")
    void deleteCamera() {
        String path = "/loss-prevention/cameras/" + cameraId;
        int version = jdbc.sql("SELECT \"configVersion\" FROM \"LpAgent\" WHERE id = ?").param(agentId)
                .query(Integer.class).single();
        JsonNode deleted = ok(200, "DELETE", path, null);
        assertThat(deleted.get("deleted").asBoolean()).isTrue();
        assertThat(deleted.get("zonesRetired").asInt()).isEqualTo(1);

        assertThat(message(as("GET", path, null), 404)).isEqualTo("Camera not found");
        assertThat(message(as("DELETE", path, null), 404)).isEqualTo("Camera not found");
        assertThat(message(as("POST", path + "/snapshot", null), 404)).isEqualTo("Camera not found");
        assertThat(message(as("POST", path + "/live", null), 404)).isEqualTo("Camera not found");
        List<String> listed = new ArrayList<>();
        ok(200, "GET", "/loss-prevention/cameras?agentId=" + agentId, null)
                .forEach(c -> listed.add(c.get("id").asString()));
        assertThat(listed).doesNotContain(cameraId);
        assertThat(ok(200, "GET", "/loss-prevention/agents/" + agentId, null).get("cameras").size()).isZero();
        assertThat(ok(200, "GET", "/loss-prevention/zones/" + zoneId, null).get("retiredAt").isNull()).isFalse();

        JsonNode config = agentOk(200, "GET", "/loss-prevention/agents/config", null);
        assertThat(config.get("cameras").size()).isZero();
        assertThat(config.get("configVersion").asInt()).isGreaterThan(version);

        // The row stays for history; the stream alias can be used again.
        Map<String, Object> row = jdbc.sql("SELECT enabled, \"deletedAt\" IS NOT NULL AS deleted FROM \"LpCamera\""
                + " WHERE id = ?").param(cameraId).query().singleRow();
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("deleted")).isEqualTo(true);
        String again = ok(201, "POST", "/loss-prevention/cameras", Map.of("agentId", agentId, "name",
                "Till cam again " + tag, "streamAlias", "fu-1")).get("id").asString();
        ok(200, "DELETE", "/loss-prevention/cameras/" + again, null);
        List<String> audit = jdbc.sql("SELECT action::text FROM \"AuditLog\" WHERE \"entityId\" = ? ORDER BY"
                + " \"createdAt\"").param(cameraId).query(String.class).list();
        assertThat(audit).contains("delete");
    }

    @Test
    @Order(4)
    @DisplayName("the new routes need lp:configure")
    void authorization() {
        String viewer = weakToken();
        assertThat(call("GET", "/loss-prevention/agents/" + agentId + "/commands", null, viewer).status())
                .isEqualTo(403);
        assertThat(call("GET", "/loss-prevention/agents/" + agentId + "/commands/" + snapshotCommand, null, viewer)
                .status()).isEqualTo(403);
        assertThat(call("DELETE", "/loss-prevention/cameras/" + cameraId, null, viewer).status()).isEqualTo(403);
        ok(201, "POST", "/loss-prevention/agents/" + agentId + "/revoke", Map.of("reason", "Follow-up test done"));
    }
}
