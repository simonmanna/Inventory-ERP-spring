package com.inventoryerp.license;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.license.crypto.Ed25519Keys;
import com.inventoryerp.license.crypto.Jws;
import com.inventoryerp.license.shared.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The licence authority end to end, over real HTTP on a real Postgres: sell, issue, activate, check
 * in, renew, suspend, resume, revoke — asserting at each step exactly what an install is told.
 *
 * <p>Driven through the wire rather than the services, so the authorization matrix, the validation
 * and the response shapes are covered by the same pass. The steps run in order and share state:
 * this is one customer's life cycle, not eighteen unrelated cases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LicenseServerIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.signing-passphrase", () -> "integration test passphrase");
        registry.add("app.admin.jwt-secret", () -> "integration-test-admin-secret-value");
        registry.add("app.admin.bootstrap-email", () -> "owner@example.com");
        registry.add("app.admin.bootstrap-password", () -> "a-very-long-password");
        registry.add("app.reminders.enabled", () -> false);
        // The walk-through makes many calls from one address; the throttle is proved separately.
        registry.add("app.activation.rate-limit-per-minute", () -> 1000);
    }

    @LocalServerPort
    private int port;

    private static final String FINGERPRINT_A = "a".repeat(64);
    private static final String FINGERPRINT_B = "b".repeat(64);

    private final HttpClient http = HttpClient.newHttpClient();

    private String ownerToken;
    private String licenseId;
    private String licenseKey;
    private String installToken;

    // ---- HTTP helpers ----

    /** A response reduced to what the assertions need. */
    private record Reply(int status, Map<String, Object> body, String raw, HttpHeadersView headers) {}

    private record HttpHeadersView(HttpResponse<String> response) {

        String get(String name) {
            return response.headers().firstValue(name).orElse(null);
        }
    }

    private Reply send(String method, String path, Object body, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(body));
        try {
            HttpResponse<String> response = http.send(request.method(method, payload).build(),
                    HttpResponse.BodyHandlers.ofString());
            String raw = response.body() == null ? "" : response.body();
            return new Reply(response.statusCode(),
                    raw.startsWith("{") ? Json.readObject(raw) : Map.of(),
                    raw, new HttpHeadersView(response));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Request failed: " + method + " " + path, e);
        }
    }

    private Reply get(String path, String token) {
        return send("GET", path, null, token);
    }

    private Reply post(String path, Object body, String token) {
        return send("POST", path, body, token);
    }

    private String signIn(String email, String password) {
        Reply reply = post("/api/v1/admin/auth/login", Map.of("email", email, "password", password), null);
        assertThat(reply.status()).isEqualTo(200);
        return (String) reply.body().get("accessToken");
    }

    // ---- the walk-through ----

    @Test
    @Order(1)
    void theBootstrapOwnerCanSignIn() {
        ownerToken = signIn("owner@example.com", "a-very-long-password");
        assertThat(ownerToken).isNotBlank();

        Reply me = get("/api/v1/admin/auth/me", ownerToken);
        assertThat(me.body()).containsEntry("email", "owner@example.com").containsEntry("role", "owner");
    }

    @Test
    @Order(2)
    void aWrongPasswordAndAnUnknownAdminAnswerTheSame() {
        Reply wrong = post("/api/v1/admin/auth/login",
                Map.of("email", "owner@example.com", "password", "wrong-but-long-enough"), null);
        Reply unknown = post("/api/v1/admin/auth/login",
                Map.of("email", "nobody@example.com", "password", "wrong-but-long-enough"), null);

        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        // Identical answers: the route cannot be used to find out which admins exist.
        assertThat(wrong.body().get("message")).isEqualTo("Email or password is incorrect.")
                .isEqualTo(unknown.body().get("message"));
    }

    @Test
    @Order(3)
    void anAdminRouteNeedsASession() {
        assertThat(get("/api/v1/admin/licenses", null).status()).isEqualTo(401);
        assertThat(get("/api/v1/admin/licenses", "nonsense").status()).isEqualTo(401);
    }

    @Test
    @Order(4)
    void sellAndIssueALicence() {
        ownerToken = signIn("owner@example.com", "a-very-long-password");

        Reply customer = post("/api/v1/admin/customers",
                Map.of("name", "Acme Cafe Ltd", "email", "acme@example.com"), ownerToken);
        assertThat(customer.status()).isEqualTo(201);

        Reply created = post("/api/v1/admin/licenses", Map.of(
                "customerId", customer.body().get("id"),
                "planCode", "pro-monthly",
                "issueNow", true), ownerToken);
        assertThat(created.status()).isEqualTo(201);

        licenseId = (String) created.body().get("id");
        licenseKey = (String) created.body().get("key");

        assertThat(created.body()).containsEntry("status", "active");
        assertThat(licenseKey).matches("ERP(-[0-9A-HJKMNP-TV-Z]{4}){4}");
        assertThat(created.body().get("expiresAt")).isNotNull();
        assertThat(((Number) created.body().get("instanceLimit")).intValue()).isEqualTo(1);
        assertThat(created.raw()).contains("\"crm\"").contains("\"accounting\"");
    }

    @Test
    @Order(5)
    void anInstallActivatesAndGetsAVerifiableToken() {
        Reply activated = post("/api/v1/activate", Map.of(
                "licenseKey", licenseKey,
                "fingerprint", FINGERPRINT_A,
                "instanceName", "Acme main till",
                "hostname", "acme-pos-1",
                "appVersion", "0.1.0"), null);
        assertThat(activated.status()).isEqualTo(200);

        installToken = (String) activated.body().get("token");
        assertThat(installToken).isNotBlank();

        // Verify exactly as an install would: against the published JWKS, by key id.
        Jws.Parsed parsed = Jws.parse(installToken);
        assertThat(parsed.kid()).isEqualTo(activated.body().get("kid"));
        assertThat(Jws.verify(publicKeyFromJwks(parsed.kid()), parsed)).isTrue();

        Map<String, Object> claims = Json.readObject(parsed.payloadJson());
        assertThat(claims).containsEntry("iss", "inventoryerp-license")
                .containsEntry("sub", licenseKey)
                .containsEntry("aud", FINGERPRINT_A);

        Map<String, Object> lic = licenceClaim(claims);
        assertThat(lic).containsEntry("customer", "Acme Cafe Ltd")
                .containsEntry("plan", "pro-monthly")
                .containsEntry("period", "MONTH")
                .containsEntry("status", "active")
                .containsEntry("offline", false);
        assertThat(lic.get("serverTime")).isNotNull();
        assertThat(lic.get("refreshAfter")).isNotNull();

        // exp covers the whole grace window, not just the term (docs/spec/licensing.md §2): a
        // signature that stopped verifying during grace would lock a shop out instead of warning it.
        // exp is whole seconds, as JWT requires; graceUntil keeps the database's precision.
        long exp = ((Number) claims.get("exp")).longValue();
        assertThat(Instant.ofEpochSecond(exp)).isEqualTo(
                Instant.parse((String) lic.get("graceUntil")).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @Order(6)
    void reactivatingTheSameInstallDoesNotConsumeASecondSlot() {
        assertThat(post("/api/v1/activate",
                Map.of("licenseKey", licenseKey, "fingerprint", FINGERPRINT_A), null).status())
                .isEqualTo(200);

        Reply activations = get("/api/v1/admin/licenses/" + licenseId + "/activations", ownerToken);
        assertThat(activations.raw()).contains(FINGERPRINT_A);

        Reply license = get("/api/v1/admin/licenses/" + licenseId, ownerToken);
        assertThat(((Number) license.body().get("activeInstances")).intValue()).isEqualTo(1);
    }

    @Test
    @Order(7)
    void aSecondInstallIsRefusedOnASingleInstanceLicence() {
        Reply refused = post("/api/v1/activate",
                Map.of("licenseKey", licenseKey, "fingerprint", FINGERPRINT_B), null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("message"))
                .isEqualTo("This licence is already activated on another installation.");
    }

    @Test
    @Order(8)
    void anUnknownKeyAndAMalformedKeyAnswerTheSame() {
        Reply unknown = post("/api/v1/activate",
                Map.of("licenseKey", "ERP-2345-6789-ABCD-0000", "fingerprint", FINGERPRINT_B), null);
        Reply malformed = post("/api/v1/activate",
                Map.of("licenseKey", "total-rubbish", "fingerprint", FINGERPRINT_B), null);

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(malformed.status()).isEqualTo(404);
        assertThat(unknown.body().get("message")).isEqualTo("Licence key not recognised.")
                .isEqualTo(malformed.body().get("message"));
    }

    @Test
    @Order(9)
    void aBadFingerprintIsRejectedByValidation() {
        Reply reply = post("/api/v1/activate",
                Map.of("licenseKey", licenseKey, "fingerprint", "too-short"), null);
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.raw()).contains("64 hexadecimal characters");
    }

    @Test
    @Order(10)
    void checkingInReportsCountsAndReturnsAFreshToken() {
        Reply beat = post("/api/v1/heartbeat", Map.of(
                "token", installToken,
                "deviceCount", 2,
                "userCount", 9,
                "orgCount", 1,
                "appVersion", "0.1.0",
                "mode", "ACTIVE"), null);
        assertThat(beat.status()).isEqualTo(200);

        String refreshed = (String) beat.body().get("token");
        assertThat(Jws.verify(publicKeyFromJwks(Jws.parse(refreshed).kid()), Jws.parse(refreshed))).isTrue();
        installToken = refreshed;

        Reply heartbeats = get("/api/v1/admin/licenses/" + licenseId + "/heartbeats", ownerToken);
        assertThat(heartbeats.raw()).contains("\"deviceCount\":2");
    }

    @Test
    @Order(11)
    void aForgedTokenIsRefusedAtCheckIn() {
        String[] parts = installToken.split("\\.");
        String forgedClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                Json.write(Map.of("jti", "whatever", "aud", FINGERPRINT_A))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Reply refused = post("/api/v1/heartbeat",
                Map.of("token", parts[0] + "." + forgedClaims + "." + parts[2]), null);
        assertThat(refused.status()).isEqualTo(401);
    }

    @Test
    @Order(12)
    void renewingEarlyExtendsFromTheCurrentExpiry() {
        Reply before = get("/api/v1/admin/licenses/" + licenseId, ownerToken);
        Instant firstExpiry = Instant.parse((String) before.body().get("expiresAt"));

        Reply renewed = post("/api/v1/admin/licenses/" + licenseId + "/renew",
                Map.of("periods", 2), ownerToken);
        assertThat(renewed.status()).isEqualTo(200);

        Instant newExpiry = Instant.parse((String) renewed.body().get("expiresAt"));
        // Two further months on top of the month already paid for, not two months from today.
        assertThat(newExpiry).isEqualTo(firstExpiry.atZone(ZoneOffset.UTC).plusMonths(2).toInstant());
    }

    @Test
    @Order(13)
    void suspendingReachesTheInstallOnItsNextCheckInAndResumingLiftsIt() {
        post("/api/v1/admin/licenses/" + licenseId + "/suspend",
                Map.of("reason", "non-payment"), ownerToken);

        // A signed token saying so, not a bare refusal: the install keeps what it holds on a 403.
        Reply suspended = post("/api/v1/heartbeat", Map.of("token", installToken), null);
        assertThat(suspended.status()).isEqualTo(200);
        assertThat(heartbeatStatus(suspended)).isEqualTo("suspended");

        assertThat(post("/api/v1/admin/licenses/" + licenseId + "/resume", null, ownerToken).status())
                .isEqualTo(200);
        Reply resumed = post("/api/v1/heartbeat", Map.of("token", installToken), null);
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(heartbeatStatus(resumed)).isEqualTo("active");
    }

    private Object heartbeatStatus(Reply reply) {
        Jws.Parsed parsed = Jws.parse((String) reply.body().get("token"));
        assertThat(Jws.verify(publicKeyFromJwks(parsed.kid()), parsed)).isTrue();
        return licenceClaim(Json.readObject(parsed.payloadJson())).get("status");
    }

    @Test
    @Order(14)
    void anOfflineTokenIsBoundToTheFingerprintItWasAskedFor() {
        Reply issued = post("/api/v1/admin/licenses/" + licenseId + "/offline-token",
                Map.of("fingerprint", FINGERPRINT_A, "instanceName", "air-gapped till"), ownerToken);
        assertThat(issued.status()).isEqualTo(200);
        assertThat(issued.headers().get("Content-Disposition")).contains(".lic");

        Jws.Parsed parsed = Jws.parse((String) issued.body().get("token"));
        assertThat(Jws.verify(publicKeyFromJwks(parsed.kid()), parsed)).isTrue();

        Map<String, Object> claims = Json.readObject(parsed.payloadJson());
        assertThat(claims).containsEntry("aud", FINGERPRINT_A);

        Map<String, Object> lic = licenceClaim(claims);
        assertThat(lic).containsEntry("offline", true);
        // An air-gapped install has no way to come back, so it is never told to.
        assertThat(lic).doesNotContainKey("refreshAfter");
    }

    @Test
    @Order(15)
    void revokingEndsEverythingAtOnce() {
        post("/api/v1/admin/licenses/" + licenseId + "/revoke", Map.of("reason", "chargeback"), ownerToken);

        // The install learns of it on its next check-in, from a token it can verify.
        Reply revoked = post("/api/v1/heartbeat", Map.of("token", installToken), null);
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(heartbeatStatus(revoked)).isEqualTo("revoked");

        Reply reactivation = post("/api/v1/activate",
                Map.of("licenseKey", licenseKey, "fingerprint", FINGERPRINT_A), null);
        assertThat(reactivation.status()).isEqualTo(403);

        // Every decision in this test is on the record, which is what a disputed lockout is settled from.
        String audit = get("/api/v1/admin/licenses/" + licenseId + "/events", ownerToken).raw();
        assertThat(audit).contains("\"issued\"").contains("\"activated\"").contains("\"renewed\"")
                .contains("\"suspended\"").contains("\"resumed\"").contains("\"revoked\"")
                .contains("\"offline_token_issued\"");
    }

    @Test
    @Order(16)
    void aRevokedLicenceCannotBeRenewedBackToLife() {
        Reply refused = post("/api/v1/admin/licenses/" + licenseId + "/renew", null, ownerToken);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.raw()).contains("revoked licence cannot be renewed");
    }

    @Test
    @Order(17)
    void supportMayReadButNotDecide() {
        post("/api/v1/admin/admins", Map.of(
                "email", "support@example.com",
                "password", "another-long-password",
                "role", "support"), ownerToken);

        String supportToken = signIn("support@example.com", "another-long-password");

        assertThat(get("/api/v1/admin/licenses", supportToken).status()).isEqualTo(200);
        assertThat(get("/api/v1/admin/dashboard", supportToken).status()).isEqualTo(200);

        assertThat(post("/api/v1/admin/licenses/" + licenseId + "/revoke", null, supportToken).status())
                .isEqualTo(403);
        assertThat(post("/api/v1/admin/keys/rotate", null, supportToken).status()).isEqualTo(403);
        assertThat(get("/api/v1/admin/admins", supportToken).status()).isEqualTo(403);
        assertThat(post("/api/v1/admin/customers", Map.of("name", "Nope"), supportToken).status())
                .isEqualTo(403);
    }

    @Test
    @Order(18)
    void rotatingKeepsOlderTokensVerifiable() {
        String kidBefore = Jws.parse(installToken).kid();

        Reply rotated = post("/api/v1/admin/keys/rotate", null, ownerToken);
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(rotated.body().get("kid")).isNotEqualTo(kidBefore);

        // The retired key stays published, so a token already in the field still verifies.
        assertThat(Jws.verify(publicKeyFromJwks(kidBefore), Jws.parse(installToken))).isTrue();
    }

    @Test
    @Order(19)
    void theJwksNeverCarriesPrivateMaterial() {
        String jwks = get("/api/v1/.well-known/license-keys.json", null).raw();

        assertThat(jwks).contains("\"kty\":\"OKP\"").contains("\"crv\":\"Ed25519\"").contains("\"x\":");
        assertThat(jwks).doesNotContain("\"d\":").doesNotContain("PRIVATE");
    }

    @Test
    @Order(20)
    void theDashboardSummarisesTheBook() {
        Reply dashboard = get("/api/v1/admin/dashboard", ownerToken);
        assertThat(dashboard.status()).isEqualTo(200);
        assertThat(dashboard.body()).containsKeys("licenses", "expiring", "activations",
                "recurringRevenueEstimate", "silentInstalls", "overLimitInstalls");

        @SuppressWarnings("unchecked")
        Map<String, Object> byStatus = (Map<String, Object>) dashboard.body().get("licenses");
        assertThat(((Number) byStatus.get("revoked")).intValue()).isEqualTo(1);
    }

    @Test
    @Order(21)
    void theAuditTrailNamesTheAdminWhoDecided() {
        Reply audit = get("/api/v1/admin/audit", ownerToken);
        assertThat(audit.status()).isEqualTo(200);
        assertThat(audit.raw()).contains("owner@example.com")
                .contains("license.revoke").contains("license.renew").contains("key.rotate");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> licenceClaim(Map<String, Object> claims) {
        return (Map<String, Object>) claims.get("lic");
    }

    /** Fetch a public key out of the served JWKS, the way an ERP install learns a new key id. */
    private PublicKey publicKeyFromJwks(String kid) {
        Map<String, Object> jwks = Json.readObject(get("/api/v1/.well-known/license-keys.json", null).raw());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        for (Map<String, Object> jwk : keys) {
            if (kid.equals(jwk.get("kid"))) {
                return Ed25519Keys.fromRawPublic(Base64.getUrlDecoder().decode((String) jwk.get("x")));
            }
        }
        throw new AssertionError("No key " + kid + " in the published JWKS");
    }
}
