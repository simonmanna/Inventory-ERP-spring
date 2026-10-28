package com.inventoryerp.communication.application.providers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Telegram Bot API provider (port of {@code telegram.provider.ts}). Stateless HTTPS; the Bot API sends no
 * delivery receipts, so deliveries stop at {@code sent}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class TelegramProvider implements MessagingProvider {

    private static final Pattern CHAT_NOT_FOUND = Pattern.compile("chat not found", Pattern.CASE_INSENSITIVE);

    private final ChannelSecrets channels;
    private final ProviderHttp http;
    private final JsonMapper mapper;
    private final String envToken;
    private final String apiBase;

    public TelegramProvider(ChannelSecrets channels, ProviderHttp http, JsonMapper mapper,
            @Value("${TELEGRAM_BOT_TOKEN:}") String envToken,
            @Value("${TELEGRAM_API_BASE:https://api.telegram.org}") String apiBase) {
        this.channels = channels;
        this.http = http;
        this.mapper = mapper;
        this.envToken = envToken;
        this.apiBase = apiBase;
    }

    @Override
    public String id() {
        return "telegram";
    }

    private String token(String channelId) {
        Object t = channels.channelConfig(channelId).get("botToken");
        if (t instanceof String s && !s.isEmpty()) {
            return s;
        }
        return envToken == null || envToken.isEmpty() ? null : envToken;
    }

    @Override
    public boolean isReady(String channelId) {
        return token(channelId) != null;
    }

    @Override
    public ChannelHealth health(String channelId) {
        boolean ready = isReady(channelId);
        return new ChannelHealth(ready ? "connected" : "disconnected",
                ready ? Instant.now().truncatedTo(ChronoUnit.MILLIS) : null,
                ready ? null : "bot token not configured", true);
    }

    @Override
    public SendOutcome send(OutboundMessage msg) {
        String token = token(msg.channelId());
        if (token == null) {
            throw new ProviderSendError("Telegram bot token not configured", "not_configured", false, false);
        }
        ProviderHttp.Response res;
        try {
            String body = mapper.writeValueAsString(Map.of("chat_id", normalizeAddress(msg.toAddress()),
                    "text", msg.body()));
            res = http.postJson(apiBase + "/bot" + token + "/sendMessage", Map.of("Content-Type", "application/json"),
                    body);
        } catch (Exception err) {
            // Network failure — ambiguous. Never echo the URL: it carries the token.
            throw new ProviderSendError(err.getClass().getSimpleName(), "network", true, true);
        }
        JsonNode json;
        try {
            json = res.body() == null || res.body().isEmpty() ? mapper.createObjectNode() : mapper.readTree(res.body());
        } catch (RuntimeException e) {
            json = mapper.createObjectNode();
        }
        JsonNode result = json.path("result");
        if (res.ok() && json.path("ok").asBoolean(false) && result.has("message_id")
                && !result.get("message_id").isNull()) {
            JsonNode chatId = result.path("chat").path("id");
            String chat = chatId.isMissingNode() || chatId.isNull() ? msg.toAddress() : chatId.asString();
            // Composite id — Telegram message_id is unique only per chat.
            return new Sent(chat + ":" + result.get("message_id").asString(),
                    Instant.now().truncatedTo(ChronoUnit.MILLIS), null);
        }
        Integer code = json.has("error_code") && json.get("error_code").isNumber() ? json.get("error_code").asInt()
                : null;
        String description = json.path("description").isString() ? json.get("description").asString() : "unknown";
        JsonNode retryAfter = json.path("parameters").path("retry_after");
        throw classifyError(code, description, retryAfter.isNumber() ? retryAfter.asLong() : null);
    }

    static ProviderSendError classifyError(Integer code, String description, Long retryAfter) {
        // 400 chat not found / 403 bot blocked → terminal. 429 → honour retry_after. 5xx → retryable.
        if (code != null && (code == 403 || (code == 400 && CHAT_NOT_FOUND.matcher(description).find()))) {
            return new ProviderSendError(description, code == 403 ? "blocked" : "chat_not_found", false, false);
        }
        if (code != null && code == 429) {
            return new ProviderSendError(description, "rate_limited", true, false,
                    (retryAfter == null ? 1 : retryAfter) * 1000);
        }
        boolean retryable = code == null || code >= 500;
        return new ProviderSendError(description, "telegram_" + (code == null ? "net" : code), retryable, false);
    }

    @Override
    public void markRead(String channelId, String externalConversationId, String externalMessageId) {
        // Bot API has no read-receipt endpoint.
    }

    @Override
    public void connect(String channelId) {
        // Stateless.
    }

    @Override
    public void disconnect(String channelId, boolean logout) {
        // Stateless.
    }

    @Override
    public String normalizeAddress(String raw) {
        return raw;
    }
}
