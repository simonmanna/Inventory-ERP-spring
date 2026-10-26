package com.inventoryerp.kernel.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The client against a real HTTP socket. Every other licensing test stubs the authority above this
 * class, so a parse error here once made every online activation answer "unreachable" while the
 * authority had in fact activated the install.
 */
class LicenseServerClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private LicenseServerClient answering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
        return new LicenseServerClient(new LicenseProperties(url, 14, true, true, Duration.ofSeconds(5), "t"));
    }

    @Test
    void anIssuedTokenIsRead() throws IOException {
        var client = answering(200, "{\"token\":\"a.b.c\",\"kid\":\"k1\",\"status\":\"active\",\"extra\":1}");

        var issued = client.activate("ERP-KEY", "f".repeat(64), "till", "host", "1.0.0");

        assertThat(issued).isEqualTo(new LicenseServerClient.Issued("a.b.c", "k1", "active"));
    }

    @Test
    void aHeartbeatTokenIsRead() throws IOException {
        var client = answering(200, "{\"token\":\"t\",\"kid\":\"k2\",\"status\":\"suspended\"}");

        var issued = client.heartbeat("old", 1, 2, 1, "1.0.0", null);

        assertThat(issued.token()).isEqualTo("t");
        assertThat(issued.status()).isEqualTo("suspended");
    }

    @Test
    void aNonObjectAnswerIsUnreachable() throws IOException {
        var client = answering(200, "[1,2]");

        assertThatThrownBy(() -> client.activate("ERP-KEY", "f".repeat(64), "till", "host", "1.0.0"))
                .isInstanceOf(LicenseServerClient.Unreachable.class);
    }

    @Test
    void aRefusalIsPassedThroughVerbatim() throws IOException {
        var client = answering(409, "{\"statusCode\":409,\"message\":\"This licence is already in use.\"}");

        assertThatThrownBy(() -> client.activate("ERP-KEY", "f".repeat(64), "till", "host", "1.0.0"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("This licence is already in use.");
                });
    }

    @Test
    void theFirstValidationMessageIsShown() throws IOException {
        var client = answering(400, "{\"message\":[\"licenseKey is required\",\"other\"]}");

        assertThatThrownBy(() -> client.activate("", "f".repeat(64), "till", "host", "1.0.0"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("licenseKey is required"));
    }

    @Test
    void aServerErrorIsUnreachable() throws IOException {
        var client = answering(503, "oops");

        assertThatThrownBy(() -> client.activate("ERP-KEY", "f".repeat(64), "till", "host", "1.0.0"))
                .isInstanceOf(LicenseServerClient.Unreachable.class);
    }
}
