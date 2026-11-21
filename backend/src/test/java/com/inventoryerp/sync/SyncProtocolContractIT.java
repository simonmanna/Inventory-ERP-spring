package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 O0: independent sync-protocol contract tests. The protocol-2 extensions have no Nest counterpart, so the
 * wire contract lives in {@code contract-tests/sync-protocol/contract.json} and every shape there is checked against
 * live responses here (pull v1/v2, unsupported protocol, push applied/failed, snapshots, dead letters, recovery).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncProtocolContractIT extends ApiTestSupport {

    /** Upper-case literals in the push service that are data values, not failure codes. */
    static final Set<String> NOT_CODES = Set.of("ADD_ON");

    JsonNode contract;
    String token;
    String deviceId;
    String adminId;
    int seq;

    @BeforeAll
    void fixtures() throws Exception {
        contract = mapper.readTree(Files.readString(Path.of("../contract-tests/sync-protocol/contract.json")));
        JsonNode d = ok(201, "POST", "/sync/devices/register", Map.of("name", "Contract tablet " + UUID.randomUUID()
                .toString().substring(0, 6), "platform", "web"));
        token = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN ('open',"
                + " 'investigating')").param(deviceId).update();
    }

    Res dev(String method, String path, Object body, String protocol) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Device-Token", token);
        if (protocol != null) {
            h.put(contract.get("protocol").get("header").asString(), protocol);
        }
        return call(method, path, body, h);
    }

    static boolean matches(JsonNode v, String type) {
        for (String t : type.split("\\|")) {
            boolean ok = switch (t) {
                case "string" -> v.isString();
                case "int" -> v.isIntegralNumber();
                case "boolean" -> v.isBoolean();
                case "array" -> v.isArray();
                case "object" -> v.isObject();
                case "null" -> v.isNull();
                case "any" -> true;
                case "iso" -> v.isString() && parses(v.asString());
                default -> t.startsWith("enum:") && v.isString() && List.of(t.substring(5).split(",")).contains(
                        v.asString());
            };
            if (ok) {
                return true;
            }
        }
        return false;
    }

    static boolean parses(String iso) {
        try {
            Instant.parse(iso);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Assert {@code body} has every field of {@code shape} with its type, and none of its absent keys. */
    void conforms(String shape, JsonNode body) {
        JsonNode spec = contract.get("shapes").get(shape);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonNode> f : spec.get("fields").properties()) {
            JsonNode v = body.get(f.getKey());
            if (v == null) {
                problems.add(f.getKey() + " missing");
            } else if (!matches(v, f.getValue().asString())) {
                problems.add(f.getKey() + " is " + v + ", expected " + f.getValue().asString());
            }
        }
        if (spec.has("absent")) {
            for (JsonNode k : spec.get("absent")) {
                if (body.has(k.asString())) {
                    problems.add(k.asString() + " must be absent");
                }
            }
        }
        assertThat(problems).as(shape + ": " + body).isEmpty();
    }

    Map<String, Object> taxOp(String name) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", "ct-" + UUID.randomUUID());
        o.put("deviceSeq", ++seq);
        o.put("type", "tax.upsert");
        o.put("actorUserId", adminId);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", Map.of("id", UUID.randomUUID().toString(), "name", name + " " + UUID.randomUUID().toString()
                .substring(0, 8), "rate", 1));
        return o;
    }

    @Test
    @DisplayName("pull: protocol 1 and 2 shapes; an unsupported major is 426 on pull but push is still accepted")
    void pullAndVersions() {
        Res v1 = dev("GET", "/sync/pull?scopes=taxes", null, null);
        assertThat(v1.status()).isEqualTo(200);
        conforms("pullV1", v1.body());
        conforms("offlinePolicy", v1.body().get("offlinePolicy"));
        Res v2 = dev("GET", "/sync/pull?scopes=taxes", null, contract.get("protocol").get("current").asString());
        conforms("pullV2", v2.body());
        assertThat(v2.body().get("protocolVersion").asString()).isEqualTo(contract.get("protocol").get("current")
                .asString());
        token = v2.body().get("deviceToken").asString(); // protocol 2 rotated it
        Res future = dev("GET", "/sync/pull?scopes=taxes", null, "9.0");
        assertThat(future.status()).isEqualTo(contract.get("protocol").get("unsupportedPullStatus").asInt());
        Res push = dev("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(taxOp("Contract VAT"))), "9.0");
        assertThat(push.status()).isEqualTo(201);
        conforms("pushResponse", push.body());
    }

    @Test
    @DisplayName("push: applied, replayed and failed results carry the contract fields and a known failure code")
    void pushResults() {
        Map<String, Object> o = taxOp("Contract push");
        Map<String, Object> bad = taxOp("Contract bad");
        bad.put("actorUserId", UUID.randomUUID().toString());
        JsonNode res = dev("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(o, bad)), null).body();
        conforms("pushResponse", res);
        conforms("pushApplied", res.get("results").get(0));
        JsonNode failed = res.get("results").get(1);
        conforms("pushFailed", failed);
        Set<String> codes = new LinkedHashSet<>();
        contract.get("failureCodes").forEach(c -> codes.add(c.asString()));
        assertThat(codes).contains(failed.get("code").asString());
        JsonNode replay = dev("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(o)), null).body();
        conforms("pushApplied", replay.get("results").get(0));
        assertThat(replay.get("results").get(0).get("status").asString()).isEqualTo("replayed");
    }

    @Test
    @DisplayName("every failure code the push service can emit is declared in the contract, retryability included")
    void failureCodesDeclared() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/inventoryerp/sync/application/SyncPushService.java"));
        Set<String> emitted = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([A-Z]+(?:_[A-Z]+)+)\"").matcher(src);
        while (m.find()) {
            if (!NOT_CODES.contains(m.group(1))) {
                emitted.add(m.group(1));
            }
        }
        Set<String> declared = new LinkedHashSet<>();
        contract.get("failureCodes").forEach(c -> declared.add(c.asString()));
        assertThat(declared).containsAll(emitted);
        for (JsonNode c : contract.get("retryableCodes")) {
            assertThat(declared).contains(c.asString());
        }
    }

    @Test
    @DisplayName("snapshot: open, page and complete shapes")
    void snapshotShapes() {
        JsonNode open = dev("POST", "/sync/snapshot", Map.of("scopes", "taxes"), null).body();
        conforms("snapshotOpen", open);
        String id = open.get("snapshotId").asString();
        String after = "";
        List<String> sums = new ArrayList<>();
        long rows = 0;
        while (true) {
            Res served = dev("GET", "/sync/snapshot/" + id + "/page?scope=taxes&after=" + after, null, null);
            JsonNode page = served.body();
            conforms("snapshotPage", page);
            // ADR-S083 O1: the web client verifies the RAW bytes of `rows` in the response (re-serializing parsed JSON
            // in a browser does not reproduce Jackson's output), so the served bytes must hash to pageChecksum.
            assertThat(com.inventoryerp.sync.application.SyncSnapshotService.sha256(rawRows(served.raw().body())))
                    .as("raw rows bytes hash to pageChecksum").isEqualTo(page.get("pageChecksum").asString());
            sums.add(page.get("pageChecksum").asString());
            rows += page.get("rows").size();
            if (!page.get("hasMore").asBoolean()) {
                break;
            }
            after = page.get("nextAfter").asString();
        }
        JsonNode done = dev("POST", "/sync/snapshot/" + id + "/complete", Map.of("scopes", Map.of("taxes", Map.of(
                "rowCount", rows, "checksum", com.inventoryerp.sync.application.SyncSnapshotService.scopeChecksum(sums)))),
                null).body();
        conforms("snapshotComplete", done);
    }

    @Test
    @DisplayName("dead letters and supervisor recovery: list item and recovery response shapes")
    void deadLetterShapes() {
        String[] other = registerAndRevoke();
        Map<String, Object> o = taxOp("Contract held");
        o.put("deviceSeq", 1);
        Res held = call("POST", "/sync/push", Map.of("deviceId", other[1], "ops", List.of(o)), Map.of("X-Device-Token",
                other[0]));
        conforms("pushFailed", held.body().get("results").get(0));
        JsonNode list = ok(200, "GET", "/sync/dead-letters?status=open", null);
        JsonNode item = null;
        for (JsonNode r : list) {
            if (o.get("opId").equals(r.get("opId").asString())) {
                item = r;
            }
        }
        assertThat(item).isNotNull();
        conforms("deadLetter", item);
        assertThat(item.get("recoverable").asBoolean()).isTrue();
        JsonNode rec = ok(201, "POST", "/sync/dead-letters/" + item.get("id").asString() + "/recover", Map.of("note",
                "Contract recovery"));
        conforms("recoverResponse", rec);
    }

    /** The raw text of the top-level {@code rows} array (it precedes any nested value in a page body). */
    static String rawRows(String body) {
        int start = body.indexOf("\"rows\":") + "\"rows\":".length();
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if ((c == ']' || c == '}') && --depth == 0) {
                return body.substring(start, i + 1);
            }
        }
        throw new AssertionError("no rows array in " + body);
    }

    String[] registerAndRevoke() {
        JsonNode d = ok(201, "POST", "/sync/devices/register", Map.of("name", "Contract revoked " + UUID.randomUUID()
                .toString().substring(0, 6), "platform", "web"));
        ok(201, "POST", "/sync/devices/" + d.get("id").asString() + "/revoke", null);
        return new String[] {d.get("deviceToken").asString(), d.get("id").asString()};
    }
}
