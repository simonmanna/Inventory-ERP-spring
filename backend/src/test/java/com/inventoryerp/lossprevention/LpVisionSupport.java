package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Helpers of the camera platform tests (LP1): a till at a branch of DEMO, an edge agent enrolled the way a box does
 * it, and calls made with the agent's own token. Same Spring context as every {@link ApiTestSupport} test.
 */
abstract class LpVisionSupport extends ApiTestSupport {

    /** Makes names unique per run: DEMO and the reused test database are shared between suites and reruns. */
    protected final String tag = UUID.randomUUID().toString().substring(0, 8);
    protected String branch;
    protected String till;
    protected String agentToken;

    /** The agent's credentials as enrolment answers them, with a token taken from them. */
    protected record Enrolled(String agentId, String clientId, String clientSecret, String token) {}

    /** A till (cash register) of DEMO with a branch; one is added when the seed has none. */
    protected void fixtures() {
        if (till != null) {
            return;
        }
        Map<String, Object> register = jdbc.sql("SELECT id, \"branchId\" FROM \"CashRegister\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"branchId\" IS NOT NULL ORDER BY code LIMIT 1")
                .param(demoOrg)
                .query((rs, n) -> Map.<String, Object>of("id", rs.getString(1), "branchId", rs.getString(2)))
                .optional().orElse(null);
        if (register != null) {
            till = (String) register.get("id");
            branch = (String) register.get("branchId");
            return;
        }
        branch = jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY id"
                + " LIMIT 1").param(demoOrg).query(String.class).single();
        String account = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? ORDER BY code LIMIT 1")
                .param(demoOrg).query(String.class).single();
        till = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CashRegister\" (id, \"organizationId\", code, name, \"branchId\", \"defaultAccountId\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(till, demoOrg, "LP-" + tag, "Vision till " + tag, branch, account).update();
    }

    /** Creates an agent at the fixture branch and enrols it with its one-time code, as the box would. */
    protected Enrolled createAndEnrol(String name) throws Exception {
        fixtures();
        JsonNode created = ok(201, "POST", "/loss-prevention/agents", Map.of("branchId", branch, "name", name));
        return enrol(created.get("enrolmentCode").asString());
    }

    protected Enrolled enrol(String code) throws Exception {
        Res enrolled = call("POST", "/loss-prevention/agents/enrol", Map.of("code", code, "hostname", "lp-box-" + tag,
                "agentVersion", "0.1.0"), (String) null);
        assertThat(enrolled.status()).as(enrolled.raw().body()).isEqualTo(201);
        JsonNode creds = enrolled.body();
        String clientId = creds.get("clientId").asString();
        String secret = creds.get("clientSecret").asString();
        return new Enrolled(creds.get("agentId").asString(), clientId, secret, clientToken(clientId, secret));
    }

    protected Res agent(String method, String path, Object body) {
        return call(method, path, body, agentToken);
    }

    protected JsonNode agentOk(int status, String method, String path, Object body) {
        Res r = agent(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    /** A request with a raw body (an image, a chunk). */
    protected Res bytes(String path, byte[] body, String contentType, String token) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
            JsonNode json = r.body() == null || r.body().isEmpty() ? null : mapper.readTree(r.body());
            return new Res(r.statusCode(), json, r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The OAuth client-credentials grant; answers the HTTP status and body. */
    protected HttpResponse<String> tokenRequest(String clientId, String secret) throws Exception {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + URLEncoder.encode(
                        "lp:ingest", StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
    }

    protected String clientToken(String clientId, String secret) throws Exception {
        HttpResponse<String> token = tokenRequest(clientId, secret);
        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        return mapper.readTree(token.body()).get("access_token").asString();
    }

    protected static List<List<Double>> square() {
        return List.of(List.of(0.4, 0.6), List.of(0.6, 0.6), List.of(0.6, 0.8), List.of(0.4, 0.8));
    }

    /** The smallest thing the backend takes for a JPEG: start and end markers. */
    protected static byte[] jpeg() {
        byte[] b = new byte[64];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        b[62] = (byte) 0xFF;
        b[63] = (byte) 0xD9;
        return b;
    }
}
