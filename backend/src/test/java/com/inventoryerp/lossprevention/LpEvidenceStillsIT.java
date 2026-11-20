package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.lossprevention.application.LpEvidenceCipher;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * Still images as evidence and the footage view (LP1c, ADR-S077 §8), with the edge agent played by the test: a still
 * asked of the box is uploaded like a clip with the checksum the box computed; a frame saved from the player is kept
 * as a still of its clip; the footage view finds the cameras and evidence around a moment, a ledger record or a sale.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LpEvidenceStillsIT extends LpVisionSupport {

    String agentId;
    String cameraId;
    String drawerZone;
    String stillId;
    String clipId;
    final byte[] clip = ("clip " + UUID.randomUUID() + " ").repeat(2_000).getBytes(StandardCharsets.UTF_8);

    String message(Res r, int status) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(status);
        return r.body().get("message").asString();
    }

    JsonNode command(String evidenceId) {
        JsonNode payload = mapper.readTree(jdbc.sql("SELECT payload::text FROM \"LpAgentCommand\" WHERE \"agentId\" = ?"
                + " AND kind = 'clip_upload' AND payload ->> 'evidenceId' = ?").params(agentId, evidenceId)
                .query(String.class).single());
        return payload;
    }

    Res upload(String evidenceId, byte[] body, String contentType) {
        String query = "?offset=0&total=" + body.length + "&sha256=" + LpEvidenceCipher.sha256(body) + "&contentType="
                + URLEncoder.encode(contentType, StandardCharsets.UTF_8);
        return bytes("/loss-prevention/evidence/" + evidenceId + "/upload" + query, body, "application/octet-stream",
                agentToken);
    }

    /** {@code POST /evidence/{id}/frame} as the browser sends it: multipart with the image as {@code file}. */
    Res frame(String evidenceId, long offsetMs, byte[] image, String type, String token) {
        try {
            String boundary = "----lp" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"reason\"\r\n\r\nThe hand at"
                    + " the drawer\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
                    + " filename=\"frame\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(image);
            body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/v1/loss-prevention/evidence/" + evidenceId + "/frame?offsetMs=" + offsetMs))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                    HttpResponse.BodyHandlers.ofString());
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    List<String> trail(String evidenceId) {
        List<String> actions = new ArrayList<>();
        ok(200, "GET", "/loss-prevention/evidence/" + evidenceId, null).get("access")
                .forEach(a -> actions.add(a.get("action").asString()));
        return actions;
    }

    @Test
    @Order(1)
    @DisplayName("an enrolled box with a camera watching a till")
    void setUp() throws Exception {
        Enrolled box = createAndEnrol("Stills box " + tag);
        agentId = box.agentId();
        agentToken = box.token();
        cameraId = ok(201, "POST", "/loss-prevention/cameras", Map.of("agentId", agentId, "name", "Till cam " + tag,
                "streamAlias", "till-" + tag)).get("id").asString();
        drawerZone = ok(201, "POST", "/loss-prevention/zones", Map.of("cameraId", cameraId, "name", "Drawer",
                "zoneType", "cash_drawer", "linkedType", "till", "linkedId", till, "polygon", square())).get("id")
                .asString();
    }

    @Test
    @Order(2)
    @DisplayName("a still is asked of the box, uploaded with the box's checksum and shown as a JPEG")
    void still() {
        String path = "/loss-prevention/evidence/still";
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        assertThat(message(as("POST", path, Map.of("cameraId", cameraId, "at", now.plusSeconds(120).toString())), 400))
                .isEqualTo("at must not be in the future");
        assertThat(message(as("POST", path, Map.of("cameraId", cameraId, "at", now.minus(400, ChronoUnit.DAYS)
                .toString())), 400)).startsWith("The edge box keeps video for ");
        assertThat(message(as("POST", path, Map.of("cameraId", cameraId, "at", "noon")), 400))
                .isEqualTo("at must be an ISO-8601 instant");
        assertThat(message(as("POST", path, Map.of("cameraId", "no-such-camera")), 404)).isEqualTo("Camera not found");

        Instant at = now.minusSeconds(60);
        JsonNode still = ok(201, "POST", path, Map.of("cameraId", cameraId, "at", at.toString(), "reason",
                "Who is at the till"));
        stillId = still.get("id").asString();
        assertThat(still.get("kind").asString()).isEqualTo("snapshot");
        assertThat(still.get("origin").asString()).isEqualTo("range");
        assertThat(still.get("state").asString()).isEqualTo("requested");
        assertThat(Instant.parse(still.get("startedAt").asString())).isEqualTo(at);
        assertThat(still.get("endedAt").asString()).isEqualTo(still.get("startedAt").asString());
        JsonNode payload = command(stillId);
        assertThat(payload.get("kind").asString()).isEqualTo("still");
        assertThat(Instant.parse(payload.get("at").asString())).isEqualTo(at);
        assertThat(payload.get("clipId").isNull()).isTrue();

        Res done = upload(stillId, jpeg(), "image/jpeg");
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(done.body().get("complete").asBoolean()).isTrue();
        HttpResponse<byte[]> content = downloadBytes("/loss-prevention/evidence/" + stillId + "/content");
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.body()).isEqualTo(jpeg());
        assertThat(content.headers().firstValue("Content-Type").orElse("")).startsWith("image/jpeg");

        JsonNode stills = ok(200, "GET", "/loss-prevention/evidence?kind=snapshot&cameraId=" + cameraId, null);
        assertThat(stills.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(message(as("GET", "/loss-prevention/evidence?kind=photo", null), 400))
                .isEqualTo("kind must be one of: clip, snapshot");
        assertThat(trail(stillId)).containsSubsequence("request", "uploaded", "view");
    }

    @Test
    @Order(3)
    @DisplayName("a frame saved from the player is kept as a still of its clip, hashed on receipt")
    void frameFromClip() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = now.minusSeconds(90);
        clipId = ok(201, "POST", "/loss-prevention/evidence/range", Map.of("cameraId", cameraId, "from",
                from.toString(), "to", now.minusSeconds(30).toString())).get("id").asString();
        assertThat(message(frame(clipId, 1_500, jpeg(), "image/jpeg", adminToken), 409))
                .isEqualTo("The clip is not uploaded yet");
        assertThat(upload(clipId, clip, "video/mp4").body().get("complete").asBoolean()).isTrue();

        assertThat(message(frame(clipId, 1_500, "not a picture".getBytes(StandardCharsets.UTF_8), "image/jpeg",
                adminToken), 400)).isEqualTo("The image must be a JPEG or PNG");
        assertThat(message(frame(clipId, 1_500, jpeg(), "image/png", adminToken), 400))
                .isEqualTo("The image must be a JPEG or PNG");
        assertThat(message(frame(clipId, 600_000, jpeg(), "image/jpeg", adminToken), 400))
                .isEqualTo("offsetMs must be between 0 and 60000");
        assertThat(message(frame(stillId, 0, jpeg(), "image/jpeg", adminToken), 400))
                .isEqualTo("A frame is saved from a video clip, not from a still");

        Res saved = frame(clipId, 1_500, jpeg(), "image/jpeg", adminToken);
        assertThat(saved.status()).as(saved.raw().body()).isEqualTo(201);
        JsonNode f = saved.body();
        String frameId = f.get("id").asString();
        assertThat(f.get("kind").asString()).isEqualTo("snapshot");
        assertThat(f.get("origin").asString()).isEqualTo("frame");
        assertThat(f.get("state").asString()).isEqualTo("uploaded");
        assertThat(f.get("sourceEvidenceId").asString()).isEqualTo(clipId);
        assertThat(f.get("frameOffsetMs").asLong()).isEqualTo(1_500);
        assertThat(f.get("sha256").asString()).isEqualTo(LpEvidenceCipher.sha256(jpeg()));
        assertThat(f.get("requestReason").asString()).isEqualTo("The hand at the drawer");
        assertThat(Instant.parse(f.get("startedAt").asString())).isEqualTo(from.plusMillis(1_500));
        assertThat(f.get("retainUntil").isNull()).isFalse();
        HttpResponse<byte[]> content = downloadBytes("/loss-prevention/evidence/" + frameId + "/content");
        assertThat(content.body()).isEqualTo(jpeg());
        assertThat(trail(clipId)).contains("snapshot");
        assertThat(trail(frameId)).containsSubsequence("uploaded", "view");
        // Asking again for a frame already kept changes nothing on the box.
        assertThat(ok(201, "POST", "/loss-prevention/evidence/" + frameId + "/request", Map.of()).get("state")
                .asString()).isEqualTo("uploaded");
        assertThat(frame(clipId, 1_500, jpeg(), "image/jpeg", otherToken).status()).isEqualTo(404);
    }

    @Test
    @Order(4)
    @DisplayName("the footage view lists the cameras and evidence around a moment, a ledger record or a sale")
    void footage() {
        assertThat(message(as("GET", "/loss-prevention/footage", null), 400))
                .isEqualTo("Give invoiceId, eventId, or branchId and at");
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);
        JsonNode moment = ok(200, "GET", "/loss-prevention/footage?branchId=" + branch + "&at=" + at, null);
        assertThat(moment.get("source").get("type").asString()).isEqualTo("moment");
        assertThat(Instant.parse(moment.get("from").asString())).isEqualTo(at.minusSeconds(30));
        assertThat(moment.get("inBuffer").asBoolean()).isTrue();
        assertThat(moment.get("bufferDays").asInt()).isPositive();
        List<String> cams = new ArrayList<>();
        moment.get("cameras").forEach(c -> cams.add(c.get("id").asString()));
        assertThat(cams).contains(cameraId);
        List<String> kept = new ArrayList<>();
        moment.get("evidence").forEach(e -> kept.add(e.get("id").asString()));
        assertThat(kept).contains(stillId, clipId);

        // A ledger record on the till's camera: that camera comes first.
        String eventId = UUID.randomUUID().toString();
        Instant seen = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(45);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", eventId);
        e.put("source", "edge-" + tag);
        e.put("sourceEventId", "obs-" + eventId);
        e.put("branchId", branch);
        e.put("deviceId", drawerZone);
        e.put("businessDate", seen.atZone(ZoneOffset.UTC).toLocalDate().toString());
        e.put("occurredAt", seen.toString());
        e.put("schemaVersion", 1);
        e.put("type", "vision.occupancy");
        e.put("payload", Map.of("cameraId", cameraId, "count", 1));
        e.put("evidenceLevel", "zone");
        e.put("evidenceSource", "camera");
        e.put("observationStatus", "observed");
        e.put("confidence", 0.9);
        JsonNode stored = agentOk(201, "POST", "/loss-prevention/events", Map.of("events", List.of(e)));
        assertThat(stored.get("accepted").asInt()).as(stored.toString()).isEqualTo(1);
        String ledgerId = jdbc.sql("SELECT id FROM \"LpEvent\" WHERE \"eventId\" = ?").param(eventId)
                .query(String.class).single();
        JsonNode record = ok(200, "GET", "/loss-prevention/footage?eventId=" + ledgerId, null);
        assertThat(record.get("source").get("type").asString()).isEqualTo("event");
        assertThat(record.get("cameras").get(0).get("id").asString()).isEqualTo(cameraId);
        assertThat(record.get("cameras").get(0).get("watches").asBoolean()).isTrue();
        assertThat(Instant.parse(record.get("at").asString())).isEqualTo(seen);
        assertThat(message(as("GET", "/loss-prevention/footage?eventId=no-such-record", null), 404))
                .isEqualTo("Record not found");
        assertThat(call("GET", "/loss-prevention/footage?eventId=" + ledgerId, null, otherToken).status())
                .isEqualTo(404);

        // A POS sale: found by its id, its branch's cameras listed.
        String invoice = jdbc.sql("SELECT id FROM \"Invoice\" WHERE \"organizationId\" = ? AND \"branchId\" IS NOT NULL"
                + " ORDER BY \"createdAt\" DESC LIMIT 1").param(demoOrg).query(String.class).optional().orElse(null);
        if (invoice != null) {
            JsonNode sale = ok(200, "GET", "/loss-prevention/footage?invoiceId=" + invoice, null);
            assertThat(sale.get("source").get("type").asString()).isEqualTo("invoice");
            assertThat(sale.get("source").get("id").asString()).isEqualTo(invoice);
            assertThat(call("GET", "/loss-prevention/footage?invoiceId=" + invoice, null, otherToken).status())
                    .isEqualTo(404);
        }
        assertThat(message(as("GET", "/loss-prevention/footage?invoiceId=no-such-sale", null), 404))
                .isEqualTo("Sale not found");
    }
}
