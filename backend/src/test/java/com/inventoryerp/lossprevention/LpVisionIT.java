package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.lossprevention.application.LpEvidenceCipher;
import com.inventoryerp.lossprevention.web.LossPreventionAgentApiController;
import com.inventoryerp.lossprevention.web.LossPreventionAgentController;
import com.inventoryerp.lossprevention.web.LossPreventionCameraController;
import com.inventoryerp.lossprevention.web.LossPreventionEvidenceController;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * The camera platform (LP1) end to end, with the edge agent played by the test: create an agent, enrol it with its
 * one-time code, take a token, read the configuration, send heartbeats and observations, register a clip, upload it
 * when asked, and see it checked, encrypted, viewed, exported and linked to the case a vision rule opened. Then live
 * view and stills, revocation, tenant isolation and the permission of every route.
 *
 * <p>No camera and no detector run here: the observations are what an agent would send. Detection accuracy is not
 * measured by this suite.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LpVisionIT extends LpVisionSupport {

    /** A past calendar day nobody else uses: DEMO is shared between suites. */
    // A past day within the ledger's 24-month retention, in a range no other loss-prevention suite uses.
    final LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(40 + (long) (Math.random() * 200));
    final byte[] clip = ("clip " + tag + " ").repeat(40_000).getBytes(StandardCharsets.UTF_8);
    String agentId;
    String cameraId;
    String drawerZone;
    String checkoutZone;
    String observationEventId;
    String evidenceId;

    // ------------------------------------------------------------------ helpers

    Map<String, Object> observation(String zone, String type, String time, Map<String, Object> payload) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("source", "edge-" + tag);
        e.put("sourceEventId", "obs-" + UUID.randomUUID());
        e.put("branchId", branch);
        e.put("deviceId", zone);
        e.put("businessDate", day.toString());
        e.put("occurredAt", day + "T" + time + "Z");
        e.put("schemaVersion", 1);
        e.put("type", type);
        e.put("payload", payload);
        e.put("evidenceLevel", "zone");
        e.put("evidenceSource", "camera");
        e.put("observationStatus", "observed");
        e.put("confidence", 0.93);
        return e;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    @DisplayName("an agent is created with a one-time code; enrolling with it gives the box its credentials once")
    void enrol() throws Exception {
        fixtures();
        JsonNode created = ok(201, "POST", "/loss-prevention/agents", Map.of("branchId", branch, "name",
                "Front till box " + tag));
        agentId = created.get("id").asString();
        assertThat(created.get("status").asString()).isEqualTo("pending");
        assertThat(created.has("enrolCodeHash")).isFalse();
        String code = created.get("enrolmentCode").asString();
        assertThat(code).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");

        Res wrong = call("POST", "/loss-prevention/agents/enrol", Map.of("code", "AAAA-BBBB-CCCC"), (String) null);
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.body().get("message").asString()).isEqualTo("Enrolment code is invalid or has expired");

        // Typed by hand: case and spaces do not matter.
        Res enrolled = call("POST", "/loss-prevention/agents/enrol", Map.of("code", code.toLowerCase().replace("-",
                " "), "hostname", "lp-box-" + tag, "agentVersion", "0.1.0"), (String) null);
        assertThat(enrolled.status()).as(enrolled.raw().body()).isEqualTo(201);
        JsonNode creds = enrolled.body();
        assertThat(creds.get("agentId").asString()).isEqualTo(agentId);
        assertThat(creds.get("branchId").asString()).isEqualTo(branch);
        assertThat(creds.get("tokenUrl").asString()).isEqualTo("/api/v1/oauth/token");
        assertThat(creds.get("apiBase").asString()).isEqualTo("/api/v1");
        assertThat(creds.get("scope").asString()).isEqualTo("lp:ingest");
        assertThat(creds.get("heartbeatSeconds").asInt()).isEqualTo(60);

        assertThat(call("POST", "/loss-prevention/agents/enrol", Map.of("code", code), (String) null).status())
                .as("a code works once").isEqualTo(400);
        agentToken = clientToken(creds.get("clientId").asString(), creds.get("clientSecret").asString());
        JsonNode shown = ok(200, "GET", "/loss-prevention/agents/" + agentId, null);
        assertThat(shown.get("status").asString()).isEqualTo("active");
        assertThat(shown.get("hostname").asString()).isEqualTo("lp-box-" + tag);
        List<String> kinds = new ArrayList<>();
        shown.get("timeline").forEach(t -> kinds.add(t.get("kind").asString()));
        assertThat(kinds).contains("created", "code_issued", "enrolled");
    }

    @Test
    @Order(2)
    @DisplayName("a camera is a name and an alias, never an address; zones are checked against their type")
    void camerasAndZones() {
        Res address = as("POST", "/loss-prevention/cameras", Map.of("agentId", agentId, "name", "Till cam",
                "streamAlias", "rtsp://admin:secret@10.0.0.5/stream1"));
        assertThat(address.status()).isEqualTo(400);
        assertThat(address.body().get("message").asString()).isEqualTo(
                "streamAlias is a name, not an address: enter the camera address and password on the edge box");
        JsonNode camera = ok(201, "POST", "/loss-prevention/cameras", Map.of("agentId", agentId, "name",
                "Till cam " + tag, "streamAlias", "till-1"));
        cameraId = camera.get("id").asString();
        assertThat(camera.get("branchId").asString()).isEqualTo(branch);
        Res twice = as("POST", "/loss-prevention/cameras", Map.of("agentId", agentId, "name", "Other", "streamAlias",
                "till-1"));
        assertThat(twice.status()).isEqualTo(409);

        Res unlinked = as("POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Drawer",
                "zoneType", "cash_drawer", "polygon", square()));
        assertThat(unlinked.body().get("message").asString()).isEqualTo("A cash_drawer zone must be linked to a till");
        Res entrance = as("POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Door",
                "zoneType", "entrance", "polygon", square()));
        assertThat(entrance.body().get("message").asString())
                .isEqualTo("An entrance zone must be a counting line of 2 points");
        Res outside = as("POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Drawer",
                "zoneType", "cash_drawer", "linkedType", "till", "linkedId", till, "polygon", List.of(List.of(0.1,
                        0.1), List.of(1.5, 0.1), List.of(0.5, 0.5))));
        assertThat(outside.body().get("message").asString())
                .isEqualTo("Each point of polygon must be [x, y] with x and y from 0 to 1");
        Res badParam = as("POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Drawer",
                "zoneType", "cash_drawer", "linkedType", "till", "linkedId", till, "polygon", square(), "params",
                Map.of("openThreshold", 3)));
        assertThat(badParam.body().get("message").asString()).isEqualTo("params.openThreshold must be between 0 and 1");

        JsonNode drawer = ok(201, "POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Drawer",
                "zoneType", "cash_drawer", "linkedType", "till", "linkedId", till, "polygon", square(), "params",
                Map.of("openThreshold", 0.25)));
        drawerZone = drawer.get("id").asString();
        assertThat(drawer.get("effectiveParams").get("openThreshold").asDouble()).isEqualTo(0.25);
        assertThat(drawer.get("effectiveParams").get("method").asString()).isEqualTo("reference_diff");
        checkoutZone = ok(201, "POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Customer side",
                "zoneType", "checkout", "linkedType", "till", "linkedId", till, "polygon", square())).get("id")
                .asString();
        JsonNode door = ok(201, "POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Door",
                "zoneType", "entrance", "inSide", "left", "polygon", List.of(List.of(0.1, 0.9), List.of(0.9, 0.9))));
        ok(200, "DELETE", "/loss-prevention/zones/" + door.get("id").asString(), null);
        assertThat(ok(200, "GET", "/loss-prevention/zones?cameraId=" + cameraId, null).size()).isEqualTo(2);
        // Every change raised the configuration version: created at 1, a camera and four zone changes.
        assertThat(ok(200, "GET", "/loss-prevention/agents/" + agentId, null).get("configVersion").asInt())
                .isEqualTo(6);
    }

    @Test
    @Order(3)
    @DisplayName("the box reads its configuration and reports heartbeats; a user cannot call the agent routes")
    void protocol() {
        JsonNode config = agentOk(200, "GET", "/loss-prevention/agents/config", null);
        assertThat(config.get("agentId").asString()).isEqualTo(agentId);
        assertThat(config.get("configVersion").asInt()).isEqualTo(6);
        JsonNode cam = config.get("cameras").get(0);
        assertThat(cam.get("streamAlias").asString()).isEqualTo("till-1");
        assertThat(cam.get("zones").size()).isEqualTo(2);
        assertThat(config.get("model").isNull()).isTrue();
        Res user = as("GET", "/loss-prevention/agents/config", null);
        assertThat(user.status()).isEqualTo(403);
        assertThat(user.body().get("message").asString()).isEqualTo("Only an enrolled edge agent can call this route");

        Map<String, Object> beat = new LinkedHashMap<>();
        beat.put("agentVersion", "0.1.0");
        beat.put("configVersion", 6);
        beat.put("clockDriftMs", 12);
        beat.put("diskFreeBytes", 50_000_000_000L);
        beat.put("diskTotalBytes", 120_000_000_000L);
        beat.put("queueLength", 0);
        beat.put("observedThrough", Instant.now().minusSeconds(5).toString());
        beat.put("cameras", List.of(Map.of("cameraId", cameraId, "streamState", "ok", "fps", 5.0, "lastFrameAt",
                Instant.now().minusSeconds(1).toString(), "observedThrough", Instant.now().minusSeconds(5)
                        .toString())));
        JsonNode answer = agentOk(200, "POST", "/loss-prevention/agents/heartbeat", beat);
        assertThat(answer.get("health").asString()).isEqualTo("online");
        List<String> kinds = new ArrayList<>();
        answer.get("commands").forEach(c -> kinds.add(c.get("kind").asString()));
        assertThat(kinds).contains("config_changed");
        String commandId = answer.get("commands").get(0).get("id").asString();
        assertThat(agentOk(200, "POST", "/loss-prevention/agents/commands/" + commandId + "/result", Map.of("status",
                "done")).get("firstReport").asBoolean()).isTrue();
        assertThat(agentOk(200, "GET", "/loss-prevention/agents/commands?waitSeconds=0", null).get("commands")
                .size()).isZero();

        beat.put("diskFreeBytes", 1_000_000_000L);
        JsonNode degraded = agentOk(200, "POST", "/loss-prevention/agents/heartbeat", beat);
        assertThat(degraded.get("health").asString()).isEqualTo("degraded");
        assertThat(degraded.get("healthReasons").get(0).asString()).isEqualTo("disk_low");
        beat.put("diskFreeBytes", 50_000_000_000L);
        assertThat(agentOk(200, "POST", "/loss-prevention/agents/heartbeat", beat).get("health").asString())
                .isEqualTo("online");
        assertThat(ok(200, "GET", "/loss-prevention/agents/" + agentId, null).get("heartbeats").size())
                .isEqualTo(3);
    }

    @Test
    @Order(4)
    @DisplayName("observations are taken for the agent's own zones only; a clip is registered and stays on the box")
    void observationsAndClips() {
        Map<String, Object> open = observation(drawerZone, "vision.state_changed", "12:00:00", Map.of("cameraId",
                cameraId, "object", "cash_drawer", "from", "closed", "to", "open", "clipId", "clip-" + tag));
        observationEventId = (String) open.get("eventId");
        JsonNode stored = agentOk(201, "POST", "/loss-prevention/events", Map.of("events", List.of(open,
                observation(UUID.randomUUID().toString(), "vision.occupancy", "12:00:05", Map.of("cameraId", cameraId,
                        "count", 1)),
                observation(checkoutZone, "vision.occupancy", "12:00:06", Map.of("cameraId", "another-camera",
                        "count", 1)))));
        assertThat(stored.get("accepted").asInt()).as(stored.toString()).isEqualTo(1);
        assertThat(stored.get("results").get(1).get("reason").asString()).isEqualTo("Zone not found");
        assertThat(stored.get("results").get(2).get("reason").asString())
                .isEqualTo("payload.cameraId is not the camera of the zone");

        Map<String, Object> registered = new LinkedHashMap<>();
        registered.put("clipId", "clip-" + tag);
        registered.put("cameraId", cameraId);
        registered.put("zoneId", drawerZone);
        registered.put("startedAt", day + "T11:59:40Z");
        registered.put("endedAt", day + "T12:00:30Z");
        registered.put("sha256", LpEvidenceCipher.sha256(clip));
        registered.put("byteSize", clip.length);
        registered.put("contentType", "video/mp4");
        registered.put("eventIds", List.of(observationEventId));
        Map<String, Object> foreign = new LinkedHashMap<>(registered);
        foreign.put("clipId", "clip-x-" + tag);
        foreign.put("cameraId", UUID.randomUUID().toString());
        JsonNode clips = agentOk(200, "POST", "/loss-prevention/agents/clips", Map.of("clips", List.of(registered,
                foreign)));
        assertThat(clips.get("registered").asInt()).isEqualTo(1);
        JsonNode first = clips.get("results").get(0);
        assertThat(first.get("status").asString()).isEqualTo("registered");
        assertThat(first.get("linkedEvents").asInt()).isEqualTo(1);
        evidenceId = first.get("evidenceId").asString();
        assertThat(clips.get("results").get(1).get("reason").asString()).isEqualTo("Camera not found for this agent");
        JsonNode again = agentOk(200, "POST", "/loss-prevention/agents/clips", Map.of("clips", List.of(registered)));
        assertThat(again.get("results").get(0).get("status").asString()).isEqualTo("duplicate");
        assertThat(again.get("results").get(0).get("evidenceId").asString()).isEqualTo(evidenceId);

        JsonNode e = ok(200, "GET", "/loss-prevention/evidence/" + evidenceId, null);
        assertThat(e.get("state").asString()).isEqualTo("on_agent");
        assertThat(e.get("observations").size()).isEqualTo(1);
        Res early = as("GET", "/loss-prevention/evidence/" + evidenceId + "/content", null);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().get("message").asString()).isEqualTo("The clip is not uploaded yet");
    }

    @Test
    @Order(5)
    @DisplayName("a requested clip is uploaded in chunks, checked, encrypted, then viewed and exported with a trail")
    void upload() throws Exception {
        JsonNode requested = ok(201, "POST", "/loss-prevention/evidence/" + evidenceId + "/request", Map.of("reason",
                "Check the drawer"));
        assertThat(requested.get("state").asString()).isEqualTo("requested");
        JsonNode beat = agentOk(200, "POST", "/loss-prevention/agents/heartbeat", Map.of("configVersion", 6,
                "cameras", List.of(Map.of("cameraId", cameraId, "streamState", "ok"))));
        JsonNode upload = null;
        for (JsonNode c : beat.get("commands")) {
            if ("clip_upload".equals(c.get("kind").asString())) {
                upload = c;
            }
        }
        assertThat(upload).as(beat.toString()).isNotNull();
        assertThat(upload.get("payload").get("clipId").asString()).isEqualTo("clip-" + tag);

        String sha = LpEvidenceCipher.sha256(clip);
        String base = "/loss-prevention/evidence/" + evidenceId + "/upload";
        assertThat(agentOk(200, "GET", base, null).get("receivedBytes").asLong()).isZero();
        int half = clip.length / 2;
        String query = "?total=" + clip.length + "&sha256=" + sha + "&offset=";
        Res wrongOffset = bytes(base + query + 5, Arrays.copyOfRange(clip, 0, half), "application/octet-stream",
                agentToken);
        assertThat(wrongOffset.status()).isEqualTo(409);
        assertThat(wrongOffset.body().get("message").asString()).isEqualTo("Expected offset 0");
        Res firstHalf = bytes(base + query + 0, Arrays.copyOfRange(clip, 0, half), "application/octet-stream",
                agentToken);
        assertThat(firstHalf.status()).as(firstHalf.raw().body()).isEqualTo(200);
        assertThat(firstHalf.body().get("complete").asBoolean()).isFalse();
        Res rest = bytes(base + query + half, Arrays.copyOfRange(clip, half, clip.length), "application/octet-stream",
                agentToken);
        assertThat(rest.status()).as(rest.raw().body()).isEqualTo(200);
        assertThat(rest.body().get("complete").asBoolean()).isTrue();
        assertThat(rest.body().get("state").asString()).isEqualTo("uploaded");

        HttpResponse<byte[]> content = downloadBytes("/loss-prevention/evidence/" + evidenceId + "/content");
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.body()).isEqualTo(clip);
        assertThat(content.headers().firstValue("Content-Type").orElse("")).startsWith("video/mp4");
        String key = jdbc.sql("SELECT \"storageKey\" FROM \"LpEvidence\" WHERE id = ?").param(evidenceId)
                .query(String.class).single();
        assertThat(key).isEqualTo("lp-evidence/" + demoOrg + "/" + evidenceId);

        HttpResponse<byte[]> export = downloadBytes("/loss-prevention/evidence/" + evidenceId + "/export");
        assertThat(export.statusCode()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(export.body()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
                if (entry.getName().equals("manifest.json")) {
                    JsonNode manifest = mapper.readTree(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    assertThat(manifest.get("sha256").asString()).isEqualTo(sha);
                }
            }
        }
        assertThat(names).containsExactly("clip.mp4", "manifest.json", "access-log.json", "SHA256SUMS");

        List<String> actions = new ArrayList<>();
        ok(200, "GET", "/loss-prevention/evidence/" + evidenceId, null).get("access")
                .forEach(a -> actions.add(a.get("action").asString()));
        assertThat(actions).containsSubsequence("request", "uploaded", "view", "export");
        // A command the box already finished is not delivered again.
        assertThat(jdbc.sql("SELECT state FROM \"LpAgentCommand\" WHERE id = ?").param(upload.get("id").asString())
                .query(String.class).single()).isEqualTo("done");
    }

    @Test
    @Order(6)
    @DisplayName("a vision finding opens a case and the clip of its observation is linked to it")
    void caseEvidence() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + adminToken);
        headers.put("Idempotency-Key", UUID.randomUUID().toString());
        Res replay = call("POST", "/loss-prevention/runs/replay", Map.of("engine", "cashier_vision", "branchId", branch,
                "from", day.minusDays(1).toString(), "to", day.plusDays(1).toString()), headers);
        // A route behind an Idempotency-Key answers 200, like every such route here.
        assertThat(replay.status()).as(replay.raw().body()).isEqualTo(200);
        assertThat(replay.body().get("findings").asInt()).isEqualTo(1);
        drainOutboxFully();
        JsonNode cases = ok(200, "GET", "/loss-prevention/cases?ruleCode=V-C1&branchId=" + branch + "&from="
                + day.minusDays(1) + "&to=" + day.plusDays(1), null);
        assertThat(cases.get("meta").get("total").asInt()).isEqualTo(1);
        String caseId = cases.get("data").get(0).get("id").asString();
        JsonNode linked = ok(200, "GET", "/loss-prevention/evidence?caseId=" + caseId, null);
        assertThat(linked.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(linked.get("data").get(0).get("id").asString()).isEqualTo(evidenceId);
        List<String> kinds = new ArrayList<>();
        ok(200, "GET", "/loss-prevention/cases/" + caseId, null).get("timeline")
                .forEach(t -> kinds.add(t.get("kind").asString()));
        assertThat(kinds).contains("opened", "evidence_linked");
    }

    @Test
    @Order(7)
    @DisplayName("stills and live view come from the box on request; the stream link is signed and ends when stopped")
    void stillsAndLive() throws Exception {
        String commandId = ok(201, "POST", "/loss-prevention/cameras/" + cameraId + "/snapshot", null)
                .get("commandId").asString();
        assertThat(as("GET", "/loss-prevention/cameras/" + cameraId + "/snapshot", null).status()).isEqualTo(404);
        assertThat(bytes("/loss-prevention/agents/snapshots/" + commandId, jpeg(), "image/jpeg", agentToken).status())
                .isEqualTo(200);
        HttpResponse<byte[]> still = downloadBytes("/loss-prevention/cameras/" + cameraId + "/snapshot");
        assertThat(still.statusCode()).isEqualTo(200);
        assertThat(still.body()).isEqualTo(jpeg());
        assertThat(still.headers().firstValue("X-Snapshot-Taken-At")).isPresent();

        JsonNode live = ok(201, "POST", "/loss-prevention/cameras/" + cameraId + "/live", null);
        String session = live.get("sessionId").asString();
        String url = live.get("streamUrl").asString();
        assertThat(url).startsWith("/api/v1/loss-prevention/live/" + session + "/stream?token=");
        HttpResponse<String> forged = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/loss-prevention/live/" + session + "/stream?token=forged")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(forged.statusCode()).isEqualTo(404);
        assertThat(bytes("/loss-prevention/agents/live/" + session + "/frames", jpeg(), "image/jpeg", agentToken)
                .body().get("continue").asBoolean()).isTrue();

        HttpResponse<InputStream> stream = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type").orElse("")).startsWith("multipart/x-mixed-replace");
        // Read only until the first part's header: the stream stays open and the next frame never comes.
        String head = CompletableFuture.supplyAsync(() -> {
            StringBuilder seen = new StringBuilder();
            try {
                InputStream in = stream.body();
                for (int b = in.read(); b >= 0 && seen.length() < 4096; b = in.read()) {
                    seen.append((char) b);
                    if (seen.indexOf("Content-Type: image/jpeg") >= 0) {
                        break;
                    }
                }
            } catch (Exception e) {
                seen.append(e);
            }
            return seen.toString();
        }).get(20, TimeUnit.SECONDS);
        assertThat(head).contains("--lpframe").contains("Content-Type: image/jpeg");
        ok(200, "DELETE", "/loss-prevention/live/" + session, null);
        stream.body().close();
        assertThat(bytes("/loss-prevention/agents/live/" + session + "/frames", jpeg(), "image/jpeg", agentToken)
                .body().get("continue").asBoolean()).isFalse();
        List<String> actions = jdbc.sql("SELECT action FROM \"LpEvidenceAccess\" WHERE \"cameraId\" = ? ORDER BY"
                + " \"createdAt\", id").param(cameraId).query(String.class).list();
        assertThat(actions).contains("snapshot", "live_view_start", "live_view_stop");
    }

    @Test
    @Order(8)
    @DisplayName("a clip that no longer matches its checksum is refused and recorded")
    void integrity() {
        jdbc.sql("UPDATE \"LpEvidence\" SET sha256 = ? WHERE id = ?").params("0".repeat(64), evidenceId).update();
        Res r = as("GET", "/loss-prevention/evidence/" + evidenceId + "/content", null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("message").asString())
                .isEqualTo("The clip does not match its checksum; it may have been altered");
        assertThat(jdbc.sql("SELECT state FROM \"LpEvidence\" WHERE id = ?").param(evidenceId).query(String.class)
                .single()).isEqualTo("integrity_failed");
        assertThat(jdbc.sql("SELECT count(*) FROM \"LpEvidenceAccess\" WHERE \"evidenceId\" = ? AND action ="
                + " 'integrity_failed'").param(evidenceId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Order(9)
    @DisplayName("another organization sees none of it")
    void tenantIsolation() {
        assertThat(call("GET", "/loss-prevention/agents/" + agentId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/loss-prevention/cameras/" + cameraId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/loss-prevention/zones/" + drawerZone, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/loss-prevention/evidence/" + evidenceId, null, otherToken).status()).isEqualTo(404);
        JsonNode agents = call("GET", "/loss-prevention/agents", null, otherToken).body();
        agents.forEach(a -> assertThat(a.get("id").asString()).isNotEqualTo(agentId));
    }

    @Test
    @Order(10)
    @DisplayName("a revoked agent's credentials stop working and it cannot be enrolled again")
    void revoke() {
        JsonNode revoked = ok(201, "POST", "/loss-prevention/agents/" + agentId + "/revoke", Map.of("reason",
                "Box replaced"));
        assertThat(revoked.get("status").asString()).isEqualTo("revoked");
        assertThat(agent("GET", "/loss-prevention/agents/config", null).status()).isIn(401, 403);
        Res code = as("POST", "/loss-prevention/agents/" + agentId + "/enrolment-code", null);
        assertThat(code.status()).isEqualTo(400);
        assertThat(code.body().get("message").asString())
                .isEqualTo("A revoked agent cannot be enrolled again; add a new agent");
        assertThat(as("POST", "/loss-prevention/agents/" + agentId + "/revoke", Map.of()).body().get("message")
                .asString()).isEqualTo("The agent is already revoked");
    }

    @Test
    @Order(11)
    @DisplayName("every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(LossPreventionAgentController.class,
                LossPreventionAgentApiController.class, LossPreventionCameraController.class,
                LossPreventionEvidenceController.class)).isEqualTo(44);
    }
}
