package com.inventoryerp.kernel.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * Licensing against the running application: what an installation is told, and what it refuses.
 *
 * <p>The authority is not involved. Instead the test generates a signing keypair, makes this install
 * trust it by writing the JWKS onto the licence row — the same path a real install takes after
 * fetching keys once — and then mints licences for itself. That exercises exactly the code a
 * customer's air-gapped deployment runs, and keeps the test free of a second service.
 *
 * <p>The licence is handed back at the end: other suites share this database, and leaving the
 * install read-only would fail them for reasons that have nothing to do with them.
 */
@Import(TestcontainersConfiguration.class)
class LicensingIT extends ApiTestSupport {

    @Autowired
    private LicenseService licenses;

    @Autowired
    private LicenseKeySet keySet;

    private static final KeyPair KEYS = generateKeys();
    private static final String KID = "integrationtestkey0123456789abcd";

    private String fingerprint;

    private static KeyPair generateKeys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    void trustTheTestKey() {
        fingerprint = licenses.fingerprint();
        // The route a connected install takes once: learn the authority's keys, then keep them so a
        // later restart with no network can still verify what it holds.
        keySet.addAll(jwks());
        jdbc.sql("UPDATE \"LicenseInstallation\" SET \"trustedKeys\" = ?::jsonb WHERE id = ?")
                .params(jwks(), LicenseInstallation.ID)
                .update();
        licenses.invalidate();
    }

    @AfterAll
    void handBackTheLicence() {
        as("POST", "/license/deactivate", null);
        licenses.invalidate();
    }

    // ---- what a fresh install looks like ----

    @Test
    void aFreshInstallIsOnTrialAndFullyUsable() {
        clearLicence();

        JsonNode status = ok(200, "GET", "/license/status", null);

        assertThat(status.path("mode").asString()).isEqualTo("TRIAL");
        assertThat(status.path("writable").asBoolean()).isTrue();
        assertThat(status.path("licensed").asBoolean()).isFalse();
        // A trial withholds nothing: an evaluation that hides half the product is not an evaluation.
        assertThat(features(status)).containsAll(LicenseFeatures.ALL);
        assertThat(ok(200, "PUT", "/settings/company", Map.of())).isNotNull();
    }

    @Test
    void theFingerprintIsSixtyFourHexCharactersAndNeedsPermission() {
        JsonNode body = ok(200, "GET", "/license/fingerprint", null);
        assertThat(body.path("fingerprint").asString()).matches("[0-9a-f]{64}").isEqualTo(fingerprint);

        // Unprivileged users see the status (the banner needs it) but not the install's identity.
        assertThat(call("GET", "/license/fingerprint", null, (String) null).status()).isEqualTo(401);
    }

    @Test
    void statusIsReadableByAnyAuthenticatedUserBecauseTheBannerNeedsIt() {
        assertThat(as("GET", "/license/status", null).status()).isEqualTo(200);
        assertThat(call("GET", "/license/status", null, (String) null).status()).isEqualTo(401);
    }

    // ---- applying a licence ----

    @Test
    void anOfflineLicenceFileIsAcceptedAndTakesEffect() {
        JsonNode status = applyLicence(licence("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting"), 5, 25, 1));

        assertThat(status.path("mode").asString()).isEqualTo("ACTIVE");
        assertThat(status.path("licensed").asBoolean()).isTrue();
        assertThat(status.path("plan").asString()).isEqualTo("pro-monthly");
        assertThat(status.path("customer").asString()).isEqualTo("Acme Cafe Ltd");
        assertThat(status.path("offline").asBoolean()).isTrue();
        assertThat(features(status)).containsExactlyInAnyOrder("crm", "accounting");
        assertThat(status.path("limits").path("devices").asInt()).isEqualTo(5);
        assertThat(status.path("usage").path("users").asInt()).isPositive();
    }

    @Test
    void aLicenceForAnotherInstallationIsRefused() {
        String foreign = token(claims("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm"), null, null, null, "b".repeat(64)));

        var refused = as("POST", "/license/offline", Map.of("token", foreign));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().path("message").asString())
                .isEqualTo("This licence file was issued for a different installation.");
    }

    @Test
    void anEditedLicenceIsRefused() {
        String good = licence("active", Instant.now().plus(Duration.ofDays(30)), 7, List.of("crm"), 1, 1, 1);
        String[] parts = good.split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("\"crm\"", "\"crm\",\"hr\"")
                        .getBytes(StandardCharsets.UTF_8));

        var refused = as("POST", "/license/offline",
                Map.of("token", parts[0] + "." + forged + "." + parts[2]));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().path("message").asString()).isEqualTo("This licence file is not valid.");
    }

    @Test
    void aLicenceSignedByAnUntrustedKeyIsRefused() throws Exception {
        KeyPair impostor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String token = sign(impostor, "someoneelseskey00000000000000000",
                claims("active", Instant.now().plus(Duration.ofDays(30)), 7, List.of("crm"), null, null, null,
                        fingerprint));

        var refused = as("POST", "/license/offline", Map.of("token", token));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().path("message").asString()).contains("does not trust");
    }

    @Test
    void rubbishIsRefusedWithoutAStackTrace() {
        var refused = as("POST", "/license/offline", Map.of("token", "this is not a licence"));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().path("message").asString())
                .isEqualTo("That does not look like a licence file.");
    }

    // ---- enforcement ----

    @Test
    void insideGraceEverythingStillWorksButTheResponseSaysSo() {
        // Expired yesterday, seven days of grace: the shop keeps trading while the invoice is sorted.
        applyLicence(licence("active", Instant.now().minus(Duration.ofDays(1)), 7,
                List.of("crm", "accounting"), 5, 25, 1));

        JsonNode status = ok(200, "GET", "/license/status", null);
        assertThat(status.path("mode").asString()).isEqualTo("GRACE");
        assertThat(status.path("writable").asBoolean()).isTrue();

        var write = as("PUT", "/settings/company", Map.of());
        assertThat(write.status()).isEqualTo(200);
        assertThat(write.raw().headers().firstValue("X-License-Warning")).contains("grace");
    }

    @Test
    void pastGraceWritesAreRefusedWithPaymentRequiredAndReadsStillWork() {
        applyLicence(licence("active", Instant.now().minus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting"), 5, 25, 1));

        assertThat(ok(200, "GET", "/license/status", null).path("mode").asString()).isEqualTo("READ_ONLY");

        var write = as("PUT", "/settings/company", Map.of());
        assertThat(write.status()).isEqualTo(402);
        assertThat(write.body().path("message").asString())
                .isEqualTo("Your licence has expired. The system is read-only until it is renewed.");
        // The body carries the licence, so one client dialog can explain every case.
        assertThat(write.body().path("license").path("mode").asString()).isEqualTo("READ_ONLY");
        assertThat(write.body().path("license").path("plan").asString()).isEqualTo("pro-monthly");
        assertThat(write.body().path("license").path("expiresAt").asString()).isNotBlank();

        // Reading is never blocked: the data is the customer's, whatever the billing says.
        assertThat(as("GET", "/settings/company", null).status()).isEqualTo(200);
        assertThat(as("GET", "/users", null).status()).isEqualTo(200);
    }

    @Test
    void theWayOutOfReadOnlyIsNotItselfBlocked() {
        applyLicence(licence("active", Instant.now().minus(Duration.ofDays(30)), 7, List.of("crm"), 1, 1, 1));
        assertThat(as("PUT", "/settings/company", Map.of()).status()).isEqualTo(402);

        // Still able to sign in and to apply a new licence — otherwise read-only would be terminal.
        assertThat(login("DEMO", "admin@demo.test", "Admin@123")).isNotBlank();
        JsonNode recovered = applyLicence(licence("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting"), 5, 25, 1));
        assertThat(recovered.path("mode").asString()).isEqualTo("ACTIVE");
        assertThat(as("PUT", "/settings/company", Map.of()).status()).isEqualTo(200);
    }

    @Test
    void aRevokedLicenceIsReadOnlyImmediatelyWithNoGrace() {
        applyLicence(licence("revoked", Instant.now().plus(Duration.ofDays(300)), 7, List.of("crm"), 1, 1, 1));

        assertThat(ok(200, "GET", "/license/status", null).path("mode").asString()).isEqualTo("REVOKED");
        var write = as("PUT", "/settings/company", Map.of());
        assertThat(write.status()).isEqualTo(402);
        assertThat(write.body().path("message").asString())
                .isEqualTo("This licence has been revoked. Contact your supplier.");
    }

    @Test
    void aSuspendedLicenceSaysSoRatherThanClaimingToHaveExpired() {
        applyLicence(licence("suspended", Instant.now().plus(Duration.ofDays(300)), 7, List.of("crm"), 1, 1, 1));

        var write = as("PUT", "/settings/company", Map.of());
        assertThat(write.status()).isEqualTo(402);
        assertThat(write.body().path("message").asString())
                .isEqualTo("This licence is suspended. Contact your supplier.");
    }

    @Test
    void anUnlicensedFeatureIsRefusedEvenOnARead() {
        // A read of a module nobody paid for is still a use of it.
        applyLicence(licence("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting"), 5, 25, 1));

        var refused = as("GET", "/webhooks/endpoints", null);

        assertThat(refused.status()).isEqualTo(402);
        assertThat(refused.body().path("message").asString())
                .isEqualTo("'webhooks' is not included in your licence.");
    }

    @Test
    void aLicensedFeatureIsReachable() {
        applyLicence(licence("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting", "webhooks"), 5, 25, 1));

        assertThat(as("GET", "/webhooks/endpoints", null).status()).isEqualTo(200);
    }

    // ---- seat caps ----

    @Test
    void goingOverTheUserCapRefusesTheNewUserAndNothingElse() {
        int users = licenses.usage().users();
        applyLicence(licence("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting"), 5, users, 1));

        var refused = as("POST", "/users", Map.of(
                "email", "over-the-cap@demo.test",
                "password", "Another@12345",
                "firstName", "Over",
                "roleIds", List.of()));

        assertThat(refused.status()).isEqualTo(402);
        assertThat(refused.body().path("message").asString())
                .isEqualTo("Your licence allows " + users + " user(s). Deactivate a user or upgrade your plan.");
        // Over a cap is not a lockout: everything already there keeps working.
        assertThat(as("GET", "/users", null).status()).isEqualTo(200);
        assertThat(as("PUT", "/settings/company", Map.of()).status()).isEqualTo(200);
    }

    @Test
    void roomForOneMoreMeansOneMoreIsAllowed() {
        int users = licenses.usage().users();
        applyLicence(licence("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting"), 5, users + 1, 1));

        String email = "within-the-cap-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        var created = as("POST", "/users", Map.of(
                "email", email,
                "password", "Another@12345",
                "firstName", "Within",
                "roleIds", List.of()));

        assertThat(created.status()).isEqualTo(201);
        jdbc.sql("DELETE FROM \"User\" WHERE email = ?").param(email).update();
        licenses.invalidate();
    }

    @Test
    void anAbsentCapMeansUnlimited() {
        applyLicence(licence("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting"), null, null, null));

        JsonNode status = ok(200, "GET", "/license/status", null);
        assertThat(status.path("limits").isEmpty()).isTrue();

        String email = "uncapped-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        var created = as("POST", "/users", Map.of(
                "email", email, "password", "Another@12345", "firstName", "Un", "roleIds", List.of()));
        assertThat(created.status()).isEqualTo(201);
        jdbc.sql("DELETE FROM \"User\" WHERE email = ?").param(email).update();
        licenses.invalidate();
    }

    // ---- entitlements drive the gating that already exists ----

    @Test
    void anUnlicensedModuleIsSwitchedOffForEveryOrganization() {
        jdbc.sql("""
                        INSERT INTO "OrganizationModule" (id, "organizationId", "moduleName", "isActive",
                                                          config, "enabledAt")
                        VALUES (?, ?, 'rental', true, '{}'::jsonb, now())""")
                .params(java.util.UUID.randomUUID().toString(), demoOrg)
                .update();

        // The licence does not include rentals, so the module row is deactivated and the existing
        // module guard refuses the routes — no second gating mechanism needed.
        applyLicence(licence("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting"), 5, 25, 1));

        Long active = jdbc.sql("SELECT count(*) FROM \"OrganizationModule\" WHERE \"moduleName\" = 'rental'"
                        + " AND \"isActive\" = true")
                .query(Long.class)
                .single();
        assertThat(active).isZero();
    }

    @Test
    void aLicensedModuleIsLeftAloneRatherThanForcedOn() {
        jdbc.sql("UPDATE \"OrganizationModule\" SET \"isActive\" = false, \"disabledAt\" = now()"
                        + " WHERE \"moduleName\" = 'crm'")
                .update();

        applyLicence(licence("active", Instant.now().plus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting"), 5, 25, 1));

        // The operator switched CRM off locally. The licence permits it; that choice is still theirs.
        Long active = jdbc.sql("SELECT count(*) FROM \"OrganizationModule\" WHERE \"moduleName\" = 'crm'"
                        + " AND \"isActive\" = true")
                .query(Long.class)
                .single();
        assertThat(active).isZero();
    }

    // ---- the Android terminal's view ----

    @Test
    void theSyncViewTellsATillOnlyWhatItNeeds() {
        applyLicence(licence("active", Instant.now().minus(Duration.ofDays(30)), 7,
                List.of("crm", "accounting", "androidPos"), 5, 25, 1));

        Map<String, Object> view = LicenseSyncView.of(licenses.current());

        assertThat(view).containsKeys("mode", "reason", "writable", "expiresAt", "graceUntil",
                "daysRemaining", "androidPos", "message");
        assertThat(view.get("writable")).isEqualTo(false);
        assertThat(view.get("message"))
                .isEqualTo("Your licence has expired. The system is read-only until it is renewed.");
        // A device token is a weaker credential than a user session, so the till is told no more.
        assertThat(view).doesNotContainKeys("customer", "fingerprint", "plan", "features", "limits", "usage");
    }

    // ---- helpers ----

    private void clearLicence() {
        jdbc.sql("""
                        UPDATE "LicenseInstallation"
                        SET token = NULL, "tokenKid" = NULL, claims = NULL, "licenseKey" = NULL,
                            status = NULL, "expiresAt" = NULL, "graceUntil" = NULL,
                            "serverTimeHighWater" = NULL
                        WHERE id = ?""")
                .param(LicenseInstallation.ID)
                .update();
        licenses.invalidate();
    }

    private JsonNode applyLicence(String token) {
        return ok(200, "POST", "/license/offline", Map.of("token", token));
    }

    private List<String> features(JsonNode status) {
        List<String> out = new java.util.ArrayList<>();
        status.path("features").forEach(node -> out.add(node.asString()));
        return out;
    }

    private String licence(String status, Instant expiresAt, int graceDays, List<String> features,
            Integer devices, Integer users, Integer organizations) {
        return token(claims(status, expiresAt, graceDays, features, devices, users, organizations,
                fingerprint));
    }

    private String token(String claimsJson) {
        try {
            return sign(KEYS, KID, claimsJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A licence exactly as the authority mints one (docs/spec/licensing.md §2). */
    private String claims(String status, Instant expiresAt, int graceDays, List<String> features,
            Integer devices, Integer users, Integer organizations, String boundTo) {
        Instant now = Instant.now();
        Instant graceUntil = expiresAt == null ? null : expiresAt.plus(Duration.ofDays(graceDays));

        Map<String, Object> limits = new LinkedHashMap<>();
        if (devices != null) {
            limits.put("devices", devices);
        }
        if (users != null) {
            limits.put("users", users);
        }
        if (organizations != null) {
            limits.put("organizations", organizations);
        }

        Map<String, Object> lic = new LinkedHashMap<>();
        lic.put("customer", "Acme Cafe Ltd");
        lic.put("customerId", "cust-1");
        lic.put("plan", "pro-monthly");
        lic.put("planName", "Pro (monthly)");
        lic.put("period", "MONTH");
        lic.put("periodCount", 1);
        lic.put("status", status);
        lic.put("trial", false);
        lic.put("startsAt", now.minus(Duration.ofDays(31)).toString());
        lic.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        lic.put("graceUntil", graceUntil == null ? null : graceUntil.toString());
        lic.put("graceDays", graceDays);
        lic.put("heartbeatSeconds", 21_600L);
        lic.put("limits", limits);
        lic.put("features", features);
        lic.put("offline", true);
        lic.put("serverTime", now.toString());

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "inventoryerp-license");
        claims.put("sub", "ERP-2345-6789-ABCD-0000");
        claims.put("aud", boundTo);
        claims.put("jti", "activation-1");
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.getEpochSecond());
        claims.put("exp", (graceUntil == null ? now.plus(Duration.ofDays(36_500)) : graceUntil)
                .getEpochSecond());
        claims.put("lic", lic);
        return mapper.writeValueAsString(claims);
    }

    /** The authority's signing half, from the spec: header, dot, payload, dot, Ed25519 signature. */
    private static String sign(KeyPair keys, String kid, String payloadJson) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        String signingInput = b64.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "." + b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64.encodeToString(signer.sign());
    }

    /** The test key as the authority would publish it. */
    private static String jwks() {
        byte[] spki = KEYS.getPublic().getEncoded();
        byte[] raw = java.util.Arrays.copyOfRange(spki, spki.length - 32, spki.length);
        return "{\"keys\":[{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"use\":\"sig\",\"kid\":\""
                + KID + "\",\"x\":" + "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
                + "\"}]}";
    }
}
