package com.inventoryerp.license.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The token format is the contract between this server and every ERP install, and the verifying half
 * is reimplemented over there. These tests pin the parts that must not drift.
 */
class JwsTest {

    private final KeyPair keys = Ed25519Keys.generate();

    @Test
    void signsAndVerifiesARoundTrip() {
        String token = Jws.sign(keys.getPrivate(), "abc123", "{\"sub\":\"ERP-1\"}");
        Jws.Parsed parsed = Jws.parse(token);

        assertThat(parsed.kid()).isEqualTo("abc123");
        assertThat(parsed.algorithm()).isEqualTo("EdDSA");
        assertThat(parsed.payloadJson()).isEqualTo("{\"sub\":\"ERP-1\"}");
        assertThat(Jws.verify(keys.getPublic(), parsed)).isTrue();
    }

    @Test
    void producesThreeUnpaddedBase64UrlParts() {
        String token = Jws.sign(keys.getPrivate(), "k", "{}");
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(token).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    void theHeaderIsExactlyTheDocumentedObject() {
        String token = Jws.sign(keys.getPrivate(), "k1", "{}");
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                StandardCharsets.UTF_8);
        assertThat(header).isEqualTo("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"k1\"}");
    }

    @Test
    void refusesVerificationUnderAnotherKey() {
        String token = Jws.sign(keys.getPrivate(), "k", "{\"a\":1}");
        assertThat(Jws.verify(Ed25519Keys.generate().getPublic(), Jws.parse(token))).isFalse();
    }

    @Test
    void refusesVerificationWhenTheClaimsAreEdited() {
        String token = Jws.sign(keys.getPrivate(), "k", "{\"features\":[]}");
        String[] parts = token.split("\\.");
        String tampered = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"features\":[\"hr\"]}".getBytes(StandardCharsets.UTF_8));
        Jws.Parsed forged = Jws.parse(parts[0] + "." + tampered + "." + parts[2]);

        assertThat(forged.payloadJson()).contains("hr");
        assertThat(Jws.verify(keys.getPublic(), forged)).isFalse();
    }

    @Test
    void rejectsMalformedTokens() {
        assertThatThrownBy(() -> Jws.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Jws.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Jws.parse("only.two")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Jws.parse("not!base64.not!base64.not!base64"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAKeyIdThatWouldBreakTheHeader() {
        assertThatThrownBy(() -> Jws.sign(keys.getPrivate(), "bad\"kid", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Key id");
        assertThatThrownBy(() -> Jws.sign(keys.getPrivate(), null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicKeysSurviveTheRawJwkRoundTrip() {
        byte[] raw = Ed25519Keys.rawPublic(keys.getPublic());
        assertThat(raw).hasSize(32);
        assertThat(Ed25519Keys.fromRawPublic(raw)).isEqualTo(keys.getPublic());
    }

    @Test
    void keyIdIsDerivedFromTheKeyAndSoIsStable() {
        String first = Ed25519Keys.kid(keys.getPublic());
        String reimported = Ed25519Keys.kid(
                Ed25519Keys.decodePublic(Ed25519Keys.encodePublic(keys.getPublic())));
        assertThat(first).isEqualTo(reimported).matches("[0-9a-f]{32}");
        assertThat(Ed25519Keys.kid(Ed25519Keys.generate().getPublic())).isNotEqualTo(first);
    }

    @Test
    void privateKeysSurviveTheStorageRoundTrip() {
        String encoded = Ed25519Keys.encodePrivate(keys.getPrivate());
        String token = Jws.sign(Ed25519Keys.decodePrivate(encoded), "k", "{\"x\":1}");
        assertThat(Jws.verify(keys.getPublic(), Jws.parse(token))).isTrue();
    }
}
