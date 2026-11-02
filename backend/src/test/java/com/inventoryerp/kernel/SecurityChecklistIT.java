package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inventoryerp.ApiTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * docs/SECURITY.md §9 checks not covered elsewhere: JWT algorithm confusion and forged signatures, uploaded HTML/SVG
 * served as attachments with {@code nosniff}, and no credentials in the logs. (Authz matrix: every module IT; cross
 * tenant: every module IT; idempotency: money-route ITs; alg=none / expiry: {@code JwtTokenServiceTest}; refresh
 * rotation: {@code KernelIT}.)
 */
class SecurityChecklistIT extends ApiTestSupport {

    static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String json(String b64url) {
        return new String(Base64.getUrlDecoder().decode(b64url), StandardCharsets.UTF_8);
    }

    int status(String token) {
        return call("GET", "/auth/me", null, token).status();
    }

    @Test
    @DisplayName("JWT: algorithm confusion (RS256 / HS512 / none headers), tampered payload and foreign key are rejected")
    void jwtConfusion() throws Exception {
        assertThat(status(adminToken)).isEqualTo(200);
        String[] parts = adminToken.split("\\.");
        for (String alg : List.of("RS256", "HS512", "none", "ES256")) {
            String header = b64(json(parts[0]).replaceAll("\"alg\"\\s*:\\s*\"[A-Za-z0-9]+\"", "\"alg\":\"" + alg + "\""));
            assertThat(status(header + "." + parts[1] + "." + parts[2])).as(alg).isEqualTo(401);
        }
        String elevated = b64(json(parts[1]).replace("\"sub\":\"", "\"sub\":\"x"));
        assertThat(status(parts[0] + "." + elevated + "." + parts[2])).isEqualTo(401);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("not-the-server-secret-but-long-enough-32b".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
        assertThat(status(parts[0] + "." + parts[1] + "." + forged)).isEqualTo(401);
        assertThat(status(parts[0] + "." + parts[1])).isEqualTo(401);
    }

    HttpResponse<String> upload(String filename, String contentType, String content) throws Exception {
        String boundary = "----erp" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/upload"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("HTML / script uploads are refused (DM-0); SVG is served as an attachment with nosniff")
    void activeContentUploads() throws Exception {
        for (String[] f : List.of(new String[] {"x.html", "text/html", "<script>alert(1)</script>"},
                new String[] {"x.js", "application/javascript", "alert(1)"},
                new String[] {"x.exe", "application/octet-stream", "MZ"})) {
            HttpResponse<String> refused = upload(f[0], f[1], f[2]);
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
            assertThat(mapper.readTree(refused.body()).get("message").asString()).isEqualTo("File type not allowed");
        }
        for (String[] f : List.<String[]>of(
                new String[] {"x.svg", "image/svg+xml", "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>"})) {
            HttpResponse<String> up = upload(f[0], f[1], f[2]);
            assertThat(up.statusCode()).as(up.body()).isEqualTo(201);
            JsonNode node = mapper.readTree(up.body());
            HttpResponse<String> down = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + node.get("url").asString())).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(down.statusCode()).isEqualTo(200);
            assertThat(down.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment");
            assertThat(down.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        }
    }

    @Test
    @DisplayName("no passwords, PINs, refresh tokens or device tokens reach the logs")
    void noSecretsInLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        String pin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        String password = "Leak@" + UUID.randomUUID().toString().substring(0, 10);
        String deviceToken;
        String refresh;
        try {
            String email = "leak-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
            String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                    .param(demoOrg).query(String.class).list().getFirst();
            String userId = createUser(demoOrg, email, password, role);
            Res login = call("POST", "/auth/login", Map.of("organizationCode", "DEMO", "email", email, "password", password),
                    (String) null);
            assertThat(login.status()).isEqualTo(200);
            refresh = login.body().get("refreshToken").asString();
            call("POST", "/auth/refresh", Map.of("refreshToken", refresh), (String) null);
            call("POST", "/auth/login", Map.of("organizationCode", "DEMO", "email", email, "password", password + "x"),
                    (String) null);
            assertThat(as("POST", "/users/" + userId + "/pin", Map.of("pin", pin)).status()).isIn(200, 201);
            call("POST", "/pos/auth/pin-login", Map.of("userId", userId, "pin", pin), adminToken);
            call("POST", "/pos/auth/pin-login", Map.of("userId", userId, "pin", "00000000"), adminToken);
            deviceToken = ok(201, "POST", "/sync/devices/register", Map.of("name", "Leak test")).get("deviceToken")
                    .asString();
            call("GET", "/sync/pull?scopes=taxes", null, Map.of("X-Device-Token", deviceToken));
        } finally {
            root.detachAppender(appender);
        }
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent e : appender.list) {
            all.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) {
                all.append(e.getThrowableProxy().getMessage()).append('\n');
            }
        }
        String logs = all.toString();
        assertThat(logs).doesNotContain(password).doesNotContain(pin).doesNotContain(refresh).doesNotContain(deviceToken);
    }
}
