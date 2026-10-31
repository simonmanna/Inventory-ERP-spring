package com.inventoryerp.manufacturing.application;

import com.inventoryerp.manufacturing.spi.MachineDataAdapter;
import com.inventoryerp.shared.web.ApiException;
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
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST machine-data adapter (ADR-S015): {@code GET ${app.manufacturing.machine-data.rest.base-url}/events?machine=
 * <code>&since=<iso>&until=<iso>} with an optional bearer token, answering a JSON array (or {@code {events: [...]}}) of
 * {@code {externalId | id, type, occurredAt, endedAt, goodQty, scrapQty, reasonCode, notes}} — e.g. a data gateway in
 * front of the PLCs. Configured by {@code MFG_MACHINE_DATA_REST_URL} / {@code MFG_MACHINE_DATA_REST_TOKEN}; the token
 * is never logged.
 */
@Component
public class RestMachineDataAdapter implements MachineDataAdapter {

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http;

    public RestMachineDataAdapter(@Value("${app.manufacturing.machine-data.rest.base-url:}") String baseUrl,
            @Value("${app.manufacturing.machine-data.rest.token:}") String token,
            @Value("${app.manufacturing.machine-data.rest.timeout-ms:10000}") long timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public String code() {
        return "rest";
    }

    @Override
    public boolean configured() {
        return !baseUrl.isBlank();
    }

    @Override
    public List<MachineEvent> poll(PollRequest request) {
        if (!configured()) {
            throw ApiException.badRequest("The REST machine-data adapter is not configured (MFG_MACHINE_DATA_REST_URL).");
        }
        List<MachineEvent> out = new ArrayList<>();
        for (MachineRef m : request.machines()) {
            StringBuilder url = new StringBuilder(baseUrl).append("/events?machine=")
                    .append(URLEncoder.encode(m.machineCode(), StandardCharsets.UTF_8));
            if (request.since() != null) {
                url.append("&since=").append(URLEncoder.encode(request.since().toString(), StandardCharsets.UTF_8));
            }
            if (request.until() != null) {
                url.append("&until=").append(URLEncoder.encode(request.until().toString(), StandardCharsets.UTF_8));
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout)
                    .header("Accept", "application/json").GET();
            if (token != null && !token.isBlank()) {
                req.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> res;
            try {
                res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                throw ApiException.badRequest("The machine-data gateway could not be reached: "
                        + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.badRequest("The machine-data request was interrupted.");
            }
            if (res.statusCode() == 404) {
                continue;
            }
            if (res.statusCode() >= 300) {
                throw ApiException.badRequest("The machine-data gateway answered HTTP " + res.statusCode() + ".");
            }
            JsonNode body = mapper.readTree(res.body());
            JsonNode items = body.isArray() ? body : body.path("events");
            for (JsonNode n : items) {
                String ext = text(n, "externalId") != null ? text(n, "externalId") : text(n, "id");
                out.add(new MachineEvent(ext, text(n, "machineCode") != null ? text(n, "machineCode") : m.machineCode(),
                        text(n, "type"), time(n, "occurredAt"), time(n, "endedAt"), num(n, "goodQty"),
                        num(n, "scrapQty"), text(n, "reasonCode"), text(n, "notes")));
            }
        }
        return out;
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asString() : null;
    }

    private static BigDecimal num(JsonNode n, String f) {
        return n.hasNonNull(f) ? new BigDecimal(n.get(f).asString()) : null;
    }

    private static Instant time(JsonNode n, String f) {
        return n.hasNonNull(f) ? Instant.parse(n.get(f).asString()) : null;
    }
}
