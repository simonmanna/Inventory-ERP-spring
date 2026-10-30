package com.inventoryerp.quality.application;

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
 * REST laboratory adapter (ADR-S013): {@code GET ${app.quality.lab.rest.base-url}/results?lot=<lotNumber>} with an
 * optional bearer token, answering a JSON array of {@code {parameterCode, sampleNo, value | textValue | sieveMm +
 * passingPct, testedAt, labReference}}. Configured by {@code QUALITY_LAB_REST_URL} / {@code QUALITY_LAB_REST_TOKEN};
 * the token is never logged.
 */
@Component
public class RestLabResultAdapter implements LabResultAdapter {

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http;

    public RestLabResultAdapter(@Value("${app.quality.lab.rest.base-url:}") String baseUrl,
            @Value("${app.quality.lab.rest.token:}") String token,
            @Value("${app.quality.lab.rest.timeout-ms:10000}") long timeoutMs) {
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
    public List<LabResult> fetch(List<LotRef> lots) {
        if (!configured()) {
            throw ApiException.badRequest("The REST laboratory adapter is not configured (QUALITY_LAB_REST_URL).");
        }
        List<LabResult> out = new ArrayList<>();
        for (LotRef lot : lots) {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/results?lot="
                    + URLEncoder.encode(lot.lotNumber(), StandardCharsets.UTF_8))).timeout(timeout)
                    .header("Accept", "application/json").GET();
            if (token != null && !token.isBlank()) {
                req.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> res;
            try {
                res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                throw ApiException.badRequest("The laboratory system could not be reached: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.badRequest("The laboratory request was interrupted.");
            }
            if (res.statusCode() == 404) {
                continue;
            }
            if (res.statusCode() >= 300) {
                throw ApiException.badRequest("The laboratory system answered HTTP " + res.statusCode() + ".");
            }
            JsonNode body = mapper.readTree(res.body());
            JsonNode items = body.isArray() ? body : body.path("results");
            for (JsonNode n : items) {
                out.add(new LabResult(lot.lotNumber(), text(n, "parameterCode"),
                        n.hasNonNull("sampleNo") ? n.get("sampleNo").asInt() : 1, num(n, "value"), text(n, "textValue"),
                        num(n, "sieveMm"), num(n, "passingPct"),
                        n.hasNonNull("testedAt") ? Instant.parse(n.get("testedAt").asString()) : null,
                        text(n, "labReference")));
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
}
