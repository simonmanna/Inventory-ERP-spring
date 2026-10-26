package com.inventoryerp.kernel.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The licence token format is the contract between this installation and the licence authority,
 * implemented twice — signing there, verifying here.
 *
 * <p>Rather than trust that the two agree, this test <b>re-implements the signing half from the
 * specification</b> (docs/spec/licensing.md §2) using only the JDK, and checks that what this side
 * accepts is exactly that. If the server's {@code Jws} ever drifts, its own
 * {@code JwsTest.theHeaderIsExactlyTheDocumentedObject} fails against the same literal header, so
 * the pair is pinned from both ends without either build depending on the other.
 */
class LicenseTokenFormatTest {

    /** The signing half, written from the spec: header, dot, payload, dot, Ed25519 signature. */
    private static String sign(PrivateKey key, String kid, String payloadJson) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        String signingInput = b64.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "." + b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64.encodeToString(signer.sign());
    }

    /**
     * A token exactly as the authority mints one. Note {@code exp} equals {@code lic.graceUntil},
     * not {@code lic.expiresAt}: verification has to keep succeeding throughout grace, or a lapsed
     * shop would be locked out instead of warned.
     */
    private static final String PAYLOAD = """
            {"iss":"inventoryerp-license","sub":"ERP-2345-6789-ABCD-0000","aud":"%s","jti":"act-1",\
            "iat":1778846400,"nbf":1778846400,"exp":1782129600,"lic":{"customer":"Acme Cafe Ltd",\
            "customerId":"cust-1","plan":"pro-monthly","planName":"Pro (monthly)","period":"MONTH",\
            "periodCount":1,"status":"active","trial":false,"startsAt":"2026-05-15T12:00:00Z",\
            "expiresAt":"2026-06-15T12:00:00Z","graceUntil":"2026-06-22T12:00:00Z","graceDays":7,\
            "heartbeatSeconds":21600,"refreshAfter":"2026-05-15T18:00:00Z",\
            "limits":{"devices":5,"users":25,"organizations":1},\
            "features":["crm","accounting"],"offline":false,"serverTime":"2026-05-15T12:00:00Z"}}\
            """.formatted("a".repeat(64));

    @Test
    void verifiesATokenSignedExactlyAsTheSpecificationDescribes() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String token = sign(keys.getPrivate(), "0123456789abcdef0123456789abcdef", PAYLOAD);

        LicenseJws.Parsed parsed = LicenseJws.parse(token);

        assertThat(parsed.kid()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(parsed.algorithm()).isEqualTo("EdDSA");
        assertThat(LicenseJws.verify(keys.getPublic(), parsed)).isTrue();
    }

    @Test
    void readsEveryClaimTheSpecificationPromises() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String token = sign(keys.getPrivate(), "k1", PAYLOAD);

        LicenseClaims claims = LicenseClaims.parse(LicenseJws.parse(token).payloadJson());

        assertThat(claims.issuer()).isEqualTo("inventoryerp-license");
        assertThat(claims.licenseKey()).isEqualTo("ERP-2345-6789-ABCD-0000");
        assertThat(claims.fingerprint()).isEqualTo("a".repeat(64));
        assertThat(claims.activationId()).isEqualTo("act-1");
        assertThat(claims.customer()).isEqualTo("Acme Cafe Ltd");
        assertThat(claims.plan()).isEqualTo("pro-monthly");
        assertThat(claims.planName()).isEqualTo("Pro (monthly)");
        assertThat(claims.period()).isEqualTo("MONTH");
        assertThat(claims.periodCount()).isEqualTo(1);
        assertThat(claims.status()).isEqualTo("active");
        assertThat(claims.trial()).isFalse();
        assertThat(claims.startsAt()).isEqualTo("2026-05-15T12:00:00Z");
        assertThat(claims.expiresAt()).isEqualTo("2026-06-15T12:00:00Z");
        assertThat(claims.graceUntil()).isEqualTo("2026-06-22T12:00:00Z");
        assertThat(claims.graceDays()).isEqualTo(7);
        assertThat(claims.heartbeatSeconds()).isEqualTo(21_600L);
        assertThat(claims.refreshAfter()).isEqualTo("2026-05-15T18:00:00Z");
        assertThat(claims.features()).containsExactlyInAnyOrder("crm", "accounting");
        assertThat(claims.maxDevices()).isEqualTo(5);
        assertThat(claims.maxUsers()).isEqualTo(25);
        assertThat(claims.maxOrganizations()).isEqualTo(1);
        assertThat(claims.offline()).isFalse();
        assertThat(claims.tokenExpiry()).isEqualTo("2026-06-22T12:00:00Z");
        assertThat(claims.isPerpetual()).isFalse();
    }

    @Test
    void rejectsASignatureFromAnyOtherKey() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var impostor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String token = sign(keys.getPrivate(), "k1", PAYLOAD);

        assertThat(LicenseJws.verify(impostor.getPublic(), LicenseJws.parse(token))).isFalse();
    }

    @Test
    void rejectsAnEditedFeatureList() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String token = sign(keys.getPrivate(), "k1", PAYLOAD);
        String[] parts = token.split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                PAYLOAD.replace("[\"crm\",\"accounting\"]", "[\"crm\",\"accounting\",\"hr\"]")
                        .getBytes(StandardCharsets.UTF_8));

        LicenseJws.Parsed tampered = LicenseJws.parse(parts[0] + "." + forged + "." + parts[2]);

        // The forged claims parse — that is the point of signing them.
        assertThat(LicenseClaims.parse(tampered.payloadJson()).features()).contains("hr");
        assertThat(LicenseJws.verify(keys.getPublic(), tampered)).isFalse();
    }

    @Test
    void rejectsAnUnsignedAlgorithm() throws Exception {
        // "alg":"none" is the classic JWT hole; this verifier refuses anything but EdDSA.
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String token = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"k1\"}"
                        .getBytes(StandardCharsets.UTF_8))
                + "." + b64.encodeToString(PAYLOAD.getBytes(StandardCharsets.UTF_8)) + ".";

        assertThatThrownBy(() -> LicenseJws.parse(token)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnHmacSignedToken() throws Exception {
        // An attacker who knows a kid must not be able to swap the asymmetric scheme for a shared one.
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}"
                .getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("k1".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String token = header + "." + payload + "."
                + b64.encodeToString(mac.doFinal((header + "." + payload)
                        .getBytes(StandardCharsets.US_ASCII)));

        assertThat(LicenseJws.verify(keys.getPublic(), LicenseJws.parse(token))).isFalse();
    }

    @Test
    void refusesVerificationWithNoKey() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String token = sign(keys.getPrivate(), "k1", PAYLOAD);

        assertThat(LicenseJws.verify(null, LicenseJws.parse(token))).isFalse();
    }

    @Test
    void acceptsAPerpetualLicenceWithNoEndDate() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String perpetual = PAYLOAD
                .replace("\"expiresAt\":\"2026-06-15T12:00:00Z\"", "\"expiresAt\":null")
                .replace("\"graceUntil\":\"2026-06-22T12:00:00Z\"", "\"graceUntil\":null");

        LicenseClaims claims = LicenseClaims.parse(
                LicenseJws.parse(sign(keys.getPrivate(), "k1", perpetual)).payloadJson());

        assertThat(claims.expiresAt()).isNull();
        assertThat(claims.isPerpetual()).isTrue();
    }

    @Test
    void toleratesClaimsThisReleaseHasNeverHeardOf() throws Exception {
        // A newer authority may add claims; an install must keep working when it does.
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String future = PAYLOAD.replace("\"offline\":false", "\"offline\":false,\"somethingNew\":{\"a\":1}");

        LicenseClaims claims = LicenseClaims.parse(
                LicenseJws.parse(sign(keys.getPrivate(), "k1", future)).payloadJson());

        assertThat(claims.plan()).isEqualTo("pro-monthly");
    }

    @Test
    void treatsAnUnreadableDateAsAnInvalidToken() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String broken = PAYLOAD.replace("\"expiresAt\":\"2026-06-15T12:00:00Z\"",
                "\"expiresAt\":\"next tuesday\"");
        String payloadJson = LicenseJws.parse(sign(keys.getPrivate(), "k1", broken)).payloadJson();

        assertThatThrownBy(() -> LicenseClaims.parse(payloadJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreadable date");
    }

    @Test
    void refusesATokenWithNoLicenceClaim() {
        assertThatThrownBy(() -> LicenseClaims.parse("{\"iss\":\"inventoryerp-license\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no 'lic' claim");
    }

    @Test
    void defaultsAMissingHeartbeatIntervalToSixHours() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String without = PAYLOAD.replace("\"heartbeatSeconds\":21600,", "");

        LicenseClaims claims = LicenseClaims.parse(
                LicenseJws.parse(sign(keys.getPrivate(), "k1", without)).payloadJson());

        assertThat(claims.heartbeatSeconds()).isEqualTo(6 * 3600L);
    }

    @Test
    void readsAnAbsentCapAsUnlimited() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String noUserCap = PAYLOAD.replace("\"users\":25,", "");

        LicenseClaims claims = LicenseClaims.parse(
                LicenseJws.parse(sign(keys.getPrivate(), "k1", noUserCap)).payloadJson());

        assertThat(claims.maxUsers()).isNull();
        assertThat(claims.maxDevices()).isEqualTo(5);
    }

    @Test
    void featureSetsAreImmutable() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        LicenseClaims claims = LicenseClaims.parse(
                LicenseJws.parse(sign(keys.getPrivate(), "k1", PAYLOAD)).payloadJson());

        Set<String> features = claims.features();
        assertThatThrownBy(() -> features.add("hr")).isInstanceOf(UnsupportedOperationException.class);
    }
}
