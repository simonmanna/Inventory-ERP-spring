package com.inventoryerp.sync.application;

import com.inventoryerp.shared.web.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-S083 O7: branch relay mode. With {@code erp.sync.mode=branch} this server is a branch node: the branch's tills
 * sync to it over the LAN; it mirrors HQ's pull scopes, keeps every pushed op in a relay outbox and forwards the ops
 * to HQ ({@code erp.sync.branch.hq-url}) under its own device token ({@code erp.sync.branch.token}, a device of
 * platform {@code branch} registered at HQ). GL is never posted here: HQ applies every op.
 */
@Component
public class SyncBranch {

    /** One HQ answer: the HTTP status and the parsed JSON body (empty when there was none). */
    public record HqResponse(int status, Object body) {
        @SuppressWarnings("unchecked")
        public Map<String, Object> map() {
            return body instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }
    }

    /** HQ could not be reached (connection refused, timeout, DNS). */
    public static class HqUnreachable extends RuntimeException {
        HqUnreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final boolean enabled;
    private final String organizationId;
    private final String token;
    private final String serverId;
    private final Duration pullInterval;
    private final Duration timeout;
    private final JsonMapper mapper;
    private final HttpClient http;
    private volatile String hqUrl;

    public SyncBranch(@Value("${erp.sync.mode:hq}") String mode,
            @Value("${erp.sync.branch.hq-url:}") String hqUrl,
            @Value("${erp.sync.branch.token:}") String token,
            @Value("${erp.sync.branch.organization-id:}") String organizationId,
            @Value("${erp.sync.server-id:hq}") String serverId,
            @Value("${erp.sync.branch.pull-interval:PT30S}") Duration pullInterval,
            @Value("${erp.sync.branch.timeout:PT10S}") Duration timeout, JsonMapper mapper) {
        this.enabled = "branch".equalsIgnoreCase(mode.trim());
        this.hqUrl = trimSlash(hqUrl);
        this.token = token;
        this.organizationId = organizationId;
        this.serverId = serverId;
        this.pullInterval = pullInterval;
        this.timeout = timeout;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        if (enabled && (this.hqUrl.isEmpty() || token.isEmpty() || organizationId.isEmpty() || "hq".equals(serverId))) {
            throw new IllegalStateException("erp.sync.mode=branch needs erp.sync.branch.hq-url, erp.sync.branch.token,"
                    + " erp.sync.branch.organization-id and a distinct erp.sync.server-id");
        }
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** True when this server runs as a branch relay node. */
    public boolean enabled() {
        return enabled;
    }

    public String organizationId() {
        return organizationId;
    }

    public String serverId() {
        return serverId;
    }

    public Duration pullInterval() {
        return pullInterval;
    }

    /** 404 on a branch-only route at HQ. */
    public void requireEnabled() {
        if (!enabled) {
            throw ApiException.notFound("This server is not a branch relay");
        }
    }

    /** Points the relay at another HQ address (a failover HQ URL; also used by tests to cut the link). */
    public void retargetHq(String url) {
        this.hqUrl = trimSlash(url);
    }

    /** GET {@code path} (under the API prefix) from HQ with the branch's device token. */
    public HqResponse get(String path) {
        return send(HttpRequest.newBuilder(URI.create(hqUrl + path)).GET());
    }

    /** POST a JSON body to HQ with the branch's device token and an optional Idempotency-Key. */
    public HqResponse post(String path, Object body, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(hqUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return send(b);
    }

    private HqResponse send(HttpRequest.Builder b) {
        b.timeout(timeout).header("X-Device-Token", token).header(SyncProtocol.HEADER, SyncProtocol.CURRENT)
                .header("Accept", "application/json");
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new HqUnreachable("HQ unreachable: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HqUnreachable("Interrupted while calling HQ", e);
        }
        Object parsed = null;
        String text = res.body();
        if (text != null && !text.isBlank()) {
            try {
                parsed = mapper.readerFor(Object.class)
                        .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(text);
            } catch (RuntimeException e) {
                parsed = text;
            }
        }
        return new HqResponse(res.statusCode(), parsed);
    }
}
