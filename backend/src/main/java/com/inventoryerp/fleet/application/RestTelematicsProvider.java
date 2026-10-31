package com.inventoryerp.fleet.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vendor adapter for a tracker platform reached over REST (INT-2, type {@value #TYPE}). The vendor is not chosen yet,
 * so this adapter fixes a small JSON contract that a real platform, or a thin gateway in front of it, answers with a
 * bearer token; only this class changes when the vendor's own API is mapped. Base URL and key come from
 * {@code FLEET_TELEMATICS_URL} / {@code FLEET_TELEMATICS_API_KEY} (never logged, never in an error).
 *
 * <ul>
 *   <li>{@code GET /units/{unit}/positions?from=&to=} → array or {@code {positions: [...]}} of
 *       {@code {externalId | id, at, latitude | lat, longitude | lon, speedKmh}}, oldest first.</li>
 *   <li>{@code GET /units/{unit}/meters} → {@code {odometerKm, engineHours, at}}; 404 = the unit reports none.</li>
 *   <li>{@code GET /events?since=} → array or {@code {events: [...]}} of
 *       {@code {externalId | id, unitId, type, at, data}}.</li>
 * </ul>
 * Any failure is an exception: {@link FleetTelematicsService} records it on the device and carries on with the rest.
 */
@Component
public class RestTelematicsProvider implements TelematicsProvider {

    public static final String TYPE = "rest";

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http;

    public RestTelematicsProvider(@Value("${app.fleet.telematics.base-url:${FLEET_TELEMATICS_URL:}}") String baseUrl,
            @Value("${app.fleet.telematics.api-key:${FLEET_TELEMATICS_API_KEY:}}") String apiKey,
            @Value("${app.fleet.telematics.timeout-ms:${FLEET_TELEMATICS_TIMEOUT_MS:10000}}") long timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Position> positions(String externalUnitId, Instant from, Instant to) {
        String path = "/units/" + encode(externalUnitId) + "/positions?from=" + encode(from.toString()) + "&to="
                + encode(to.toString());
        JsonNode body = get(path);
        List<Position> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (JsonNode n : body.isArray() ? body : body.path("positions")) {
            Instant at = Instant.parse(n.get("at").asString());
            JsonNode lat = n.hasNonNull("latitude") ? n.get("latitude") : n.get("lat");
            JsonNode lon = n.hasNonNull("longitude") ? n.get("longitude") : n.get("lon");
            String id = id(n);
            out.add(new Position(externalUnitId, at, new BigDecimal(lat.asString()).doubleValue(),
                    new BigDecimal(lon.asString()).doubleValue(), dec(n, "speedKmh"),
                    id != null ? id : externalUnitId + ":p:" + at.getEpochSecond()));
        }
        return out;
    }

    @Override
    public Optional<MeterSnapshot> meters(String externalUnitId) {
        JsonNode n = get("/units/" + encode(externalUnitId) + "/meters");
        if (n == null || !n.hasNonNull("at")) {
            return Optional.empty();
        }
        return Optional.of(new MeterSnapshot(dec(n, "odometerKm"), dec(n, "engineHours"),
                Instant.parse(n.get("at").asString())));
    }

    @Override
    public List<TelematicsEvent> events(Instant since) {
        JsonNode body = get("/events?since=" + encode(since.toString()));
        List<TelematicsEvent> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (JsonNode n : body.isArray() ? body : body.path("events")) {
            Map<String, Object> data = new LinkedHashMap<>();
            if (n.hasNonNull("data")) {
                n.get("data").properties().forEach(e -> data.put(e.getKey(), scalar(e.getValue())));
            }
            Instant at = Instant.parse(n.get("at").asString());
            String unit = n.get("unitId").asString();
            String type = n.get("type").asString();
            String id = id(n);
            out.add(new TelematicsEvent(unit, type, at, data,
                    id != null ? id : unit + ":" + type + ":" + at.getEpochSecond()));
        }
        return out;
    }

    /** The parsed body, or null on 404. */
    private JsonNode get(String pathAndQuery) {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("The telematics provider is not configured (FLEET_TELEMATICS_URL)");
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).timeout(timeout)
                .header("Accept", "application/json").GET();
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("The telematics platform could not be reached: "
                    + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The telematics request was interrupted");
        }
        if (res.statusCode() == 404) {
            return null;
        }
        if (res.statusCode() >= 300) {
            throw new IllegalStateException("The telematics platform answered HTTP " + res.statusCode());
        }
        return mapper.readTree(res.body());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String id(JsonNode n) {
        if (n.hasNonNull("externalId")) {
            return n.get("externalId").asString();
        }
        return n.hasNonNull("id") ? n.get("id").asString() : null;
    }

    private static BigDecimal dec(JsonNode n, String f) {
        return n.hasNonNull(f) ? new BigDecimal(n.get(f).asString()) : null;
    }

    private static Object scalar(JsonNode v) {
        if (v.isNumber()) {
            return new BigDecimal(v.asString());
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        return v.isNull() ? null : v.asString();
    }
}
