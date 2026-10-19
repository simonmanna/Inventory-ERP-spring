package com.inventoryerp.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code jwt-token.service.spec.ts}: token-type confinement. */
@DisplayName("JwtTokenService — token type confinement")
class JwtTokenServiceTest {

    static final String ACCESS = "test-access-secret-at-least-32-chars-long";
    static final String REFRESH = "test-refresh-secret-at-least-32-chars-long";
    static final String POS = "test-pos-secret-at-least-32-characters-long";

    static JwtTokenService build(String pos) {
        return new JwtTokenService(new JwtProperties(ACCESS, REFRESH, pos, null, null, null));
    }

    @Test
    @DisplayName("rejects a POS token presented as an access token")
    void posTokenIsNotAccess() {
        JwtTokenService svc = build(null);
        String posToken = svc.signPos("user-1", "org-1", "cashier@example.com");
        assertThatThrownBy(() -> svc.verifyAccess(posToken)).isInstanceOf(ApiException.class)
                .hasMessage("Invalid or expired access token");
    }

    @Test
    @DisplayName("still rejects a POS token when JWT_POS_SECRET gives it its own key")
    void posTokenWithOwnKey() {
        JwtTokenService svc = build(POS);
        String posToken = svc.signPos("user-1", "org-1", "cashier@example.com");
        assertThatThrownBy(() -> svc.verifyAccess(posToken)).isInstanceOf(ApiException.class);
        assertThat(svc.verifyPos(posToken).sub()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("rejects a refresh token presented as an access token")
    void refreshIsNotAccess() {
        JwtTokenService svc = build(null);
        String refresh = svc.signRefresh("user-1", "org-1");
        assertThatThrownBy(() -> svc.verifyAccess(refresh)).isInstanceOf(ApiException.class);
        assertThat(svc.verifyRefresh(refresh).sub()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("accepts a genuine access token and stamps it with type=access")
    void accessRoundTrip() {
        JwtTokenService svc = build(null);
        var decoded = svc.verifyAccess(svc.signAccess("user-1", "org-1", "cashier@example.com"));
        assertThat(decoded.sub()).isEqualTo("user-1");
        assertThat(decoded.type()).isEqualTo("access");
        assertThat(decoded.exp() - decoded.iat()).isEqualTo(15 * 60);
    }

    @Test
    @DisplayName("accepts a legacy access token that predates the type claim (jsonwebtoken format)")
    void legacyAccessToken() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"sub\":\"user-1\",\"organizationId\":\"org-1\",\"email\":\"c@x\",\"permissions\":[\"pos:read\"],\"iat\":"
                + now + ",\"exp\":" + (now + 900) + "}");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(ACCESS.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        assertThat(build(null).verifyAccess(header + "." + payload + "." + sig).sub()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("rejects alg=none and expired tokens")
    void rejectsNoneAndExpired() {
        String none = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + b64("{\"sub\":\"u\"}") + ".";
        assertThatThrownBy(() -> build(null).verifyAccess(none)).isInstanceOf(ApiException.class);
        JwtTokenService expired = new JwtTokenService(new JwtProperties(ACCESS, REFRESH, null, "-10s", null, null));
        String t = expired.signAccess("u", "o", "e");
        assertThatThrownBy(() -> build(null).verifyAccess(t)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("does not accept an access token on the POS path")
    void accessIsNotPos() {
        JwtTokenService svc = build(null);
        String access = svc.signAccess("user-1", "org-1", "e");
        assertThatThrownBy(() -> svc.verifyPos(access)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("signs POS tokens with JWT_POS_SECRET when set, so the access key cannot verify them")
    void posSecretIsolation() {
        String posToken = build(POS).signPos("user-1", "org-1", "e");
        assertThatThrownBy(() -> build(null).verifyPos(posToken)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parses jsonwebtoken/ms TTL strings")
    void ttl() {
        assertThat(JwtTokenService.parseTtlSeconds("15m")).isEqualTo(900);
        assertThat(JwtTokenService.parseTtlSeconds("7d")).isEqualTo(604_800);
        assertThat(JwtTokenService.parseTtlSeconds("12h")).isEqualTo(43_200);
        assertThat(JwtTokenService.parseTtlSeconds("90000")).isEqualTo(90);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
