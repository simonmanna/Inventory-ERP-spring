package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.kernel.health.JobMetrics;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpAgentCommandService;
import com.inventoryerp.lossprevention.application.LpAgentHealthAlertSource;
import com.inventoryerp.lossprevention.application.LpAgentService;
import com.inventoryerp.lossprevention.application.LpEvidenceCipher;
import com.inventoryerp.lossprevention.application.LpEvidenceService;
import com.inventoryerp.lossprevention.application.LpLiveService;
import com.inventoryerp.lossprevention.application.LpVisionJob;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The camera platform (LP1) in operation, after {@link LpVisionIT} set it up: changing agents, cameras and zones, a
 * new enrolment of the same box, clips cut from the buffer, uploads that fail or never come, and the scheduled work
 * (silent agents, expired commands, the health alert, evidence retention, heartbeat pruning, live view sweeping).
 *
 * <p>The scheduled job is not a bean while tests run ({@code app.jobs.enabled=false}), so it is built here from the
 * application's own services and run by hand. Time is moved by back-dating rows, as the services read the system
 * clock.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LpVisionOpsIT extends LpVisionSupport {

    @Autowired
    LpAgentService agents;

    @Autowired
    LpAgentCommandService commands;

    @Autowired
    LpEvidenceService evidence;

    @Autowired
    LpLiveService live;

    @Autowired
    LpAgentHealthAlertSource healthAlerts;

    @Autowired
    JobMetrics metrics;

    String agentId;
    String cameraId;
    String spareCameraId;
    String drawerZone;
    String checkoutZone;
    String cutEvidence;
    String busyEvidence;
    String staleEvidence;

    // ------------------------------------------------------------------ helpers

    LpVisionJob job() {
        return new LpVisionJob(jdbc, agents, commands, evidence, live, metrics);
    }

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

    /**
     * The id of the agent's open command of a kind whose payload names {@code value}, taken the way the box takes it.
     * A heartbeat answer also hands out commands, so one already delivered is looked up in the table instead.
     */
    String command(String kind, String key, String value) {
        for (JsonNode c : agentOk(200, "GET", "/loss-prevention/agents/commands?waitSeconds=0", null)
                .get("commands")) {
            if (kind.equals(c.get("kind").asString()) && value.equals(c.get("payload").get(key).asString())) {
                return c.get("id").asString();
            }
        }
        return jdbc.sql("SELECT id FROM \"LpAgentCommand\" WHERE \"agentId\" = ? AND kind = ? AND payload ->> ? = ?"
                + " ORDER BY \"createdAt\" DESC LIMIT 1").params(agentId, kind, key, value).query(String.class)
                .single();
    }

    JsonNode result(String commandId, Map<String, Object> body) {
        return agentOk(200, "POST", "/loss-prevention/agents/commands/" + commandId + "/result", body);
    }

    /** A clip the box kept two minutes ago, registered as the box does it. */
    String register(String name, byte[] content) {
        Instant end = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> clip = new LinkedHashMap<>();
        clip.put("clipId", name + "-" + tag);
        clip.put("cameraId", cameraId);
        clip.put("zoneId", drawerZone);
        clip.put("startedAt", end.minusSeconds(50).toString());
        clip.put("endedAt", end.toString());
        clip.put("sha256", LpEvidenceCipher.sha256(content));
        clip.put("byteSize", content.length);
        clip.put("contentType", "video/mp4");
        JsonNode r = agentOk(200, "POST", "/loss-prevention/agents/clips", Map.of("clips", List.of(clip)));
        assertThat(r.get("registered").asInt()).as(r.toString()).isEqualTo(1);
        return r.get("results").get(0).get("evidenceId").asString();
    }

    void request(String evidenceId) {
        assertThat(ok(201, "POST", "/loss-prevention/evidence/" + evidenceId + "/request", Map.of("reason",
                "Operations test")).get("state").asString()).isEqualTo("requested");
    }

    Res upload(String evidenceId, byte[] chunk, long total, String sha, String contentType) {
        String query = "?offset=0&total=" + total + "&sha256=" + sha + (contentType == null ? ""
                : "&contentType=" + URLEncoder.encode(contentType, StandardCharsets.UTF_8));
        return bytes("/loss-prevention/evidence/" + evidenceId + "/upload" + query, chunk, "application/octet-stream",
                agentToken);
    }

    String state(String evidenceId) {
        return jdbc.sql("SELECT state FROM \"LpEvidence\" WHERE id = ?").param(evidenceId).query(String.class).single();
    }

    String commandState(String commandId) {
        return jdbc.sql("SELECT state FROM \"LpAgentCommand\" WHERE id = ?").param(commandId).query(String.class)
                .single();
    }

    List<String> trail(String evidenceId) {
        List<String> actions = new ArrayList<>();
        ok(200, "GET", "/loss-prevention/evidence/" + evidenceId, null).get("access")
                .forEach(a -> actions.add(a.get("action").asString()));
        return actions;
    }

    String message(Res r, int status) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(status);
        return r.body().get("message").asString();
    }

    static byte[] content(String text) {
        return text.repeat(500).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    @DisplayName("an agent is renamed, refuses an unknown model or release, and a new code replaces its secret")
    void agentAdministration() throws Exception {
        Enrolled first = createAndEnrol("Ops box " + tag);
        agentId = first.agentId();
        agentToken = first.token();
        List<String> listed = new ArrayList<>();
        ok(200, "GET", "/loss-prevention/agents?branchId=" + branch, null)
                .forEach(a -> listed.add(a.get("id").asString()));
        assertThat(listed).contains(agentId);

        String path = "/loss-prevention/agents/" + agentId;
        assertThat(message(as("PUT", path, Map.of("name", "Ops box " + tag, "desiredModelVersion", "nope")), 400))
                .isEqualTo("Unknown model version 'nope'");
        assertThat(message(as("PUT", path, Map.of("name", "Ops box " + tag, "desiredAgentVersion", "9.9.9")), 400))
                .isEqualTo("Unknown agent release '9.9.9'");
        int version = ok(200, "GET", path, null).get("configVersion").asInt();
        JsonNode renamed = ok(200, "PUT", path, Map.of("name", "  Ops box renamed " + tag + " "));
        assertThat(renamed.get("name").asString()).isEqualTo("Ops box renamed " + tag);
        // Only a new model or release target is a configuration change for the box.
        assertThat(renamed.get("configVersion").asInt()).isEqualTo(version);

        JsonNode catalog = ok(200, "GET", "/loss-prevention/models", null);
        assertThat(catalog.get("models").isArray()).isTrue();
        assertThat(catalog.get("releases").isArray()).isTrue();

        JsonNode code = ok(201, "POST", path + "/enrolment-code", null);
        assertThat(code.get("enrolmentCode").asString()).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
        Enrolled again = enrol(code.get("enrolmentCode").asString());
        assertThat(again.agentId()).isEqualTo(agentId);
        assertThat(again.clientId()).isEqualTo(first.clientId());
        assertThat(tokenRequest(first.clientId(), first.clientSecret()).statusCode()).isEqualTo(401);
        agentToken = again.token();
        assertThat(agentOk(200, "GET", "/loss-prevention/agents/config", null).get("agentId").asString())
                .isEqualTo(agentId);
        List<String> kinds = new ArrayList<>();
        ok(200, "GET", path, null).get("timeline").forEach(t -> kinds.add(t.get("kind").asString()));
        assertThat(kinds).contains("created", "updated", "code_issued", "enrolled");
        assertThat(kinds.stream().filter("enrolled"::equals).count()).isEqualTo(2);
    }

    @Test
    @Order(2)
    @DisplayName("cameras and zones are listed, read and changed; a drawer is calibrated only when it can be")
    void camerasAndZones() {
        cameraId = ok(201, "POST", "/loss-prevention/cameras", Map.of("agentId", agentId, "name", "Ops cam " + tag,
                "streamAlias", "ops-1")).get("id").asString();
        spareCameraId = ok(201, "POST", "/loss-prevention/cameras", Map.of("agentId", agentId, "name",
                "Ops spare " + tag, "streamAlias", "ops-2", "analysed", false)).get("id").asString();
        List<String> listed = new ArrayList<>();
        ok(200, "GET", "/loss-prevention/cameras?branchId=" + branch + "&agentId=" + agentId, null)
                .forEach(c -> listed.add(c.get("id").asString()));
        assertThat(listed).containsExactlyInAnyOrder(cameraId, spareCameraId);

        String spare = "/loss-prevention/cameras/" + spareCameraId;
        assertThat(message(as("PUT", spare, Map.of("name", "Ops spare", "streamAlias", "ops-1")), 409))
                .isEqualTo("Another camera of this agent already uses this streamAlias");
        JsonNode changed = ok(200, "PUT", spare, Map.of("name", "Back door " + tag, "streamAlias", "ops-2", "enabled",
                false, "notes", "Spare"));
        assertThat(changed.get("name").asString()).isEqualTo("Back door " + tag);
        assertThat(changed.get("enabled").asBoolean()).isFalse();
        // Left out, analysed keeps what it was.
        assertThat(changed.get("analysed").asBoolean()).isFalse();
        assertThat(changed.get("zones").size()).isZero();

        drawerZone = ok(201, "POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Drawer",
                "zoneType", "cash_drawer", "linkedType", "till", "linkedId", till, "polygon", square())).get("id")
                .asString();
        checkoutZone = ok(201, "POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Customer side",
                "zoneType", "checkout", "linkedType", "till", "linkedId", till, "polygon", square())).get("id")
                .asString();
        assertThat(ok(200, "GET", "/loss-prevention/zones/" + drawerZone, null).get("zoneType").asString())
                .isEqualTo("cash_drawer");
        assertThat(ok(200, "GET", "/loss-prevention/cameras/" + cameraId, null).get("zones").size()).isEqualTo(2);
        String drawer = "/loss-prevention/zones/" + drawerZone;
        JsonNode moved = ok(200, "PUT", drawer, Map.of("name", "Drawer left", "polygon", square(), "linkedType",
                "till", "linkedId", till, "params", Map.of("openThreshold", 0.3)));
        assertThat(moved.get("name").asString()).isEqualTo("Drawer left");
        assertThat(moved.get("effectiveParams").get("openThreshold").asDouble()).isEqualTo(0.3);
        assertThat(message(as("PUT", drawer, Map.of("name", "Drawer", "polygon", square(), "linkedType", "till",
                "linkedId", "no-such-till")), 400)).isEqualTo("Till not found at the camera's branch");
        assertThat(message(as("POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Store",
                "zoneType", "storage", "linkedType", "location", "linkedId", "no-such-location", "polygon",
                square())), 400)).isEqualTo("Location not found at the camera's branch");
        String location = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND (\"branchId\""
                + " IS NULL OR \"branchId\" = ?) ORDER BY id LIMIT 1").params(demoOrg, branch).query(String.class)
                .optional().orElse(null);
        if (location != null) {
            JsonNode store = ok(201, "POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Store",
                    "zoneType", "storage", "linkedType", "location", "linkedId", location, "polygon", square()));
            assertThat(store.get("linkedId").asString()).isEqualTo(location);
            ok(200, "DELETE", "/loss-prevention/zones/" + store.get("id").asString(), null);
        }

        // Calibration needs the box online and the camera on.
        assertThat(message(as("POST", drawer + "/calibrate", null), 409))
                .isEqualTo("The edge agent of this camera is offline");
        assertThat(heartbeat().get("health").asString()).isIn("online", "degraded");
        assertThat(message(as("POST", "/loss-prevention/zones/" + checkoutZone + "/calibrate", null), 400))
                .isEqualTo("Only a cash_drawer zone is calibrated");
        JsonNode calibrated = ok(201, "POST", drawer + "/calibrate", null);
        assertThat(calibrated.get("zoneId").asString()).isEqualTo(drawerZone);
        String calibration = calibrated.get("commandId").asString();
        assertThat(command("calibrate", "zoneId", drawerZone)).isEqualTo(calibration);
        assertThat(result(calibration, Map.of("status", "done", "message", "Reference taken")).get("state")
                .asString()).isEqualTo("done");

        String offDrawer = ok(201, "POST", "/loss-prevention/zones", Map.of("cameraId", spareCameraId, "name",
                "Spare drawer", "zoneType", "cash_drawer", "linkedType", "till", "linkedId", till, "polygon",
                square())).get("id").asString();
        String off = "/loss-prevention/zones/" + offDrawer;
        assertThat(message(as("POST", off + "/calibrate", null), 409)).isEqualTo("The camera is switched off");
        ok(200, "DELETE", off, null);
        assertThat(message(as("DELETE", off, null), 400)).isEqualTo("The zone is already retired");
        assertThat(message(as("PUT", off, Map.of("name", "Again", "polygon", square())), 400))
                .isEqualTo("A retired zone cannot be changed");
        assertThat(message(as("POST", off + "/calibrate", null), 400)).isEqualTo("A retired zone cannot be changed");
        assertThat(ok(200, "GET", "/loss-prevention/zones?cameraId=" + spareCameraId, null).size()).isZero();
        assertThat(ok(200, "GET", "/loss-prevention/zones?cameraId=" + spareCameraId + "&includeRetired=true", null)
                .size()).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("a clip cut from the buffer is checked, then uploaded with the checksum and type the box sends")
    void bufferCut() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String path = "/loss-prevention/evidence/range";
        assertThat(message(as("POST", path, Map.of("cameraId", cameraId, "from", now.minusSeconds(60).toString(), "to",
                now.minusSeconds(60).toString())), 400)).isEqualTo("to must be after from");
        assertThat(message(as("POST", path, Map.of("cameraId", cameraId, "from", now.minusSeconds(700).toString(),
                "to", now.minusSeconds(60).toString())), 400))
                .isEqualTo("A clip from the buffer covers at most 10 minutes");
        assertThat(message(as("POST", path, Map.of("cameraId", cameraId, "from", now.minusSeconds(60).toString(), "to",
                now.plusSeconds(300).toString())), 400)).isEqualTo("to must not be in the future");
        Instant old = now.minus(400, ChronoUnit.DAYS);
        assertThat(message(as("POST", path, Map.of("cameraId", cameraId, "from", old.toString(), "to",
                old.plusSeconds(60).toString())), 400)).startsWith("The edge box keeps video for ");
        assertThat(message(as("POST", path, Map.of("cameraId", cameraId, "from", "yesterday", "to", now.toString())),
                400)).isEqualTo("from must be an ISO-8601 instant");
        assertThat(message(as("POST", path, Map.of("cameraId", "no-such-camera", "from", now.minusSeconds(90)
                .toString(), "to", now.minusSeconds(30).toString())), 404)).isEqualTo("Camera not found");

        JsonNode cut = ok(201, "POST", path, Map.of("cameraId", cameraId, "from", now.minusSeconds(90).toString(),
                "to", now.minusSeconds(30).toString(), "reason", "Look at the till"));
        cutEvidence = cut.get("id").asString();
        assertThat(cut.get("origin").asString()).isEqualTo("range");
        assertThat(cut.get("state").asString()).isEqualTo("requested");
        assertThat(cut.get("sha256").isNull()).isTrue();
        String upload = command("clip_upload", "evidenceId", cutEvidence);

        byte[] clip = content("cut " + tag + " ");
        String sha = LpEvidenceCipher.sha256(clip);
        assertThat(message(upload(cutEvidence, clip, clip.length, sha, "video/x-unknown"), 400))
                .startsWith("contentType must be one of: ");
        Res done = upload(cutEvidence, clip, clip.length, sha, "video/webm");
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(done.body().get("complete").asBoolean()).isTrue();
        JsonNode shown = ok(200, "GET", "/loss-prevention/evidence/" + cutEvidence, null);
        assertThat(shown.get("sha256").asString()).isEqualTo(sha);
        assertThat(shown.get("contentType").asString()).isEqualTo("video/webm");
        assertThat(shown.get("retainUntil").isNull()).isFalse();
        assertThat(commandState(upload)).isEqualTo("done");
        HttpResponse<byte[]> bytes = downloadBytes("/loss-prevention/evidence/" + cutEvidence + "/content");
        assertThat(bytes.statusCode()).isEqualTo(200);
        assertThat(bytes.body()).isEqualTo(clip);
        // Sending the last chunk again changes nothing.
        assertThat(upload(cutEvidence, clip, clip.length, sha, null).body().get("complete").asBoolean()).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("an upload that does not match its capture, a clip the box lost, and a failed upload that waits")
    void failedUploads() {
        byte[] first = content("altered " + tag + " ");
        String altered = register("altered", first);
        request(altered);
        assertThat(message(upload(altered, first, first.length, LpEvidenceCipher.sha256(content("other")), null),
                409)).isEqualTo("Checksum does not match the clip registered at capture");
        assertThat(state(altered)).isEqualTo("integrity_failed");
        assertThat(trail(altered)).contains("request", "integrity_failed");

        String lost = register("lost", content("lost " + tag + " "));
        request(lost);
        JsonNode reported = result(command("clip_upload", "evidenceId", lost), Map.of("status", "failed", "code",
                "not_found", "message", "The clip rotated out of the buffer"));
        assertThat(reported.get("state").asString()).isEqualTo("failed");
        assertThat(reported.get("firstReport").asBoolean()).isTrue();
        assertThat(state(lost)).isEqualTo("missing");
        assertThat(trail(lost)).contains("missing");
        assertThat(message(as("POST", "/loss-prevention/evidence/" + lost + "/request", Map.of()), 409))
                .isEqualTo("The edge agent no longer has this clip");

        busyEvidence = register("busy", content("busy " + tag + " "));
        request(busyEvidence);
        String busy = command("clip_upload", "evidenceId", busyEvidence);
        result(busy, Map.of("status", "failed", "code", "busy", "message", "Upload slot taken"));
        assertThat(state(busyEvidence)).isEqualTo("on_agent");
        // A second report of the same command changes nothing.
        assertThat(result(busy, Map.of("status", "failed", "code", "not_found")).get("firstReport").asBoolean())
                .isFalse();
        assertThat(state(busyEvidence)).isEqualTo("on_agent");
    }

    @Test
    @Order(5)
    @DisplayName("a live view the box cannot start ends; a switched-off camera has none")
    void liveViewFailure() {
        JsonNode started = ok(201, "POST", "/loss-prevention/cameras/" + cameraId + "/live", null);
        String session = started.get("sessionId").asString();
        result(command("live_start", "sessionId", session), Map.of("status", "failed", "message", "Stream busy"));
        Res frame = bytes("/loss-prevention/agents/live/" + session + "/frames", jpeg(), "image/jpeg", agentToken);
        assertThat(frame.status()).as(frame.raw().body()).isEqualTo(200);
        assertThat(frame.body().get("continue").asBoolean()).isFalse();
        assertThat(message(as("DELETE", "/loss-prevention/live/" + session, null), 404))
                .isEqualTo("Live view not found");
        assertThat(message(as("POST", "/loss-prevention/cameras/" + spareCameraId + "/live", null), 409))
                .isEqualTo("The camera is switched off");
    }

    @Test
    @Order(6)
    @DisplayName("the scheduled work: silent agents go offline, commands expire, the alert is raised, retention runs")
    void scheduledWork() {
        LpVisionJob job = job();
        String lapsing = register("lapsing", content("lapsing " + tag + " "));
        request(lapsing);
        String lapsingUpload = command("clip_upload", "evidenceId", lapsing);
        jdbc.sql("UPDATE \"LpAgentCommand\" SET \"expiresAt\" = CURRENT_TIMESTAMP - interval '1 minute' WHERE id = ?")
                .param(lapsingUpload).update();
        jdbc.sql("UPDATE \"LpAgent\" SET \"lastHeartbeatAt\" = CURRENT_TIMESTAMP - interval '2 hours', \"enrolledAt\" ="
                + " CURRENT_TIMESTAMP - interval '3 hours' WHERE id = ?").param(agentId).update();

        job.tick();
        assertThat(jdbc.sql("SELECT health FROM \"LpAgent\" WHERE id = ?").param(agentId).query(String.class).single())
                .isEqualTo("offline");
        assertThat(commandState(lapsingUpload)).isEqualTo("expired");
        // Asked for but never taken: the clip waits on the box again.
        assertThat(state(lapsing)).isEqualTo("on_agent");
        List<String> timeline = new ArrayList<>();
        ok(200, "GET", "/loss-prevention/agents/" + agentId, null).get("timeline")
                .forEach(t -> timeline.add(t.get("kind").asString()));
        // A health change is written to the timeline under the new health.
        assertThat(timeline).contains("offline");
        assertThat(message(as("POST", "/loss-prevention/cameras/" + cameraId + "/live", null), 409))
                .isEqualTo("The edge agent of this camera is offline");

        String subject = "agent:" + agentId + ":offline";
        List<AlertItem> now = TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> healthAlerts.collect(new AlertScan(demoOrg, LocalDate.now(), BigDecimal.ZERO)));
        AlertItem alert = now.stream().filter(i -> subject.equals(i.subjectKey())).findFirst().orElseThrow();
        assertThat(alert.severity()).isEqualTo(AlertItem.CRITICAL);
        assertThat(alert.link()).isEqualTo("/loss-prevention");
        List<AlertItem> dayLater = TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> healthAlerts.collect(new AlertScan(demoOrg, LocalDate.now(), new BigDecimal("24"))));
        assertThat(dayLater).noneMatch(i -> subject.equals(i.subjectKey()));

        // Retention: the cut has no case, so it is kept evidenceClosedCaseDays after upload.
        jdbc.sql("UPDATE \"LpEvidence\" SET \"uploadedAt\" = CURRENT_TIMESTAMP - interval '4000 days' WHERE id = ?")
                .param(cutEvidence).update();
        // The box keeps video evidenceBufferDays: a clip that ended long ago is gone from it.
        jdbc.sql("UPDATE \"LpEvidence\" SET \"startedAt\" = \"startedAt\" - interval '400 days', \"endedAt\" ="
                + " \"endedAt\" - interval '400 days' WHERE id = ?").param(busyEvidence).update();
        // Asked for days ago, with no command left for the box: the request lapses.
        staleEvidence = register("stale", content("stale " + tag + " "));
        request(staleEvidence);
        jdbc.sql("UPDATE \"LpAgentCommand\" SET state = 'expired' WHERE kind = 'clip_upload' AND payload ->>"
                + " 'evidenceId' = ?").param(staleEvidence).update();
        jdbc.sql("UPDATE \"LpEvidence\" SET \"updatedAt\" = CURRENT_TIMESTAMP - interval '2 days' WHERE id = ?")
                .param(staleEvidence).update();
        jdbc.sql("UPDATE \"LpAgentHeartbeat\" SET \"receivedAt\" = CURRENT_TIMESTAMP - interval '400 days' WHERE"
                + " \"agentId\" = ?").param(agentId).update();

        job.housekeeping();
        Map<String, Object> deleted = jdbc.sql("SELECT state, \"deleteReason\", \"storageKey\" FROM \"LpEvidence\""
                + " WHERE id = ?").param(cutEvidence).query().singleRow();
        assertThat(deleted.get("state")).isEqualTo("deleted");
        assertThat(deleted.get("deleteReason")).isEqualTo("retention");
        assertThat(deleted.get("storageKey")).isNull();
        assertThat(trail(cutEvidence)).contains("uploaded", "deleted");
        assertThat(message(as("GET", "/loss-prevention/evidence/" + cutEvidence + "/content", null), 410))
                .isEqualTo("The clip was deleted under the retention rules");
        assertThat(state(busyEvidence)).isEqualTo("missing");
        assertThat(state(staleEvidence)).isEqualTo("on_agent");
        assertThat(jdbc.sql("SELECT count(*) FROM \"LpAgentHeartbeat\" WHERE \"agentId\" = ?").param(agentId)
                .query(Long.class).single()).isZero();

        // Live views live in memory; sweeping with none due changes nothing.
        job.liveSweep();
    }

    @Test
    @Order(7)
    @DisplayName("revoking the agent leaves the reused test database without an active box from this run")
    void revoke() {
        assertThat(ok(201, "POST", "/loss-prevention/agents/" + agentId + "/revoke", Map.of("reason",
                "Operations test done")).get("status").asString()).isEqualTo("revoked");
        assertThat(message(as("PUT", "/loss-prevention/agents/" + agentId, Map.of("name", "Too late")), 400))
                .isEqualTo("A revoked agent cannot be changed");
        assertThat(message(as("PUT", "/loss-prevention/cameras/" + cameraId, Map.of("name", "Too late",
                "streamAlias", "ops-1")), 400)).isEqualTo("A camera of a revoked agent cannot be changed");
        assertThat(message(as("POST", "/loss-prevention/evidence/" + staleEvidence + "/request", Map.of()), 409))
                .isEqualTo("The edge agent of this camera is not enrolled or was revoked");
    }
}
