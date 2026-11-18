package com.inventoryerp.biexport.application;

import com.inventoryerp.shared.web.EntityJson;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Superset REST calls behind an embedded dashboard: log in as the service account, then ask for a guest token for
 * one dashboard with row-level security clauses. Superset protects the guest-token POST with CSRF, so a CSRF token and
 * its session cookie are fetched first. No token, password or clause is ever logged.
 */
@Component
public class SupersetClient {

    private static final Logger log = LoggerFactory.getLogger(SupersetClient.class);

    /** How long a service-account access token is reused; Superset's default lifetime is 15 minutes. */
    private static final Duration ACCESS_REUSE = Duration.ofMinutes(5);

    /** Superset could not be reached or refused the service account. */
    public static class Unavailable extends RuntimeException {

        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SupersetProperties properties;
    private final HttpClient http;
    private String accessToken;
    private Instant accessTokenAt = Instant.EPOCH;

    public SupersetClient(SupersetProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.httpTimeout())
                .cookieHandler(new CookieManager())
                .build();
    }

    /**
     * A guest token for one embedded dashboard.
     *
     * @param dashboardId the Superset embedded-dashboard id
     * @param username    who the token is for, as shown in Superset's logs
     * @param rlsClauses  SQL clauses Superset adds to every query the token runs
     */
    public String guestToken(String dashboardId, String username, List<String> rlsClauses) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("first_name", "ERP");
        user.put("last_name", "User");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user);
        body.put("resources", List.of(Map.of("type", "dashboard", "id", dashboardId)));
        body.put("rls", rlsClauses.stream().map(c -> Map.of("clause", c)).toList());
        String json = EntityJson.toJson(body);
        try {
            return requestGuestToken(json, false);
        } catch (Unauthorized e) {
            try {
                return requestGuestToken(json, true);
            } catch (Unauthorized again) {
                throw new Unavailable("Superset rejected a fresh service-account login", null);
            }
        }
    }

    private static final class Unauthorized extends RuntimeException {}

    private String requestGuestToken(String json, boolean freshLogin) {
        String bearer = access(freshLogin);
        String csrf = csrfToken(bearer);
        HttpResponse<String> res = send(HttpRequest.newBuilder(uri("/api/v1/security/guest_token/"))
                .timeout(properties.httpTimeout())
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .header("X-CSRFToken", csrf)
                .header("Referer", properties.url())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
        if (res.statusCode() == 401) {
            throw new Unauthorized();
        }
        String token = res.statusCode() == 200 ? EntityJson.tree(res.body()).path("token").asString("") : "";
        if (token.isEmpty()) {
            throw new Unavailable("Superset refused the guest token (HTTP " + res.statusCode() + ")", null);
        }
        return token;
    }

    private synchronized String access(boolean fresh) {
        if (!fresh && accessToken != null && Instant.now().isBefore(accessTokenAt.plus(ACCESS_REUSE))) {
            return accessToken;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", properties.username());
        body.put("password", properties.password());
        body.put("provider", "db");
        body.put("refresh", false);
        HttpResponse<String> res = send(HttpRequest.newBuilder(uri("/api/v1/security/login"))
                .timeout(properties.httpTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(EntityJson.toJson(body)))
                .build());
        String token = res.statusCode() == 200 ? EntityJson.tree(res.body()).path("access_token").asString("") : "";
        if (token.isEmpty()) {
            accessToken = null;
            throw new Unavailable("Superset refused the service account (HTTP " + res.statusCode() + ")", null);
        }
        accessToken = token;
        accessTokenAt = Instant.now();
        return token;
    }

    private String csrfToken(String bearer) {
        HttpResponse<String> res = send(HttpRequest.newBuilder(uri("/api/v1/security/csrf_token/"))
                .timeout(properties.httpTimeout())
                .header("Authorization", "Bearer " + bearer)
                .GET()
                .build());
        if (res.statusCode() == 401) {
            throw new Unauthorized();
        }
        JsonNode node = res.statusCode() == 200 ? EntityJson.tree(res.body()) : null;
        String csrf = node == null ? "" : node.path("result").asString("");
        if (csrf.isEmpty()) {
            throw new Unavailable("Superset gave no CSRF token (HTTP " + res.statusCode() + ")", null);
        }
        return csrf;
    }

    private URI uri(String path) {
        return URI.create(properties.url() + path);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Superset unreachable at {}: {}", properties.url(), e.getClass().getSimpleName());
            throw new Unavailable("Superset is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while calling Superset", e);
        }
    }
}
