package com.inventoryerp.communication.application.providers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WhatsApp via Meta's official Cloud API (port of {@code whatsapp-cloud.provider.ts}). Stateless HTTPS; config
 * ({@code phoneNumberId}, {@code accessToken}, {@code apiVersion}) lives on {@code CommunicationChannel.config}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class WhatsAppCloudProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppCloudProvider.class);

    private record CloudConfig(String phoneNumberId, String accessToken, String apiVersion) {}

    private final ChannelSecrets channels;
    private final ProviderHttp http;
    private final JsonMapper mapper;
    private final String graphBase;

    public WhatsAppCloudProvider(ChannelSecrets channels, ProviderHttp http, JsonMapper mapper,
            @Value("${WHATSAPP_GRAPH_BASE:https://graph.facebook.com}") String graphBase) {
        this.channels = channels;
        this.http = http;
        this.mapper = mapper;
        this.graphBase = graphBase;
    }

    @Override
    public String id() {
        return "whatsapp";
    }

    private CloudConfig config(String channelId) {
        Map<String, Object> cfg = channels.channelConfig(channelId);
        return new CloudConfig(str(cfg.get("phoneNumberId")), str(cfg.get("accessToken")), str(cfg.get("apiVersion")));
    }

    private static String str(Object v) {
        return v instanceof String s && !s.isEmpty() ? s : null;
    }

    private String url(CloudConfig cfg) {
        String version = cfg.apiVersion() == null ? "v19.0" : cfg.apiVersion();
        return graphBase + "/" + version + "/" + cfg.phoneNumberId() + "/messages";
    }

    @Override
    public boolean isReady(String channelId) {
        CloudConfig cfg = config(channelId);
        return cfg.phoneNumberId() != null && cfg.accessToken() != null;
    }

    @Override
    public ChannelHealth health(String channelId) {
        boolean ready = isReady(channelId);
        return new ChannelHealth(ready ? "connected" : "disconnected",
                ready ? Instant.now().truncatedTo(ChronoUnit.MILLIS) : null,
                ready ? null : "phoneNumberId/accessToken not configured", true);
    }

    @Override
    public SendOutcome send(OutboundMessage msg) {
        CloudConfig cfg = config(msg.channelId());
        if (cfg.phoneNumberId() == null || cfg.accessToken() == null) {
            throw new ProviderSendError("WhatsApp Cloud channel not configured", "not_configured", false, false);
        }
        String to = normalizeAddress(msg.toAddress());
        ProviderHttp.Response res;
        try {
            String body = mapper.writeValueAsString(Map.of("messaging_product", "whatsapp", "to", to, "type", "text",
                    "text", Map.of("body", msg.body())));
            res = http.postJson(url(cfg), headers(cfg), body);
        } catch (Exception err) {
            // Network-level failure — ambiguous (the request may have been received).
            throw new ProviderSendError(err.getClass().getSimpleName(), "network", true, true);
        }
        if (!res.ok()) {
            String bodyText = res.body() == null ? "" : res.body();
            boolean retryable = res.status() == 429 || res.status() >= 500;
            Long retryAfter = null;
            try {
                long s = Long.parseLong(String.valueOf(res.header("retry-after")).trim());
                retryAfter = s > 0 ? s * 1000 : null;
            } catch (NumberFormatException ignored) {
                // no usable retry-after
            }
            throw new ProviderSendError("Cloud API " + res.status() + ": "
                    + bodyText.substring(0, Math.min(200, bodyText.length())),
                    res.status() == 429 ? "rate_limited" : "http_" + res.status(), retryable, false, retryAfter);
        }
        String wamid = null;
        try {
            JsonNode id = mapper.readTree(res.body()).path("messages").path(0).path("id");
            wamid = id.isString() && !id.asString().isEmpty() ? id.asString() : null;
        } catch (RuntimeException ignored) {
            // treated as no id
        }
        if (wamid == null) {
            throw new ProviderSendError("Cloud API returned no message id", "no_message_id", true, true);
        }
        return new Sent(wamid, Instant.now().truncatedTo(ChronoUnit.MILLIS), null);
    }

    private static Map<String, String> headers(CloudConfig cfg) {
        return Map.of("Authorization", "Bearer " + cfg.accessToken(), "Content-Type", "application/json");
    }

    @Override
    public void markRead(String channelId, String externalConversationId, String externalMessageId) {
        CloudConfig cfg = config(channelId);
        if (cfg.phoneNumberId() == null || cfg.accessToken() == null) {
            return;
        }
        try {
            http.postJson(url(cfg), headers(cfg), mapper.writeValueAsString(Map.of("messaging_product", "whatsapp",
                    "status", "read", "message_id", externalMessageId)));
        } catch (Exception e) {
            log.debug("markRead failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void connect(String channelId) {
        // Stateless — nothing to connect. Readiness is config presence.
    }

    @Override
    public void disconnect(String channelId, boolean logout) {
        // Stateless — nothing to disconnect.
    }

    @Override
    public String normalizeAddress(String raw) {
        // Cloud API wants a bare E.164 number without the plus sign or a jid suffix.
        int at = raw.indexOf('@');
        String base = at >= 0 ? raw.substring(0, at) : raw;
        return base.replaceAll("[^\\d]", "");
    }
}
