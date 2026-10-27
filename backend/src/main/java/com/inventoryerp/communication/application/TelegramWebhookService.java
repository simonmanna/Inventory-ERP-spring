package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.InboundMessageService.InboundMessage;
import com.inventoryerp.communication.application.providers.ChannelSecrets;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Telegram webhook handling (the body of {@code TelegramWebhookController.receive}): resolves the org from the
 * channel in the path, checks the channel secret, and funnels text/caption messages into
 * {@link InboundMessageService} inside that org's tenant scope. Anything that cannot be authenticated is a silent
 * {@code {ok: true}}.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class TelegramWebhookService {

    private record Channel(String organizationId, String providerId, String config, boolean disabled) {}

    private final JdbcClient jdbc;
    private final ChannelSecrets secrets;
    private final InboundMessageService inbound;
    private final JsonMapper mapper;

    public TelegramWebhookService(JdbcClient jdbc, ChannelSecrets secrets, InboundMessageService inbound,
            JsonMapper mapper) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.inbound = inbound;
        this.mapper = mapper;
    }

    public Map<String, Object> receive(String channelId, String secret, JsonNode update) {
        Map<String, Object> ok = Obj.of("ok", true);
        Channel channel = jdbc.sql("SELECT \"organizationId\", \"providerId\", config::text AS config,"
                        + " \"disabledAt\" IS NOT NULL AS disabled FROM \"CommunicationChannel\" WHERE id = ?")
                .param(channelId)
                .query((rs, n) -> new Channel(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                .optional().orElse(null);
        if (channel == null || !"telegram".equals(channel.providerId()) || channel.disabled()) {
            return ok;
        }
        Object expected = secrets.open(secrets.parse(channel.config())).get("webhookSecret");
        if (expected instanceof String exp && !exp.isEmpty() && !constantTimeEquals(exp, secret)) {
            return ok;
        }

        JsonNode m = update == null ? null : update.get("message");
        if (m == null || !m.isObject()) {
            return ok;
        }
        String text = textOf(m.get("text"));
        String caption = textOf(m.get("caption"));
        if (text == null && caption == null) {
            return ok;
        }

        JsonNode from = m.get("from");
        JsonNode chat = m.path("chat");
        String chatId = chat.path("id").asString();
        String displayName = null;
        if (from != null && from.isObject()) {
            List<String> parts = new ArrayList<>();
            for (String k : List.of("first_name", "last_name")) {
                String v = textOf(from.get(k));
                if (v != null) {
                    parts.add(v);
                }
            }
            displayName = String.join(" ", parts);
            if (displayName.isEmpty()) {
                displayName = textOf(from.get("username"));
            }
        }
        String username = from == null ? null : textOf(from.get("username"));
        JsonNode fromId = from == null ? null : from.get("id");
        String senderId = fromId == null || fromId.isNull() ? chatId : fromId.asString();
        JsonNode date = m.get("date");
        Instant occurredAt = date != null && date.isNumber()
                ? Instant.ofEpochMilli(Math.round(date.asDouble() * 1000)) : CommTime.now();

        @SuppressWarnings("unchecked")
        Map<String, Object> raw = mapper.treeToValue(update, Map.class);
        InboundMessage input = new InboundMessage(channel.organizationId(), channelId, "telegram", chatId,
                chatId + ":" + m.path("message_id").asString(), senderId, username != null ? "@" + username : null,
                displayName, text != null ? text : caption, "text", occurredAt, raw);
        TenantContext.run(TenantContext.TenantStore.organization(channel.organizationId()), () -> inbound.ingest(input));
        return ok;
    }

    /** A non-empty JS string, else {@code null} (falsy). */
    private static String textOf(JsonNode v) {
        return v != null && v.isString() && !v.asString().isEmpty() ? v.asString() : null;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
