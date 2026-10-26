package com.inventoryerp.kernel.licensing;

import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Talks to the licence authority. The only part of licensing that needs a network, and nothing it
 * does is on the critical path of a sale.
 *
 * <p>Failure handling is the point of this class:
 *
 * <ul>
 *   <li>A <b>transport failure</b> (no DNS, no route, timeout) is reported as "unreachable" and the
 *       caller carries on with the licence it already holds. A shop must not stop trading because a
 *       router rebooted.
 *   <li>A <b>4xx answer</b> is a decision by the authority — an unknown key, a revoked licence, a
 *       slot already taken — and is surfaced verbatim to the user, because they are the exact words
 *       the spec promises them.
 *   <li>A <b>5xx answer</b> is the authority's problem, not the customer's, and is treated like
 *       unreachable.
 * </ul>
 */
@Component
public class LicenseServerClient {

    private static final Logger log = LoggerFactory.getLogger(LicenseServerClient.class);

    /** A token the authority handed back. */
    public record Issued(String token, String kid, String status) {}

    /** The licence server could not be reached; the caller should keep what it has. */
    public static class Unreachable extends RuntimeException {

        Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final LicenseProperties properties;
    private final HttpClient http;

    public LicenseServerClient(LicenseProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.httpTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Issued activate(String licenseKey, String fingerprint, String instanceName, String hostname,
            String appVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("licenseKey", licenseKey);
        body.put("fingerprint", fingerprint);
        body.put("instanceName", instanceName);
        body.put("hostname", hostname);
        body.put("appVersion", appVersion);
        return issued(post("/activate", body));
    }

    public Issued heartbeat(String token, Integer devices, Integer users, Integer organizations,
            String appVersion, LicenseMode mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("deviceCount", devices);
        body.put("userCount", users);
        body.put("orgCount", organizations);
        body.put("appVersion", appVersion);
        body.put("mode", mode == null ? null : mode.name());
        return issued(post("/heartbeat", body));
    }

    public void deactivate(String licenseKey, String fingerprint) {
        post("/deactivate", Map.of("licenseKey", licenseKey, "fingerprint", fingerprint));
    }

    /** The authority's JWKS, or empty when it cannot be reached. Never throws. */
    public Optional<String> jwks() {
        if (!properties.hasServer()) {
            return Optional.empty();
        }
        try {
            HttpResponse<String> response = send(HttpRequest
                    .newBuilder(URI.create(properties.endpoint("/.well-known/license-keys.json")))
                    .timeout(properties.httpTimeout())
                    .GET()
                    .build());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (Unreachable e) {
            log.debug("Could not fetch licence keys: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) {
        if (!properties.hasServer()) {
            throw new Unreachable("No licence server is configured (app.license.server-url)", null);
        }
        return send(HttpRequest.newBuilder(URI.create(properties.endpoint(path)))
                .timeout(properties.httpTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(EntityJson.toJson(body)))
                .build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Unreachable(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unreachable("Interrupted while contacting the licence server", e);
        }
    }

    /**
     * Turn the authority's answer into either a token or the right error.
     *
     * <p>Its 4xx messages are passed through unchanged at the same status: they are written for the
     * person reading the screen, and re-wording them here would lose that.
     */
    private Issued issued(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            Object parsed = EntityJson.parseJson(response.body());
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new Unreachable("The licence server returned something unreadable", null);
            }
            return new Issued(string(map.get("token")), string(map.get("kid")), string(map.get("status")));
        }
        if (status >= 500) {
            throw new Unreachable("The licence server answered " + status, null);
        }
        String message = messageOf(response.body());
        HttpStatus mapped = HttpStatus.resolve(status) == null ? HttpStatus.BAD_REQUEST
                : HttpStatus.valueOf(status);
        throw new ApiException(mapped, message);
    }

    private static String messageOf(String body) {
        Object parsed;
        try {
            parsed = EntityJson.parseJson(body);
        } catch (RuntimeException e) {
            return "The licence server refused this request.";
        }
        if (parsed instanceof Map<?, ?> map && map.get("message") != null) {
            Object message = map.get("message");
            // Validation failures come back as a list of messages.
            if (message instanceof java.util.List<?> list && !list.isEmpty()) {
                return String.valueOf(list.getFirst());
            }
            return String.valueOf(message);
        }
        return "The licence server refused this request.";
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
